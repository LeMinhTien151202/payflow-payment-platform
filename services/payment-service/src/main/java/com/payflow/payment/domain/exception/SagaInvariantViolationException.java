package com.payflow.payment.domain.exception;

/** A persisted or requested Saga transition contradicts its current financial facts. */
public final class SagaInvariantViolationException extends PaymentDomainException {

    public SagaInvariantViolationException(String message) {
        super(message);
    }
}

