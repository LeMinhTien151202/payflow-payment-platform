package com.payflow.payment.application.exception;

import java.util.UUID;

/** The requested decision is unsafe for the persisted Payment/Saga state. */
public final class ManualReviewResolutionRejectedException extends PaymentApplicationException {

    public ManualReviewResolutionRejectedException(UUID paymentId) {
        super("manual-review resolution rejected for payment " + paymentId);
    }
}
