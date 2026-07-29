package com.payflow.payment.domain.exception;

import com.payflow.payment.domain.model.PaymentStatus;
import java.util.Objects;
import java.util.UUID;

/** Refund intake is only legal after a payment succeeded and before it is fully refunded. */
public final class RefundNotAllowedException extends PaymentDomainException {

    private final UUID paymentId;
    private final PaymentStatus status;

    public RefundNotAllowedException(UUID paymentId, PaymentStatus status) {
        super("payment status does not allow refunds");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.status = Objects.requireNonNull(status, "status");
    }

    public UUID paymentId() {
        return paymentId;
    }

    public PaymentStatus status() {
        return status;
    }
}
