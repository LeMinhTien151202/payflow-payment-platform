package com.payflow.payment.application.exception;

/** An incoming cause or outgoing payload would break the Payment Saga contract chain. */
public final class PaymentSagaContractMismatchException extends PaymentApplicationException {

    private final String field;

    public PaymentSagaContractMismatchException(String field, Object expected, Object actual) {
        super("payment saga " + field + " mismatch: expected=" + expected + ", actual=" + actual);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
