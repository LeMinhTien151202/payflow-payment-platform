package com.payflow.events.account;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code account.funds-reserved} v1. */
public record AccountFundsReservedData(
        UUID paymentId,
        UUID accountId,
        UUID reservationId,
        BigDecimal amount,
        String currency) {

    public AccountFundsReservedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(reservationId, "reservationId is required");
        amount = AccountEventMoney.positiveAmount(amount);
        currency = AccountEventMoney.currency(currency);
    }
}
