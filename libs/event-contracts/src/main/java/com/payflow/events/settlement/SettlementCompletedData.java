package com.payflow.events.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Terminal settlement batch fact. Amounts are immutable calculated totals. */
public record SettlementCompletedData(
        UUID batchId,
        UUID merchantId,
        LocalDate settlementDate,
        String currency,
        BigDecimal grossAmount,
        BigDecimal refundAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        int transactionCount,
        int refundCount,
        Instant completedAt) {

    public SettlementCompletedData {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(settlementDate, "settlementDate");
        Objects.requireNonNull(completedAt, "completedAt");
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
        }
        grossAmount = money(grossAmount, "grossAmount");
        refundAmount = money(refundAmount, "refundAmount");
        feeAmount = money(feeAmount, "feeAmount");
        netAmount = money(netAmount, "netAmount");
        if (grossAmount.signum() < 0 || refundAmount.signum() < 0) {
            throw new IllegalArgumentException("grossAmount and refundAmount must be non-negative");
        }
        if (transactionCount < 0 || refundCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        BigDecimal expected = grossAmount.subtract(refundAmount).subtract(feeAmount);
        if (expected.compareTo(netAmount) != 0) {
            throw new IllegalArgumentException("netAmount must equal grossAmount - refundAmount - feeAmount");
        }
    }

    private static BigDecimal money(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.scale() > 4) {
            throw new IllegalArgumentException(field + " scale must be <= 4");
        }
        return value.setScale(4, RoundingMode.UNNECESSARY);
    }
}
