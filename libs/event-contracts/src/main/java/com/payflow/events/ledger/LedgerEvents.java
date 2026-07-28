package com.payflow.events.ledger;

import com.payflow.events.EventType;

/** Versioned contracts owned by the Ledger boundary. */
public final class LedgerEvents {

    public static final String AGGREGATE_TYPE = "PAYMENT";

    public static final EventType PAYMENT_POSTED =
            new EventType("ledger.payment-posted", 1, AGGREGATE_TYPE);

    public static final EventType POST_PAYMENT_REQUESTED =
            new EventType("ledger.post-payment.requested", 1, AGGREGATE_TYPE);

    private LedgerEvents() {
    }
}
