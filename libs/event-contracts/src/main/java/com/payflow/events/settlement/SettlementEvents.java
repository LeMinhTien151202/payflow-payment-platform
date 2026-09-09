package com.payflow.events.settlement;

import com.payflow.events.EventType;

/** Versioned facts owned by settlement-service. */
public final class SettlementEvents {

    public static final String AGGREGATE_TYPE = "SETTLEMENT";

    public static final EventType SETTLEMENT_COMPLETED =
            new EventType("settlement.completed", 1, AGGREGATE_TYPE);

    private SettlementEvents() {
    }
}
