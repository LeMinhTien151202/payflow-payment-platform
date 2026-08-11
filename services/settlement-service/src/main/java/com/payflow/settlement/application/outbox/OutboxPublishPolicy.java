package com.payflow.settlement.application.outbox;

import java.time.Duration;

public record OutboxPublishPolicy(int batchSize, Duration lease, int maxAttempts, Duration maxBackoff) {
    public OutboxPublishPolicy {
        if (batchSize < 1 || maxAttempts < 1) throw new IllegalArgumentException("outbox counts must be positive");
        if (lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("lease must be positive");
        if (maxBackoff == null || maxBackoff.isZero() || maxBackoff.isNegative()) throw new IllegalArgumentException("maxBackoff must be positive");
    }
    public Duration backoffFor(int attempt) {
        return Duration.ofSeconds(Math.min(1L << Math.min(Math.max(attempt, 0), 30), maxBackoff.toSeconds()));
    }
}
