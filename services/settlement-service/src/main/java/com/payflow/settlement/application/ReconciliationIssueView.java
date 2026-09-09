package com.payflow.settlement.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReconciliationIssueView(
        UUID id,
        String issueKey,
        String issueType,
        UUID referenceId,
        UUID paymentId,
        UUID merchantId,
        LocalDate settlementDate,
        String expectedCurrency,
        String actualCurrency,
        BigDecimal expectedAmount,
        BigDecimal actualAmount,
        String status,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        Instant resolvedAt) {
}
