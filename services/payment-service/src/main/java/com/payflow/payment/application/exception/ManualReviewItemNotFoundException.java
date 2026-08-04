package com.payflow.payment.application.exception;

import java.util.UUID;

/** No Payment/Saga pair exists for the requested operations work item. */
public final class ManualReviewItemNotFoundException extends PaymentApplicationException {

    public ManualReviewItemNotFoundException(UUID paymentId) {
        super("manual-review work item not found for payment " + paymentId);
    }
}
