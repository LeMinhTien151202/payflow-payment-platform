package com.payflow.accountledger.application.exception;

import java.util.UUID;

/** Required local reference data is missing, so the event must be retried or reviewed. */
public final class RefundWorkflowDataException extends RuntimeException {
    public RefundWorkflowDataException(String resource, UUID id) {
        super(resource + " not found for refund workflow: " + id);
    }
}
