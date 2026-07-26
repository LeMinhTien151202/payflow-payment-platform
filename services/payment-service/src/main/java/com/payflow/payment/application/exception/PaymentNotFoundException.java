package com.payflow.payment.application.exception;

import java.util.UUID;

/**
 * No payment with this id is visible to this merchant.
 *
 * <p>One exception for two situations — the payment does not exist, and the payment belongs to someone else —
 * because the boundary must answer both with the same 404. A 403 for the second case would confirm that the
 * id is real, which turns the endpoint into a way to enumerate other merchants' payments.
 *
 * <p>The merchant id is carried for the server-side log only. It must not reach the response body.
 */
public final class PaymentNotFoundException extends PaymentApplicationException {

    private final UUID paymentId;
    private final UUID merchantId;

    public PaymentNotFoundException(UUID paymentId, UUID merchantId) {
        super("payment " + paymentId + " is not visible to merchant " + merchantId);
        this.paymentId = paymentId;
        this.merchantId = merchantId;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID merchantId() {
        return merchantId;
    }
}
