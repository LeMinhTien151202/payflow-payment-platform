package com.payflow.settlement.application.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ClaimedOutboxEvent(UUID eventId, String aggregateId, String topic, String payload,
        Map<String, String> headers, int attemptCount, Instant createdAt, boolean reclaimed) {
    public ClaimedOutboxEvent {
        Objects.requireNonNull(eventId); Objects.requireNonNull(aggregateId); Objects.requireNonNull(topic);
        Objects.requireNonNull(payload); headers = Map.copyOf(headers); Objects.requireNonNull(createdAt);
        if (attemptCount < 1) throw new IllegalArgumentException("attemptCount must be positive");
    }
}
