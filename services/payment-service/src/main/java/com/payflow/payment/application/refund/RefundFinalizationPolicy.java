package com.payflow.payment.application.refund;

import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.ledger.LedgerRefundPostingFailedData;
import com.payflow.events.refund.RefundFailedData;
import com.payflow.events.refund.RefundSucceededData;
import com.payflow.payment.application.exception.RefundFinalizationMismatchException;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.Refund;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Pure ADR-021 policy. Runtime must persist inbox, aggregates and outbox atomically. */
public final class RefundFinalizationPolicy {

    /** A posted immutable journal starts processing and permits the Account credit command. */
    public AccountRefundCreditRequestedData requestCredit(
            Payment payment,
            Refund refund,
            LedgerRefundPostedData ledger,
            Instant processedAt) {
        Objects.requireNonNull(processedAt, "processedAt");
        validateRefundOwnership(payment, refund);
        validateLedger(payment, refund, ledger);

        refund.startProcessing(ledger.journalId(), processedAt);
        return new AccountRefundCreditRequestedData(
                refund.id(),
                payment.id(),
                payment.sourceAccountId(),
                ledger.journalId(),
                refund.amount().amount(),
                refund.amount().currency());
    }

    /** Finalizes only after both principal journal and Account credit facts match. */
    public RefundSucceededData complete(
            Payment payment,
            Refund refund,
            LedgerRefundPostedData ledger,
            AccountRefundCreditedData credit,
            Instant processedAt) {
        Objects.requireNonNull(processedAt, "processedAt");
        validateRefundOwnership(payment, refund);
        validateLedger(payment, refund, ledger);
        validateCredit(payment, refund, ledger, credit);

        Money feeReversal = payment.completeRefund(refund.amount(), processedAt);
        refund.succeed(credit.creditId(), feeReversal, processedAt);
        return new RefundSucceededData(
                refund.id(),
                payment.id(),
                payment.merchantId(),
                ledger.journalId(),
                credit.creditId(),
                refund.amount().amount(),
                feeReversal.amount(),
                refund.amount().currency(),
                processedAt);
    }

    /** A definitive pre-journal rejection is the only automatic failure that releases capacity. */
    public RefundFailedData failBeforeJournal(
            Payment payment,
            Refund refund,
            LedgerRefundPostingFailedData failure,
            Instant processedAt) {
        Objects.requireNonNull(processedAt, "processedAt");
        validateRefundOwnership(payment, refund);
        requireEqual("ledger failure refundId", refund.id(), failure.refundId());
        requireEqual("ledger failure paymentId", payment.id(), failure.paymentId());
        requireEqual("ledger failure amount", refund.amount().amount(), failure.amount());
        requireEqual("ledger failure currency", refund.amount().currency(), failure.currency());

        payment.releaseRefund(refund.amount(), processedAt);
        refund.fail(failure.failureCode(), processedAt);
        return new RefundFailedData(
                refund.id(),
                payment.id(),
                payment.merchantId(),
                refund.amount().amount(),
                refund.amount().currency(),
                failure.failureCode(),
                processedAt);
    }

    private static void validateRefundOwnership(Payment payment, Refund refund) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(refund, "refund");
        requireEqual("refund paymentId", payment.id(), refund.paymentId());
        requireEqual("refund merchantId", payment.merchantId(), refund.merchantId());
        requireEqual("refund currency", payment.amount().currency(), refund.amount().currency());
    }

    private static void validateLedger(
            Payment payment, Refund refund, LedgerRefundPostedData ledger) {
        Objects.requireNonNull(ledger, "ledger");
        requireEqual("ledger refundId", refund.id(), ledger.refundId());
        requireEqual("ledger paymentId", payment.id(), ledger.paymentId());
        requireEqual("ledger accountId", payment.sourceAccountId(), ledger.accountId());
        requireEqual("ledger amount", refund.amount().amount(), ledger.amount());
        requireEqual("ledger currency", refund.amount().currency(), ledger.currency());
    }

    private static void validateCredit(
            Payment payment,
            Refund refund,
            LedgerRefundPostedData ledger,
            AccountRefundCreditedData credit) {
        Objects.requireNonNull(credit, "credit");
        requireEqual("credit refundId", refund.id(), credit.refundId());
        requireEqual("credit paymentId", payment.id(), credit.paymentId());
        requireEqual("credit accountId", payment.sourceAccountId(), credit.accountId());
        requireEqual("credit journalId", ledger.journalId(), credit.journalId());
        requireEqual("credit amount", refund.amount().amount(), credit.amount());
        requireEqual("credit currency", refund.amount().currency(), credit.currency());
    }

    private static void requireEqual(String field, Object expected, Object actual) {
        boolean equal = expected instanceof BigDecimal expectedAmount
                && actual instanceof BigDecimal actualAmount
                ? expectedAmount.compareTo(actualAmount) == 0
                : Objects.equals(expected, actual);
        if (!equal) {
            throw new RefundFinalizationMismatchException(field, expected, actual);
        }
    }
}
