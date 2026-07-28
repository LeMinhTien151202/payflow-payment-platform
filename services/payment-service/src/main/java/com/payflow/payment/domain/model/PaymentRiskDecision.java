package com.payflow.payment.domain.model;

/** Payment-owned interpretation of the wire decision from ADR-016. */
public enum PaymentRiskDecision {
    APPROVED,
    REVIEW_REQUIRED,
    REJECTED
}
