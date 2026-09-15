package com.payflow.payment.application.idempotency;

import java.util.UUID;

/**
 * Builds the {@code idempotency_records.scope} value, in the format the column documents:
 * {@code <merchantId>:<METHOD> <path>}.
 *
 * <p>A separate type because handlers and persistence adapters need the same answer. If each formatted
 * its own, a change to the format would break lookup/race recovery pairing.
 *
 * <p>Both parts of the scope are load-bearing. Without the merchant, one merchant's key could collide with
 * another's and return a payment belonging to someone else. Without the endpoint, a key spent creating a
 * payment would also answer a refund request for the same merchant.
 */
public final class IdempotencyScope {

    /** Endpoint part for {@code POST /api/v1/payments}, whose path spec 7.4 fixes. */
    public static final String CREATE_PAYMENT = "POST /api/v1/payments";

    /** Template scope: paymentId is part of the request fingerprint, not the bounded scope string. */
    public static final String CREATE_REFUND = "POST /api/v1/payments/{paymentId}/refunds";

    /** Template scope: paymentId is part of the request fingerprint. */
    public static final String CANCEL_PAYMENT = "POST /api/v1/payments/{paymentId}/cancel";

    /** Matches {@code idempotency_records.scope VARCHAR(100)}. */
    public static final int MAX_LENGTH = 100;

    private IdempotencyScope() {
    }

    /** Scope for the create-payment endpoint. */
    public static String createPayment(UUID merchantId) {
        return of(merchantId, CREATE_PAYMENT);
    }

    public static String createRefund(UUID merchantId) {
        return of(merchantId, CREATE_REFUND);
    }

    public static String cancelPayment(UUID merchantId) {
        return of(merchantId, CANCEL_PAYMENT);
    }

    public static String of(UUID merchantId, String endpoint) {
        String scope = merchantId + ":" + endpoint;
        if (scope.length() > MAX_LENGTH) {
            // Unreachable with the endpoints defined here, and checked anyway: the alternative is a column
            // overflow inside the payment transaction, which would roll the payment back.
            throw new IllegalArgumentException("idempotency scope exceeds " + MAX_LENGTH + " characters");
        }
        return scope;
    }
}
