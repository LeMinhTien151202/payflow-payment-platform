package com.payflow.events.payment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable outcome emitted when a merchant cancels a pre-reservation payment. */
public record PaymentCancelledData(
        UUID paymentId,
        UUID merchantId,
        String cancelledBy,
        Instant cancelledAt) {

    public PaymentCancelledData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(merchantId, "merchantId is required");
        Objects.requireNonNull(cancelledBy, "cancelledBy is required");
        Objects.requireNonNull(cancelledAt, "cancelledAt is required");
        if (cancelledBy.isBlank() || cancelledBy.length() > 255) {
            throw new IllegalArgumentException("cancelledBy must contain 1 through 255 characters");
        }
    }
}
