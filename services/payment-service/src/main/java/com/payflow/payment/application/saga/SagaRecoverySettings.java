package com.payflow.payment.application.saga;

import java.time.Duration;
import java.util.Objects;

/** Bounded settings shared by Saga creation and deadline recovery. */
public record SagaRecoverySettings(Duration stepTimeout, int maxRetries, int batchSize) {

    public SagaRecoverySettings {
        Objects.requireNonNull(stepTimeout, "stepTimeout");
        if (stepTimeout.isZero() || stepTimeout.isNegative()) {
            throw new IllegalArgumentException("Saga stepTimeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Saga maxRetries cannot be negative");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Saga batchSize must be between 1 and 1000");
        }
    }
}
