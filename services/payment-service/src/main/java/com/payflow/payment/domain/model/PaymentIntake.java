package com.payflow.payment.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything needed to create one payment, already validated and typed.
 *
 * <p>Exists so {@link Payment#create} takes two arguments instead of ten. A factory with ten
 * positional parameters, four of them strings, is a transposition waiting to happen — and swapping
 * {@code merchantReference} with {@code idempotencyKey} would compile, pass, and quietly break
 * idempotency.
 *
 * <p>Defined in the domain rather than reusing the application's command object, so that the dependency
 * still points inward. The mapping between the two is written out by hand in the application layer,
 * which makes it a step a reviewer can see.
 *
 * <p>The identifier and the timestamp are supplied by the caller, not generated here. A domain object
 * that calls {@code UUID.randomUUID()} or {@code Instant.now()} cannot be tested about either.
 *
 * @param metadata merchant-supplied key/value pairs, bounded and copied defensively
 * @param description free text from the merchant; never used in a decision, only stored and displayed
 */
public record PaymentIntake(
        UUID paymentId,
        UUID customerId,
        UUID sourceAccountId,
        String merchantReference,
        String idempotencyKey,
        Money amount,
        String description,
        Map<String, String> metadata,
        Instant createdAt) {

    /** Matches {@code payments.merchant_reference}. */
    public static final int MAX_MERCHANT_REFERENCE_LENGTH = 100;

    /** Matches {@code payments.idempotency_key}. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    /** Matches {@code payments.description}. */
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    /**
     * Metadata bounds.
     *
     * <p>The column is {@code jsonb} and would happily accept a megabyte. These caps exist because the
     * value is opaque third-party content stored on the write path of a payment: unbounded, it becomes
     * a way to make every insert slow, and a place to park data this platform never promised to
     * protect.
     */
    public static final int MAX_METADATA_ENTRIES = 20;

    public static final int MAX_METADATA_KEY_LENGTH = 64;

    public static final int MAX_METADATA_VALUE_LENGTH = 512;

    public PaymentIntake {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(createdAt, "createdAt");

        merchantReference =
                requireBounded(merchantReference, "merchantReference", MAX_MERCHANT_REFERENCE_LENGTH);
        idempotencyKey =
                requireBounded(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);

        // A payment for nothing is not a payment (spec 7.4 validation). The API rejects this first
        // with a field-level error; reaching here with zero means that path was bypassed, so this is
        // an IllegalArgumentException rather than a domain rejection.
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("payment amount must be positive: " + amount);
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        metadata = validatedMetadata(metadata);
    }

    private static String requireBounded(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static Map<String, String> validatedMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException(
                    "metadata must have at most " + MAX_METADATA_ENTRIES + " entries");
        }
        metadata.forEach(
                (key, value) -> {
                    requireBounded(key, "metadata key", MAX_METADATA_KEY_LENGTH);
                    if (value == null || value.length() > MAX_METADATA_VALUE_LENGTH) {
                        throw new IllegalArgumentException(
                                "metadata value for '"
                                        + key
                                        + "' must be non-null and at most "
                                        + MAX_METADATA_VALUE_LENGTH
                                        + " characters");
                    }
                });
        // Map.copyOf, so a caller holding the original map cannot change what was validated.
        return Map.copyOf(metadata);
    }
}
