package com.payflow.payment.application.exception;

import java.util.UUID;

/** A consumed risk payload names a different payment than the aggregate selected by its key. */
public final class RiskAssessmentPaymentMismatchException extends PaymentApplicationException {

    private final UUID paymentId;
    private final UUID assessmentPaymentId;

    public RiskAssessmentPaymentMismatchException(UUID paymentId, UUID assessmentPaymentId) {
        super(
                "risk assessment payment "
                        + assessmentPaymentId
                        + " does not match loaded payment "
                        + paymentId);
        this.paymentId = paymentId;
        this.assessmentPaymentId = assessmentPaymentId;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID assessmentPaymentId() {
        return assessmentPaymentId;
    }
}
