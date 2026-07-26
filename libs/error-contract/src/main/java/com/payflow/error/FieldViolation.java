package com.payflow.error;

import java.util.Objects;

/**
 * One entry in the {@code fieldErrors} member of a Problem Details body (spec 10.3).
 *
 * <p>Carries the field and a message, and deliberately not the rejected value. Echoing the value back would
 * put whatever the client sent into a response and into any log that records responses — which for a payment
 * API is how a card number ends up somewhere it was never meant to be. The client already knows what it sent;
 * it needs to be told which field was wrong and why.
 *
 * @param field the request field, in the client's own terms — {@code amount}, {@code metadata[orderId]}
 * @param message why it was rejected; safe, caller-facing text with no internal detail
 */
public record FieldViolation(String field, String message) {

    public FieldViolation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
