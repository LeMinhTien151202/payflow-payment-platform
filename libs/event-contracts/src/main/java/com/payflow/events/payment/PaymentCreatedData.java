package com.payflow.events.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload of {@code payment.created} v1, per spec 8.4.
 *
 * <p>{@code amount} is a {@link BigDecimal} and never a {@code double}. ADR-007 bans binary floating
 * point for money everywhere, explicitly including event payloads: an event is persisted in the outbox
 * and replayed later, so a rounding artefact introduced here outlives the request that caused it.
 *
 * <p>Amount and currency stay two flat fields rather than a shared {@code Money} type.
 * {@code MODULE_MAP.md} forbids shared money policy in a library, and a {@code Money} class is where
 * rounding and scale rules accumulate. Each service keeps its own money value object; only the wire
 * shape is shared.
 *
 * <p>{@code customerId} and {@code sourceAccountId} are copied from the request without being
 * checked against anything. account-service does not exist yet, so Phase 1A cannot confirm the
 * account is real or belongs to that customer. That validation arrives with the Saga in Phase 1B —
 * consumers of v1 must not read these fields as verified.
 *
 * @param paymentId the aggregate id, and the Kafka key for this event
 * @param merchantId merchant the payment belongs to
 * @param customerId customer as supplied by the merchant, unverified in v1
 * @param sourceAccountId account the funds are to come from, unverified in v1
 * @param amount positive amount, normalised to scale 4
 * @param currency ISO-4217 alphabetic code; MVP accepts VND only
 * @param createdAt when the payment was accepted
 */
public record PaymentCreatedData(
        UUID paymentId,
        UUID merchantId,
        UUID customerId,
        UUID sourceAccountId,
        BigDecimal amount,
        String currency,
        Instant createdAt) {

    /** ADR-007 fixes money at {@code NUMERIC(19,4)}, so the payload scale must match the column. */
    public static final int MONEY_SCALE = 4;

    public PaymentCreatedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(merchantId, "merchantId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(createdAt, "createdAt is required");

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (amount.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(
                    "amount scale " + amount.scale() + " exceeds " + MONEY_SCALE);
        }
        // Widen to exactly scale 4 — exact, never rounding, because a larger scale was rejected
        // above. This makes the serialised JSON deterministic, which is what lets a republish after a
        // crash produce byte-identical bytes for the same eventId (ADR-014).
        amount = amount.setScale(MONEY_SCALE);

        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO-4217 code");
        }
        for (int i = 0; i < currency.length(); i++) {
            char c = currency.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new IllegalArgumentException("currency must be uppercase ISO-4217: " + currency);
            }
        }
    }
}
