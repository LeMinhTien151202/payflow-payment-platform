package com.payflow.payment.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.payflow.events.account.AccountFundsCapturedData;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentManualReviewRequiredData;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.events.risk.RiskEvents;
import com.payflow.events.risk.RiskLevelValue;
import com.payflow.payment.application.inbox.EventProcessingResult;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.port.ProcessedEventStore;
import com.payflow.payment.application.saga.ApplyRiskAssessmentPolicy;
import com.payflow.payment.application.saga.PaymentFinalizationPolicy;
import com.payflow.payment.application.saga.PaymentFundsReservationPolicy;
import com.payflow.payment.application.saga.PaymentSagaRecoveryPolicy;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

class HandlePaymentWorkflowEventHandlerTest {

    private static final UUID PAYMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SAGA_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MERCHANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID RESERVATION_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID JOURNAL_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-07-29T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-07-29T01:01:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("500000.0000");

    private final ProcessedEventStore inbox = mock(ProcessedEventStore.class);
    private final PaymentWorkflowStore payments = mock(PaymentWorkflowStore.class);
    private final PaymentSagaStore sagas = mock(PaymentSagaStore.class);
    private final OutboxAppender outbox = mock(OutboxAppender.class);
    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    private HandlePaymentWorkflowEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HandlePaymentWorkflowEventHandler(
                inbox,
                payments,
                sagas,
                outbox,
                new ApplyRiskAssessmentPolicy(),
                new PaymentFundsReservationPolicy(),
                new PaymentFinalizationPolicy(),
                new PaymentSagaRecoveryPolicy(),
                new SagaRecoverySettings(Duration.ofSeconds(30), 3, 50),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(transactionManager));
    }

    @Test
    void duplicateCommitsWithoutLoadingOrMutatingWorkflow() {
        var event = riskEvent(RiskDecisionValue.APPROVED);
        when(inbox.recordIfNew(any())).thenReturn(false);

        assertThat(handler.handleRiskAssessment(event)).isEqualTo(EventProcessingResult.DUPLICATE);

        verify(payments, never()).findForWorkflow(any());
        verify(sagas, never()).findByPaymentId(any());
        verify(outbox, never()).appendCausedBy(any(), any(), any(), any(), any(), any());
        assertThat(transactionManager.committed).isEqualTo(1);
    }

    @Test
    void approvedRiskAdvancesBothAndAppendsCausallyLinkedReserveCommand() {
        Payment payment = payment(PaymentStatus.RISK_CHECKING);
        PaymentSaga saga = saga(PaymentSagaStep.RISK_ASSESSMENT, PaymentSagaStatus.RUNNING, null, null, null);
        givenWorkflow(payment, saga);
        var event = riskEvent(RiskDecisionValue.APPROVED);

        assertThat(handler.handleRiskAssessment(event)).isEqualTo(EventProcessingResult.PROCESSED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.RESERVE_FUNDS);
        assertOutgoing(AccountEvents.RESERVE_REQUESTED, AccountReserveRequestedData.class, event);
        verify(payments).updateWorkflow(any());
        verify(sagas).update(any());
    }

    @Test
    void rejectedRiskEndsSagaAndPublishesFailedOutcome() {
        Payment payment = payment(PaymentStatus.RISK_CHECKING);
        PaymentSaga saga = saga(PaymentSagaStep.RISK_ASSESSMENT, PaymentSagaStatus.RUNNING, null, null, null);
        givenWorkflow(payment, saga);
        var event = riskEvent(RiskDecisionValue.REJECTED);

        handler.handleRiskAssessment(event);

        assertThat(payment.status()).isEqualTo(PaymentStatus.RISK_REJECTED);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.FAILED);
        assertOutgoing(PaymentEvents.PAYMENT_FAILED, PaymentFailedData.class, event);
    }

    @Test
    void reviewRiskStopsAutomationAndPublishesManualReview() {
        Payment payment = payment(PaymentStatus.RISK_CHECKING);
        PaymentSaga saga = saga(PaymentSagaStep.RISK_ASSESSMENT, PaymentSagaStatus.RUNNING, null, null, null);
        givenWorkflow(payment, saga);
        var event = riskEvent(RiskDecisionValue.REVIEW_REQUIRED);

        handler.handleRiskAssessment(event);

        assertThat(payment.status()).isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.MANUAL_REVIEW_REQUIRED);
        assertOutgoing(PaymentEvents.MANUAL_REVIEW_REQUIRED, PaymentManualReviewRequiredData.class, event);
    }

    @Test
    void fundsReservedPersistsFactAndRequestsLedgerPosting() {
        Payment payment = payment(PaymentStatus.RESERVING_FUNDS);
        PaymentSaga saga = saga(PaymentSagaStep.RESERVE_FUNDS, PaymentSagaStatus.RUNNING, null, null, null);
        givenWorkflow(payment, saga);
        var event = envelope(
                AccountEvents.FUNDS_RESERVED,
                new AccountFundsReservedData(PAYMENT_ID, ACCOUNT_ID, RESERVATION_ID, AMOUNT, "VND"));

        handler.handleFundsReserved(event);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.POST_LEDGER);
        assertThat(saga.reservationId()).isEqualTo(RESERVATION_ID);
        assertOutgoing(LedgerEvents.POST_PAYMENT_REQUESTED, LedgerPostPaymentRequestedData.class, event);
    }

    @Test
    void reservationFailureEndsBothWithoutLedgerCommand() {
        Payment payment = payment(PaymentStatus.RESERVING_FUNDS);
        PaymentSaga saga = saga(PaymentSagaStep.RESERVE_FUNDS, PaymentSagaStatus.RUNNING, null, null, null);
        givenWorkflow(payment, saga);
        var event = envelope(
                AccountEvents.FUNDS_RESERVATION_FAILED,
                new AccountFundsReservationFailedData(PAYMENT_ID, ACCOUNT_ID, "ACCOUNT_INSUFFICIENT_FUNDS"));

        handler.handleFundsReservationFailed(event);

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.FAILED);
        assertOutgoing(PaymentEvents.PAYMENT_FAILED, PaymentFailedData.class, event);
    }

    @Test
    void ledgerPostedStoresJournalAndRequestsCaptureWithoutEarlySuccess() {
        Payment payment = payment(PaymentStatus.PROCESSING);
        PaymentSaga saga = saga(PaymentSagaStep.POST_LEDGER, PaymentSagaStatus.RUNNING, RESERVATION_ID, null, null);
        givenWorkflow(payment, saga);
        var event = envelope(
                LedgerEvents.PAYMENT_POSTED,
                new LedgerPaymentPostedData(PAYMENT_ID, JOURNAL_ID, AMOUNT, "VND"));

        handler.handleLedgerPosted(event);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.CAPTURE_FUNDS);
        assertThat(saga.journalId()).isEqualTo(JOURNAL_ID);
        assertOutgoing(AccountEvents.CAPTURE_REQUESTED, com.payflow.events.account.AccountCaptureRequestedData.class, event);
        verify(payments, never()).updateWorkflow(any());
    }

    @Test
    void definitiveLedgerFailureBeginsSafePreLedgerRelease() {
        Payment payment = payment(PaymentStatus.PROCESSING);
        PaymentSaga saga = saga(PaymentSagaStep.POST_LEDGER, PaymentSagaStatus.RUNNING, RESERVATION_ID, null, null);
        givenWorkflow(payment, saga);
        var event = envelope(
                LedgerEvents.PAYMENT_POSTING_FAILED,
                new LedgerPaymentPostingFailedData(PAYMENT_ID, "LEDGER_STORAGE_REJECTED", NOW.minusSeconds(1)));

        handler.handleLedgerPostingFailed(event);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.COMPENSATING);
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.RELEASE_FUNDS);
        assertOutgoing(AccountEvents.RELEASE_REQUESTED, AccountReleaseRequestedData.class, event);
    }

    @Test
    void fundsCapturedCompletesSagaAndOnlyThenPublishesSuccess() {
        Payment payment = payment(PaymentStatus.PROCESSING);
        PaymentSaga saga = saga(PaymentSagaStep.CAPTURE_FUNDS, PaymentSagaStatus.RUNNING, RESERVATION_ID, JOURNAL_ID, null);
        givenWorkflow(payment, saga);
        var event = envelope(
                AccountEvents.FUNDS_CAPTURED,
                new AccountFundsCapturedData(
                        PAYMENT_ID, ACCOUNT_ID, RESERVATION_ID, AMOUNT, "VND", NOW.minusSeconds(1)));

        handler.handleFundsCaptured(event);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.COMPLETED);
        assertOutgoing(PaymentEvents.PAYMENT_SUCCEEDED, PaymentSucceededData.class, event);
    }

    @Test
    void releasedFundsCompleteCompensationAndPublishFailedOutcome() {
        Payment payment = payment(PaymentStatus.PROCESSING);
        PaymentSaga saga = saga(
                PaymentSagaStep.RELEASE_FUNDS,
                PaymentSagaStatus.COMPENSATING,
                RESERVATION_ID,
                null,
                "LEDGER_STORAGE_REJECTED");
        givenWorkflow(payment, saga);
        var event = envelope(
                AccountEvents.FUNDS_RELEASED,
                new AccountFundsReleasedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        AMOUNT,
                        "VND",
                        "LEDGER_STORAGE_REJECTED",
                        NOW.minusSeconds(1)));

        handler.handleFundsReleased(event);

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.COMPENSATED);
        assertOutgoing(PaymentEvents.PAYMENT_FAILED, PaymentFailedData.class, event);
    }

    private void givenWorkflow(Payment payment, PaymentSaga saga) {
        when(inbox.recordIfNew(any())).thenReturn(true);
        when(payments.findForWorkflow(PAYMENT_ID))
                .thenReturn(Optional.of(new VersionedPayment(payment, 2)));
        when(sagas.findByPaymentId(PAYMENT_ID))
                .thenReturn(Optional.of(new VersionedPaymentSaga(saga, 3)));
    }

    private void assertOutgoing(
            EventType type, Class<?> dataType, EventEnvelope<?> cause) {
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(outbox).appendCausedBy(
                eq(type),
                eq(PayFlowTopics.PAYMENT_EVENTS),
                eq(PAYMENT_ID.toString()),
                eq(NOW),
                data.capture(),
                eq(cause));
        assertThat(data.getValue()).isInstanceOf(dataType);
    }

    private static EventEnvelope<RiskAssessmentCompletedData> riskEvent(RiskDecisionValue decision) {
        int score = decision == RiskDecisionValue.REJECTED ? 80 : decision == RiskDecisionValue.REVIEW_REQUIRED ? 50 : 10;
        RiskLevelValue level = decision == RiskDecisionValue.REJECTED
                ? RiskLevelValue.CRITICAL
                : decision == RiskDecisionValue.REVIEW_REQUIRED ? RiskLevelValue.HIGH : RiskLevelValue.LOW;
        return envelope(
                RiskEvents.RISK_ASSESSMENT_COMPLETED,
                new RiskAssessmentCompletedData(PAYMENT_ID, decision, score, level, List.of(), "rules-v1"));
    }

    private static <T> EventEnvelope<T> envelope(EventType type, T data) {
        return EventEnvelope.of(
                EVENT_ID,
                type,
                PAYMENT_ID.toString(),
                "corr-workflow-1",
                "upstream-service",
                NOW.minusSeconds(1),
                data);
    }

    private static Payment payment(PaymentStatus status) {
        return Payment.rehydrateLegacyNoFee(
                PAYMENT_ID,
                MERCHANT_ID,
                new PaymentIntake(
                        PAYMENT_ID,
                        CUSTOMER_ID,
                        ACCOUNT_ID,
                        "ORDER-WORKFLOW-1",
                        "idempotency-workflow-1",
                        new Money(AMOUNT, "VND"),
                        null,
                        Map.of(),
                        CREATED),
                status,
                CREATED.plusSeconds(5));
    }

    private static PaymentSaga saga(
            PaymentSagaStep step,
            PaymentSagaStatus status,
            UUID reservationId,
            UUID journalId,
            String errorCode) {
        return PaymentSaga.rehydrate(
                SAGA_ID,
                PAYMENT_ID,
                step,
                status,
                NOW.plusSeconds(30),
                0,
                errorCode,
                reservationId,
                journalId,
                CREATED,
                CREATED.plusSeconds(5));
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
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
            // Assertions for rollback belong to PostgreSQL integration tests.
        }
    }
}
