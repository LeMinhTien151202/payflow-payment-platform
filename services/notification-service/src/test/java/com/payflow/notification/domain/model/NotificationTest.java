package com.payflow.notification.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTest {

    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant CREATED_AT = Instant.parse("2026-07-28T01:00:00Z");

    @Test
    void createsPendingEmailNotificationWithAnImmutablePayload() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("paymentId", "22222222-2222-4222-8222-222222222222");

        Notification notification = notification(source);
        source.put("status", "tampered");

        assertThat(notification.id()).isEqualTo(ID);
        assertThat(notification.channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.attemptCount()).isZero();
        assertThat(notification.payload()).containsOnlyKeys("paymentId");
        assertThatThrownBy(() -> notification.payload().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recordsSuccessfulDeliveryOnce() {
        Notification notification = notification(Map.of());
        Instant completedAt = CREATED_AT.plusSeconds(2);

        assertThat(notification.recordSent(completedAt)).isTrue();
        assertThat(notification.recordSent(completedAt.plusSeconds(1))).isFalse();

        assertThat(notification.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.attemptCount()).isEqualTo(1);
        assertThat(notification.lastAttemptAt()).isEqualTo(completedAt);
        assertThat(notification.sentAt()).isEqualTo(completedAt);
        assertThat(notification.failureCode()).isNull();
    }

    @Test
    void recordsStableFailureOnceWithoutPretendingTheNotificationWasSent() {
        Notification notification = notification(Map.of());
        Instant completedAt = CREATED_AT.plusSeconds(3);

        assertThat(notification.recordFailure(completedAt, "MOCK_PROVIDER_UNAVAILABLE")).isTrue();
        assertThat(notification.recordFailure(completedAt.plusSeconds(1), "OTHER")).isFalse();

        assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.attemptCount()).isEqualTo(1);
        assertThat(notification.lastAttemptAt()).isEqualTo(completedAt);
        assertThat(notification.sentAt()).isNull();
        assertThat(notification.failureCode()).isEqualTo("MOCK_PROVIDER_UNAVAILABLE");
    }

    @Test
    void rejectsChangingOneTerminalOutcomeIntoAnother() {
        Notification sent = notification(Map.of());
        sent.recordSent(CREATED_AT);
        Notification failed = notification(Map.of());
        failed.recordFailure(CREATED_AT, "MOCK_FAILURE");

        assertThatThrownBy(() -> sent.recordFailure(CREATED_AT, "MOCK_FAILURE"))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("SENT to FAILED");
        assertThatThrownBy(() -> failed.recordSent(CREATED_AT))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("FAILED to SENT");
    }

    @Test
    void rejectsCompletionBeforeCreationWithoutChangingState() {
        Notification notification = notification(Map.of());

        assertThatThrownBy(() -> notification.recordSent(CREATED_AT.minusNanos(1)))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("before notification creation");

        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.attemptCount()).isZero();
    }

    @Test
    void rejectsBlankFailureCodeWithoutPartiallyRecordingAnAttempt() {
        Notification notification = notification(Map.of());

        assertThatThrownBy(() -> notification.recordFailure(CREATED_AT.plusSeconds(1), " "))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("failureCode");

        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.attemptCount()).isZero();
        assertThat(notification.lastAttemptAt()).isNull();
        assertThat(notification.failureCode()).isNull();
    }

    @Test
    void rejectsBlankIdentityTemplateAndPayloadFields() {
        assertThatThrownBy(() -> Notification.createEmail(
                        ID, " ", "recipient-1", "PAYMENT_SUCCEEDED", Map.of(), CREATED_AT))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("recipientType");
        assertThatThrownBy(() -> Notification.createEmail(
                        ID, "CUSTOMER", " ", "PAYMENT_SUCCEEDED", Map.of(), CREATED_AT))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("recipientId");
        assertThatThrownBy(() -> Notification.createEmail(
                        ID, "CUSTOMER", "recipient-1", " ", Map.of(), CREATED_AT))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("templateCode");
        assertThatThrownBy(() -> Notification.createEmail(
                        ID,
                        "CUSTOMER",
                        "recipient-1",
                        "PAYMENT_SUCCEEDED",
                        Map.of("paymentId", " "),
                        CREATED_AT))
                .isInstanceOf(NotificationInvariantViolationException.class)
                .hasMessageContaining("payload value");
    }

    private static Notification notification(Map<String, String> payload) {
        return Notification.createEmail(
                ID, "CUSTOMER", "recipient-1", "PAYMENT_SUCCEEDED", payload, CREATED_AT);
    }
}
