package com.payflow.account.application.outbox;

import java.time.Duration;

/** Runtime policy fixed by ADR-014 and supplied by configuration. */
public record OutboxPublishPolicy(
        int batchSize, Duration lease, int maxAttempts, Duration maxBackoff) {

    public OutboxPublishPolicy {
        if (batchSize < 1) {
            throw new IllegalArgumentException("outbox batchSize must be positive");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("outbox lease must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("outbox maxAttempts must be positive");
        }
        if (maxBackoff == null || maxBackoff.isZero() || maxBackoff.isNegative()) {
            throw new IllegalArgumentException("outbox maxBackoff must be positive");
        }
    }

    /** ADR-014: {@code min(2^attempt_count, maxBackoff)} seconds. */
    public Duration backoffFor(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount, 0), 30);
        long seconds = Math.min(1L << exponent, maxBackoff.toSeconds());
        return Duration.ofSeconds(seconds);
    }
}
