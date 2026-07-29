package com.payflow.payment.application.saga;

import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentManualReviewRequiredData;
import com.payflow.payment.application.exception.PaymentSagaContractMismatchException;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Pure timeout/retry/compensation policy required by ADR-012 and ADR-018. */
public final class PaymentSagaRecoveryPolicy {

    public static final String LEDGER_RETRY_EXHAUSTED = "LEDGER_RETRY_EXHAUSTED";
    public static final String LEDGER_POSTING_FAILED = "LEDGER_POSTING_FAILED";
    public static final String COMPENSATION_RETRY_EXHAUSTED =
            "COMPENSATION_RETRY_EXHAUSTED";

    public SagaRecoveryAction onDeadline(
            Payment payment,
            PaymentSaga saga,
            AccountFundsReservedData reservation,
            Instant now,
            Instant nextDeadline,
            int maxRetries) {
        requireCommon(payment, saga);
        Objects.requireNonNull(now, "now");
        requireMaxRetries(maxRetries);
        if (!saga.isOverdueAt(now)) {
            return new SagaRecoveryAction.NoAction("SAGA_NOT_OVERDUE_OR_NOT_AUTOMATED");
        }

        String timeoutCode = "SAGA_" + saga.currentStep().name() + "_TIMEOUT";
        if (saga.retryCount() < maxRetries) {
            saga.recordRetry(timeoutCode, nextDeadline, now);
            return new SagaRecoveryAction.RetryStep(
                    saga.currentStep(), saga.retryCount(), timeoutCode);
        }

        if (saga.status() == PaymentSagaStatus.RUNNING
                && saga.currentStep() == PaymentSagaStep.POST_LEDGER
                && saga.journalId() == null
                && reservation != null) {
            validateReservation(payment, saga, reservation);
            return beginRelease(
                    saga, reservation, LEDGER_RETRY_EXHAUSTED, nextDeadline, now);
        }

        String reason = saga.status() == PaymentSagaStatus.COMPENSATING
                ? COMPENSATION_RETRY_EXHAUSTED
                : timeoutCode + "_RETRY_EXHAUSTED";
        return enterManualReview(payment, saga, reason, now);
    }

    public SagaRecoveryAction onLedgerPostingFailed(
            Payment payment,
            PaymentSaga saga,
            AccountFundsReservedData reservation,
            LedgerPaymentPostingFailedData failure,
            boolean retryable,
            Instant processedAt,
            Instant nextDeadline,
            int maxRetries) {
        requireCommon(payment, saga);
        Objects.requireNonNull(failure, "failure");
        requireEqual("failure paymentId", payment.id(), failure.paymentId());
        validateReservation(payment, saga, reservation);
        requireMaxRetries(maxRetries);
        if (saga.status() != PaymentSagaStatus.RUNNING
                || saga.currentStep() != PaymentSagaStep.POST_LEDGER) {
            throw new PaymentSagaContractMismatchException(
                    "Saga step", PaymentSagaStep.POST_LEDGER, saga.currentStep());
        }

        if (retryable && saga.retryCount() < maxRetries) {
            saga.recordRetry(failure.failureCode(), nextDeadline, processedAt);
            return new SagaRecoveryAction.RetryStep(
                    PaymentSagaStep.POST_LEDGER,
                    saga.retryCount(),
                    failure.failureCode());
        }
        return beginRelease(
                saga,
                reservation,
                failure.failureCode(),
                nextDeadline,
                processedAt);
    }

    public PaymentFailedData onFundsReleased(
            Payment payment,
            PaymentSaga saga,
            AccountFundsReservedData reservation,
            AccountFundsReleasedData released,
            Instant processedAt) {
        requireCommon(payment, saga);
        Objects.requireNonNull(released, "released");
        Objects.requireNonNull(processedAt, "processedAt");
        validateReservation(payment, saga, reservation);
        requireEqual("release paymentId", payment.id(), released.paymentId());
        requireEqual("release accountId", reservation.accountId(), released.accountId());
        requireEqual(
                "release reservationId", reservation.reservationId(), released.reservationId());
        requireEqual("release amount", reservation.amount(), released.amount());
        requireEqual("release currency", reservation.currency(), released.currency());
        requireEqual("release reasonCode", saga.lastErrorCode(), released.reasonCode());

        saga.recordFundsReleased(processedAt);
        payment.failAfterCompensation(LEDGER_POSTING_FAILED, processedAt);
        return new PaymentFailedData(payment.id(), LEDGER_POSTING_FAILED, processedAt);
    }

    private static SagaRecoveryAction beginRelease(
            PaymentSaga saga,
            AccountFundsReservedData reservation,
            String reasonCode,
            Instant nextDeadline,
            Instant at) {
        saga.beginCompensation(reasonCode, nextDeadline, at);
        return new SagaRecoveryAction.ReleaseFunds(new AccountReleaseRequestedData(
                reservation.paymentId(),
                reservation.accountId(),
                reservation.reservationId(),
                reservation.amount(),
                reservation.currency(),
                reasonCode));
    }

    private static SagaRecoveryAction enterManualReview(
            Payment payment, PaymentSaga saga, String reasonCode, Instant at) {
        saga.requireManualReview(reasonCode, at);
        payment.requireManualReview(reasonCode, at);
        return new SagaRecoveryAction.ManualReview(new PaymentManualReviewRequiredData(
                payment.id(), saga.currentStep().name(), reasonCode));
    }

    private static void requireCommon(Payment payment, PaymentSaga saga) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(saga, "saga");
        requireEqual("Saga paymentId", payment.id(), saga.paymentId());
    }

    private static void validateReservation(
            Payment payment, PaymentSaga saga, AccountFundsReservedData reservation) {
        Objects.requireNonNull(reservation, "reservation");
        requireEqual("reservation paymentId", payment.id(), reservation.paymentId());
        requireEqual("reservation accountId", payment.sourceAccountId(), reservation.accountId());
        requireEqual("reservation Saga id", saga.reservationId(), reservation.reservationId());
        requireEqual("reservation amount", payment.amount().amount(), reservation.amount());
        requireEqual("reservation currency", payment.amount().currency(), reservation.currency());
    }

    private static void requireMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
    }

    private static void requireEqual(String field, Object expected, Object actual) {
        boolean equal = expected instanceof BigDecimal expectedAmount
                && actual instanceof BigDecimal actualAmount
                ? expectedAmount.compareTo(actualAmount) == 0
                : Objects.equals(expected, actual);
        if (!equal) {
            throw new PaymentSagaContractMismatchException(field, expected, actual);
        }
    }
}
