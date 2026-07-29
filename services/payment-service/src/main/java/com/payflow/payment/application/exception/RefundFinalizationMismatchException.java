package com.payflow.payment.application.exception;

/** A refund workflow acknowledgement disagrees with Payment, Refund or another financial fact. */
public final class RefundFinalizationMismatchException extends PaymentApplicationException {

    private final String field;

    public RefundFinalizationMismatchException(String field, Object expected, Object actual) {
        super("refund finalization " + field + " mismatch: expected=" + expected + ", actual=" + actual);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
