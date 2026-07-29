package com.payflow.payment.application.exception;

import java.util.UUID;

/** A refund event references local workflow state that cannot be recovered. */
public final class RefundWorkflowDataException extends PaymentApplicationException {

    public RefundWorkflowDataException(String aggregate, UUID id) {
        super("refund workflow cannot recover " + aggregate + " " + id);
    }
}
