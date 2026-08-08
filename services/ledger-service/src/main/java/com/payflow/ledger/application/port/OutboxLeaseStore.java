package com.payflow.ledger.application.port;

import com.payflow.ledger.application.outbox.ClaimedOutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Short-transaction database operations used by the polling publisher. */
public interface OutboxLeaseStore {

    List<ClaimedOutboxEvent> claim(String owner, Duration lease, int batchSize);

    boolean markPublished(UUID eventId, String owner);

    boolean markRetry(UUID eventId, String owner, Instant nextAttemptAt, String safeError);

    boolean markFailed(UUID eventId, String owner, String safeError);

    double oldestPendingAgeSeconds();
}
