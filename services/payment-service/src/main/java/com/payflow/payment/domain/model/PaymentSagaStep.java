package com.payflow.payment.domain.model;

/** Durable orchestration step owned by payment-service. */
public enum PaymentSagaStep {
    RISK_ASSESSMENT,
    RESERVE_FUNDS,
    POST_LEDGER,
    CAPTURE_FUNDS,
    RELEASE_FUNDS,
    COMPLETED
}

