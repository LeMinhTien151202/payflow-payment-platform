package com.payflow.events.account;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code account.release.requested} v1 for pre-ledger Saga compensation. */
public record AccountReleaseRequestedData(
        UUID paymentId,
        UUID accountId,
        UUID reservationId,
        BigDecimal amount,
        String currency,
        String reasonCode) {

    public AccountReleaseRequestedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(reservationId, "reservationId is required");
        amount = AccountEventMoney.positiveAmount(amount);
        currency = AccountEventMoney.currency(currency);
        reasonCode = stableCode(reasonCode, "reasonCode");
    }

    static String stableCode(String value, String field) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new IllegalArgumentException(
                    field + " must be an uppercase stable code of at most 100 characters");
        }
        return value;
    }
}

