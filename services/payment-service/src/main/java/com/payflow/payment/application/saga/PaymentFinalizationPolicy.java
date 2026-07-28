package com.payflow.payment.application.saga;

import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountFundsCapturedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.payment.application.exception.FinancialFinalizationMismatchException;
import com.payflow.payment.domain.exception.UnexpectedPaymentStatusException;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure ADR-011 finalization policy.
 *
 * <p>The runtime handler must load the durable reservation/ledger facts and wrap inbox, Payment/Saga
 * mutation and outbox append in one local transaction. This class intentionally performs no I/O and
 * does not pretend its method arguments are durable Saga state.
 */
public final class PaymentFinalizationPolicy {

    /**
     * Converts a committed journal acknowledgement into an explicit capture command.
     * Payment deliberately remains PROCESSING until capture acknowledgement arrives.
     */
    public AccountCaptureRequestedData requestCapture(
            Payment payment,
            AccountFundsReservedData reservation,
            LedgerPaymentPostedData ledger) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(ledger, "ledger");
        requireProcessing(payment, "request account capture");
        validateReservation(payment, reservation);
        validateLedger(payment, ledger);
        requireEqual("ledger/reservation amount", reservation.amount(), ledger.amount());
        requireEqual("ledger/reservation currency", reservation.currency(), ledger.currency());

        return new AccountCaptureRequestedData(
                payment.id(),
                reservation.accountId(),
                reservation.reservationId(),
                payment.amount().amount(),
                payment.amount().currency());
    }

    /**
     * Completes Payment only when both durable financial acknowledgements match the reserved intent.
     * Uses Payment Service's local processing time so producer clock skew cannot move updatedAt.
     */
    public PaymentSucceededData complete(
            Payment payment,
            AccountFundsReservedData reservation,
            LedgerPaymentPostedData ledger,
            AccountFundsCapturedData capture,
            Instant processedAt) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(processedAt, "processedAt");
        requireProcessing(payment, "complete financial finalization");
        validateReservation(payment, reservation);
        validateLedger(payment, ledger);
        validateCapture(payment, reservation, capture);
        requireEqual("ledger/reservation amount", reservation.amount(), ledger.amount());
        requireEqual("ledger/reservation currency", reservation.currency(), ledger.currency());

        payment.transitionTo(
                PaymentStatus.SUCCEEDED, "FINANCIAL_FINALIZATION_CONFIRMED", processedAt);
        return new PaymentSucceededData(
                payment.id(),
                payment.merchantId(),
                payment.customerId(),
                payment.amount().amount(),
                payment.amount().currency(),
                processedAt);
    }

    private static void validateReservation(Payment payment, AccountFundsReservedData reservation) {
        requireEqual("reservation paymentId", payment.id(), reservation.paymentId());
        requireEqual("reservation accountId", payment.sourceAccountId(), reservation.accountId());
        requireEqual("reservation amount", payment.amount().amount(), reservation.amount());
        requireEqual("reservation currency", payment.amount().currency(), reservation.currency());
    }

    private static void validateLedger(Payment payment, LedgerPaymentPostedData ledger) {
        requireEqual("ledger paymentId", payment.id(), ledger.paymentId());
        requireEqual("ledger amount", payment.amount().amount(), ledger.amount());
        requireEqual("ledger currency", payment.amount().currency(), ledger.currency());
    }

    private static void validateCapture(
            Payment payment,
            AccountFundsReservedData reservation,
            AccountFundsCapturedData capture) {
        requireEqual("capture paymentId", payment.id(), capture.paymentId());
        requireEqual("capture accountId", reservation.accountId(), capture.accountId());
        requireEqual("capture reservationId", reservation.reservationId(), capture.reservationId());
        requireEqual("capture amount", reservation.amount(), capture.amount());
        requireEqual("capture currency", reservation.currency(), capture.currency());
    }

    private static void requireProcessing(Payment payment, String operation) {
        if (payment.status() != PaymentStatus.PROCESSING) {
            throw new UnexpectedPaymentStatusException(
                    payment.id(), PaymentStatus.PROCESSING, payment.status(), operation);
        }
    }

    private static void requireEqual(String field, Object expected, Object actual) {
        boolean equal = expected instanceof BigDecimal expectedAmount
                && actual instanceof BigDecimal actualAmount
                ? expectedAmount.compareTo(actualAmount) == 0
                : Objects.equals(expected, actual);
        if (!equal) {
            throw new FinancialFinalizationMismatchException(field, expected, actual);
        }
    }
}
