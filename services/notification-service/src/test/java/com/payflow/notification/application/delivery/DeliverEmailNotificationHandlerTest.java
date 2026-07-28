package com.payflow.notification.application.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.notification.application.port.EmailDeliveryPort;
import com.payflow.notification.domain.model.Notification;
import com.payflow.notification.domain.model.NotificationStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeliverEmailNotificationHandlerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant CREATED_AT = Instant.parse("2026-07-28T01:00:00Z");

    @Test
    void mapsNotificationToPortAndRecordsSuccess() {
        AtomicReference<EmailMessage> delivered = new AtomicReference<>();
        EmailDeliveryPort port = message -> {
            delivered.set(message);
            return EmailDeliveryResult.delivered(CREATED_AT.plusSeconds(1));
        };
        Notification notification = notification();

        DeliveryDisposition disposition = new DeliverEmailNotificationHandler(port).handle(notification);

        assertThat(disposition).isEqualTo(DeliveryDisposition.SENT);
        assertThat(notification.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivered.get().notificationId()).isEqualTo(ID);
        assertThat(delivered.get().recipientId()).isEqualTo("customer-1");
        assertThat(delivered.get().payload()).containsEntry("paymentId", "payment-1");
    }

    @Test
    void recordsClassifiedFailureWithoutLeakingAnExceptionMessage() {
        EmailDeliveryPort port = message -> EmailDeliveryResult.failed(
                CREATED_AT.plusSeconds(1), "MOCK_PROVIDER_UNAVAILABLE");
        Notification notification = notification();

        DeliveryDisposition disposition = new DeliverEmailNotificationHandler(port).handle(notification);

        assertThat(disposition).isEqualTo(DeliveryDisposition.FAILED);
        assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.failureCode()).isEqualTo("MOCK_PROVIDER_UNAVAILABLE");
    }

    @Test
    void duplicateDeliveryAfterTerminalStateDoesNotCallThePortAgain() {
        AtomicInteger calls = new AtomicInteger();
        EmailDeliveryPort port = message -> {
            calls.incrementAndGet();
            return EmailDeliveryResult.delivered(CREATED_AT.plusSeconds(1));
        };
        DeliverEmailNotificationHandler handler = new DeliverEmailNotificationHandler(port);
        Notification notification = notification();

        assertThat(handler.handle(notification)).isEqualTo(DeliveryDisposition.SENT);
        assertThat(handler.handle(notification)).isEqualTo(DeliveryDisposition.ALREADY_FINALIZED);
        assertThat(calls).hasValue(1);
        assertThat(notification.attemptCount()).isEqualTo(1);
    }

    @Test
    void rejectsNullPortResultAndLeavesNotificationPending() {
        Notification notification = notification();

        assertThatThrownBy(() -> new DeliverEmailNotificationHandler(message -> null)
                        .handle(notification))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("deliveryPort result");
        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.attemptCount()).isZero();
    }

    private static Notification notification() {
        return Notification.createEmail(
                ID,
                "CUSTOMER",
                "customer-1",
                "PAYMENT_SUCCEEDED",
                Map.of("paymentId", "payment-1"),
                CREATED_AT);
    }
}
