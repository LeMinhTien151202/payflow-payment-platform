package com.payflow.notification.application.delivery;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ClaimedNotification(
        UUID id,
        String recipientId,
        String templateCode,
        Map<String, String> payload,
        int attemptCount,
        Instant createdAt,
        boolean reclaimed) {

    public ClaimedNotification {
        Objects.requireNonNull(id, "id");
        if (recipientId == null || recipientId.isBlank()) {
            throw new IllegalArgumentException("recipientId must not be blank");
        }
        if (templateCode == null || templateCode.isBlank()) {
            throw new IllegalArgumentException("templateCode must not be blank");
        }
        Objects.requireNonNull(payload, "payload");
        payload = Map.copyOf(new LinkedHashMap<>(payload));
        if (attemptCount < 1) {
            throw new IllegalArgumentException("claimed notification requires an attempt");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
