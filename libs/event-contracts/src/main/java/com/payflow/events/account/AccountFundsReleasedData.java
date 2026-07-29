package com.payflow.events.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code account.funds-released} v1 after reservation compensation commits. */
public record AccountFundsReleasedData(
        UUID paymentId,
        UUID accountId,
        UUID reservationId,
        BigDecimal amount,
        String currency,
        String reasonCode,
        Instant releasedAt) {

    public AccountFundsReleasedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(reservationId, "reservationId is required");
        amount = AccountEventMoney.positiveAmount(amount);
        currency = AccountEventMoney.currency(currency);
        reasonCode = AccountReleaseRequestedData.stableCode(reasonCode, "reasonCode");
        Objects.requireNonNull(releasedAt, "releasedAt is required");
    }
}

