package com.payflow.risk.domain.model;

/** Rule order is part of policy v1 so matched-rules serialization remains deterministic. */
public enum RiskRuleCode {
    AMOUNT_HIGH(30),
    VELOCITY_1M(40),
    VELOCITY_1H(35),
    NEW_DEVICE(10),
    FAILED_BURST(25),
    MERCHANT_SUSPICIOUS(50),
    IP_CHANGE(20);

    private final int points;

    RiskRuleCode(int points) {
        this.points = points;
    }

    public int points() {
        return points;
    }
}
