package com.payflow.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.payment.PaymentEvents;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    private static final UUID EVENT_ID = UUID.fromString("31734b31-8e75-4570-bdc6-979fa02ab446");
    private static final String AGGREGATE_ID = "c73e17b5-aaca-48da-9ed5-bb0937499f01";
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-24T03:00:00Z");
    private static final String CORRELATION_ID = "0a1b2c3d-4e5f-6789-abcd-ef0123456789";

    private static EventEnvelope<String> envelope() {
        return EventEnvelope.of(
                EVENT_ID,
                PaymentEvents.PAYMENT_CREATED,
                AGGREGATE_ID,
                CORRELATION_ID,
                "payment-service",
                OCCURRED_AT,
                "payload");
    }

    @Test
    @DisplayName("of() expands the event type into the three flat wire fields")
    void ofExpandsEventType() {
        EventEnvelope<String> envelope = envelope();

        assertThat(envelope.eventType()).isEqualTo("payment.created");
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.aggregateType()).isEqualTo("PAYMENT");
        assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("an event caused by a request has no causationId")
    void requestCausedEventHasNoCausationId() {
        assertThat(envelope().causationId()).isNull();
    }

    @Test
    @DisplayName("causedBy() inherits the correlation id and records the cause's eventId")
    void causedByLinksTheChain() {
        EventEnvelope<String> cause = envelope();

        EventEnvelope<String> effect =
                EventEnvelope.causedBy(
                        UUID.randomUUID(),
                        PaymentEvents.PAYMENT_CREATED,
                        AGGREGATE_ID,
                        cause,
                        "payment-service",
                        OCCURRED_AT,
                        "payload");

        assertThat(effect.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(effect.causationId()).isEqualTo(EVENT_ID.toString());
    }

    @Test
    @DisplayName("a correlation id with a newline is rejected, not sanitised")
    void rejectsLogInjectionInCorrelationId() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.of(
                                        EVENT_ID,
                                        PaymentEvents.PAYMENT_CREATED,
                                        AGGREGATE_ID,
                                        "abc\nWARN forged log line",
                                        "payment-service",
                                        OCCURRED_AT,
                                        "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    @DisplayName("a missing correlation id is rejected: an event with no trace is not publishable")
    void rejectsMissingCorrelationId() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.of(
                                        EVENT_ID,
                                        PaymentEvents.PAYMENT_CREATED,
                                        AGGREGATE_ID,
                                        null,
                                        "payment-service",
                                        OCCURRED_AT,
                                        "payload"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("data is required: an envelope with no payload is a bug, not an empty event")
    void rejectsNullData() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.of(
                                        EVENT_ID,
                                        PaymentEvents.PAYMENT_CREATED,
                                        AGGREGATE_ID,
                                        CORRELATION_ID,
                                        "payment-service",
                                        OCCURRED_AT,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("aggregateId is required: without it there is no Kafka key and no ordering")
    void rejectsBlankAggregateId() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.of(
                                        EVENT_ID,
                                        PaymentEvents.PAYMENT_CREATED,
                                        "  ",
                                        CORRELATION_ID,
                                        "payment-service",
                                        OCCURRED_AT,
                                        "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    @DisplayName("an aggregateId too long for the outbox column fails here, not at insert time")
    void rejectsOverlongAggregateId() {
        String tooLong = "x".repeat(EventEnvelope.MAX_AGGREGATE_ID_LENGTH + 1);

        assertThatThrownBy(
                        () ->
                                EventEnvelope.of(
                                        EVENT_ID,
                                        PaymentEvents.PAYMENT_CREATED,
                                        tooLong,
                                        CORRELATION_ID,
                                        "payment-service",
                                        OCCURRED_AT,
                                        "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    @DisplayName("producer is required: an event nobody owns cannot be diagnosed")
    void rejectsBlankProducer() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.of(
                                        EVENT_ID,
                                        PaymentEvents.PAYMENT_CREATED,
                                        AGGREGATE_ID,
                                        CORRELATION_ID,
                                        "",
                                        OCCURRED_AT,
                                        "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("producer");
    }

    @Test
    @DisplayName("eventId is required: it is the consumer's deduplication key")
    void rejectsNullEventId() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.of(
                                        null,
                                        PaymentEvents.PAYMENT_CREATED,
                                        AGGREGATE_ID,
                                        CORRELATION_ID,
                                        "payment-service",
                                        OCCURRED_AT,
                                        "payload"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventId");
    }
}
