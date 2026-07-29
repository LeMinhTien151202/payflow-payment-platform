package com.payflow.events.payment;

import java.util.Objects;
import java.util.UUID;

/** Payload of {@code payment.manual-review-required} v1, per ADR-018. */
public record PaymentManualReviewRequiredData(
        UUID paymentId, String sagaStep, String reasonCode) {

    public PaymentManualReviewRequiredData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        sagaStep = stableCode(sagaStep, "sagaStep", 50);
        reasonCode = stableCode(reasonCode, "reasonCode", 100);
    }

    private static String stableCode(String value, String field, int maxLength) {
        if (value == null
                || value.length() > maxLength
                || !value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    field + " must be an uppercase stable code of at most " + maxLength + " characters");
        }
        return value;
    }
}

