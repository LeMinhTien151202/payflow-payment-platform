package com.payflow.payment.infrastructure.messaging;

import com.payflow.payment.application.inbox.IncomingEventIdentity;
import com.payflow.payment.application.port.ProcessedEventStore;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL {@code ON CONFLICT DO NOTHING} implementation of ADR-017's inbox gate. */
@Component
class JdbcProcessedEventStore implements ProcessedEventStore {

    static final String INSERT_IF_NEW =
            """
            insert into payment.processed_events (
                event_id, consumer_name, event_type, aggregate_id, processed_at)
            values (
                :eventId, :consumerName, :eventType, :aggregateId, :processedAt)
            on conflict (event_id, consumer_name) do nothing
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcProcessedEventStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordIfNew(IncomingEventIdentity event) {
        // Bound as OffsetDateTime, not Instant: the PostgreSQL driver cannot infer a SQL type for
        // java.time.Instant and fails the statement. UTC because the column is TIMESTAMPTZ, which
        // mirrors how every read here converts back with getObject(..., OffsetDateTime.class).
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("consumerName", event.consumerName())
                .addValue("eventType", event.eventType())
                .addValue("aggregateId", event.aggregateId())
                .addValue("processedAt", event.processedAt().atOffset(ZoneOffset.UTC));
        return jdbc.update(INSERT_IF_NEW, parameters) == 1;
    }
}
