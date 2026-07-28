package com.payflow.payment.domain.exception;

import com.payflow.payment.domain.model.PaymentStatus;
import java.util.UUID;

public final class UnexpectedPaymentStatusException extends PaymentDomainException {

    private final UUID paymentId;
    private final PaymentStatus expected;
    private final PaymentStatus actual;

    public UnexpectedPaymentStatusException(
            UUID paymentId, PaymentStatus expected, PaymentStatus actual, String operation) {
        super(
                "payment "
                        + paymentId
                        + " must be "
                        + expected
                        + " for "
                        + operation
                        + ", actual="
                        + actual);
        this.paymentId = paymentId;
        this.expected = expected;
        this.actual = actual;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public PaymentStatus expected() {
        return expected;
    }

    public PaymentStatus actual() {
        return actual;
    }
}
