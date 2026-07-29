package com.payflow.events.refund;

import com.payflow.events.EventType;

/** Versioned facts owned by payment-service for the refund workflow. */
public final class RefundEvents {

    /** Payment is the ordering aggregate: every refund for one payment uses the same Kafka key. */
    public static final String AGGREGATE_TYPE = "PAYMENT";

    /** Refund exists and its capacity has been durably reserved. */
    public static final EventType REFUND_REQUESTED =
            new EventType("refund.requested", 1, AGGREGATE_TYPE);

    public static final EventType REFUND_SUCCEEDED =
            new EventType("refund.succeeded", 1, AGGREGATE_TYPE);

    public static final EventType REFUND_FAILED =
            new EventType("refund.failed", 1, AGGREGATE_TYPE);

    private RefundEvents() {
    }
}
