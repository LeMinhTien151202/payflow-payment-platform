package com.payflow.payment.application.command;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Authenticated request to reserve capacity and create a refund. */
public record CreateRefundCommand(
        UUID merchantId,
        String actorId,
        UUID paymentId,
        String idempotencyKey,
        BigDecimal amount,
        String reason) {

    public CreateRefundCommand {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(amount, "amount");
    }
}
