package com.payflow.payment.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.events.PayFlowTopics;
import com.payflow.events.payment.PaymentCancelledData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.payment.application.CancelPaymentResult;
import com.payflow.payment.application.PaymentAcceptance;
import com.payflow.payment.application.command.CancelPaymentCommand;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.idempotency.IdempotentResponse;
import com.payflow.payment.application.idempotency.RequestFingerprint;
import com.payflow.payment.application.port.IdempotencyStore;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.saga.VersionedPayment;
import com.payflow.payment.application.saga.VersionedPaymentSaga;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class CancelPaymentHandlerTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MERCHANT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-09-10T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-10T09:01:00Z");
    private static final String KEY = "cancel-order-1";

    private final PaymentWorkflowStore payments = mock(PaymentWorkflowStore.class);
    private final PaymentSagaStore sagas = mock(PaymentSagaStore.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final OutboxAppender outbox = mock(OutboxAppender.class);
    private final RecordingTransactions transactionManager = new RecordingTransactions();
    private CancelPaymentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CancelPaymentHandler(
                payments,
                sagas,
                idempotency,
                outbox,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(transactionManager));
    }

    @Test
    void atomicallyCancelsPaymentAndSagaAndPublishesOneAuditableEvent() {
        Payment payment = payment(PaymentStatus.RISK_CHECKING);
        PaymentSaga saga = saga(PaymentSagaStatus.RUNNING);
        CancelPaymentCommand command = command();
        when(idempotency.find(IdempotencyScope.cancelPayment(MERCHANT_ID), KEY))
                .thenReturn(Optional.empty());
        when(payments.findForCancellation(PAYMENT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(new VersionedPayment(payment, 2)));
        when(sagas.findByPaymentIdForCancellation(PAYMENT_ID))
                .thenReturn(Optional.of(new VersionedPaymentSaga(saga, 3)));

        CancelPaymentResult result = handler.handle(command);

        assertThat(result).isInstanceOf(CancelPaymentResult.Cancelled.class);
        assertThat(result.responseStatus()).isEqualTo(200);
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.CANCELLED);
        verify(payments).updateWorkflow(any());
        verify(sagas).update(any());
        verify(idempotency).record(
                eq(IdempotencyScope.cancelPayment(MERCHANT_ID)),
                eq(KEY),
                any(IdempotentResponse.class),
                eq(NOW.plus(CreatePaymentHandler.REPLAY_WINDOW)));

        ArgumentCaptor<PaymentCancelledData> eventData =
                ArgumentCaptor.forClass(PaymentCancelledData.class);
        verify(outbox).append(
                eq(PaymentEvents.PAYMENT_CANCELLED),
                eq(PayFlowTopics.PAYMENT_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                eventData.capture());
        assertThat(eventData.getValue().cancelledBy()).isEqualTo("merchant-operator");
        assertThat(transactionManager.committed).isEqualTo(1);
    }

    @Test
    void aMatchingRetryReplaysWithoutLockingOrPublishing() {
        CancelPaymentCommand command = command();
        PaymentAcceptance acceptance = new PaymentAcceptance(
                PAYMENT_ID,
                PaymentStatus.CANCELLED,
                new BigDecimal("500000.0000"),
                "VND",
                NOW);
        when(idempotency.find(IdempotencyScope.cancelPayment(MERCHANT_ID), KEY))
                .thenReturn(Optional.of(new IdempotentResponse(
                        RequestFingerprint.of(command), PAYMENT_ID, 200, acceptance)));

        CancelPaymentResult result = handler.handle(command);

        assertThat(result).isInstanceOf(CancelPaymentResult.Replayed.class);
        assertThat(result.payment()).isEqualTo(acceptance);
        verify(payments, never()).findForCancellation(any(), any());
        verify(sagas, never()).findByPaymentIdForCancellation(any());
        verify(outbox, never()).append(any(), any(), any(), any(), any());
        assertThat(transactionManager.committed).isZero();
    }

    @Test
    void reusingTheKeyForAnotherPaymentIsAConflict() {
        CancelPaymentCommand command = command();
        PaymentAcceptance acceptance = new PaymentAcceptance(
                PAYMENT_ID,
                PaymentStatus.CANCELLED,
                new BigDecimal("500000.0000"),
                "VND",
                NOW);
        when(idempotency.find(IdempotencyScope.cancelPayment(MERCHANT_ID), KEY))
                .thenReturn(Optional.of(new IdempotentResponse(
                        "different-request", PAYMENT_ID, 200, acceptance)));

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(payments, never()).findForCancellation(any(), any());
    }

    private static CancelPaymentCommand command() {
        return new CancelPaymentCommand(MERCHANT_ID, "merchant-operator", PAYMENT_ID, KEY);
    }

    private static Payment payment(PaymentStatus status) {
        return Payment.rehydrateLegacyNoFee(
                PAYMENT_ID,
                MERCHANT_ID,
                new PaymentIntake(
                        PAYMENT_ID,
                        CUSTOMER_ID,
                        ACCOUNT_ID,
                        "ORDER-CANCEL-1",
                        "create-order-1",
                        Money.of("500000", "VND"),
                        null,
                        Map.of(),
                        CREATED),
                status,
                CREATED.plusSeconds(1));
    }

    private static PaymentSaga saga(PaymentSagaStatus status) {
        return PaymentSaga.rehydrate(
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                PAYMENT_ID,
                PaymentSagaStep.RISK_ASSESSMENT,
                status,
                NOW.plusSeconds(30),
                0,
                null,
                null,
                null,
                CREATED,
                CREATED.plusSeconds(1));
    }

    private static final class RecordingTransactions implements PlatformTransactionManager {
        private int committed;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            // Rollback semantics belong to PostgreSQL integration tests.
        }
    }
}
