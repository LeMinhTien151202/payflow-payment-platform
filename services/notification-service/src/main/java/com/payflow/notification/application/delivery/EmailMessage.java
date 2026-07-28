package com.payflow.notification.application.delivery;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EmailMessage(
        UUID notificationId, String recipientId, String templateCode, Map<String, String> payload) {

    public EmailMessage {
        Objects.requireNonNull(notificationId, "notificationId");
        requireText(recipientId, "recipientId");
        requireText(templateCode, "templateCode");
        Objects.requireNonNull(payload, "payload");
        payload = Map.copyOf(new LinkedHashMap<>(payload));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
