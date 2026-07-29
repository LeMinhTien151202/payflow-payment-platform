package com.payflow.events.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code account.refund-credited} v1. */
public record AccountRefundCreditedData(
        UUID refundId,
        UUID paymentId,
        UUID accountId,
        UUID journalId,
        UUID creditId,
        BigDecimal amount,
        String currency) {

    public AccountRefundCreditedData {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(creditId, "creditId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0 || amount.scale() > 4) {
            throw new IllegalArgumentException("amount must be positive with scale <= 4");
        }
        amount = amount.setScale(4, RoundingMode.UNNECESSARY);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
        }
    }
}
