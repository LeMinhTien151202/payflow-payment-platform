package com.payflow.events.refund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Terminal refund fact emitted only after journal and account credit acknowledgements. */
public record RefundSucceededData(
        UUID refundId,
        UUID paymentId,
        UUID merchantId,
        UUID journalId,
        UUID creditId,
        BigDecimal amount,
        BigDecimal feeReversalAmount,
        String currency,
        Instant completedAt) {

    public RefundSucceededData {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(creditId, "creditId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(feeReversalAmount, "feeReversalAmount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(completedAt, "completedAt");
        if (amount.signum() <= 0 || amount.scale() > 4) {
            throw new IllegalArgumentException("amount must be positive with scale <= 4");
        }
        if (feeReversalAmount.signum() < 0 || feeReversalAmount.scale() > 4) {
            throw new IllegalArgumentException("feeReversalAmount must be non-negative with scale <= 4");
        }
        amount = amount.setScale(4, RoundingMode.UNNECESSARY);
        feeReversalAmount = feeReversalAmount.setScale(4, RoundingMode.UNNECESSARY);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
        }
    }
}
