package com.payflow.notification.application.notification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record OutcomeNotificationIntent(
        UUID sourceEventId,
        String sourceEventType,
        String aggregateId,
        String businessReferenceType,
        UUID businessReferenceId,
        String recipientType,
        String recipientId,
        String templateCode,
        Map<String, String> payload,
        Instant createdAt) {

    public OutcomeNotificationIntent {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        sourceEventType = requireText(sourceEventType, "sourceEventType", 150);
        aggregateId = requireText(aggregateId, "aggregateId", 100);
        businessReferenceType = requireText(businessReferenceType, "businessReferenceType", 40);
        Objects.requireNonNull(businessReferenceId, "businessReferenceId");
        recipientType = requireText(recipientType, "recipientType", 40);
        recipientId = requireText(recipientId, "recipientId", 100);
        templateCode = requireText(templateCode, "templateCode", 80);
        Objects.requireNonNull(payload, "payload");
        payload = Map.copyOf(new LinkedHashMap<>(payload));
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return value;
    }
}
