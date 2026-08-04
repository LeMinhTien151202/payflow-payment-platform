package com.payflow.payment.api;

import com.payflow.error.ErrorCode;

/**
 * Error codes owned by payment-service.
 *
 * <p>They live here, not in {@code libs/error-contract}. MODULE_MAP.md allows a shared library to hold a
 * generic error convention and forbids it holding one service's business codes: a code is part of the contract
 * of the endpoint that returns it, and centralising them would let another service return
 * {@code PAYMENT_DUPLICATE_REFERENCE} without owning the invariant behind it.
 *
 * <p>The names follow the {@code <DOMAIN>_<REASON>} convention in spec 10.4. Four of them —
 * {@code PAYMENT_NOT_FOUND}, {@code PAYMENT_DUPLICATE_REFERENCE}, {@code IDEMPOTENCY_KEY_REQUIRED},
 * {@code IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST} — are named by the spec itself and are copied
 * verbatim. The others are new, which the spec allows: its list is explicitly a set of examples.
 *
 * <p>Clients branch on these strings. Renaming one is a breaking API change.
 */
public enum PaymentErrorCode implements ErrorCode {

    /** No such payment, or it belongs to another merchant. 404 either way. */
    PAYMENT_NOT_FOUND,

    /** Refund absent, attached to another payment, or owned by another merchant. */
    REFUND_NOT_FOUND,

    /** No Payment/Saga pair exists for the requested operations work item. */
    MANUAL_REVIEW_NOT_FOUND,

    /** The decision does not match the current Saga step or durable facts. */
    MANUAL_REVIEW_RESOLUTION_REJECTED,

    /** The merchant has already used this {@code merchantReference} for another payment. 409. */
    PAYMENT_DUPLICATE_REFERENCE,

    /**
     * The merchant is not permitted to take payments — pending onboarding, suspended, or closed. 409 rather
     * than 400: nothing the client changes about the request will make it succeed.
     */
    PAYMENT_MERCHANT_NOT_ACCEPTING,

    /**
     * The amount is above the merchant's per-payment ceiling. 400, because a smaller amount does succeed, so
     * this is a fact about the request rather than about the state of the world.
     */
    PAYMENT_LIMIT_EXCEEDED,

    /** The platform does not support this currency at all. 400. */
    PAYMENT_CURRENCY_NOT_SUPPORTED,

    /** The platform supports the currency but this merchant does not settle in it. 400. */
    PAYMENT_CURRENCY_NOT_ACCEPTED,

    /** Payment has not succeeded, or has already been fully refunded. 409. */
    PAYMENT_REFUND_NOT_ALLOWED,

    /** Requested amount exceeds succeeded-minus-reserved refundable capacity. 409. */
    PAYMENT_REFUND_CAPACITY_EXCEEDED,

    /** The {@code Idempotency-Key} header is absent or blank. 400. */
    IDEMPOTENCY_KEY_REQUIRED,

    /**
     * The key was already used, by a request that was not the same one. 409, and the response deliberately
     * does not say what differed: the client has both requests and the server would have to echo stored
     * request content to explain.
     */
    IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST,

    /**
     * A concurrent request holds this key and has not finished. 409 with a retry being the right response.
     *
     * <p>Rare by construction — the loser of that race normally reads the winner's stored response and
     * replays it, so this only surfaces if the key was taken and no response was ever recorded against it.
     */
    IDEMPOTENCY_KEY_REQUEST_IN_PROGRESS;

    /** The constant name is the wire value; a code and its name drifting apart is a bug waiting to happen. */
    @Override
    public String code() {
        return name();
    }
}
