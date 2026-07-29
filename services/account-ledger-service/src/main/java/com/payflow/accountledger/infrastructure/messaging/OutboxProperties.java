package com.payflow.accountledger.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Type-safe {@code payflow.outbox.*} configuration with ADR-014 fail-fast guards. */
@ConfigurationProperties(prefix = "payflow.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("120s") Duration lease,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("300s") Duration maxBackoff,
        @DefaultValue("30s") Duration deliveryTimeout) {

    public OutboxProperties {
        requirePositive(pollInterval, "pollInterval");
        requirePositive(lease, "lease");
        requirePositive(maxBackoff, "maxBackoff");
        requirePositive(deliveryTimeout, "deliveryTimeout");
        if (batchSize < 1) {
            throw new IllegalArgumentException("payflow.outbox.batch-size must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("payflow.outbox.max-attempts must be positive");
        }
        if (lease.compareTo(deliveryTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "payflow.outbox.lease must be greater than delivery-timeout");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("payflow.outbox." + name + " must be positive");
        }
    }
}
