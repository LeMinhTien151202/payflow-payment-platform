package com.payflow.accountledger.account.domain.model;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Monetary value owned by the Account bounded context. Zero is valid for a balance. */
public record Money(BigDecimal amount, String currency) {

    private static final int SCALE = 4;
    private static final int PRECISION = 19;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        if (!currency.matches("[A-Z]{3}")) {
            throw new AccountInvariantViolationException("currency must be three uppercase letters");
        }
        if (amount.scale() > SCALE) {
            throw new AccountInvariantViolationException("amount scale must not exceed 4");
        }

        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        if (amount.precision() > PRECISION) {
            throw new AccountInvariantViolationException("amount exceeds NUMERIC(19,4)");
        }
        if (amount.signum() < 0) {
            throw new AccountInvariantViolationException("money must not be negative");
        }
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public Money requirePositive() {
        if (amount.signum() <= 0) {
            throw new AccountInvariantViolationException("operation amount must be greater than zero");
        }
        return this;
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        BigDecimal result = amount.subtract(other.amount);
        if (result.signum() < 0) {
            throw new AccountInvariantViolationException("money subtraction would become negative");
        }
        return new Money(result, currency);
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    public void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new AccountInvariantViolationException(
                    "currency mismatch: " + currency + " and " + other.currency);
        }
    }
}
