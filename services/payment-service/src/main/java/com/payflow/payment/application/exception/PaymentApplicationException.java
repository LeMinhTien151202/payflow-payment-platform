package com.payflow.payment.application.exception;

/**
 * Base type for a use case that could not complete for a reason the caller can act on.
 *
 * <p>Separate from {@code PaymentDomainException} because these failures are not invariant violations.
 * They are facts about the world outside the aggregate — another payment already used that reference, a
 * concurrent request won the race — which no single {@code Payment} instance can see.
 *
 * <p>Like the domain exceptions, these carry no HTTP status and no caller-facing message. The API
 * boundary owns both, so that changing what a client is told is a change in one place rather than a
 * change to a thrown exception in the middle of a transaction.
 */
public abstract class PaymentApplicationException extends RuntimeException {

    protected PaymentApplicationException(String message) {
        super(message);
    }

    protected PaymentApplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
