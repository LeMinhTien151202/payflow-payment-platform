package com.payflow.notification.application.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.notification.application.port.EmailDeliveryPort;
import com.payflow.notification.application.port.NotificationDeliveryStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliverPendingNotificationsHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private final NotificationDeliveryStore store = mock(NotificationDeliveryStore.class);
    private final EmailDeliveryPort email = mock(EmailDeliveryPort.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final NotificationDeliveryPolicy properties = new NotificationDeliveryPolicy(
            10, Duration.ofSeconds(30), Duration.ofSeconds(5), 5);
    private final DeliverPendingNotificationsHandler handler =
            new DeliverPendingNotificationsHandler(store, email, properties, metrics);

    @Test
    void deliversAfterClaimAndMarksSent() {
        var claimed = claimed();
        when(store.claim(anyString(), eq(properties.lease()), eq(5), eq(10)))
                .thenReturn(new NotificationClaimBatch(List.of(claimed), 0));
        when(store.oldestPendingAgeSeconds()).thenReturn(2.0);
        when(email.deliver(any())).thenReturn(EmailDeliveryResult.delivered(NOW));
        when(store.markSent(eq(claimed.id()), anyString(), eq(NOW))).thenReturn(true);

        handler.deliverDue();

        verify(email).deliver(any());
        verify(store).markSent(eq(claimed.id()), anyString(), eq(NOW));
        assertThat(metrics.counter("payflow.notification.delivery", "outcome", "sent").count())
                .isEqualTo(1);
    }

    @Test
    void providerExceptionBecomesStableFailureCodeWithoutLeakingMessage() {
        var claimed = claimed();
        when(store.claim(anyString(), any(), eq(5), eq(10)))
                .thenReturn(new NotificationClaimBatch(List.of(claimed), 0));
        when(email.deliver(any())).thenThrow(new IllegalStateException("secret provider response"));
        when(store.markFailed(eq(claimed.id()), anyString(), eq("EMAIL_PROVIDER_ERROR")))
                .thenReturn(true);

        handler.deliverDue();

        verify(store).markFailed(eq(claimed.id()), anyString(), eq("EMAIL_PROVIDER_ERROR"));
        verify(store, never()).markSent(any(), anyString(), any());
    }

    @Test
    void unsafeProviderFailureCodeIsNormalized() {
        var claimed = claimed();
        when(store.claim(anyString(), any(), eq(5), eq(10)))
                .thenReturn(new NotificationClaimBatch(List.of(claimed), 0));
        when(email.deliver(any())).thenReturn(EmailDeliveryResult.failed(NOW, "raw provider detail"));
        when(store.markFailed(eq(claimed.id()), anyString(), eq("EMAIL_PROVIDER_REJECTED")))
                .thenReturn(true);

        handler.deliverDue();

        verify(store).markFailed(eq(claimed.id()), anyString(), eq("EMAIL_PROVIDER_REJECTED"));
    }

    private static ClaimedNotification claimed() {
        return new ClaimedNotification(UUID.randomUUID(), "customer-1", "PAYMENT_SUCCEEDED",
                Map.of("paymentId", "payment-1"), 1, NOW.minusSeconds(1), false);
    }
}
