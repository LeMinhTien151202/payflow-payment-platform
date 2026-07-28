package com.payflow.events.payment;

import com.payflow.events.EventType;

/**
 * Event contracts owned by payment-service.
 *
 * <p>The schemas live in this shared module because {@code MODULE_MAP.md} requires the final event
 * names and schemas to be defined in the event-contract module, and because a consumer cannot depend
 * on a producer's internals. Ownership stays with payment-service: it alone may add an event type
 * here or bump a version, and it alone answers for compatibility.
 *
 * <p>{@code payment.succeeded} follows the financial preconditions fixed by ADR-011; it is an
 * outcome fact and must never be reused as Account's capture command.
 */
public final class PaymentEvents {

    /** Aggregate kind for every event in this file. */
    public static final String AGGREGATE_TYPE = "PAYMENT";

    /** A payment was accepted for processing. Spec 8.4. */
    public static final EventType PAYMENT_CREATED = new EventType("payment.created", 1, AGGREGATE_TYPE);

    /** A payment reached an unsuccessful terminal outcome. Spec 8.4 and ADR-016. */
    public static final EventType PAYMENT_FAILED = new EventType("payment.failed", 1, AGGREGATE_TYPE);

    /** Ledger and capture have both committed, per ADR-011. */
    public static final EventType PAYMENT_SUCCEEDED =
            new EventType("payment.succeeded", 1, AGGREGATE_TYPE);

    private PaymentEvents() {
    }
}
