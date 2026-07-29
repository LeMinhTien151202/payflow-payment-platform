package com.payflow.payment.domain.exception;

import com.payflow.payment.domain.model.Money;
import java.util.Objects;
import java.util.UUID;

/** A valid refund request that cannot fit in the payment's remaining capacity. */
public final class RefundCapacityExceededException extends PaymentDomainException {

    private final UUID paymentId;
    private final Money requested;
    private final Money available;

    public RefundCapacityExceededException(UUID paymentId, Money requested, Money available) {
        super("refund amount exceeds the payment's available capacity");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.requested = Objects.requireNonNull(requested, "requested");
        this.available = Objects.requireNonNull(available, "available");
    }

    public UUID paymentId() {
        return paymentId;
    }

    public Money requested() {
        return requested;
    }

    public Money available() {
        return available;
    }
}
