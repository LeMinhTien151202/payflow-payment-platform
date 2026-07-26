package com.payflow.payment.infrastructure.persistence;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventHeaders;
import com.payflow.events.EventType;
import com.payflow.observability.CorrelationId;
import com.payflow.payment.application.port.OutboxAppender;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends an event to {@code payment.outbox_events} inside the caller's transaction.
 *
 * <p>The row id is generated here and used as the envelope's {@code eventId}. That identity is the reason the
 * whole at-least-once chain works: a republish after a crash reuses the stored row, so it carries the same
 * {@code eventId}, so a consumer inbox recognises it as a duplicate (ADR-014). Generating the id when the
 * message is sent instead would make every retry look like a new event.
 *
 * <p>The correlation id is read from the MDC rather than passed down through the use case. It is request
 * context, not a business input, and threading it through every command and port would put a logging concern
 * into the signature of every method between the filter and here.
 */
@Component
class JpaOutboxAppender implements OutboxAppender {

    /**
     * Hardcoded rather than read from {@code spring.application.name}. {@code producer} is a value consumers
     * may filter on, and it should not change because someone edited a configuration property.
     */
    private static final String PRODUCER = "payment-service";

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    JpaOutboxAppender(EntityManager entityManager, ObjectMapper objectMapper, Clock clock) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public <T> UUID append(
            EventType type, String topic, String aggregateId, Instant occurredAt, T data) {

        UUID eventId = UUID.randomUUID();

        EventEnvelope<T> envelope =
                EventEnvelope.of(
                        eventId, type, aggregateId, correlationId(), PRODUCER, occurredAt, data);

        entityManager.persist(
                OutboxEventEntity.pending(
                        eventId,
                        type.aggregateType(),
                        aggregateId,
                        type.name(),
                        type.version(),
                        topic,
                        objectMapper.writeValueAsString(envelope),
                        objectMapper.writeValueAsString(headers(envelope)),
                        // The row's own timestamp. occurredAt is when the business fact happened and stays in
                        // the envelope; if the two ever differ, that difference is worth being able to see.
                        clock.instant()));

        return eventId;
    }

    /**
     * The same values the envelope already carries, duplicated as Kafka headers so a consumer can filter or an
     * operator can read a topic without deserialising the payload. Spec 8.2 makes the body authoritative.
     */
    private static Map<String, String> headers(EventEnvelope<?> envelope) {
        return Map.of(
                EventHeaders.CORRELATION_ID, envelope.correlationId(),
                EventHeaders.EVENT_ID, envelope.eventId().toString(),
                EventHeaders.EVENT_TYPE, envelope.eventType(),
                EventHeaders.EVENT_VERSION, Integer.toString(envelope.eventVersion()));
    }

    /**
     * Falls back to a fresh id rather than failing. An outbox row written outside a request — by a consumer or
     * a scheduled job later on — has no correlation id in scope, and the envelope requires one; refusing to
     * append the event would turn a traceability gap into a lost business fact.
     */
    private static String correlationId() {
        return CorrelationId.resolveOrGenerate(MDC.get(CorrelationId.MDC_KEY));
    }
}
