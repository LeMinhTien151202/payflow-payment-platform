package com.payflow.events.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code refund.requested} v1. User-supplied reason is deliberately not propagated. */
public record RefundRequestedData(
        UUID refundId,
        UUID paymentId,
        UUID merchantId,
        UUID customerId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        Instant requestedAt) {

    public static final int MONEY_SCALE = 4;

    public RefundRequestedData {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(requestedAt, "requestedAt");

        if (amount.signum() <= 0 || amount.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException("refund amount must be positive with scale <= 4");
        }
        amount = amount.setScale(MONEY_SCALE);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
        }
    }
}
