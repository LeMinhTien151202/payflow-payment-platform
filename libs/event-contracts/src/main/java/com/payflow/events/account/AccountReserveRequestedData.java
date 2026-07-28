package com.payflow.events.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code account.reserve.requested} v1. */
public record AccountReserveRequestedData(
        UUID paymentId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        Instant expiresAt) {

    public AccountReserveRequestedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        amount = AccountEventMoney.positiveAmount(amount);
        currency = AccountEventMoney.currency(currency);
        Objects.requireNonNull(expiresAt, "expiresAt is required");
    }
}
