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
 * <p>{@code payment.failed} is available for terminal unsuccessful outcomes whose taxonomy is
 * already resolved. Success/finalization remains absent because OD-001 is still open; adding it now
 * would turn a guess about capture ordering into a published schema.
 */
public final class PaymentEvents {

    /** Aggregate kind for every event in this file. */
    public static final String AGGREGATE_TYPE = "PAYMENT";

    /** A payment was accepted for processing. Spec 8.4. */
    public static final EventType PAYMENT_CREATED = new EventType("payment.created", 1, AGGREGATE_TYPE);

    /** A payment reached an unsuccessful terminal outcome. Spec 8.4 and ADR-016. */
    public static final EventType PAYMENT_FAILED = new EventType("payment.failed", 1, AGGREGATE_TYPE);

    private PaymentEvents() {
    }
}
