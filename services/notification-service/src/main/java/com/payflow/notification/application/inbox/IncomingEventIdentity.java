package com.payflow.notification.application.inbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record IncomingEventIdentity(
        UUID eventId,
        String consumerName,
        String eventType,
        String aggregateId,
        Instant processedAt) {

    private static final Pattern CONSUMER_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,99}");

    public IncomingEventIdentity {
        Objects.requireNonNull(eventId, "eventId");
        if (consumerName == null || !CONSUMER_NAME.matcher(consumerName).matches()) {
            throw new IllegalArgumentException("invalid stable consumerName");
        }
        eventType = requireText(eventType, "eventType", 150);
        aggregateId = requireText(aggregateId, "aggregateId", 100);
        Objects.requireNonNull(processedAt, "processedAt");
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return value;
    }
}
