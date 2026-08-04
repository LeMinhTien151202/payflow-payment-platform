package com.payflow.payment.application.operations;

import com.payflow.observability.CorrelationId;
import java.util.Objects;
import java.util.UUID;

/** Trusted identity plus a stable, non-free-form decision submitted by operations. */
public record ResolveManualReviewCommand(
        UUID paymentId,
        ManualReviewDecision decision,
        String decisionCode,
        String actorSubject,
        String correlationId) {

    public ResolveManualReviewCommand {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(decision, "decision");
        if (decisionCode == null || !decisionCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new IllegalArgumentException("decisionCode must be a stable code");
        }
        if (actorSubject == null || actorSubject.isBlank() || actorSubject.length() > 255) {
            throw new IllegalArgumentException("actorSubject must contain 1..255 characters");
        }
        if (!CorrelationId.isSafe(correlationId)) {
            throw new IllegalArgumentException("correlationId is invalid");
        }
    }
}
