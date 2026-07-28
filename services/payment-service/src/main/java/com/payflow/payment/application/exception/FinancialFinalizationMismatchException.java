package com.payflow.payment.application.exception;

/** A persisted financial acknowledgement disagrees with Payment or another Saga fact. */
public final class FinancialFinalizationMismatchException extends PaymentApplicationException {

    private final String field;

    public FinancialFinalizationMismatchException(String field, Object expected, Object actual) {
        super("financial finalization " + field + " mismatch: expected=" + expected + ", actual=" + actual);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
