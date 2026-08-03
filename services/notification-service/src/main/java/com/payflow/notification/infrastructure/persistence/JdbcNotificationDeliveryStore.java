package com.payflow.notification.infrastructure.persistence;

import com.payflow.notification.application.delivery.ClaimedNotification;
import com.payflow.notification.application.delivery.NotificationClaimBatch;
import com.payflow.notification.application.port.NotificationDeliveryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    // Dùng clock_timestamp() thay vì một instant truyền vào, trong tất cả điều kiện bên dưới. next_attempt_at được
    // ghi bởi clock_timestamp() khi insert, do đó việc đọc nó dựa trên đồng hồ của một worker sẽ so sánh
    // hai đồng hồ khác nhau: khi bị trôi đồng hồ (drift), dòng đó có thể chưa đến hạn khi nó thực sự đã đến hạn, hoặc một lease
    // đang sống lại trông như đã hết hạn và worker thứ hai gửi cùng một email. Lease là một protocol giữa các
    // host, và database là đồng hồ duy nhất mà tất cả các host cùng quan sát. Đây là cùng một idiom mà các
    // outbox lease store trong payment, account-ledger và risk đã sử dụng.
    private static final String FAIL_EXHAUSTED = """
            update notification.notifications
               set status = 'FAILED', failure_code = 'DELIVERY_LEASE_EXHAUSTED',
                   lock_owner = null, lock_until = null
             where status = 'PROCESSING' and lock_until <= clock_timestamp()
               and attempt_count >= :maxAttempts
            """;
    private static final String CLAIM = """
            with candidates as (
                select id, (status = 'PROCESSING') as reclaimed
                  from notification.notifications
                 where ((status = 'PENDING' and next_attempt_at <= clock_timestamp())
                        or (status = 'PROCESSING' and lock_until <= clock_timestamp()))
                   and attempt_count < :maxAttempts
                 order by case when status = 'PROCESSING' then 0 else 1 end,
                          next_attempt_at, created_at
                 for update skip locked
                 limit :batchSize
            )
            update notification.notifications n
               set status = 'PROCESSING', attempt_count = n.attempt_count + 1,
                   last_attempt_at = clock_timestamp(), lock_owner = :owner,
                   lock_until = clock_timestamp() + make_interval(secs => :leaseSeconds)
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
    // created_at là occurredAt của nguồn event, nên phép đo này đo độ tuổi từ sự thật nghiệp vụ (business fact)
    // thay vì từ chính dòng này, vốn là độ trễ mà một merchant thực sự nhận thấy. Phép trừ
    // vẫn đi xuyên service, do đó có thêm giới hạn Math.max bên dưới.
    private static final String OLDEST_PENDING_AGE = """
            select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
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
            String owner, Duration lease, int maxAttempts, int batchSize) {
        var parameters = new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("leaseSeconds", lease.toSeconds())
                .addValue("maxAttempts", maxAttempts)
                .addValue("batchSize", batchSize);
        int exhausted = jdbc.update(FAIL_EXHAUSTED, parameters);
        return new NotificationClaimBatch(jdbc.query(CLAIM, parameters, this::mapClaim), exhausted);
    }

    @Override
    @Transactional
    public boolean markSent(UUID notificationId, String owner, Instant sentAt) {
        return jdbc.update(MARK_SENT, terminalParameters(notificationId, owner)
                .addValue("sentAt", sentAt.atOffset(ZoneOffset.UTC))) == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(UUID notificationId, String owner, String failureCode) {
        return jdbc.update(MARK_FAILED, terminalParameters(notificationId, owner)
                .addValue("failureCode", failureCode)) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public double oldestPendingAgeSeconds() {
        Double age = jdbc.getJdbcTemplate().queryForObject(OLDEST_PENDING_AGE, Double.class);
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
