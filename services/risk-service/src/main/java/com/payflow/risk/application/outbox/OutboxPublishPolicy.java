package com.payflow.risk.application.outbox;

import java.time.Duration;

public record OutboxPublishPolicy(
        int batchSize, Duration lease, int maxAttempts, Duration maxBackoff) {

    public OutboxPublishPolicy {
        if (batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("outbox sizes and attempts must be positive");
        }
        requirePositive(lease, "lease");
        requirePositive(maxBackoff, "maxBackoff");
    }

    public Duration backoffFor(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount, 0), 30);
        return Duration.ofSeconds(Math.min(1L << exponent, maxBackoff.toSeconds()));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("outbox " + name + " must be positive");
        }
    }
}
