package com.payflow.payment.application.refund;

import com.payflow.events.EventEnvelope;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Adds causal envelopes to the data produced by {@link RefundFinalizationPolicy}. */
public final class RefundWorkflowEventFactory {

    public static final String PRODUCER = "payment-service";

    public EventEnvelope<AccountRefundCreditRequestedData> creditRequested(
            UUID eventId,
            EventEnvelope<LedgerRefundPostedData> cause,
            AccountRefundCreditRequestedData data,
            Instant occurredAt) {
        requireCause(cause, LedgerEvents.REFUND_POSTED, data.paymentId());
        requireEqual("credit command refundId", cause.data().refundId(), data.refundId());
        requireEqual("credit command accountId", cause.data().accountId(), data.accountId());
        requireEqual("credit command journalId", cause.data().journalId(), data.journalId());
        requireMoney(cause.data().amount(), cause.data().currency(), data.amount(), data.currency());
        return causedBy(eventId, AccountEvents.REFUND_CREDIT_REQUESTED, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<RefundSucceededData> succeeded(
            UUID eventId,
            EventEnvelope<AccountRefundCreditedData> cause,
            RefundSucceededData data,
            Instant occurredAt) {
        requireCause(cause, AccountEvents.REFUND_CREDITED, data.paymentId());
        requireEqual("success refundId", cause.data().refundId(), data.refundId());
        requireEqual("success journalId", cause.data().journalId(), data.journalId());
        requireEqual("success creditId", cause.data().creditId(), data.creditId());
        requireMoney(cause.data().amount(), cause.data().currency(), data.amount(), data.currency());
        return causedBy(eventId, RefundEvents.REFUND_SUCCEEDED, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<RefundFailedData> failed(
            UUID eventId,
            EventEnvelope<LedgerRefundPostingFailedData> cause,
            RefundFailedData data,
            Instant occurredAt) {
        requireCause(cause, LedgerEvents.REFUND_POSTING_FAILED, data.paymentId());
        requireEqual("failure refundId", cause.data().refundId(), data.refundId());
        requireEqual("failure code", cause.data().failureCode(), data.failureCode());
        requireMoney(cause.data().amount(), cause.data().currency(), data.amount(), data.currency());
        return causedBy(eventId, RefundEvents.REFUND_FAILED, cause, data.paymentId(), occurredAt, data);
    }

    private static void requireCause(
            EventEnvelope<?> cause, com.payflow.events.EventType type, UUID paymentId) {
        Objects.requireNonNull(cause, "cause");
        boolean matches = type.name().equals(cause.eventType())
                && type.version() == cause.eventVersion()
                && type.aggregateType().equals(cause.aggregateType())
                && paymentId.toString().equals(cause.aggregateId());
        if (!matches) {
            throw new RefundFinalizationMismatchException("cause", type.name(), cause.eventType());
        }
    }

    private static <T> EventEnvelope<T> causedBy(
            UUID eventId,
            com.payflow.events.EventType type,
            EventEnvelope<?> cause,
            UUID paymentId,
            Instant occurredAt,
            T data) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return EventEnvelope.causedBy(
                eventId, type, paymentId.toString(), cause, PRODUCER, occurredAt, data);
    }

    private static void requireMoney(
            BigDecimal expectedAmount,
            String expectedCurrency,
            BigDecimal actualAmount,
            String actualCurrency) {
        requireEqual("amount", expectedAmount, actualAmount);
        requireEqual("currency", expectedCurrency, actualCurrency);
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
