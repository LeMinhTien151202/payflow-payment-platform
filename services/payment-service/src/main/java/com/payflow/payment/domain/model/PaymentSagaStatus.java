package com.payflow.payment.domain.model;

/** Status vocabulary from spec §9.4. */
public enum PaymentSagaStatus {
    RUNNING,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    FAILED,
    MANUAL_REVIEW_REQUIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED;
    }
}

