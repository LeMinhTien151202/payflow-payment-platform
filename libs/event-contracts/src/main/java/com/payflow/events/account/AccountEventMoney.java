package com.payflow.events.account;

import java.math.BigDecimal;
import java.util.Objects;

/** Wire validation only; Account's domain Money policy remains inside Account. */
final class AccountEventMoney {

    static final int SCALE = 4;

    private AccountEventMoney() {
    }

    static BigDecimal positiveAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (amount.scale() > SCALE) {
            throw new IllegalArgumentException(
                    "amount scale " + amount.scale() + " exceeds " + SCALE);
        }
        return amount.setScale(SCALE);
    }

    static String currency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter uppercase ISO-4217 code");
        }
        return currency;
    }
}
