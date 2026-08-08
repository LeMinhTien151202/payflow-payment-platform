package com.payflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.notification.application.notification.CreateOutcomeNotificationHandler;
import com.payflow.notification.application.notification.OutcomeNotificationFactory;
import com.payflow.notification.application.port.WebhookDeliveryStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL evidence for duplicate-safe webhook retry and audited manual requeue. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WebhookPersistenceIT extends AbstractNotificationRuntimeIT {

    @Autowired private CreateOutcomeNotificationHandler handler;
    @Autowired private OutcomeNotificationFactory factory;
    @Autowired private WebhookDeliveryStore webhooks;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("delete from notification.webhook_audit");
        jdbc.execute("delete from notification.webhook_deliveries");
        jdbc.execute("delete from notification.processed_events");
        jdbc.execute("delete from notification.notifications");
    }

    @Test
    void duplicateAndRetryKeepOneEventIdAndTheExactRawBody() {
        Instant now = Instant.parse("2026-08-07T00:00:00Z");
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        var data = new PaymentSucceededData(
                paymentId,
                merchantId,
                UUID.randomUUID(),
                new BigDecimal("100.0000"),
                "VND",
                now);
        var event = EventEnvelope.of(
                UUID.randomUUID(),
                PaymentEvents.PAYMENT_SUCCEEDED,
                paymentId.toString(),
                "corr-webhook-persistence-it",
                "payment-service",
                now,
                data);

        handler.handle(factory.paymentSucceeded(event));
        handler.handle(factory.paymentSucceeded(event));
        var first = webhooks.claim("worker-a", Duration.ofSeconds(30), 1).getFirst();
        assertThat(first.eventId()).isEqualTo(event.eventId());
        assertThat(first.rawBody()).contains(event.eventId().toString());
        assertThat(webhooks.retry(
                        first.id(), "worker-a", 503, "temporary", Instant.EPOCH))
                .isTrue();

        var second = webhooks.claim("worker-b", Duration.ofSeconds(30), 1).getFirst();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(second.rawBody()).isEqualTo(first.rawBody());
        assertThat(jdbc.queryForObject(
                        "select count(*) from notification.webhook_deliveries where event_id=?",
                        Integer.class,
                        event.eventId()))
                .isEqualTo(1);

        assertThat(webhooks.dead(second.id(), "worker-b", 400, "bad request", "PERMANENT"))
                .isTrue();
        assertThat(webhooks.manualRequeue(
                        second.id(), "operations", "corr-manual-retry", Instant.now()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from notification.webhook_audit where delivery_id=?",
                        Integer.class,
                        second.id()))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                        "update notification.webhook_audit set actor_id='tampered'"
                                + " where delivery_id=?",
                        second.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
