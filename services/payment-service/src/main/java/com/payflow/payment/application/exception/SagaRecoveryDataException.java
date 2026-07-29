package com.payflow.payment.application.exception;

import java.util.UUID;

/** Durable Saga points at workflow data that Payment Service can no longer load consistently. */
public final class SagaRecoveryDataException extends PaymentApplicationException {

    public SagaRecoveryDataException(String aggregateType, UUID aggregateId) {
        super("Saga recovery could not load " + aggregateType + " " + aggregateId);
    }
}
