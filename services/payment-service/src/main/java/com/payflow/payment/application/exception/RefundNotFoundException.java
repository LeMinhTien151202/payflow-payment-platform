package com.payflow.payment.application.exception;

import java.util.UUID;

/** Refund absent, attached to another payment, or owned by another merchant; all map to one 404. */
public final class RefundNotFoundException extends PaymentApplicationException {

    public RefundNotFoundException(UUID refundId, UUID paymentId, UUID merchantId) {
        super("refund " + refundId + " is not visible for payment " + paymentId
                + " and merchant " + merchantId);
    }
}
