package com.payflow.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.notification.application.delivery.EmailDeliveryResult;
import com.payflow.notification.application.delivery.EmailMessage;
import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryEmailDeliveryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-28T02:00:00Z");

    @Test
    void storesOneMockMessageAndReturnsDeterministicCompletionTime() {
        InMemoryEmailDeliveryAdapter adapter =
                new InMemoryEmailDeliveryAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
        EmailMessage message = message("11111111-1111-4111-8111-111111111111");

        EmailDeliveryResult result = adapter.deliver(message);

        assertThat(result.delivered()).isTrue();
        assertThat(result.completedAt()).isEqualTo(NOW);
        assertThat(adapter.deliveredMessages()).containsExactly(message);
    }

    @Test
    void sameNotificationDoesNotCreateAnotherMockSideEffect() {
        InMemoryEmailDeliveryAdapter adapter =
                new InMemoryEmailDeliveryAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
        EmailMessage first = message("11111111-1111-4111-8111-111111111111");

        adapter.deliver(first);
        adapter.deliver(first);

        assertThat(adapter.deliveredMessages()).containsExactly(first);
    }

    @Test
    void sameNotificationIdWithDifferentContentIsAnIdempotencyConflict() {
        InMemoryEmailDeliveryAdapter adapter =
                new InMemoryEmailDeliveryAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
        EmailMessage first = message("11111111-1111-4111-8111-111111111111");
        EmailMessage conflicting = new EmailMessage(
                first.notificationId(), "customer-2", "OTHER", Map.of());
        adapter.deliver(first);

        assertThatThrownBy(() -> adapter.deliver(conflicting))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("different email content");
        assertThat(adapter.deliveredMessages()).containsExactly(first);
    }

    private static EmailMessage message(String id) {
        return new EmailMessage(
                UUID.fromString(id),
                "customer-1",
                "PAYMENT_SUCCEEDED",
                Map.of("paymentId", "payment-1"));
    }
}
