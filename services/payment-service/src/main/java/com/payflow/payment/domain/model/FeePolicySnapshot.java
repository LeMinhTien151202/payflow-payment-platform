package com.payflow.payment.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Immutable merchant fee policy copied into the payment decision. ADR-019. */
public record FeePolicySnapshot(
        String policyVersion, BigDecimal rate, RoundingMode roundingMode) {

    public static final int RATE_SCALE = 6;
    public static final RoundingMode REQUIRED_ROUNDING = RoundingMode.HALF_UP;

    public FeePolicySnapshot {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(roundingMode, "roundingMode");

        if (policyVersion.isBlank() || policyVersion.length() > 100) {
            throw new IllegalArgumentException("fee policy version must contain 1..100 characters");
        }
        if (rate.scale() > RATE_SCALE) {
            throw new IllegalArgumentException("fee rate scale must not exceed " + RATE_SCALE + ": " + rate);
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("fee rate must be between 0 and 1 inclusive: " + rate);
        }
        if (roundingMode != REQUIRED_ROUNDING) {
            throw new IllegalArgumentException("MVP fee rounding mode must be HALF_UP");
        }

        rate = rate.setScale(RATE_SCALE);
    }

    public static FeePolicySnapshot legacyNoFee() {
        return new FeePolicySnapshot(
                "LEGACY_NO_FEE_V1", BigDecimal.ZERO, REQUIRED_ROUNDING);
    }
}
