package com.payflow.events.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Fee-aware {@code payment.succeeded} v2 fact required by ADR-019 and ADR-025. */
public record PaymentSucceededV2Data(
        UUID paymentId,
        UUID merchantId,
        UUID customerId,
        BigDecimal amount,
        String currency,
        String feePolicyVersion,
        BigDecimal appliedFeeRate,
        BigDecimal feeAmount,
        String feeCurrency,
        String feeRoundingMode,
        Instant completedAt) {

    public PaymentSucceededV2Data {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(appliedFeeRate, "appliedFeeRate");
        Objects.requireNonNull(feeAmount, "feeAmount");
        Objects.requireNonNull(completedAt, "completedAt");
        if (amount.signum() <= 0 || amount.scale() > 4) {
            throw new IllegalArgumentException("amount must be positive with scale <= 4");
        }
        amount = amount.setScale(4, RoundingMode.UNNECESSARY);
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
        }
        if (feePolicyVersion == null || feePolicyVersion.isBlank() || feePolicyVersion.length() > 100) {
            throw new IllegalArgumentException("feePolicyVersion is required and limited to 100 characters");
        }
        if (appliedFeeRate.signum() < 0
                || appliedFeeRate.compareTo(BigDecimal.ONE) > 0
                || appliedFeeRate.scale() > 6) {
            throw new IllegalArgumentException("appliedFeeRate must be between 0 and 1 with scale <= 6");
        }
        appliedFeeRate = appliedFeeRate.setScale(6, RoundingMode.UNNECESSARY);
        if (feeAmount.signum() < 0 || feeAmount.scale() > 4 || feeAmount.compareTo(amount) > 0) {
            throw new IllegalArgumentException("feeAmount must be non-negative, scale <= 4 and not exceed amount");
        }
        feeAmount = feeAmount.setScale(4, RoundingMode.UNNECESSARY);
        if (!Objects.equals(currency, feeCurrency)) {
            throw new IllegalArgumentException("feeCurrency must equal payment currency");
        }
        try {
            RoundingMode.valueOf(feeRoundingMode);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("feeRoundingMode must be a Java RoundingMode", invalid);
        }
    }
}
