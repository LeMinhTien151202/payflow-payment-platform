package com.payflow.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One immutable Payment or Refund economic contribution to a daily settlement batch. */
public record SettlementContribution(
        SettlementReferenceType referenceType,
        UUID referenceId,
        UUID paymentId,
        UUID merchantId,
        BigDecimal grossAmount,
        BigDecimal refundAmount,
        BigDecimal feeAmount,
        String currency,
        Instant occurredAt) {

    public SettlementContribution {
        Objects.requireNonNull(referenceType, "referenceType");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        grossAmount = money(grossAmount, "grossAmount");
        refundAmount = money(refundAmount, "refundAmount");
        feeAmount = money(feeAmount, "feeAmount");
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
        }
        if (grossAmount.signum() < 0 || refundAmount.signum() < 0) {
            throw new IllegalArgumentException("gross and refund amounts must be non-negative");
        }
        switch (referenceType) {
            case PAYMENT -> {
                if (grossAmount.signum() <= 0 || refundAmount.signum() != 0 || feeAmount.signum() < 0) {
                    throw new IllegalArgumentException("payment contribution requires positive gross, zero refund and non-negative fee");
                }
                if (feeAmount.compareTo(grossAmount) > 0) {
                    throw new IllegalArgumentException("payment fee cannot exceed gross amount");
                }
            }
            case REFUND -> {
                if (grossAmount.signum() != 0 || refundAmount.signum() <= 0 || feeAmount.signum() > 0) {
                    throw new IllegalArgumentException("refund contribution requires zero gross, positive refund and non-positive fee delta");
                }
                if (feeAmount.abs().compareTo(refundAmount) > 0) {
                    throw new IllegalArgumentException("fee reversal cannot exceed refund amount");
                }
            }
        }
    }

    public BigDecimal netAmount() {
        return grossAmount.subtract(refundAmount).subtract(feeAmount).setScale(4);
    }

    public static SettlementContribution payment(
            UUID paymentId,
            UUID merchantId,
            BigDecimal amount,
            BigDecimal feeAmount,
            String currency,
            Instant occurredAt) {
        return new SettlementContribution(
                SettlementReferenceType.PAYMENT,
                paymentId,
                paymentId,
                merchantId,
                amount,
                BigDecimal.ZERO,
                feeAmount,
                currency,
                occurredAt);
    }

    public static SettlementContribution refund(
            UUID refundId,
            UUID paymentId,
            UUID merchantId,
            BigDecimal amount,
            BigDecimal feeReversalAmount,
            String currency,
            Instant occurredAt) {
        Objects.requireNonNull(feeReversalAmount, "feeReversalAmount");
        return new SettlementContribution(
                SettlementReferenceType.REFUND,
                refundId,
                paymentId,
                merchantId,
                BigDecimal.ZERO,
                amount,
                feeReversalAmount.negate(),
                currency,
                occurredAt);
    }

    private static BigDecimal money(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.scale() > 4) {
            throw new IllegalArgumentException(field + " scale must be <= 4");
        }
        return value.setScale(4, RoundingMode.UNNECESSARY);
    }
}
