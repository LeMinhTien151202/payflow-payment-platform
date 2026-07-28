package com.payflow.notification.infrastructure.delivery;

import com.payflow.notification.application.delivery.EmailDeliveryResult;
import com.payflow.notification.application.delivery.EmailMessage;
import com.payflow.notification.application.port.EmailDeliveryPort;
import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Local email mock that records one message per notification id and performs no network I/O.
 * Duplicate sends return success without creating another mock side effect.
 */
public final class InMemoryEmailDeliveryAdapter implements EmailDeliveryPort {

    private final Clock clock;
    private final ConcurrentMap<java.util.UUID, EmailMessage> deliveredMessages =
            new ConcurrentHashMap<>();

    public InMemoryEmailDeliveryAdapter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EmailDeliveryResult deliver(EmailMessage message) {
        Objects.requireNonNull(message, "message");
        EmailMessage existing = deliveredMessages.putIfAbsent(message.notificationId(), message);
        if (existing != null && !existing.equals(message)) {
            throw new NotificationInvariantViolationException(
                    "notification id was already delivered with different email content");
        }
        return EmailDeliveryResult.delivered(Instant.now(clock));
    }

    public List<EmailMessage> deliveredMessages() {
        return List.copyOf(deliveredMessages.values());
    }
}
