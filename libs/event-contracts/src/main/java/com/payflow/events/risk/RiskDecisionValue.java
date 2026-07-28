package com.payflow.events.risk;

/** Wire values fixed by ADR-016. This is a contract enum, not Risk domain behavior. */
public enum RiskDecisionValue {
    APPROVED,
    REVIEW_REQUIRED,
    REJECTED
}
