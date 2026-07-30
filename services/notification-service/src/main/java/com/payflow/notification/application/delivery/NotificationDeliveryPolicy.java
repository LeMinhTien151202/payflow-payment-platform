package com.payflow.notification.application.delivery;

import java.time.Duration;

public record NotificationDeliveryPolicy(
        int batchSize, Duration lease, Duration providerTimeout, int maxAttempts) {

    public NotificationDeliveryPolicy {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("notification batch-size must be between 1 and 500");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("notification lease must be positive");
        }
        if (providerTimeout == null || providerTimeout.isZero() || providerTimeout.isNegative()) {
            throw new IllegalArgumentException("notification provider-timeout must be positive");
        }
        if (lease.compareTo(providerTimeout) <= 0) {
            throw new IllegalArgumentException("notification lease must exceed provider-timeout");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("notification max-attempts must be between 1 and 100");
        }
    }
}
