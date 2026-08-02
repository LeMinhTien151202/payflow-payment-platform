package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.accountledger.application.outbox.ClaimedOutboxEvent;
import com.payflow.accountledger.application.port.OutboxLeaseStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/** PostgreSQL implementation of ADR-014's two-query lease claim and conditional marks. */
@Component
class JdbcOutboxLeaseStore implements OutboxLeaseStore {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};
    private static final String RETURNING =
            " returning id, aggregate_id, topic, payload::text as payload,"
                    + " headers::text as headers, attempt_count, created_at";

    private static final String RECLAIM =
            """
            update account_ledger.outbox_events
               set lock_owner = :owner,
                   lock_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                   attempt_count = attempt_count + 1
             where id in (
                   select id from account_ledger.outbox_events
                    where status = 'PROCESSING' and lock_until < clock_timestamp()
                    order by created_at
                    limit :limit
                    for update skip locked)
            """
                    + RETURNING;

    private static final String CLAIM_PENDING =
            """
            update account_ledger.outbox_events
               set status = 'PROCESSING',
                   lock_owner = :owner,
                   lock_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                   attempt_count = attempt_count + 1
             where id in (
                   select id from account_ledger.outbox_events
                    where status = 'PENDING' and next_attempt_at <= clock_timestamp()
                    order by created_at
                    limit :limit
                    for update skip locked)
            """
                    + RETURNING;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcOutboxLeaseStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<ClaimedOutboxEvent> claim(String owner, Duration lease, int batchSize) {
        List<ClaimedOutboxEvent> claimed = new ArrayList<>(batchSize);
        claimed.addAll(queryClaims(RECLAIM, owner, lease, batchSize, true));
        int remaining = batchSize - claimed.size();
        if (remaining > 0) {
            claimed.addAll(queryClaims(CLAIM_PENDING, owner, lease, remaining, false));
        }
        claimed.sort(Comparator.comparing(ClaimedOutboxEvent::createdAt));
        return List.copyOf(claimed);
    }

    private List<ClaimedOutboxEvent> queryClaims(
            String sql, String owner, Duration lease, int limit, boolean reclaimed) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
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
        return update(
                        """
                        update account_ledger.outbox_events
                           set status = 'PUBLISHED', published_at = clock_timestamp(),
                               lock_owner = null, lock_until = null, last_error = null
                         where id = :id and status = 'PROCESSING' and lock_owner = :owner
                        """,
                        eventId,
                        owner,
                        Map.of())
                == 1;
    }

    @Override
    @Transactional
    public boolean markRetry(
            UUID eventId, String owner, Instant nextAttemptAt, String safeError) {
        return update(
                        """
                        update account_ledger.outbox_events
                           set status = 'PENDING', next_attempt_at = :nextAttemptAt,
                               lock_owner = null, lock_until = null, last_error = :error
                         where id = :id and status = 'PROCESSING' and lock_owner = :owner
                        """,
                        eventId,
                        owner,
                        Map.of("nextAttemptAt", nextAttemptAt.atOffset(ZoneOffset.UTC), "error", safeError))
                == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(UUID eventId, String owner, String safeError) {
        return update(
                        """
                        update account_ledger.outbox_events
                           set status = 'FAILED', lock_owner = null, lock_until = null,
                               last_error = :error
                         where id = :id and status = 'PROCESSING' and lock_owner = :owner
                        """,
                        eventId,
                        owner,
                        Map.of("error", safeError))
                == 1;
    }

    private int update(
            String sql, UUID eventId, String owner, Map<String, ?> additionalParameters) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource().addValue("id", eventId).addValue("owner", owner);
        additionalParameters.forEach(parameters::addValue);
        return jdbc.update(sql, parameters);
    }

    @Override
    @Transactional(readOnly = true)
    public double oldestPendingAgeSeconds() {
        Double result =
                jdbc.getJdbcTemplate()
                        .queryForObject(
                                """
                                select coalesce(
                                    extract(epoch from (clock_timestamp() - min(created_at))), 0)
                                  from account_ledger.outbox_events
                                 where status = 'PENDING'
                                   and next_attempt_at <= clock_timestamp()
                                """,
                                Double.class);
        return result == null ? 0 : Math.max(result, 0);
    }
}
