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
 * <p>Only {@code payment.created} exists so far. The rest of the payment lifecycle
 * ({@code payment.succeeded}, {@code payment.failed}, the account and risk commands) is deliberately
 * absent — those belong to Phase 1B, and several depend on decisions still marked {@code OPEN} in
 * {@code .docs/OPEN_DECISIONS.md}. Adding a contract before its decision is resolved would turn a
 * guess into a published schema.
 */
public final class PaymentEvents {

    /** Aggregate kind for every event in this file. */
    public static final String AGGREGATE_TYPE = "PAYMENT";

    /** A payment was accepted for processing. Spec 8.4. */
    public static final EventType PAYMENT_CREATED = new EventType("payment.created", 1, AGGREGATE_TYPE);

    private PaymentEvents() {
    }
}
