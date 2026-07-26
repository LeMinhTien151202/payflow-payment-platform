package com.payflow.payment.api.exception;

/** The create-payment boundary did not receive a usable {@code Idempotency-Key} header. */
public final class IdempotencyKeyRequiredException extends RuntimeException {

    public IdempotencyKeyRequiredException() {
        super("Idempotency-Key is required");
    }
}
