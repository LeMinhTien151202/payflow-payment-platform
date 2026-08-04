package com.payflow.payment.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.payflow.events.EventType;
import com.payflow.events.account.AccountEvents;
import com.payflow.payment.application.audit.AuditRecord;
import com.payflow.payment.application.exception.ManualReviewResolutionRejectedException;
import com.payflow.payment.application.operations.ManualReviewDecision;
import com.payflow.payment.application.operations.ResolveManualReviewCommand;
import com.payflow.payment.application.port.AuditLogAppender;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.saga.SagaRecoverySettings;
import com.payflow.payment.application.saga.VersionedPayment;
import com.payflow.payment.application.saga.VersionedPaymentSaga;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResolveManualReviewHandlerTest {

    private static final Instant CREATED = Instant.parse("2026-08-04T12:00:00Z");
    private static final Instant NOW = CREATED.plusSeconds(60);
    private static final UUID PAYMENT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID MERCHANT_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID CUSTOMER_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID ACCOUNT_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID RESERVATION_ID = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final UUID JOURNAL_ID = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    private static final UUID EVENT_ID = UUID.fromString("12345678-1234-4234-8234-123456789012");

    @Mock PaymentSagaStore sagas;
    @Mock PaymentWorkflowStore payments;
    @Mock OutboxAppender outbox;
    @Mock AuditLogAppender audit;

    private ResolveManualReviewHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ResolveManualReviewHandler(
                sagas, payments, outbox, audit,
                new SagaRecoverySettings(Duration.ofSeconds(30), 3, 20),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void riskApprovalCommitsReserveCommandAndTypedAudit() {
        Payment payment = paymentInReview();
        PaymentSaga saga = sagaInReview(PaymentSagaStep.RISK_ASSESSMENT, null, null);
        arrange(payment, saga);
        arrangeOutbox();

        var result = handler.handle(command(ManualReviewDecision.APPROVE_RISK));

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
        assertThat(result.sagaStep()).isEqualTo(PaymentSagaStep.RESERVE_FUNDS);
        assertThat(result.commandEventId()).isEqualTo(EVENT_ID);
        verify(outbox).append(
                org.mockito.ArgumentMatchers.eq(AccountEvents.RESERVE_REQUESTED),
                anyString(), anyString(), any(), any());
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).append(record.capture());
        assertThat(record.getValue().actorSubject()).isEqualTo("operations-user-1");
        assertThat(record.getValue().before().paymentStatus())
                .isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(record.getValue().after().paymentStatus())
                .isEqualTo(PaymentStatus.RESERVING_FUNDS);
    }

    @Test
    void captureRetryPreservesFactsAndEmitsCaptureCommand() {
        Payment payment = paymentInReview();
        PaymentSaga saga = sagaInReview(
                PaymentSagaStep.CAPTURE_FUNDS, RESERVATION_ID, JOURNAL_ID);
        arrange(payment, saga);
        arrangeOutbox();

        var result = handler.handle(command(ManualReviewDecision.RETRY_CURRENT_STEP));

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(result.sagaStatus()).isEqualTo(PaymentSagaStatus.RUNNING);
        assertThat(saga.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(saga.journalId()).isEqualTo(JOURNAL_ID);
        verify(outbox).append(
                org.mockito.ArgumentMatchers.eq(AccountEvents.CAPTURE_REQUESTED),
                anyString(), anyString(), any(), any());
        verify(sagas).update(any());
        verify(payments).updateWorkflow(any());
    }

    @Test
    void riskCannotUseGenericRetryAndNothingIsWritten() {
        Payment payment = paymentInReview();
        PaymentSaga saga = sagaInReview(PaymentSagaStep.RISK_ASSESSMENT, null, null);
        arrange(payment, saga);

        assertThatThrownBy(() -> handler.handle(command(ManualReviewDecision.RETRY_CURRENT_STEP)))
                .isInstanceOf(ManualReviewResolutionRejectedException.class);
        verify(outbox, never()).append(any(), anyString(), anyString(), any(), any());
        verify(audit, never()).append(any());
        verify(sagas, never()).update(any());
        verify(payments, never()).updateWorkflow(any());
    }

    private void arrange(Payment payment, PaymentSaga saga) {
        given(sagas.findByPaymentId(PAYMENT_ID))
                .willReturn(Optional.of(new VersionedPaymentSaga(saga, 2)));
        given(payments.findForWorkflow(PAYMENT_ID))
                .willReturn(Optional.of(new VersionedPayment(payment, 3)));
    }

    private void arrangeOutbox() {
        given(outbox.append(any(EventType.class), anyString(), anyString(), any(), any()))
                .willReturn(EVENT_ID);
    }

    private static ResolveManualReviewCommand command(ManualReviewDecision decision) {
        return new ResolveManualReviewCommand(
                PAYMENT_ID, decision, "OPS_VERIFIED_SAFE_ACTION", "operations-user-1", "ops-test-1");
    }

    private static Payment paymentInReview() {
        var intake = new PaymentIntake(
                PAYMENT_ID, CUSTOMER_ID, ACCOUNT_ID, "ORDER-OPS-1", "key-ops-1",
                Money.of("500000", "VND"), null, Map.of(), CREATED);
        return Payment.rehydrateLegacyNoFee(
                PAYMENT_ID, MERCHANT_ID, intake, PaymentStatus.MANUAL_REVIEW_REQUIRED, NOW.minusSeconds(1));
    }

    private static PaymentSaga sagaInReview(
            PaymentSagaStep step, UUID reservationId, UUID journalId) {
        return PaymentSaga.rehydrate(
                UUID.randomUUID(), PAYMENT_ID, step, PaymentSagaStatus.MANUAL_REVIEW_REQUIRED,
                NOW.minusSeconds(5), 3, "AUTOMATION_EXHAUSTED", reservationId, journalId,
                CREATED, NOW.minusSeconds(1));
    }
}
