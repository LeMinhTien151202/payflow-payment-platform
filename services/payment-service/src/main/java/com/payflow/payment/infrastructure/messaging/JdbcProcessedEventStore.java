package com.payflow.payment.infrastructure.messaging;

import com.payflow.payment.application.inbox.IncomingEventIdentity;
import com.payflow.payment.application.port.ProcessedEventStore;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implementation {@code ON CONFLICT DO NOTHING} trên PostgreSQL cho inbox gate của ADR-017. */
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
        // Khởi tạo dưới dạng OffsetDateTime, chứ không phải Instant: driver PostgreSQL không thể tự suy luận kiểu SQL cho
        // java.time.Instant và làm câu lệnh thất bại. Dùng UTC vì cột DB có kiểu TIMESTAMPTZ,
        // phản chiếu lại cách mọi câu lệnh đọc ở đây convert ngược lại với getObject(..., OffsetDateTime.class).
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("consumerName", event.consumerName())
                .addValue("eventType", event.eventType())
                .addValue("aggregateId", event.aggregateId())
                .addValue("processedAt", event.processedAt().atOffset(ZoneOffset.UTC));
        return jdbc.update(INSERT_IF_NEW, parameters) == 1;
    }
}
