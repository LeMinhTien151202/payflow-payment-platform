package com.payflow.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.domain.exception.SagaInvariantViolationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSagaTest {

    private static final Instant CREATED = Instant.parse("2026-07-28T11:00:00Z");
    private static final UUID PAYMENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID RESERVATION_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID JOURNAL_ID =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Test
    void startsAtRiskWithFutureDeadlineAndNoFinancialFacts() {
        PaymentSaga saga = start();

        assertThat(saga.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.RISK_ASSESSMENT);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.RUNNING);
        assertThat(saga.retryCount()).isZero();
        assertThat(saga.reservationId()).isNull();
        assertThat(saga.journalId()).isNull();
    }

    @Test
    void cancellationIsTerminalOnlyWhileRiskIsTheActiveStep() {
        PaymentSaga cancelled = start();
        cancelled.cancelBeforeReservation(CREATED.plusSeconds(1));

        assertThat(cancelled.status()).isEqualTo(PaymentSagaStatus.CANCELLED);
        assertThat(cancelled.currentStep()).isEqualTo(PaymentSagaStep.RISK_ASSESSMENT);
        assertThat(cancelled.lastErrorCode()).isEqualTo("MERCHANT_CANCELLED");
        assertThat(cancelled.status().isTerminal()).isTrue();
        assertThat(cancelled.isOverdueAt(CREATED.plusSeconds(100))).isFalse();

        PaymentSaga reserving = start();
        reserving.recordRiskApproved(CREATED.plusSeconds(20), CREATED.plusSeconds(1));
        assertThatThrownBy(() -> reserving.cancelBeforeReservation(CREATED.plusSeconds(2)))
                .isInstanceOf(SagaInvariantViolationException.class);
    }

    @Test
    void recordsDurableFactsBeforeAdvancingAndCompleting() {
        PaymentSaga saga = sagaAtPostLedger();
        saga.recordLedgerPosted(
                JOURNAL_ID, CREATED.plusSeconds(40), CREATED.plusSeconds(3));
        saga.complete(CREATED.plusSeconds(4));

        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.COMPLETED);
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.COMPLETED);
        assertThat(saga.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(saga.journalId()).isEqualTo(JOURNAL_ID);
    }

    @Test
    void timeoutIsInclusiveAndRetryKeepsCurrentStep() {
        PaymentSaga saga = start();

        assertThat(saga.isOverdueAt(CREATED.plusSeconds(9))).isFalse();
        assertThat(saga.isOverdueAt(CREATED.plusSeconds(10))).isTrue();

        saga.recordRetry(
                "RISK_TIMEOUT", CREATED.plusSeconds(20), CREATED.plusSeconds(10));
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.RISK_ASSESSMENT);
        assertThat(saga.retryCount()).isEqualTo(1);
        assertThat(saga.lastErrorCode()).isEqualTo("RISK_TIMEOUT");
    }

    @Test
    void onlyPreLedgerSagaWithReservationCanBeginReleaseCompensation() {
        PaymentSaga saga = sagaAtPostLedger();

        saga.beginCompensation(
                "LEDGER_RETRY_EXHAUSTED",
                CREATED.plusSeconds(50),
                CREATED.plusSeconds(20));

        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.COMPENSATING);
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.RELEASE_FUNDS);
        assertThat(saga.retryCount()).isZero();
    }

    @Test
    void postedJournalFactMakesAutomaticReleaseImpossible() {
        PaymentSaga saga = sagaAtPostLedger();
        saga.recordLedgerPosted(
                JOURNAL_ID, CREATED.plusSeconds(40), CREATED.plusSeconds(3));

        assertThatThrownBy(() -> saga.beginCompensation(
                        "CAPTURE_TIMEOUT",
                        CREATED.plusSeconds(60),
                        CREATED.plusSeconds(40)))
                .isInstanceOf(SagaInvariantViolationException.class)
                .hasMessageContaining("POST_LEDGER");
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.RUNNING);
        assertThat(saga.journalId()).isEqualTo(JOURNAL_ID);
    }

    @Test
    void compensationCompletionIsIdempotencyProtectedByState() {
        PaymentSaga saga = sagaAtPostLedger();
        saga.beginCompensation(
                "LEDGER_FAILED", CREATED.plusSeconds(50), CREATED.plusSeconds(20));
        saga.recordFundsReleased(CREATED.plusSeconds(21));

        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.COMPENSATED);
        assertThat(saga.isOverdueAt(CREATED.plusSeconds(100))).isFalse();
        assertThatThrownBy(() -> saga.recordFundsReleased(CREATED.plusSeconds(22)))
                .isInstanceOf(SagaInvariantViolationException.class);
    }

    @Test
    void manualReviewStopsAutomatedDeadlineProcessingWithoutBecomingTerminal() {
        PaymentSaga saga = sagaAtPostLedger();
        saga.requireManualReview("LEDGER_OUTCOME_AMBIGUOUS", CREATED.plusSeconds(20));

        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(saga.status().isTerminal()).isFalse();
        assertThat(saga.isOverdueAt(CREATED.plusSeconds(100))).isFalse();
    }

    @Test
    void operationsCanApproveOrRejectOnlyRiskManualReview() {
        PaymentSaga approved = start();
        approved.requireManualReview("RISK_REVIEW_REQUIRED", CREATED.plusSeconds(1));
        approved.approveRiskManualReview(CREATED.plusSeconds(30), CREATED.plusSeconds(2));

        PaymentSaga rejected = start();
        rejected.requireManualReview("RISK_REVIEW_REQUIRED", CREATED.plusSeconds(1));
        rejected.rejectRiskManualReview(CREATED.plusSeconds(2));

        assertThat(approved.status()).isEqualTo(PaymentSagaStatus.RUNNING);
        assertThat(approved.currentStep()).isEqualTo(PaymentSagaStep.RESERVE_FUNDS);
        assertThat(rejected.status()).isEqualTo(PaymentSagaStatus.FAILED);
        assertThat(rejected.lastErrorCode()).isEqualTo("MANUAL_REVIEW_RISK_REJECTED");
    }

    @Test
    void operationsRetryPreservesFinancialFactsAndCompensationDirection() {
        PaymentSaga saga = sagaAtPostLedger();
        saga.beginCompensation(
                "LEDGER_RETRY_EXHAUSTED", CREATED.plusSeconds(50), CREATED.plusSeconds(20));
        saga.requireManualReview("RELEASE_OUTCOME_AMBIGUOUS", CREATED.plusSeconds(21));

        saga.retryCurrentStepAfterManualReview(
                CREATED.plusSeconds(60), CREATED.plusSeconds(22));

        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.COMPENSATING);
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.RELEASE_FUNDS);
        assertThat(saga.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(saga.journalId()).isNull();
        assertThat(saga.lastErrorCode()).isEqualTo("RELEASE_OUTCOME_AMBIGUOUS");
    }

    @Test
    void genericRetryCannotBypassRiskDecision() {
        PaymentSaga saga = start();
        saga.requireManualReview("RISK_REVIEW_REQUIRED", CREATED.plusSeconds(1));

        assertThatThrownBy(() -> saga.retryCurrentStepAfterManualReview(
                        CREATED.plusSeconds(30), CREATED.plusSeconds(2)))
                .isInstanceOf(SagaInvariantViolationException.class)
                .hasMessageContaining("resumable financial");
    }

    @Test
    void definitiveRiskOrReservationFailureEndsSagaBeforeFinancialSideEffects() {
        PaymentSaga riskSaga = start();
        riskSaga.failBeforeLedger("RISK_REJECTED", CREATED.plusSeconds(1));
        PaymentSaga reserveSaga = start();
        reserveSaga.recordRiskApproved(CREATED.plusSeconds(20), CREATED.plusSeconds(1));
        reserveSaga.failBeforeLedger("ACCOUNT_INSUFFICIENT_FUNDS", CREATED.plusSeconds(2));

        assertThat(riskSaga.status()).isEqualTo(PaymentSagaStatus.FAILED);
        assertThat(riskSaga.lastErrorCode()).isEqualTo("RISK_REJECTED");
        assertThat(reserveSaga.status()).isEqualTo(PaymentSagaStatus.FAILED);
        assertThat(reserveSaga.lastErrorCode()).isEqualTo("ACCOUNT_INSUFFICIENT_FUNDS");
    }

    @Test
    void preLedgerFailureCannotRegressAPostLedgerOrTerminalSaga() {
        PaymentSaga saga = sagaAtPostLedger();

        assertThatThrownBy(() -> saga.failBeforeLedger(
                        "LEDGER_FAILED", CREATED.plusSeconds(3)))
                .isInstanceOf(SagaInvariantViolationException.class)
                .hasMessageContaining("pre-ledger failure");
        assertThat(saga.status()).isEqualTo(PaymentSagaStatus.RUNNING);
        assertThat(saga.currentStep()).isEqualTo(PaymentSagaStep.POST_LEDGER);
    }

    @Test
    void rehydrationRejectsReleaseStepWhenJournalAlreadyExists() {
        assertThatThrownBy(() -> PaymentSaga.rehydrate(
                        UUID.randomUUID(),
                        PAYMENT_ID,
                        PaymentSagaStep.RELEASE_FUNDS,
                        PaymentSagaStatus.COMPENSATING,
                        CREATED.plusSeconds(20),
                        0,
                        "LEDGER_FAILED",
                        RESERVATION_ID,
                        JOURNAL_ID,
                        CREATED,
                        CREATED.plusSeconds(2)))
                .isInstanceOf(SagaInvariantViolationException.class)
                .hasMessageContaining("forbidden after journal");
    }

    @Test
    void rejectsAnOutOfOrderChangeSoUpdatedAtNeverMovesBackwards() {
        PaymentSaga saga = start();
        saga.recordRiskApproved(
                CREATED.plusSeconds(20), CREATED.plusSeconds(5));

        assertThatThrownBy(() -> saga.recordRetry(
                        "RISK_TIMEOUT",
                        CREATED.plusSeconds(30),
                        CREATED.plusSeconds(4)))
                .isInstanceOf(SagaInvariantViolationException.class)
                .hasMessageContaining("last update");
        assertThat(saga.updatedAt()).isEqualTo(CREATED.plusSeconds(5));
        assertThat(saga.retryCount()).isZero();
    }

    private static PaymentSaga start() {
        return PaymentSaga.start(
                UUID.randomUUID(), PAYMENT_ID, CREATED.plusSeconds(10), CREATED);
    }

    private static PaymentSaga sagaAtPostLedger() {
        PaymentSaga saga = start();
        saga.recordRiskApproved(
                CREATED.plusSeconds(20), CREATED.plusSeconds(1));
        saga.recordFundsReserved(
                RESERVATION_ID,
                CREATED.plusSeconds(30),
                CREATED.plusSeconds(2));
        return saga;
    }
}
