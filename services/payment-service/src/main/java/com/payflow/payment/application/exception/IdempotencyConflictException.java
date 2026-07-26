package com.payflow.payment.application.exception;

/**
 * The idempotency key has been used before, by a different request.
 *
 * <p>Spec 14.2 requires this to be a 409. Returning the stored response instead would be worse than
 * useless: the client asked for payment B and would be told payment A succeeded, with a paymentId that
 * belongs to an amount they did not send.
 *
 * <p>Carries no hash and no field list. Telling a caller which part of their request differs would let
 * them probe the stored request one field at a time, and they already know what they sent.
 */
public final class IdempotencyConflictException extends PaymentApplicationException {

    private final String scope;
    private final String idempotencyKey;

    public IdempotencyConflictException(String scope, String idempotencyKey) {
        super("idempotency key was already used with a different request in scope " + scope);
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
    }

    public String scope() {
        return scope;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
