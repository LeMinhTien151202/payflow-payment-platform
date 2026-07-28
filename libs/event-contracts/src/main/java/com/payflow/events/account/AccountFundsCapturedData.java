package com.payflow.events.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code account.funds-captured} v1. */
public record AccountFundsCapturedData(
        UUID paymentId,
        UUID accountId,
        UUID reservationId,
        BigDecimal amount,
        String currency,
        Instant capturedAt) {

    public AccountFundsCapturedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(reservationId, "reservationId is required");
        amount = AccountEventMoney.positiveAmount(amount);
        currency = AccountEventMoney.currency(currency);
        Objects.requireNonNull(capturedAt, "capturedAt is required");
    }
}
