package com.payflow.payment.application.command;

import java.util.Objects;
import java.util.UUID;

/** Authenticated, idempotent request to cancel a payment before funds reservation. */
public record CancelPaymentCommand(
        UUID merchantId,
        String actorId,
        UUID paymentId,
        String idempotencyKey) {

    public CancelPaymentCommand {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (actorId.isBlank() || actorId.length() > 255) {
            throw new IllegalArgumentException("actorId must contain 1 through 255 characters");
        }
    }
}
