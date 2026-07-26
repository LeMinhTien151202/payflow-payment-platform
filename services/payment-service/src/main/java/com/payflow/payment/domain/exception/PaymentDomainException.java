package com.payflow.payment.domain.exception;

/**
 * Base type for a rejected domain rule.
 *
 * <p>Deliberately carries no error code, no HTTP status, and no caller-facing message. The domain
 * knows that a rule was broken; deciding what a client is told about it is the API layer's job, and
 * mixing the two here is how internal wording ends up in a public response.
 *
 * <p>Subclasses expose the facts as typed accessors rather than pre-formatted text, so the boundary
 * can choose what to reveal and what to keep in the log.
 *
 * <p>Distinct from {@link IllegalArgumentException} and {@link NullPointerException}, which this
 * package still uses for a caller that violated a method contract. A domain exception means valid
 * input that a business rule refuses; an {@code IllegalArgumentException} means a bug.
 */
public abstract class PaymentDomainException extends RuntimeException {

    protected PaymentDomainException(String message) {
        super(message);
    }
}
