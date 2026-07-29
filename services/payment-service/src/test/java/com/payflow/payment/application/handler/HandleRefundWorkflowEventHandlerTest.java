package com.payflow.payment.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.ledger.LedgerRefundPostingFailedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundFailedData;
import com.payflow.events.refund.RefundSucceededData;
import com.payflow.payment.application.exception.RefundFinalizationMismatchException;
import com.payflow.payment.application.inbox.EventProcessingResult;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.ProcessedEventStore;
import com.payflow.payment.application.port.RefundPaymentStore;
import com.payflow.payment.application.port.RefundRepository;
import com.payflow.payment.application.refund.RefundFinalizationPolicy;
import com.payflow.payment.domain.model.FeePolicySnapshot;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentFeeSnapshot;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.domain.model.RefundStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

class HandleRefundWorkflowEventHandlerTest {

    private static final UUID PAYMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID REFUND_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MERCHANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID JOURNAL_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID CREDIT_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-07-29T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-29T12:01:00Z");

    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final RefundPaymentStore payments = mock(RefundPaymentStore.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final OutboxAppender outbox = mock(OutboxAppender.class);
    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    private HandleRefundWorkflowEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HandleRefundWorkflowEventHandler(
                inbox,
                payments,
                refunds,
                outbox,
                new RefundFinalizationPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(transactionManager));
    }

