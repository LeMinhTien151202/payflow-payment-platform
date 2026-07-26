package com.payflow.payment.application.exception;

/**
 * Another request with the same idempotency key committed first.
 *
 * <p>Not an error the caller ever sees. It is thrown from inside the transaction, caught by the handler,
 * and answered by reading and returning the response the winning request stored — which is precisely the
 * behaviour a client sending the same request twice in parallel is asking for.
 *
 * <p>It exists as a distinct type so the handler does not have to catch a persistence exception and guess.
 * Two unique indexes can produce this race, {@code uq_idempotency_records_scope_key} and
 * {@code uq_payments_merchant_idempotency_key}, and a third index on the same insert means something else
 * entirely; only the adapter can tell them apart, and only by name.
 */
public final class ConcurrentIdempotentRequestException extends PaymentApplicationException {

    private final String scope;
    private final String idempotencyKey;

    public ConcurrentIdempotentRequestException(
            String scope, String idempotencyKey, Throwable cause) {

        super("idempotency key already used within scope " + scope, cause);
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
