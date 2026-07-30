package com.payflow.notification.infrastructure.persistence;

import com.payflow.notification.application.delivery.ClaimedNotification;
import com.payflow.notification.application.delivery.NotificationClaimBatch;
import com.payflow.notification.application.port.NotificationDeliveryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcNotificationDeliveryStore implements NotificationDeliveryStore {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final String FAIL_EXHAUSTED = """
            update notification.notifications
               set status = 'FAILED', failure_code = 'DELIVERY_LEASE_EXHAUSTED',
                   lock_owner = null, lock_until = null
             where status = 'PROCESSING' and lock_until <= :now and attempt_count >= :maxAttempts
            """;
    private static final String CLAIM = """
            with candidates as (
                select id, (status = 'PROCESSING') as reclaimed
                  from notification.notifications
                 where ((status = 'PENDING' and next_attempt_at <= :now)
                        or (status = 'PROCESSING' and lock_until <= :now))
                   and attempt_count < :maxAttempts
                 order by case when status = 'PROCESSING' then 0 else 1 end,
                          next_attempt_at, created_at
                 for update skip locked
                 limit :batchSize
            )
            update notification.notifications n
               set status = 'PROCESSING', attempt_count = n.attempt_count + 1,
                   last_attempt_at = :now, lock_owner = :owner, lock_until = :lockUntil
              from candidates c
             where n.id = c.id
            returning n.id, n.recipient_id, n.template_code, n.payload::text as payload,
                      n.attempt_count, n.created_at, c.reclaimed
            """;
    private static final String MARK_SENT = """
            update notification.notifications
               set status = 'SENT', sent_at = :sentAt, failure_code = null,
                   lock_owner = null, lock_until = null
             where id = :id and status = 'PROCESSING' and lock_owner = :owner
            """;
    private static final String MARK_FAILED = """
            update notification.notifications
               set status = 'FAILED', sent_at = null, failure_code = :failureCode,
                   lock_owner = null, lock_until = null
             where id = :id and status = 'PROCESSING' and lock_owner = :owner
            """;
    private static final String OLDEST_PENDING_AGE = """
            select coalesce(extract(epoch from (:now - min(created_at))), 0)
              from notification.notifications where status in ('PENDING', 'PROCESSING')
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcNotificationDeliveryStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public NotificationClaimBatch claim(
            String owner, Instant now, Duration lease, int maxAttempts, int batchSize) {
        var parameters = new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("now", now)
                .addValue("lockUntil", now.plus(lease))
                .addValue("maxAttempts", maxAttempts)
                .addValue("batchSize", batchSize);
        int exhausted = jdbc.update(FAIL_EXHAUSTED, parameters);
        return new NotificationClaimBatch(jdbc.query(CLAIM, parameters, this::mapClaim), exhausted);
    }

    @Override
    @Transactional
    public boolean markSent(UUID notificationId, String owner, Instant sentAt) {
        return jdbc.update(MARK_SENT, terminalParameters(notificationId, owner)
                .addValue("sentAt", sentAt)) == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(UUID notificationId, String owner, String failureCode) {
        return jdbc.update(MARK_FAILED, terminalParameters(notificationId, owner)
                .addValue("failureCode", failureCode)) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public double oldestPendingAgeSeconds(Instant now) {
        Double age = jdbc.queryForObject(
                OLDEST_PENDING_AGE, new MapSqlParameterSource("now", now), Double.class);
        return age == null ? 0 : Math.max(age, 0);
    }

    private MapSqlParameterSource terminalParameters(UUID id, String owner) {
        return new MapSqlParameterSource().addValue("id", id).addValue("owner", owner);
    }

    private ClaimedNotification mapClaim(ResultSet row, int rowNumber) throws SQLException {
        return new ClaimedNotification(
                row.getObject("id", UUID.class),
                row.getString("recipient_id"),
                row.getString("template_code"),
                objectMapper.readValue(row.getString("payload"), STRING_MAP),
                row.getInt("attempt_count"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getBoolean("reclaimed"));
    }
}
