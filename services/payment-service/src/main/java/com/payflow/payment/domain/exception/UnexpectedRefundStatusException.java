package com.payflow.payment.domain.exception;

import com.payflow.payment.domain.model.RefundStatus;
import java.util.Objects;
import java.util.UUID;

/** A refund command arrived in a state where it cannot be applied. */
public final class UnexpectedRefundStatusException extends PaymentDomainException {

    private final UUID refundId;
    private final RefundStatus actual;
    private final RefundStatus target;

    public UnexpectedRefundStatusException(UUID refundId, RefundStatus actual, RefundStatus target) {
        super("refund status transition is not allowed");
        this.refundId = Objects.requireNonNull(refundId, "refundId");
        this.actual = Objects.requireNonNull(actual, "actual");
        this.target = Objects.requireNonNull(target, "target");
    }

    public UUID refundId() {
        return refundId;
    }

    public RefundStatus actual() {
        return actual;
    }

    public RefundStatus target() {
        return target;
    }
}
