package com.payflow.risk.infrastructure.messaging;

import com.payflow.risk.application.outbox.ClaimedOutboxEvent;
import com.payflow.risk.application.port.OutboxLeaseStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcOutboxLeaseStore implements OutboxLeaseStore {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};
    private static final String RETURNING =
            " returning id, aggregate_id, topic, payload::text as payload,"
                    + " headers::text as headers, attempt_count, created_at";
    private static final String RECLAIM = """
            update risk.outbox_events
               set lock_owner = :owner,
                   lock_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                   attempt_count = attempt_count + 1
             where id in (
                   select candidate.id from risk.outbox_events candidate
                    where candidate.status = 'PROCESSING'
                      and candidate.lock_until < clock_timestamp()
                      and not exists (
                          select 1 from risk.outbox_events earlier
                           where earlier.aggregate_type = candidate.aggregate_type
                             and earlier.aggregate_id = candidate.aggregate_id
                             and (earlier.created_at, earlier.id)
                                 < (candidate.created_at, candidate.id)
                             and earlier.status <> 'PUBLISHED')
                    order by created_at limit :limit for update skip locked)
            """ + RETURNING;
    private static final String CLAIM_PENDING = """
            update risk.outbox_events
               set status = 'PROCESSING', lock_owner = :owner,
                   lock_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                   attempt_count = attempt_count + 1
             where id in (
                   select candidate.id from risk.outbox_events candidate
                    where candidate.status = 'PENDING'
                      and candidate.next_attempt_at <= clock_timestamp()
                      and not exists (
                          select 1 from risk.outbox_events earlier
                           where earlier.aggregate_type = candidate.aggregate_type
                             and earlier.aggregate_id = candidate.aggregate_id
                             and (earlier.created_at, earlier.id)
                                 < (candidate.created_at, candidate.id)
                             and earlier.status <> 'PUBLISHED')
                    order by created_at limit :limit for update skip locked)
            """ + RETURNING;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcOutboxLeaseStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<ClaimedOutboxEvent> claim(String owner, Duration lease, int batchSize) {
        var claimed = new ArrayList<ClaimedOutboxEvent>(batchSize);
        claimed.addAll(query(RECLAIM, owner, lease, batchSize, true));
        if (claimed.size() < batchSize) {
            claimed.addAll(query(
                    CLAIM_PENDING, owner, lease, batchSize - claimed.size(), false));
        }
        claimed.sort(Comparator.comparing(ClaimedOutboxEvent::createdAt));
        return List.copyOf(claimed);
    }

    private List<ClaimedOutboxEvent> query(
            String sql, String owner, Duration lease, int limit, boolean reclaimed) {
        var parameters = new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("leaseSeconds", lease.toSeconds())
                .addValue("limit", limit);
        return jdbc.query(sql, parameters, (row, rowNumber) -> map(row, reclaimed));
    }

    private ClaimedOutboxEvent map(ResultSet row, boolean reclaimed) throws SQLException {
        return new ClaimedOutboxEvent(
                row.getObject("id", UUID.class),
                row.getString("aggregate_id"),
                row.getString("topic"),
                row.getString("payload"),
                objectMapper.readValue(row.getString("headers"), HEADERS),
                row.getInt("attempt_count"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                reclaimed);
    }

    @Override
    @Transactional
    public boolean markPublished(UUID eventId, String owner) {
        return update("""
                update risk.outbox_events
                   set status = 'PUBLISHED', published_at = clock_timestamp(),
                       lock_owner = null, lock_until = null, last_error = null
                 where id = :id and status = 'PROCESSING' and lock_owner = :owner
                """, eventId, owner, Map.of()) == 1;
    }

    @Override
    @Transactional
    public boolean markRetry(UUID eventId, String owner, Instant nextAttemptAt, String error) {
        return update("""
                update risk.outbox_events
                   set status = 'PENDING', next_attempt_at = :nextAttemptAt,
                       lock_owner = null, lock_until = null, last_error = :error
                 where id = :id and status = 'PROCESSING' and lock_owner = :owner
                """, eventId, owner, Map.of("nextAttemptAt", nextAttemptAt, "error", error)) == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(UUID eventId, String owner, String error) {
        return update("""
                update risk.outbox_events
                   set status = 'FAILED', lock_owner = null, lock_until = null, last_error = :error
                 where id = :id and status = 'PROCESSING' and lock_owner = :owner
                """, eventId, owner, Map.of("error", error)) == 1;
    }

    private int update(String sql, UUID eventId, String owner, Map<String, ?> extra) {
        var parameters = new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("owner", owner);
        extra.forEach(parameters::addValue);
        return jdbc.update(sql, parameters);
    }

    @Override
    @Transactional(readOnly = true)
    public double oldestPendingAgeSeconds() {
        Double value = jdbc.getJdbcTemplate().queryForObject("""
                select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
                  from risk.outbox_events
                 where status = 'PENDING' and next_attempt_at <= clock_timestamp()
                """, Double.class);
        return value == null ? 0 : Math.max(value, 0);
    }
}
