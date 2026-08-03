package com.payflow.accountledger.ledger.application.payment;

import com.payflow.accountledger.ledger.domain.exception.JournalInvariantViolationException;
import com.payflow.accountledger.ledger.domain.model.EntryDirection;
import com.payflow.accountledger.ledger.domain.model.Journal;
import com.payflow.accountledger.ledger.domain.model.JournalStatus;
import com.payflow.events.EventEnvelope;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Maps an already-posted, balanced PAYMENT_CAPTURE journal to its causally linked event. */
public final class LedgerPaymentPostedEventFactory {

    public static final String PRODUCER = "account-ledger-service";

    public EventEnvelope<LedgerPaymentPostedData> posted(
            UUID eventId,
            EventEnvelope<LedgerPostPaymentRequestedData> cause,
            Journal journal,
            Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireCommand(cause);
        requireMatchingJournal(cause, journal, occurredAt);

        LedgerPaymentPostedData data = new LedgerPaymentPostedData(
                cause.data().paymentId(),
                journal.id(),
                cause.data().amount(),
                cause.data().currency());
        return EventEnvelope.causedBy(
                eventId,
                LedgerEvents.PAYMENT_POSTED,
                cause.aggregateId(),
                cause,
                PRODUCER,
                occurredAt,
                data);
    }

    private static void requireCommand(
            EventEnvelope<LedgerPostPaymentRequestedData> cause) {
        if (!LedgerEvents.POST_PAYMENT_REQUESTED.name().equals(cause.eventType())
                || LedgerEvents.POST_PAYMENT_REQUESTED.version() != cause.eventVersion()
                || !LedgerEvents.AGGREGATE_TYPE.equals(cause.aggregateType())) {
            throw new JournalInvariantViolationException(
                    "ledger outcome must be caused by ledger.post-payment.requested v1");
        }
        if (!cause.data().paymentId().toString().equals(cause.aggregateId())) {
            throw new JournalInvariantViolationException(
                    "ledger command aggregateId does not match paymentId");
        }
    }

    private static void requireMatchingJournal(
            EventEnvelope<LedgerPostPaymentRequestedData> cause,
            Journal journal,
            Instant occurredAt) {
        LedgerPostPaymentRequestedData command = cause.data();
        BigDecimal debits = journal.entries().stream()
                .filter(entry -> entry.direction() == EntryDirection.DEBIT)
                .map(entry -> entry.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean matches = journal.status() == JournalStatus.POSTED
                && "PAYMENT".equals(journal.referenceType())
                && command.paymentId().equals(journal.referenceId())
                && "PAYMENT_CAPTURE".equals(journal.journalType())
                && command.currency().equals(journal.currency())
                && command.amount().compareTo(debits) == 0;
        if (!matches) {
            throw new JournalInvariantViolationException(
                    "posted journal does not match ledger payment command");
        }
        // Thời điểm command thuộc về đồng hồ của Payment. Chỉ so sánh các timestamp được tạo cục bộ bởi
        // Ledger; causationId sẽ mang thứ tự logic giữa các service.
        if (occurredAt.isBefore(journal.createdAt())) {
            throw new JournalInvariantViolationException(
                    "ledger event timestamp cannot precede journal commit");
        }
    }
}
