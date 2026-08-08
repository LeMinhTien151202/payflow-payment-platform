package com.payflow.ledger.infrastructure.persistence;

import com.payflow.ledger.application.port.OutboxAppender;
import com.payflow.events.EventEnvelope;
import com.payflow.events.EventHeaders;
import com.payflow.events.EventType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Persists the finished wire envelope using one stable id for every publish attempt. */
@Component
class JdbcOutboxAppender implements OutboxAppender {

    private static final String PRODUCER = "ledger-service";
    private static final String INSERT = """
            insert into ledger_runtime.outbox_events (
                id, aggregate_type, aggregate_id, event_type, event_version, topic,
                payload, headers, status, attempt_count, next_attempt_at, created_at)
            values (
                :id, :aggregateType, :aggregateId, :eventType, :eventVersion, :topic,
                cast(:payload as jsonb), cast(:headers as jsonb), 'PENDING', 0, :createdAt, :createdAt)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    JdbcOutboxAppender(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> UUID appendCausedBy(
            EventType type,
            String topic,
            String aggregateId,
            Instant occurredAt,
            T data,
            EventEnvelope<?> cause) {
        UUID eventId = UUID.randomUUID();
        EventEnvelope<T> envelope = EventEnvelope.causedBy(
                eventId, type, aggregateId, cause, PRODUCER, occurredAt, data);
        Map<String, String> headers = Map.of(
                EventHeaders.CORRELATION_ID, envelope.correlationId(),
                EventHeaders.EVENT_ID, eventId.toString(),
                EventHeaders.EVENT_TYPE, envelope.eventType(),
                EventHeaders.EVENT_VERSION, Integer.toString(envelope.eventVersion()));
        var parameters = new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("aggregateType", envelope.aggregateType())
                .addValue("aggregateId", envelope.aggregateId())
                .addValue("eventType", envelope.eventType())
                .addValue("eventVersion", envelope.eventVersion())
                .addValue("topic", topic)
                .addValue("payload", objectMapper.writeValueAsString(envelope))
                .addValue("headers", objectMapper.writeValueAsString(headers))
                .addValue("createdAt", clock.instant().atOffset(ZoneOffset.UTC));
        jdbc.update(INSERT, parameters);
        return eventId;
    }
}
