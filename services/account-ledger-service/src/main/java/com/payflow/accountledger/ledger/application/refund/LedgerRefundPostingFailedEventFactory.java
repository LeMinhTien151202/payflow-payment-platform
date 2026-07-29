package com.payflow.accountledger.ledger.application.refund;

import com.payflow.accountledger.ledger.domain.exception.JournalInvariantViolationException;
import com.payflow.events.EventEnvelope;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerRefundPostingFailedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Creates a definitive Ledger rejection only when no refund journal was posted. */
public final class LedgerRefundPostingFailedEventFactory {

    public EventEnvelope<LedgerRefundPostingFailedData> failed(
            UUID eventId,
            EventEnvelope<RefundRequestedData> cause,
            String failureCode,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(occurredAt, "occurredAt");
        boolean matches = RefundEvents.REFUND_REQUESTED.name().equals(cause.eventType())
                && RefundEvents.REFUND_REQUESTED.version() == cause.eventVersion()
                && RefundEvents.AGGREGATE_TYPE.equals(cause.aggregateType())
                && cause.data().paymentId().toString().equals(cause.aggregateId());
        if (!matches) {
            throw new JournalInvariantViolationException(
                    "refund rejection must be caused by matching refund.requested v1");
        }
        RefundRequestedData request = cause.data();
        return EventEnvelope.causedBy(
                eventId,
                LedgerEvents.REFUND_POSTING_FAILED,
                cause.aggregateId(),
                cause,
                LedgerRefundPostedEventFactory.PRODUCER,
                occurredAt,
                new LedgerRefundPostingFailedData(
                        request.refundId(),
                        request.paymentId(),
                        request.amount(),
                        request.currency(),
                        failureCode));
    }
}
