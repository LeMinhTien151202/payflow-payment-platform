package com.payflow.payment.domain.model;

import com.payflow.payment.domain.exception.SagaInvariantViolationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable payment workflow state. Financial fact IDs are retained so restart recovery never has to
 * infer whether reserve or Ledger already committed.
 */
public final class PaymentSaga {

    private final UUID id;
    private final UUID paymentId;
    private final Instant createdAt;
    private PaymentSagaStep currentStep;
    private PaymentSagaStatus status;
    private Instant deadlineAt;
    private int retryCount;
    private String lastErrorCode;
    private UUID reservationId;
    private UUID journalId;
    private Instant updatedAt;

    private PaymentSaga(
            UUID id,
            UUID paymentId,
            PaymentSagaStep currentStep,
            PaymentSagaStatus status,
            Instant deadlineAt,
            int retryCount,
            String lastErrorCode,
            UUID reservationId,
            UUID journalId,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.currentStep = Objects.requireNonNull(currentStep, "currentStep");
        this.status = Objects.requireNonNull(status, "status");
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (retryCount < 0) {
            throw new SagaInvariantViolationException("retryCount cannot be negative");
        }
        this.retryCount = retryCount;
        this.lastErrorCode = stableCodeOrNull(lastErrorCode);
        this.reservationId = reservationId;
        this.journalId = journalId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        validateSnapshot();
    }

    public static PaymentSaga start(
            UUID id, UUID paymentId, Instant deadlineAt, Instant createdAt) {
        requireFutureDeadline(deadlineAt, createdAt);
        return new PaymentSaga(
                id,
                paymentId,
                PaymentSagaStep.RISK_ASSESSMENT,
                PaymentSagaStatus.RUNNING,
                deadlineAt,
                0,
                null,
                null,
                null,
                createdAt,
                createdAt);
    }

    public static PaymentSaga rehydrate(
            UUID id,
            UUID paymentId,
            PaymentSagaStep currentStep,
            PaymentSagaStatus status,
            Instant deadlineAt,
            int retryCount,
            String lastErrorCode,
            UUID reservationId,
            UUID journalId,
            Instant createdAt,
            Instant updatedAt) {
        return new PaymentSaga(
                id,
                paymentId,
                currentStep,
                status,
                deadlineAt,
                retryCount,
                lastErrorCode,
                reservationId,
                journalId,
                createdAt,
                updatedAt);
    }

    public void recordRiskApproved(Instant nextDeadline, Instant at) {
        requireRunningStep(PaymentSagaStep.RISK_ASSESSMENT, "record risk approval");
        advance(PaymentSagaStep.RESERVE_FUNDS, nextDeadline, at);
    }

    public void recordFundsReserved(UUID reservationId, Instant nextDeadline, Instant at) {
        requireRunningStep(PaymentSagaStep.RESERVE_FUNDS, "record funds reservation");
        UUID confirmedReservationId = Objects.requireNonNull(reservationId, "reservationId");
        advance(PaymentSagaStep.POST_LEDGER, nextDeadline, at);
        this.reservationId = confirmedReservationId;
    }

    public void recordLedgerPosted(UUID journalId, Instant nextDeadline, Instant at) {
        requireRunningStep(PaymentSagaStep.POST_LEDGER, "record Ledger posting");
        if (reservationId == null) {
            throw new SagaInvariantViolationException(
                    "cannot record Ledger posting without reservation fact");
        }
        UUID confirmedJournalId = Objects.requireNonNull(journalId, "journalId");
        advance(PaymentSagaStep.CAPTURE_FUNDS, nextDeadline, at);
        this.journalId = confirmedJournalId;
    }

    public void recordRetry(String errorCode, Instant nextDeadline, Instant at) {
        if (status != PaymentSagaStatus.RUNNING && status != PaymentSagaStatus.COMPENSATING) {
            throw new SagaInvariantViolationException("only active Saga may record retry");
        }
        requireFutureDeadline(nextDeadline, at);
        Instant changedAt = requireChronological(at);
        String stableErrorCode = stableCode(errorCode);
        retryCount++;
        lastErrorCode = stableErrorCode;
        deadlineAt = nextDeadline;
        updatedAt = changedAt;
    }

    public void beginCompensation(String errorCode, Instant nextDeadline, Instant at) {
        requireRunningStep(PaymentSagaStep.POST_LEDGER, "begin compensation");
        if (reservationId == null || journalId != null) {
            throw new SagaInvariantViolationException(
                    "release compensation requires reservation and no posted journal fact");
        }
        requireFutureDeadline(nextDeadline, at);
        Instant changedAt = requireChronological(at);
        String stableErrorCode = stableCode(errorCode);
        currentStep = PaymentSagaStep.RELEASE_FUNDS;
        status = PaymentSagaStatus.COMPENSATING;
        retryCount = 0;
        lastErrorCode = stableErrorCode;
        deadlineAt = nextDeadline;
        updatedAt = changedAt;
    }

    public void recordFundsReleased(Instant at) {
        if (status != PaymentSagaStatus.COMPENSATING
                || currentStep != PaymentSagaStep.RELEASE_FUNDS) {
            throw new SagaInvariantViolationException(
                    "funds release can only complete active compensation");
        }
        Instant changedAt = requireChronological(at);
        status = PaymentSagaStatus.COMPENSATED;
        updatedAt = changedAt;
    }

