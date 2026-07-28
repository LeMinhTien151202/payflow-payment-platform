package com.payflow.payment.application.exception;

/** An Account reservation fact disagrees with the Payment selected by the message key. */
public final class FundsReservationMismatchException extends PaymentApplicationException {

    private final String field;

    public FundsReservationMismatchException(String field, Object expected, Object actual) {
        super("funds reservation " + field + " mismatch: expected=" + expected + ", actual=" + actual);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
