package com.payflow.ledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.ledger.domain.exception.JournalInvariantViolationException;
import com.payflow.ledger.domain.exception.UnbalancedJournalException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalTest {

    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void postsABalancedTwoEntryJournal() {
        Journal journal = post(List.of(debit("500000", "VND"), credit("500000", "VND")));

        assertThat(journal.status()).isEqualTo(JournalStatus.POSTED);
        assertThat(journal.currency()).isEqualTo("VND");
        assertThat(journal.entries()).hasSize(2);
    }

    @Test
    void postsABalancedThreeEntryJournal() {
        Journal journal =
                post(
                        List.of(
                                debit("500000", "VND"),
                                credit("490000", "VND"),
                                credit("10000", "VND")));

        assertThat(journal.entries()).hasSize(3);
    }

    @Test
    void rejectsAnUnbalancedJournal() {
        assertThatThrownBy(() -> post(List.of(debit("500000", "VND"), credit("499999", "VND"))))
                .isInstanceOf(UnbalancedJournalException.class)
                .hasMessageContaining("debits=500000.0000")
                .hasMessageContaining("credits=499999.0000");
    }

    @Test
    void rejectsMixedCurrencies() {
        assertThatThrownBy(() -> post(List.of(debit("1", "VND"), credit("1", "USD"))))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("mix currencies");
    }

    @Test
    void requiresAtLeastTwoEntriesAndBothDirections() {
        assertThatThrownBy(() -> post(List.of(debit("1", "VND"))))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("at least two");
        assertThatThrownBy(() -> post(List.of(debit("1", "VND"), debit("1", "VND"))))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("one debit and one credit");
    }

    @Test
    void entryAmountMustBePositiveAndFitNumericShape() {
        assertThatThrownBy(() -> debit("0", "VND"))
                .isInstanceOf(JournalInvariantViolationException.class);
        assertThatThrownBy(() -> debit("-1", "VND"))
                .isInstanceOf(JournalInvariantViolationException.class);
        assertThatThrownBy(() -> debit("1.00001", "VND"))
                .isInstanceOf(JournalInvariantViolationException.class);
    }

    @Test
    void copiesTheEntryListSoPostedJournalCannotBeMutated() {
        List<LedgerEntry> proposed = new ArrayList<>();
        proposed.add(debit("1", "VND"));
        proposed.add(credit("1", "VND"));

        Journal journal = post(proposed);
        proposed.clear();

        assertThat(journal.entries()).hasSize(2);
        assertThatThrownBy(() -> journal.entries().add(debit("1", "VND")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void creationCannotPredateTheBusinessOccurrence() {
        assertThatThrownBy(
                        () ->
                                Journal.post(
                                        UUID.randomUUID(),
                                        "PAYMENT",
                                        UUID.randomUUID(),
                                        "PAYMENT_CAPTURE",
                                        null,
                                        List.of(debit("1", "VND"), credit("1", "VND")),
                                        NOW,
                                        NOW.minusSeconds(1)))
                .isInstanceOf(JournalInvariantViolationException.class)
                .hasMessageContaining("createdAt");
    }

    private static Journal post(List<LedgerEntry> entries) {
        return Journal.post(
                UUID.randomUUID(),
                "PAYMENT",
                UUID.randomUUID(),
                "PAYMENT_CAPTURE",
                "Sandbox payment capture",
                entries,
                NOW,
                NOW);
    }

    private static LedgerEntry debit(String amount, String currency) {
        return entry(EntryDirection.DEBIT, amount, currency);
    }

    private static LedgerEntry credit(String amount, String currency) {
        return entry(EntryDirection.CREDIT, amount, currency);
    }

    private static LedgerEntry entry(EntryDirection direction, String amount, String currency) {
        return new LedgerEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                direction,
                new BigDecimal(amount),
                currency);
    }
}
