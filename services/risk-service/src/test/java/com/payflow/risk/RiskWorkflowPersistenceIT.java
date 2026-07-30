package com.payflow.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.risk.application.handler.HandlePaymentCreatedHandler;
import com.payflow.risk.application.inbox.EventProcessingResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL + Redis evidence prepared for the later infrastructure-enabled verification gate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RiskWorkflowPersistenceIT extends AbstractRiskRuntimeIT {

    @Autowired private HandlePaymentCreatedHandler handler;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void sixthPaymentTriggersVelocityAndEveryDeliveryStaysExactlyOnceInDatabase() {
        UUID customerId = UUID.randomUUID();
        EventEnvelope<PaymentCreatedData> sixth = null;
        for (int index = 0; index < 6; index++) {
            EventEnvelope<PaymentCreatedData> event = paymentCreated(
                    UUID.randomUUID(), customerId, new BigDecimal("100.0000"), index);
            assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);
            sixth = event;
        }

        assertThat(handler.handle(sixth)).isEqualTo(EventProcessingResult.DUPLICATE);
        assertThat(jdbc.queryForObject(
                        "select score from risk.risk_assessments where payment_id = ?",
                        Integer.class,
                        sixth.data().paymentId()))
                .isEqualTo(40);
        assertThat(jdbc.queryForObject(
                        "select decision from risk.risk_assessments where payment_id = ?",
                        String.class,
                        sixth.data().paymentId()))
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from risk.outbox_events where aggregate_id = ?",
                        Integer.class,
                        sixth.aggregateId()))
                .isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackInboxAndAssessment() {
        EventEnvelope<PaymentCreatedData> event = paymentCreated(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.0000"), 0);
        jdbc.execute("""
                create function risk.test_reject_risk_outbox() returns trigger
                language plpgsql as $$
                begin
                    raise exception 'injected risk outbox failure';
                end;
                $$
                """);
        jdbc.execute("""
                create trigger trg_test_reject_risk_outbox
                before insert on risk.outbox_events
                for each row execute function risk.test_reject_risk_outbox()
                """);
        try {
            assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("drop trigger trg_test_reject_risk_outbox on risk.outbox_events");
            jdbc.execute("drop function risk.test_reject_risk_outbox()");
        }

        assertThat(jdbc.queryForObject(
                        "select count(*) from risk.processed_events where event_id = ?",
                        Integer.class,
                        event.eventId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from risk.risk_assessments where payment_id = ?",
                        Integer.class,
                        event.data().paymentId()))
                .isZero();
    }

    private static EventEnvelope<PaymentCreatedData> paymentCreated(
            UUID paymentId, UUID customerId, BigDecimal amount, int seconds) {
        Instant occurredAt = Instant.parse("2026-07-30T02:00:00Z").plusSeconds(seconds);
        var data = new PaymentCreatedData(
                paymentId, UUID.randomUUID(), customerId, UUID.randomUUID(),
                amount, "VND", occurredAt);
        return EventEnvelope.of(
                UUID.randomUUID(), PaymentEvents.PAYMENT_CREATED, paymentId.toString(),
                "risk-runtime-it", "payment-service", occurredAt, data);
    }
}
