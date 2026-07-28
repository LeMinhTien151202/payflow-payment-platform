package com.payflow.notification.domain.model;

import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Notification record aggregate for the Phase 1B email-mock slice.
 *
 * <p>The aggregate protects deterministic state transitions only. A future persistence adapter must
 * store consumer inbox state and notification creation in one local transaction. Outbound delivery
 * must happen after that transaction and must never extend a financial transaction over network I/O.
 */
public final class Notification {

    private final UUID id;
    private final String recipientType;
    private final String recipientId;
    private final NotificationChannel channel;
    private final String templateCode;
    private final Map<String, String> payload;
    private final Instant createdAt;
    private NotificationStatus status;
    private int attemptCount;
    private Instant lastAttemptAt;
    private Instant sentAt;
    private String failureCode;

    private Notification(
            UUID id,
            String recipientType,
            String recipientId,
            NotificationChannel channel,
            String templateCode,
            Map<String, String> payload,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.recipientType = requireText(recipientType, "recipientType");
        this.recipientId = requireText(recipientId, "recipientId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.templateCode = requireText(templateCode, "templateCode");
        this.payload = immutablePayload(payload);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = NotificationStatus.PENDING;
    }

    public static Notification createEmail(
            UUID id,
            String recipientType,
            String recipientId,
            String templateCode,
            Map<String, String> payload,
            Instant createdAt) {
        return new Notification(
                id,
                recipientType,
                recipientId,
                NotificationChannel.EMAIL,
                templateCode,
                payload,
                createdAt);
    }

    public boolean recordSent(Instant completedAt) {
        if (status == NotificationStatus.SENT) {
            return false;
        }
        requirePending(NotificationStatus.SENT);
        Instant validCompletedAt = requireAttemptTime(completedAt);

        attemptCount++;
        lastAttemptAt = validCompletedAt;
        sentAt = validCompletedAt;
        failureCode = null;
        status = NotificationStatus.SENT;
        return true;
    }

    public boolean recordFailure(Instant completedAt, String stableFailureCode) {
        if (status == NotificationStatus.FAILED) {
            return false;
        }
        requirePending(NotificationStatus.FAILED);
        Instant validCompletedAt = requireAttemptTime(completedAt);
        String validFailureCode = requireText(stableFailureCode, "failureCode");

        attemptCount++;
        lastAttemptAt = validCompletedAt;
        failureCode = validFailureCode;
        status = NotificationStatus.FAILED;
        return true;
    }

    private void requirePending(NotificationStatus target) {
        if (status != NotificationStatus.PENDING) {
            throw new NotificationInvariantViolationException(
                    "notification cannot transition from " + status + " to " + target);
        }
    }

    private Instant requireAttemptTime(Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(createdAt)) {
            throw new NotificationInvariantViolationException(
                    "delivery attempt cannot complete before notification creation");
        }
        return completedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new NotificationInvariantViolationException(field + " must not be blank");
        }
        return value;
    }

    private static Map<String, String> immutablePayload(Map<String, String> value) {
        if (value == null) {
            throw new NotificationInvariantViolationException("payload must not be null");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> copy.put(
                requireText(key, "payload key"), requireText(item, "payload value")));
        return Map.copyOf(copy);
    }

    public UUID id() {
        return id;
    }

    public String recipientType() {
        return recipientType;
    }

    public String recipientId() {
        return recipientId;
    }

    public NotificationChannel channel() {
        return channel;
    }

    public String templateCode() {
        return templateCode;
    }

    public Map<String, String> payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public NotificationStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public String failureCode() {
        return failureCode;
    }
}
