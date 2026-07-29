package com.payflow.events.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code ledger.refund-posted} v1. */
public record LedgerRefundPostedData(
        UUID refundId,
        UUID paymentId,
        UUID journalId,
        UUID accountId,
        BigDecimal amount,
        String currency) {

    public LedgerRefundPostedData {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(accountId, "accountId");
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
