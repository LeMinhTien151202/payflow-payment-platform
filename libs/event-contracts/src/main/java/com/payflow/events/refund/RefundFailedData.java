package com.payflow.events.refund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Terminal refund rejection emitted only before a Ledger journal was posted. */
public record RefundFailedData(
        UUID refundId,
        UUID paymentId,
        UUID merchantId,
        BigDecimal amount,
        String currency,
        String failureCode,
        Instant failedAt) {

    public RefundFailedData {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(failedAt, "failedAt");
        if (amount.signum() <= 0 || amount.scale() > 4) {
            throw new IllegalArgumentException("amount must be positive with scale <= 4");
        }
        amount = amount.setScale(4, RoundingMode.UNNECESSARY);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
        }
        if (failureCode.isBlank() || failureCode.length() > 100) {
            throw new IllegalArgumentException("failureCode must contain 1..100 characters");
        }
    }
}
