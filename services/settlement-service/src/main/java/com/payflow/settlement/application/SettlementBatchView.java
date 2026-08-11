package com.payflow.settlement.application;

import com.payflow.settlement.domain.SettlementStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SettlementBatchView(
        UUID id,
        UUID merchantId,
        LocalDate settlementDate,
        String currency,
        BigDecimal grossAmount,
        BigDecimal refundAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        int transactionCount,
        int refundCount,
        SettlementStatus status,
        Instant createdAt,
        Instant calculatedAt,
        Instant completedAt,
        long version) {
}