    public void requireManualReview(String reasonCode, Instant at) {
        if (status != PaymentSagaStatus.RUNNING && status != PaymentSagaStatus.COMPENSATING) {
            throw new SagaInvariantViolationException(
                    "only running or compensating Saga may enter manual review");
        }
        Instant changedAt = requireChronological(at);
        String stableReasonCode = stableCode(reasonCode);
        status = PaymentSagaStatus.MANUAL_REVIEW_REQUIRED;
        lastErrorCode = stableReasonCode;
        updatedAt = changedAt;
    }

    /** A definitive pre-ledger rejection ends the automated Saga without compensation. */
    public void failBeforeLedger(String reasonCode, Instant at) {
        if (status != PaymentSagaStatus.RUNNING
                || (currentStep != PaymentSagaStep.RISK_ASSESSMENT
                        && currentStep != PaymentSagaStep.RESERVE_FUNDS)) {
            throw new SagaInvariantViolationException(
                    "pre-ledger failure requires RUNNING risk or reserve step");
        }
        Instant changedAt = requireChronological(at);
        status = PaymentSagaStatus.FAILED;
        lastErrorCode = stableCode(reasonCode);
        updatedAt = changedAt;
    }

    public void complete(Instant at) {
        requireRunningStep(PaymentSagaStep.CAPTURE_FUNDS, "complete Saga");
        if (reservationId == null || journalId == null) {
            throw new SagaInvariantViolationException(
                    "Saga completion requires reservation and journal facts");
        }
        Instant changedAt = requireChronological(at);
        currentStep = PaymentSagaStep.COMPLETED;
        status = PaymentSagaStatus.COMPLETED;
        updatedAt = changedAt;
    }

    public boolean isOverdueAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return !status.isTerminal() && status != PaymentSagaStatus.MANUAL_REVIEW_REQUIRED
                && !at.isBefore(deadlineAt);
    }

    private void advance(PaymentSagaStep target, Instant nextDeadline, Instant at) {
        requireFutureDeadline(nextDeadline, at);
        Instant changedAt = requireChronological(at);
        currentStep = target;
        deadlineAt = nextDeadline;
        retryCount = 0;
        lastErrorCode = null;
        updatedAt = changedAt;
    }

    private void requireRunningStep(PaymentSagaStep expected, String operation) {
        if (status != PaymentSagaStatus.RUNNING || currentStep != expected) {
            throw new SagaInvariantViolationException(
                    operation + " requires RUNNING/" + expected + " but was " + status + "/" + currentStep);
        }
    }

    private void validateSnapshot() {
        if (updatedAt.isBefore(createdAt)) {
            throw new SagaInvariantViolationException("updatedAt cannot precede createdAt");
        }
        if ((currentStep == PaymentSagaStep.POST_LEDGER
                        || currentStep == PaymentSagaStep.CAPTURE_FUNDS
                        || currentStep == PaymentSagaStep.RELEASE_FUNDS
                        || currentStep == PaymentSagaStep.COMPLETED)
                && reservationId == null) {
            throw new SagaInvariantViolationException(
                    "current Saga step requires reservation fact");
        }
        if ((currentStep == PaymentSagaStep.CAPTURE_FUNDS
                        || currentStep == PaymentSagaStep.COMPLETED)
                && journalId == null) {
            throw new SagaInvariantViolationException("current Saga step requires journal fact");
        }
        if (currentStep == PaymentSagaStep.RELEASE_FUNDS && journalId != null) {
            throw new SagaInvariantViolationException(
                    "release compensation is forbidden after journal posted");
        }
        if (status == PaymentSagaStatus.COMPLETED
                && currentStep != PaymentSagaStep.COMPLETED) {
            throw new SagaInvariantViolationException(
                    "completed Saga must be at COMPLETED step");
        }
        if (status == PaymentSagaStatus.COMPENSATING
                && currentStep != PaymentSagaStep.RELEASE_FUNDS) {
            throw new SagaInvariantViolationException(
                    "compensating Saga must be at RELEASE_FUNDS step");
        }
    }

    private Instant requireChronological(Instant at) {
        Objects.requireNonNull(at, "at");
        if (at.isBefore(createdAt)) {
            throw new SagaInvariantViolationException("Saga change cannot precede creation");
        }
        if (at.isBefore(updatedAt)) {
            throw new SagaInvariantViolationException("Saga change cannot precede its last update");
        }
        return at;
    }

    private static void requireFutureDeadline(Instant deadline, Instant at) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(at, "at");
        if (!deadline.isAfter(at)) {
            throw new SagaInvariantViolationException("Saga deadline must be after change time");
        }
    }

    private static String stableCodeOrNull(String value) {
        return value == null ? null : stableCode(value);
    }

    private static String stableCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new SagaInvariantViolationException(
                    "Saga error code must be an uppercase stable code of at most 100 characters");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public PaymentSagaStep currentStep() {
        return currentStep;
    }

    public PaymentSagaStatus status() {
        return status;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    public int retryCount() {
        return retryCount;
    }

    public String lastErrorCode() {
        return lastErrorCode;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public UUID journalId() {
        return journalId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
