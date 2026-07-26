package com.payflow.payment.application.command;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to create a payment, as the application layer sees it.
 *
 * <p>{@code merchantId} is first, and is not part of the HTTP request body. The body in spec 7.4 has no
 * merchant field: the merchant is the authenticated caller, resolved from the token. AGENTS.md section 8
 * is explicit that ownership must never be taken from the request body — a merchant that could name
 * itself could name someone else.
 *
 * <p>{@code idempotencyKey} comes from the {@code Idempotency-Key} header rather than the body, so it is
 * carried separately from the fields that describe the payment. That separation is what lets the request
 * fingerprint cover the payment and not the key.
 *
 * <p>Amount and currency stay separate primitives here and only become a {@code Money} inside the
 * handler. The boundary has to accept whatever a client sent in order to reject it with a 400; turning it
 * into a domain type at the edge would make an invalid amount a domain exception instead of a validation
 * failure.
 */
public record CreatePaymentCommand(
        UUID merchantId,
        String idempotencyKey,
        String merchantReference,
        UUID customerId,
        UUID sourceAccountId,
        BigDecimal amount,
        String currency,
        String description,
        Map<String, String> metadata) {

    public CreatePaymentCommand {
        // Only the two values the API boundary cannot validate for itself are checked here. The rest is
        // bounded by PaymentIntake and Money, and duplicating those rules in a third place is how the
        // three copies end up disagreeing.
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        if (metadata == null) {
            metadata = Map.of();
        } else {
            // Checked before copying rather than left to PaymentIntake, because Map.copyOf answers a
            // null value with a bare NullPointerException — which the error handler can only turn into
            // a 500, for what is a client mistake.
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException(
                            "metadata value must not be null: " + entry.getKey());
                }
            }
            metadata = Map.copyOf(metadata);
        }
    }
}