    @Test
    void duplicateEventCommitsWithoutLoadingFinancialState() {
        var event = envelope(LedgerEvents.REFUND_POSTED, ledgerPosted("40"));
        when(inbox.recordIfNew(any())).thenReturn(false);

        assertThat(handler.handleLedgerRefundPosted(event))
                .isEqualTo(EventProcessingResult.DUPLICATE);

        verify(payments, never()).findForRefundWorkflow(any());
        verify(refunds, never()).findForWorkflow(any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
        assertThat(transactionManager.committed).isEqualTo(1);
    }

    @Test
    void ledgerPostedPersistsJournalFactAndRequestsCreditWithoutChangingPayment() {
        Fixture fixture = fixture(false);
        givenState(fixture);
        var event = envelope(LedgerEvents.REFUND_POSTED, ledgerPosted("40"));

        assertThat(handler.handleLedgerRefundPosted(event))
                .isEqualTo(EventProcessingResult.PROCESSED);

        assertThat(fixture.refund().status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(fixture.refund().ledgerJournalId()).isEqualTo(JOURNAL_ID);
        verify(payments, never()).updateRefundState(any());
        verify(refunds).updateWorkflow(fixture.refund());
        assertOutgoing(
                AccountEvents.REFUND_CREDIT_REQUESTED,
                PayFlowTopics.PAYMENT_EVENTS,
                AccountRefundCreditRequestedData.class,
                event);
    }

    @Test
    void accountCreditConsumesCapacityAndPublishesTerminalSuccess() {
        Fixture fixture = fixture(true);
        givenState(fixture);
        var event = envelope(AccountEvents.REFUND_CREDITED, credited("40"));

        handler.handleAccountRefundCredited(event);

        assertThat(fixture.refund().status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(fixture.refund().accountCreditId()).isEqualTo(CREDIT_ID);
        assertThat(fixture.payment().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(fixture.payment().reservedRefundAmount()).isEqualTo(Money.zero("VND"));
        verify(payments).updateRefundState(fixture.payment());
        verify(refunds).updateWorkflow(fixture.refund());
        assertOutgoing(
                RefundEvents.REFUND_SUCCEEDED,
                PayFlowTopics.REFUND_EVENTS,
                RefundSucceededData.class,
                event);
    }

    @Test
    void preJournalFailureReleasesCapacityAndPublishesFailure() {
        Fixture fixture = fixture(false);
        givenState(fixture);
        var event = envelope(
                LedgerEvents.REFUND_POSTING_FAILED,
                new LedgerRefundPostingFailedData(
                        REFUND_ID,
                        PAYMENT_ID,
                        new BigDecimal("40"),
                        "VND",
                        "LEDGER_ACCOUNT_NOT_FOUND"));

        handler.handleLedgerRefundPostingFailed(event);

        assertThat(fixture.refund().status()).isEqualTo(RefundStatus.FAILED);
        assertThat(fixture.payment().reservedRefundAmount()).isEqualTo(Money.zero("VND"));
        verify(payments).updateRefundState(fixture.payment());
        assertOutgoing(
                RefundEvents.REFUND_FAILED,
                PayFlowTopics.REFUND_EVENTS,
                RefundFailedData.class,
                event);
    }

    @Test
    void mismatchedOutcomeRollsBackAndDoesNotAppendOutbox() {
        Fixture fixture = fixture(false);
        givenState(fixture);
        var event = envelope(LedgerEvents.REFUND_POSTED, ledgerPosted("39"));

        assertThatThrownBy(() -> handler.handleLedgerRefundPosted(event))
                .isInstanceOf(RefundFinalizationMismatchException.class)
                .hasMessageContaining("ledger amount");

        verify(refunds, never()).updateWorkflow(any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
        assertThat(transactionManager.rolledBack).isEqualTo(1);
    }

    private void givenState(Fixture fixture) {
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(payments.findForRefundWorkflow(PAYMENT_ID))
                .thenReturn(Optional.of(fixture.payment()));
        when(refunds.findForWorkflow(REFUND_ID))
                .thenReturn(Optional.of(fixture.refund()));
    }

    private void assertOutgoing(
            EventType type,
            String topic,
            Class<?> dataType,
            EventEnvelope<?> cause) {
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(outbox).appendCausedBy(
                eq(type),
                eq(topic),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                data.capture(),
                eq(cause));
        assertThat(data.getValue()).isInstanceOf(dataType);
    }

    private static Fixture fixture(boolean processing) {
        PaymentIntake intake = new PaymentIntake(
                PAYMENT_ID,
                CUSTOMER_ID,
                ACCOUNT_ID,
                "ORDER-REFUND-RUNTIME",
                "payment-key",
                Money.of("100", "VND"),
                null,
                Map.of(),
                CREATED);
        Payment payment = Payment.rehydrate(
                PAYMENT_ID,
                MERCHANT_ID,
                intake,
                PaymentFeeSnapshot.calculate(
                        new FeePolicySnapshot(
                                "STANDARD_V1",
                                new BigDecimal("0.02"),
                                RoundingMode.HALF_UP),
                        intake.amount()),
                PaymentStatus.SUCCEEDED,
                Money.zero("VND"),
                Money.zero("VND"),
                Money.zero("VND"),
                CREATED);
        Money amount = Money.of("40", "VND");
        payment.reserveRefund(amount, CREATED.plusSeconds(1));
        Refund refund = Refund.create(
                REFUND_ID,
                PAYMENT_ID,
                MERCHANT_ID,
                "refund-key",
                amount,
                null,
                "merchant-user",
                CREATED.plusSeconds(1));
        if (processing) {
            refund.startProcessing(JOURNAL_ID, CREATED.plusSeconds(2));
        }
        return new Fixture(payment, refund);
    }

    private static LedgerRefundPostedData ledgerPosted(String amount) {
        return new LedgerRefundPostedData(
                REFUND_ID,
                PAYMENT_ID,
                JOURNAL_ID,
                ACCOUNT_ID,
                new BigDecimal(amount),
                "VND");
    }

    private static AccountRefundCreditedData credited(String amount) {
        return new AccountRefundCreditedData(
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                JOURNAL_ID,
                CREDIT_ID,
                new BigDecimal(amount),
                "VND");
    }

    private static <T> EventEnvelope<T> envelope(EventType type, T data) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                type,
                PAYMENT_ID.toString(),
                "corr-refund-runtime",
                "account-ledger-service",
                NOW.minusSeconds(1),
                data);
    }

    private record Fixture(Payment payment, Refund refund) {
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private int committed;
        private int rolledBack;

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
            rolledBack++;
        }
    }
}
