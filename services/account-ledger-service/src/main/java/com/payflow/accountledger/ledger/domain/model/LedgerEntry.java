package com.payflow.accountledger.ledger.domain.model;

import com.payflow.accountledger.ledger.domain.exception.JournalInvariantViolationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** One immutable debit or credit line. */
public record LedgerEntry(
        UUID id,
        UUID ledgerAccountId,
        EntryDirection direction,
        BigDecimal amount,
        String currency) {

    public LedgerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        if (!currency.matches("[A-Z]{3}")) {
            throw new JournalInvariantViolationException(
                    "currency must be three uppercase letters");
        }
        if (amount.scale() > 4) {
            throw new JournalInvariantViolationException("entry amount scale must not exceed 4");
        }
        amount = amount.setScale(4, RoundingMode.UNNECESSARY);
        if (amount.precision() > 19) {
            throw new JournalInvariantViolationException("entry amount exceeds NUMERIC(19,4)");
        }
        if (amount.signum() <= 0) {
            throw new JournalInvariantViolationException("entry amount must be greater than zero");
        }
    }
}
