package com.payflow.account.application.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A durable Account-Ledger outbox row leased to one publisher instance. */
public record ClaimedOutboxEvent(
        UUID eventId,
        String aggregateId,
        String topic,
        String payload,
        Map<String, String> headers,
        int attemptCount,
        Instant createdAt,
        boolean reclaimed) {

    public ClaimedOutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        headers = Map.copyOf(headers);
        Objects.requireNonNull(createdAt, "createdAt");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("a claimed event must have at least one attempt");
        }
    }
}
