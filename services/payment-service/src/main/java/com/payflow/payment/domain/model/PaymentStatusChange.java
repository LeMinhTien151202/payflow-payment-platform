package com.payflow.payment.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One movement of a payment through the state machine, as it will be written to
 * {@code payment.payment_status_history}.
 *
 * <p>{@code from} is null for the first entry, which records the payment coming into existence rather
 * than moving. Everything else is required, because a history row that cannot say when something
 * happened answers no question worth asking.
 *
 * <p>The history table also has a {@code metadata} column that this record has no field for. Nothing
 * in Phase 1A has anything to put in it, and inventing a shape now would fix the format of an audit
 * column before there is a single reader to satisfy.
 *
 * @param occurredAt supplied by the caller from an injected {@code Clock}, never read from the system
 *     clock here — a domain object that reads the time cannot be tested about time
 */
public record PaymentStatusChange(
        PaymentStatus from, PaymentStatus to, String reasonCode, Instant occurredAt) {

    /** Matches {@code payment_status_history.reason_code}. */
    public static final int MAX_REASON_CODE_LENGTH = 100;

    public PaymentStatusChange {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(occurredAt, "occurredAt");

        if (from == to) {
            throw new IllegalArgumentException("a status change must record movement, got " + to);
        }
        if (reasonCode != null && reasonCode.length() > MAX_REASON_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "reasonCode must be at most " + MAX_REASON_CODE_LENGTH + " characters");
        }
    }

    /** The entry recording a payment's creation, where there is no previous status. */
    public static PaymentStatusChange initial(PaymentStatus to, Instant occurredAt) {
        return new PaymentStatusChange(null, to, null, occurredAt);
    }

    public boolean isInitial() {
        return from == null;
    }
}
