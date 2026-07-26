package com.payflow.accountledger.ledger.domain.model;

import com.payflow.accountledger.ledger.domain.exception.JournalInvariantViolationException;
import com.payflow.accountledger.ledger.domain.exception.UnbalancedJournalException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable posted double-entry journal. There is intentionally no update or delete operation;
 * corrections must later be represented by a new linked reversal journal.
 */
public final class Journal {

    private final UUID id;
    private final String referenceType;
    private final UUID referenceId;
    private final String journalType;
    private final String description;
    private final String currency;
    private final List<LedgerEntry> entries;
    private final Instant occurredAt;
    private final Instant createdAt;
    private final JournalStatus status;

    private Journal(
            UUID id,
            String referenceType,
            UUID referenceId,
            String journalType,
            String description,
            String currency,
            List<LedgerEntry> entries,
            Instant occurredAt,
            Instant createdAt) {
        this.id = id;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.journalType = journalType;
        this.description = description;
        this.currency = currency;
        this.entries = entries;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.status = JournalStatus.POSTED;
    }

    public static Journal post(
            UUID id,
            String referenceType,
            UUID referenceId,
            String journalType,
            String description,
            List<LedgerEntry> entries,
            Instant occurredAt,
            Instant createdAt) {
        Objects.requireNonNull(id, "id");
        validateCode(referenceType, "referenceType");
        Objects.requireNonNull(referenceId, "referenceId");
        validateCode(journalType, "journalType");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (description != null && description.length() > 500) {
            throw new JournalInvariantViolationException("description must not exceed 500 characters");
        }
        if (createdAt.isBefore(occurredAt)) {
            throw new JournalInvariantViolationException("createdAt cannot precede occurredAt");
        }
        if (entries.size() < 2) {
            throw new JournalInvariantViolationException("journal requires at least two entries");
        }

        List<LedgerEntry> immutableEntries = List.copyOf(entries);
        String currency = immutableEntries.getFirst().currency();
        BigDecimal debits = BigDecimal.ZERO.setScale(4);
        BigDecimal credits = BigDecimal.ZERO.setScale(4);
        for (LedgerEntry entry : immutableEntries) {
            if (!currency.equals(entry.currency())) {
                throw new JournalInvariantViolationException(
                        "journal cannot mix currencies: " + currency + " and " + entry.currency());
            }
            if (entry.direction() == EntryDirection.DEBIT) {
                debits = debits.add(entry.amount());
            } else {
                credits = credits.add(entry.amount());
            }
        }
        if (debits.signum() == 0 || credits.signum() == 0) {
            throw new JournalInvariantViolationException(
                    "journal requires at least one debit and one credit");
        }
        if (debits.compareTo(credits) != 0) {
            throw new UnbalancedJournalException(debits, credits, currency);
        }

        return new Journal(
                id,
                referenceType,
                referenceId,
                journalType,
                description,
                currency,
                immutableEntries,
                occurredAt,
                createdAt);
    }

    private static void validateCode(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 30) {
            throw new JournalInvariantViolationException(
                    field + " must be non-blank and at most 30 characters");
        }
    }

    public UUID id() {
        return id;
    }

    public String referenceType() {
        return referenceType;
    }

    public UUID referenceId() {
        return referenceId;
    }

    public String journalType() {
        return journalType;
    }

    public String description() {
        return description;
    }

    public String currency() {
        return currency;
    }

    public List<LedgerEntry> entries() {
        return entries;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public JournalStatus status() {
        return status;
    }
}
