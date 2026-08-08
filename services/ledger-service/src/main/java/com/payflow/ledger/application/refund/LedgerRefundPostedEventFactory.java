package com.payflow.ledger.application.refund;

import com.payflow.ledger.domain.exception.JournalInvariantViolationException;
import com.payflow.ledger.domain.model.EntryDirection;
import com.payflow.ledger.domain.model.Journal;
import com.payflow.events.EventEnvelope;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Maps a committed refund journal to its causally linked acknowledgement. */
public final class LedgerRefundPostedEventFactory {

    public static final String PRODUCER = "ledger-service";

    public EventEnvelope<LedgerRefundPostedData> posted(
            UUID eventId,
            EventEnvelope<RefundRequestedData> cause,
            Journal journal,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireRequest(cause);
        requireMatchingJournal(cause.data(), journal, occurredAt);

        RefundRequestedData request = cause.data();
        LedgerRefundPostedData data = new LedgerRefundPostedData(
                request.refundId(),
                request.paymentId(),
                journal.id(),
                request.accountId(),
                request.amount(),
                request.currency());
        return EventEnvelope.causedBy(
                eventId,
                LedgerEvents.REFUND_POSTED,
                cause.aggregateId(),
                cause,
                PRODUCER,
                occurredAt,
                data);
    }

    private static void requireRequest(EventEnvelope<RefundRequestedData> cause) {
        boolean matches = RefundEvents.REFUND_REQUESTED.name().equals(cause.eventType())
                && RefundEvents.REFUND_REQUESTED.version() == cause.eventVersion()
                && RefundEvents.AGGREGATE_TYPE.equals(cause.aggregateType())
                && cause.data().paymentId().toString().equals(cause.aggregateId());
        if (!matches) {
            throw new JournalInvariantViolationException(
                    "refund journal must be caused by matching refund.requested v1");
        }
    }

    private static void requireMatchingJournal(
            RefundRequestedData request, Journal journal, Instant occurredAt) {
        BigDecimal debits = journal.entries().stream()
                .filter(entry -> entry.direction() == EntryDirection.DEBIT)
                .map(entry -> entry.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean matches = RefundJournalFactory.REFERENCE_TYPE.equals(journal.referenceType())
                && request.refundId().equals(journal.referenceId())
                && RefundJournalFactory.JOURNAL_TYPE.equals(journal.journalType())
                && request.currency().equals(journal.currency())
                && request.amount().compareTo(debits) == 0;
        if (!matches) {
            throw new JournalInvariantViolationException(
                    "posted journal does not match refund request");
        }
        if (occurredAt.isBefore(journal.createdAt())) {
            throw new JournalInvariantViolationException(
                    "ledger event timestamp cannot precede journal commit");
        }
    }
}
