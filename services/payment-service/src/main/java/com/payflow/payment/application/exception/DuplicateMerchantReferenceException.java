package com.payflow.payment.application.exception;

import java.util.UUID;

/**
 * The merchant has already created a payment with this {@code merchantReference}.
 *
 * <p>Spec 7.4 requires the reference to be unique per merchant, and this is not the same failure as a
 * repeated idempotency key. A repeated key with a matching request means "you already asked me this, here
 * is the answer"; a repeated reference with a new key means the merchant is trying to charge for the same
 * order twice under a different request, and the honest answer is 409 rather than a replay of a payment
 * they did not ask for again.
 *
 * <p>Detected by {@code uq_payments_merchant_reference}, not by a preceding read. A read-then-insert check
 * would pass for both of two concurrent requests.
 */
public final class DuplicateMerchantReferenceException extends PaymentApplicationException {

    private final UUID merchantId;
    private final String merchantReference;

    public DuplicateMerchantReferenceException(
            UUID merchantId, String merchantReference, Throwable cause) {

        super(
                "merchant " + merchantId + " already has a payment with reference " + merchantReference,
                cause);
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
    }

    public UUID merchantId() {
        return merchantId;
    }

    /**
     * Safe to return to this caller: they supplied it, and the scope of the constraint is their own
     * merchant, so it reveals nothing about anyone else.
     */
    public String merchantReference() {
        return merchantReference;
    }
}
