package com.payflow.events.risk;

import com.payflow.events.EventType;

/** Event contracts owned by risk-service. */
public final class RiskEvents {

    /** The workflow stays ordered by payment, so the aggregate identity is the payment. */
    public static final String AGGREGATE_TYPE = "PAYMENT";

    /** One normalized automated assessment completed. ADR-016. */
    public static final EventType RISK_ASSESSMENT_COMPLETED =
            new EventType("risk.assessment.completed", 1, AGGREGATE_TYPE);

    private RiskEvents() {
    }
}
