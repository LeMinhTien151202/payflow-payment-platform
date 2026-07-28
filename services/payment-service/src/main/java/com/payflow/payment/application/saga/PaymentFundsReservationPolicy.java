package com.payflow.payment.application.saga;

import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.payment.application.exception.FundsReservationMismatchException;
import com.payflow.payment.domain.exception.UnexpectedPaymentStatusException;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Pure Payment orchestration policy for the reservation portion of the Phase 1B Saga. */
public final class PaymentFundsReservationPolicy {

    public AccountReserveRequestedData request(
            Payment payment, Instant requestedAt, Instant expiresAt) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireReserving(payment, "request funds reservation");
        if (!expiresAt.isAfter(requestedAt)) {
            throw new IllegalArgumentException("reservation expiry must be after request time");
        }
        return new AccountReserveRequestedData(
                payment.id(),
                payment.sourceAccountId(),
                payment.amount().amount(),
                payment.amount().currency(),
                expiresAt);
    }

    public LedgerPostPaymentRequestedData applyReserved(
            Payment payment, AccountFundsReservedData reserved, Instant processedAt) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(processedAt, "processedAt");
        requireReserving(payment, "apply funds-reserved");
        requireEqual("paymentId", payment.id(), reserved.paymentId());
        requireEqual("accountId", payment.sourceAccountId(), reserved.accountId());
        requireEqual("amount", payment.amount().amount(), reserved.amount());
        requireEqual("currency", payment.amount().currency(), reserved.currency());

        LedgerPostPaymentRequestedData command = new LedgerPostPaymentRequestedData(
                payment.id(),
                payment.customerId(),
                payment.merchantId(),
                payment.amount().amount(),
                payment.amount().currency());
        payment.confirmFundsReserved(processedAt);
        return command;
    }

    public PaymentFailedData applyFailed(
            Payment payment,
            AccountFundsReservationFailedData failed,
            Instant processedAt) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(processedAt, "processedAt");
        requireReserving(payment, "apply funds-reservation-failed");
        requireEqual("paymentId", payment.id(), failed.paymentId());
        requireEqual("accountId", payment.sourceAccountId(), failed.accountId());

        PaymentFailedData outcome =
                new PaymentFailedData(payment.id(), failed.reasonCode(), processedAt);
        payment.failFundsReservation(failed.reasonCode(), processedAt);
        return outcome;
    }

    private static void requireReserving(Payment payment, String operation) {
        if (payment.status() != PaymentStatus.RESERVING_FUNDS) {
            throw new UnexpectedPaymentStatusException(
                    payment.id(), PaymentStatus.RESERVING_FUNDS, payment.status(), operation);
        }
    }

    private static void requireEqual(String field, Object expected, Object actual) {
        boolean equal = expected instanceof BigDecimal expectedAmount
                && actual instanceof BigDecimal actualAmount
                ? expectedAmount.compareTo(actualAmount) == 0
                : Objects.equals(expected, actual);
        if (!equal) {
            throw new FundsReservationMismatchException(field, expected, actual);
        }
    }
}
