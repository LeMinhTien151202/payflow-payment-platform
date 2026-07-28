package com.payflow.events.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code payment.succeeded} v1, emitted only after ADR-011 finalization. */
public record PaymentSucceededData(
        UUID paymentId,
        UUID merchantId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        Instant completedAt) {

    public static final int MONEY_SCALE = 4;

    public PaymentSucceededData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(merchantId, "merchantId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (amount.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(
                    "amount scale " + amount.scale() + " exceeds " + MONEY_SCALE);
        }
        amount = amount.setScale(MONEY_SCALE);
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter uppercase ISO-4217 code");
        }
    }
}
