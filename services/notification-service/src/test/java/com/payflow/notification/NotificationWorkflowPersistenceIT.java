package com.payflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.notification.application.delivery.NotificationClaimBatch;
import com.payflow.notification.application.inbox.EventProcessingResult;
import com.payflow.notification.application.notification.CreateOutcomeNotificationHandler;
import com.payflow.notification.application.notification.OutcomeNotificationFactory;
import com.payflow.notification.application.port.NotificationDeliveryStore;
import com.payflow.notification.domain.exception.NotificationInvariantViolationException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL evidence prepared for the later infrastructure-enabled verification gate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationWorkflowPersistenceIT extends AbstractNotificationRuntimeIT {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");

    @Autowired private CreateOutcomeNotificationHandler handler;
    @Autowired private OutcomeNotificationFactory factory;
    @Autowired private NotificationDeliveryStore deliveryStore;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("delete from notification.processed_events");
        jdbc.execute("delete from notification.notifications");
    }

    @Test
    void transportAndBusinessDuplicatesCreateOneNotification() {
        var event = paymentSucceeded(UUID.randomUUID(), UUID.randomUUID());
        var republishedBusinessFact = new EventEnvelope<>(UUID.randomUUID(), event.eventType(),
                event.eventVersion(), event.aggregateType(), event.aggregateId(),
                event.correlationId(), null, event.producer(), event.occurredAt(), event.data());

        assertThat(handler.handle(factory.paymentSucceeded(event)))
                .isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(handler.handle(factory.paymentSucceeded(event)))
                .isEqualTo(EventProcessingResult.DUPLICATE);
        assertThat(handler.handle(factory.paymentSucceeded(republishedBusinessFact)))
                .isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);

        assertThat(jdbc.queryForObject(
                "select count(*) from notification.notifications where business_reference_id = ?",
                Integer.class, event.data().paymentId())).isEqualTo(1);
    }

    @Test
    void conflictingOutcomeRollsBackItsInboxMarker() {
        UUID paymentId = UUID.randomUUID();
        var succeeded = paymentSucceeded(paymentId, UUID.randomUUID());
        var failed = EventEnvelope.of(UUID.randomUUID(), PaymentEvents.PAYMENT_FAILED,
                paymentId.toString(), "notification-runtime-it", "payment-service", NOW,
                new PaymentFailedData(paymentId, "FAILED", NOW));
        assertThat(handler.handle(factory.paymentSucceeded(succeeded)))
                .isEqualTo(EventProcessingResult.PROCESSED);

        assertThatThrownBy(() -> handler.handle(factory.paymentFailed(failed)))
                .isInstanceOf(NotificationInvariantViolationException.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from notification.processed_events where event_id = ?",
                Integer.class, failed.eventId())).isZero();
    }

    @Test
    void notificationInsertFailureRollsBackInbox() {
        var event = paymentSucceeded(UUID.randomUUID(), UUID.randomUUID());
        jdbc.execute("""
                create function notification.test_reject_notification() returns trigger
                language plpgsql as $$
                begin
                    raise exception 'injected notification failure';
                end;
                $$
                """);
        jdbc.execute("""
                create trigger trg_test_reject_notification
                before insert on notification.notifications
                for each row execute function notification.test_reject_notification()
                """);
        try {
            assertThatThrownBy(() -> handler.handle(factory.paymentSucceeded(event)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("drop trigger trg_test_reject_notification on notification.notifications");
            jdbc.execute("drop function notification.test_reject_notification()");
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from notification.processed_events where event_id = ?",
                Integer.class, event.eventId())).isZero();
    }

    @Test
    void claimedNotificationCanOnlyBeCompletedByItsLeaseOwner() {
        var event = paymentSucceeded(UUID.randomUUID(), UUID.randomUUID());
        handler.handle(factory.paymentSucceeded(event));

        NotificationClaimBatch batch = deliveryStore.claim(
                "worker-a", NOW.plusSeconds(1), Duration.ofSeconds(30), 5, 10);
        assertThat(batch.notifications()).hasSize(1);
        UUID notificationId = batch.notifications().getFirst().id();
        assertThat(deliveryStore.markSent(notificationId, "worker-b", NOW.plusSeconds(2))).isFalse();
        assertThat(deliveryStore.markSent(notificationId, "worker-a", NOW.plusSeconds(2))).isTrue();
        assertThat(jdbc.queryForObject(
                "select status from notification.notifications where id = ?",
                String.class, notificationId)).isEqualTo("SENT");
    }

    private static EventEnvelope<PaymentSucceededData> paymentSucceeded(
            UUID paymentId, UUID customerId) {
        var data = new PaymentSucceededData(paymentId, UUID.randomUUID(), customerId,
                new BigDecimal("100.0000"), "VND", NOW);
        return EventEnvelope.of(UUID.randomUUID(), PaymentEvents.PAYMENT_SUCCEEDED,
                paymentId.toString(), "notification-runtime-it", "payment-service", NOW, data);
    }
}
