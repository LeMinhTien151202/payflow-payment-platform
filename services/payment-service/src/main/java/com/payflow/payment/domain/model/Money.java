package com.payflow.payment.domain.model;

import com.payflow.payment.domain.exception.UnsupportedCurrencyException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * An amount and the currency it is denominated in, inseparable.
 *
 * <p>The pairing is the point. A bare {@code BigDecimal} passed between methods loses its currency,
 * and the resulting bug is not a crash — it is a number that looks plausible and is wrong.
 *
 * <p>{@code BigDecimal} at a fixed scale of 4, matching {@code NUMERIC(19,4)} in PostgreSQL.
 * AGENTS.md section 5 forbids {@code double} and {@code float} for money outright: they cannot
 * represent 0.1, so a sum of them drifts, and the drift lands in someone's balance.
 *
 * <p>Scale is normalised on construction so that two amounts equal in value are also equal by
 * {@code equals}. Without it, {@code Money.of("100")} and {@code Money.of("100.0000")} would be
 * different objects representing the same money, and every comparison in the codebase would have to
 * remember to use {@code compareTo}.
 *
 * <p>Zero is permitted; negative is not. A zero amount is a real quantity — nothing refunded yet, no
 * fee charged. A negative one would mean direction, which belongs in the ledger's debit/credit
 * distinction rather than smuggled into a sign here. That a <em>payment</em> must be strictly
 * positive is a separate rule, enforced by {@link Payment}.
 */
public record Money(BigDecimal amount, String currency) {

    /** Matches {@code NUMERIC(19,4)}. Changing it is a database migration, not a constant edit. */
    public static final int SCALE = 4;

    /**
     * MVP is VND only (spec 7.4).
     *
     * <p>Adding a currency is not adding an entry here: it needs an FX-rate source, a rounding rule
     * per currency, and a decision about what a multi-currency merchant balance means. The narrow set
     * is what forces that conversation instead of letting a second currency arrive unnoticed.
     */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("VND");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter uppercase ISO-4217 code: " + currency);
        }
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new UnsupportedCurrencyException(currency);
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        // Rejected rather than rounded. PostgreSQL would round the extra digits away silently, and a
        // rounding policy is a business decision that must not be made by a value object's
        // constructor. See PaymentIntakeSchemaIT.extraScaleIsRoundedNotRejected.
        if (amount.scale() > SCALE) {
            throw new IllegalArgumentException(
                    "amount scale must not exceed " + SCALE + ": " + amount);
        }

        // Exact by construction: the scale was just checked to be at most SCALE, so this widens and
        // never rounds.
        amount = amount.setScale(SCALE);
    }

    /**
     * @param amount decimal text, for example {@code "500000"} or {@code "1234.5678"}
     */
    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /**
     * @throws IllegalArgumentException if the currencies differ — a mismatch here is not a business
     *     rejection but two values that came from sources which disagree, and returning any boolean
     *     would be a guess
     */
    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot compare " + currency + " with " + other.currency);
        }
    }

    /** Plain notation, so a large amount never renders as {@code 5E+5} in a log or a message. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
