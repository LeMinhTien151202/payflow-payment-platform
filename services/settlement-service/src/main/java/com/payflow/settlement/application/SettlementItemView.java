package com.payflow.settlement.application;

import com.payflow.settlement.domain.SettlementReferenceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SettlementItemView(
        UUID id,
        UUID eventId,
        SettlementReferenceType referenceType,
        UUID referenceId,
        UUID paymentId,
        BigDecimal grossAmount,
        BigDecimal refundAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String currency,
        Instant occurredAt) {
}
