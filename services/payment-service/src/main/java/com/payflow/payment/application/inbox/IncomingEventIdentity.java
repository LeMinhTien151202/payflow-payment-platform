package com.payflow.payment.application.inbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Minimal non-payload metadata persisted for durable consumer deduplication. */
public record IncomingEventIdentity(
        UUID eventId,
        String consumerName,
        String eventType,
        String aggregateId,
        Instant processedAt) {

    private static final Pattern CONSUMER_NAME =
            Pattern.compile("[a-z0-9][a-z0-9.-]{0,99}");

    public IncomingEventIdentity {
        Objects.requireNonNull(eventId, "eventId");
        consumerName = requireConsumerName(consumerName);
        eventType = requireText(eventType, "eventType", 150);
        aggregateId = requireText(aggregateId, "aggregateId", 100);
        Objects.requireNonNull(processedAt, "processedAt");
    }

    private static String requireConsumerName(String value) {
        if (value == null || !CONSUMER_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "consumerName must be a stable lowercase name using letters, digits, dot or hyphen");
        }
        return value;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + maxLength + " characters");
        }
        return value;
    }
}

