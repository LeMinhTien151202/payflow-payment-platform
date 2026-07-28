package com.payflow.accountledger.ledger.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.accountledger.ledger.domain.exception.JournalInvariantViolationException;
import com.payflow.accountledger.ledger.domain.model.EntryDirection;
import com.payflow.accountledger.ledger.domain.model.Journal;
import com.payflow.accountledger.ledger.domain.model.LedgerEntry;
import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerPaymentPostedEventFactoryTest {

    private static final UUID COMMAND_EVENT_ID =
            UUID.fromString("31734b31-8e75-4570-bdc6-979fa02ab443");
    private static final UUID OUTCOME_EVENT_ID =
            UUID.fromString("41734b31-8e75-4570-bdc6-979fa02ab444");
    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID JOURNAL_ID =
            UUID.fromString("51734b31-8e75-4570-bdc6-979fa02ab445");
    private static final Instant REQUESTED_AT = Instant.parse("2026-07-28T10:00:02Z");
    private static final Instant POSTED_AT = REQUESTED_AT.plusSeconds(1);

    private final LedgerPaymentPostedEventFactory factory =
            new LedgerPaymentPostedEventFactory();

    @Test
    void mapsPostedJournalWithPaymentKeyAndCausation() {
        EventEnvelope<LedgerPaymentPostedData> outcome =
                factory.posted(OUTCOME_EVENT_ID, command(), journal("500000"), POSTED_AT);

        assertThat(outcome.eventType()).isEqualTo("ledger.payment-posted");
        assertThat(outcome.aggregateId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(outcome.correlationId()).isEqualTo(command().correlationId());
        assertThat(outcome.causationId()).isEqualTo(COMMAND_EVENT_ID.toString());
        assertThat(outcome.producer()).isEqualTo("account-ledger-service");
        assertThat(outcome.data().journalId()).isEqualTo(JOURNAL_ID);
    }

    @Test
    void rejectsJournalWhoseDebitTotalDoesNotMatchCommand() {
        assertThatThrownBy(() -> factory.posted(
                        OUTCOME_EVENT_ID, command(), journal("499999"), POSTED_AT))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsWrongReferenceAndCauseContract() {
        Journal wrongReference = Journal.post(
                JOURNAL_ID,
                "REFUND",
                PAYMENT_ID,
                "PAYMENT_CAPTURE",
                null,
                entries("500000"),
                POSTED_AT,
                POSTED_AT);
        var wrongCause = EventEnvelope.of(
                COMMAND_EVENT_ID,
                new EventType("ledger.other", 1, "PAYMENT"),
                PAYMENT_ID.toString(),
                "correlation-1",
                "payment-service",
                REQUESTED_AT,
                command().data());

        assertThatThrownBy(() -> factory.posted(
                        OUTCOME_EVENT_ID, command(), wrongReference, POSTED_AT))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> factory.posted(
                        OUTCOME_EVENT_ID, wrongCause, journal("500000"), POSTED_AT))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("post-payment.requested");
    }

    @Test
    void eventCannotPredateJournalCommit() {
        assertThatThrownBy(() -> factory.posted(
                        OUTCOME_EVENT_ID,
                        command(),
                        journal("500000"),
                        POSTED_AT.minusNanos(1)))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("journal commit");
    }

    private static EventEnvelope<LedgerPostPaymentRequestedData> command() {
        return EventEnvelope.of(
                COMMAND_EVENT_ID,
                LedgerEvents.POST_PAYMENT_REQUESTED,
                PAYMENT_ID.toString(),
                "correlation-1",
                "payment-service",
                REQUESTED_AT,
                new LedgerPostPaymentRequestedData(
                        PAYMENT_ID,
                        UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95"),
                        UUID.fromString("2f1c7a30-0b5e-4a4e-9a94-6a1a1cbb1111"),
                        new BigDecimal("500000"),
                        "VND"));
    }

    private static Journal journal(String amount) {
        return Journal.post(
                JOURNAL_ID,
                "PAYMENT",
                PAYMENT_ID,
                "PAYMENT_CAPTURE",
                null,
                entries(amount),
                POSTED_AT,
                POSTED_AT);
    }

    private static List<LedgerEntry> entries(String amount) {
        return List.of(
                new LedgerEntry(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        EntryDirection.DEBIT,
                        new BigDecimal(amount),
                        "VND"),
                new LedgerEntry(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        EntryDirection.CREDIT,
                        new BigDecimal(amount),
                        "VND"));
    }
}
