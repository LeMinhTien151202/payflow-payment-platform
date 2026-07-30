package com.payflow.risk.infrastructure.persistence;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventHeaders;
import com.payflow.risk.application.port.OutboxAppender;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcOutboxAppender implements OutboxAppender {

    private static final String INSERT = """
            insert into risk.outbox_events (
                id, aggregate_type, aggregate_id, event_type, event_version, topic,
                payload, headers, status, attempt_count, next_attempt_at, created_at)
            values (
                :id, :aggregateType, :aggregateId, :eventType, :eventVersion, :topic,
                cast(:payload as jsonb), cast(:headers as jsonb), 'PENDING', 0, :createdAt, :createdAt)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcOutboxAppender(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, EventEnvelope<?> event) {
        Map<String, String> headers = Map.of(
                EventHeaders.CORRELATION_ID, event.correlationId(),
                EventHeaders.EVENT_ID, event.eventId().toString(),
                EventHeaders.EVENT_TYPE, event.eventType(),
                EventHeaders.EVENT_VERSION, Integer.toString(event.eventVersion()));
        var parameters = new MapSqlParameterSource()
                .addValue("id", event.eventId())
                .addValue("aggregateType", event.aggregateType())
                .addValue("aggregateId", event.aggregateId())
                .addValue("eventType", event.eventType())
                .addValue("eventVersion", event.eventVersion())
                .addValue("topic", topic)
                .addValue("payload", objectMapper.writeValueAsString(event))
                .addValue("headers", objectMapper.writeValueAsString(headers))
                .addValue("createdAt", event.occurredAt());
        jdbc.update(INSERT, parameters);
    }
}
