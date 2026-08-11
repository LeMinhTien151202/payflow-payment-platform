package com.payflow.settlement.application;

import com.payflow.settlement.domain.SettlementContribution;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimal typed financial fact retained for settlement and reconciliation. */
public record SettlementFact(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateId,
        String correlationId,
        SettlementFactType factType,
        UUID referenceId,
        UUID paymentId,
        UUID merchantId,
        BigDecimal amount,
        BigDecimal feeAmount,
        String currency,
        Instant occurredAt,
        SettlementContribution contribution) {

    public SettlementFact {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(factType, "factType");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventType == null || eventType.isBlank() || aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("event type and aggregate id are required");
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("event version must be positive");
        }
        amount = money(amount, "amount");
        feeAmount = money(feeAmount, "feeAmount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("fact amount must be positive");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be uppercase ISO-4217");
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
