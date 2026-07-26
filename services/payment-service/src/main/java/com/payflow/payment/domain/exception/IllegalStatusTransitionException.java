package com.payflow.payment.domain.exception;

import com.payflow.payment.domain.model.PaymentStatus;
import java.util.UUID;

/**
 * An attempt to move a payment along an edge the state machine does not have.
 *
 * <p>In a system consuming at-least-once, this is the expected shape of a redelivered or reordered
 * message, not necessarily a defect. A consumer that has already applied {@code PROCESSING ->
 * SUCCEEDED} and receives the same event again sees exactly this, which is why the caller has to
 * decide whether it means "ignore, already done" or "alert" — the domain only reports that the move is
 * not legal.
 */
public final class IllegalStatusTransitionException extends PaymentDomainException {

    private final UUID paymentId;
    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalStatusTransitionException(UUID paymentId, PaymentStatus from, PaymentStatus to) {
        super("payment " + paymentId + " cannot move from " + from + " to " + to);
        this.paymentId = paymentId;
        this.from = from;
        this.to = to;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public PaymentStatus from() {
        return from;
    }

    public PaymentStatus to() {
        return to;
    }
}
