package com.payflow.ledger.application.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.ledger.domain.exception.JournalInvariantViolationException;
import com.payflow.ledger.domain.model.EntryDirection;
import com.payflow.ledger.domain.model.Journal;
import com.payflow.events.EventEnvelope;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundJournalFactoryTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-07-29T12:00:00Z");
    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID REFUND_ID = UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");

    private final RefundJournalFactory journals = new RefundJournalFactory();
    private final LedgerRefundPostedEventFactory events = new LedgerRefundPostedEventFactory();

    @Test
    void postsBalancedImmutablePrincipalReversal() {
        UUID merchantLedger = UUID.randomUUID();
        UUID customerLedger = UUID.randomUUID();
        Journal journal = journal(request(), merchantLedger, customerLedger);

        assertThat(journal.referenceType()).isEqualTo("REFUND");
        assertThat(journal.referenceId()).isEqualTo(REFUND_ID);
        assertThat(journal.journalType()).isEqualTo("REFUND_REVERSAL");
        assertThat(journal.entries()).extracting(entry -> entry.direction())
                .containsExactly(EntryDirection.DEBIT, EntryDirection.CREDIT);
        assertThat(journal.entries()).extracting(entry -> entry.ledgerAccountId())
                .containsExactly(merchantLedger, customerLedger);
        assertThat(journal.entries()).extracting(entry -> entry.amount())
                .containsExactly(new BigDecimal("200.0000"), new BigDecimal("200.0000"));
    }

    @Test
    void outcomeIsCausallyLinkedAndMatchesJournal() {
        RefundRequestedData request = request();
        EventEnvelope<RefundRequestedData> cause = EventEnvelope.of(
                UUID.randomUUID(),
                RefundEvents.REFUND_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-refund-1",
                "payment-service",
                REQUESTED_AT,
                request);
        Journal journal = journal(request, UUID.randomUUID(), UUID.randomUUID());

        var outcome = events.posted(
                UUID.randomUUID(), cause, journal, REQUESTED_AT.plusSeconds(1));

        assertThat(outcome.eventType()).isEqualTo(LedgerEvents.REFUND_POSTED.name());
        assertThat(outcome.aggregateId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(outcome.causationId()).isEqualTo(cause.eventId().toString());
        assertThat(outcome.data().journalId()).isEqualTo(journal.id());
        assertThat(outcome.data().refundId()).isEqualTo(REFUND_ID);
    }

    @Test
    void definitiveRejectionCopiesIntentAndCausation() {
        RefundRequestedData request = request();
        EventEnvelope<RefundRequestedData> cause = EventEnvelope.of(
                UUID.randomUUID(),
                RefundEvents.REFUND_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-refund-failed",
                "payment-service",
                REQUESTED_AT,
                request);

        var outcome = new LedgerRefundPostingFailedEventFactory().failed(
                UUID.randomUUID(), cause, "LEDGER_ACCOUNT_NOT_FOUND", REQUESTED_AT.plusSeconds(1));

        assertThat(outcome.eventType()).isEqualTo(LedgerEvents.REFUND_POSTING_FAILED.name());
        assertThat(outcome.causationId()).isEqualTo(cause.eventId().toString());
        assertThat(outcome.data().refundId()).isEqualTo(REFUND_ID);
        assertThat(outcome.data().amount()).isEqualByComparingTo("200");
    }

    @Test
    void rejectsJournalThatDoesNotBelongToRequest() {
        RefundRequestedData request = request();
        EventEnvelope<RefundRequestedData> cause = EventEnvelope.of(
                UUID.randomUUID(),
                RefundEvents.REFUND_REQUESTED,
                PAYMENT_ID.toString(),
                "corr-refund-2",
                "payment-service",
                REQUESTED_AT,
                request);
        RefundRequestedData other = new RefundRequestedData(
                UUID.randomUUID(),
                PAYMENT_ID,
                request.merchantId(),
                request.customerId(),
                request.accountId(),
                request.amount(),
                request.currency(),
                request.requestedAt());

        assertThatThrownBy(() -> events.posted(
                        UUID.randomUUID(),
                        cause,
                        journal(other, UUID.randomUUID(), UUID.randomUUID()),
                        REQUESTED_AT.plusSeconds(1)))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void producerClockAheadDoesNotInvalidateLocalJournalTime() {
        RefundRequestedData futureRequest = new RefundRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("200"),
                "VND",
                REQUESTED_AT.plusSeconds(30));

        Journal journal = journals.post(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                futureRequest,
                REQUESTED_AT);

        assertThat(journal.occurredAt()).isEqualTo(REQUESTED_AT);
        assertThat(journal.createdAt()).isEqualTo(REQUESTED_AT);
    }

    private Journal journal(
            RefundRequestedData request, UUID merchantLedger, UUID customerLedger) {
        return journals.post(
                UUID.randomUUID(),
                merchantLedger,
                customerLedger,
                UUID.randomUUID(),
                UUID.randomUUID(),
                request,
                REQUESTED_AT.plusMillis(1));
    }

    private static RefundRequestedData request() {
        return new RefundRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3"),
                new BigDecimal("200"),
                "VND",
                REQUESTED_AT);
    }
}
