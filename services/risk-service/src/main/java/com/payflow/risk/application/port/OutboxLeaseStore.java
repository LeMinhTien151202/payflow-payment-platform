package com.payflow.risk.application.port;

import com.payflow.risk.application.outbox.ClaimedOutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxLeaseStore {
    List<ClaimedOutboxEvent> claim(String owner, Duration lease, int batchSize);

    boolean markPublished(UUID eventId, String owner);

    boolean markRetry(UUID eventId, String owner, Instant nextAttemptAt, String safeError);

    boolean markFailed(UUID eventId, String owner, String safeError);

    double oldestPendingAgeSeconds();
}
