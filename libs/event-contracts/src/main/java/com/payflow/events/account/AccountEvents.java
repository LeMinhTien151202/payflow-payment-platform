package com.payflow.events.account;

import com.payflow.events.EventType;

/** Versioned contracts for Account reservation and capture facts/commands. */
public final class AccountEvents {

    /** Saga messages are keyed and aggregated by payment even though Account owns the balance. */
    public static final String AGGREGATE_TYPE = "PAYMENT";

    public static final EventType FUNDS_RESERVED =
            new EventType("account.funds-reserved", 1, AGGREGATE_TYPE);

    public static final EventType CAPTURE_REQUESTED =
            new EventType("account.capture.requested", 1, AGGREGATE_TYPE);

    public static final EventType FUNDS_CAPTURED =
            new EventType("account.funds-captured", 1, AGGREGATE_TYPE);

    private AccountEvents() {
    }
}
