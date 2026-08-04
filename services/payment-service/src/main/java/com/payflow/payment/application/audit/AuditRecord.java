package com.payflow.payment.application.audit;

import com.payflow.observability.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Typed audit command; deliberately has no map, request body, token, header or free-form note. */
public record AuditRecord(
        UUID auditId,
        String action,
        String resourceType,
        UUID resourceId,
        String actorSubject,
        String decisionCode,
        String correlationId,
        PaymentReviewAuditFacts before,
        PaymentReviewAuditFacts after,
        Instant occurredAt) {

    public AuditRecord {
        Objects.requireNonNull(auditId, "auditId");
        action = stableCode(action, "action");
        resourceType = stableCode(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        actorSubject = bounded(actorSubject, 255, "actorSubject");
        decisionCode = stableCode(decisionCode, "decisionCode");
        if (!CorrelationId.isSafe(correlationId)) {
            throw new IllegalArgumentException("audit correlationId is invalid");
        }
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static String stableCode(String value, String name) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new IllegalArgumentException(name + " must be a stable code");
        }
        return value;
    }

    private static String bounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1.." + max + " characters");
        }
        return value;
    }
}
