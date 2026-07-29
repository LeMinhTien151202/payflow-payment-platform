package com.payflow.accountledger.infrastructure.persistence;

import com.payflow.accountledger.application.inbox.IncomingEventIdentity;
import com.payflow.accountledger.application.port.ProcessedEventStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL insert-if-new inbox gate from ADR-017. */
@Component
class JdbcProcessedEventStore implements ProcessedEventStore {

    private static final String INSERT = """
            insert into account_ledger.processed_events (
                event_id, consumer_name, event_type, aggregate_id, processed_at)
            values (:eventId, :consumerName, :eventType, :aggregateId, :processedAt)
            on conflict (event_id, consumer_name) do nothing
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcProcessedEventStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordIfNew(IncomingEventIdentity event) {
        var parameters = new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("consumerName", event.consumerName())
                .addValue("eventType", event.eventType())
                .addValue("aggregateId", event.aggregateId())
                .addValue("processedAt", event.processedAt());
        return jdbc.update(INSERT, parameters) == 1;
    }
}
