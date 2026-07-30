package com.payflow.notification.infrastructure.persistence;

import com.payflow.notification.application.notification.OutcomeNotificationIntent;
import com.payflow.notification.application.port.NotificationRecord;
import com.payflow.notification.application.port.NotificationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcNotificationStore implements NotificationStore {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final String SELECT = """
            select id, source_event_id, source_event_type, aggregate_id,
                   business_reference_type, business_reference_id, recipient_type, recipient_id,
                   template_code, payload::text as payload, created_at
              from notification.notifications
             where business_reference_type = :referenceType
               and business_reference_id = :referenceId
               and channel = 'EMAIL'
            """;
    private static final String INSERT = """
            insert into notification.notifications (
                id, source_event_id, source_event_type, aggregate_id,
                business_reference_type, business_reference_id, recipient_type, recipient_id,
                channel, template_code, payload, status, attempt_count, next_attempt_at, created_at)
            values (
                :id, :sourceEventId, :sourceEventType, :aggregateId,
                :referenceType, :referenceId, :recipientType, :recipientId,
                'EMAIL', :templateCode, cast(:payload as jsonb), 'PENDING', 0,
                clock_timestamp(), :createdAt)
            on conflict (business_reference_type, business_reference_id, channel) do nothing
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcNotificationStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<NotificationRecord> findByBusinessReference(
            String referenceType, UUID referenceId) {
        var parameters = new MapSqlParameterSource()
                .addValue("referenceType", referenceType)
                .addValue("referenceId", referenceId);
        return jdbc.query(SELECT, parameters, this::map).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean saveIfAbsent(NotificationRecord record) {
        var intent = record.intent();
        var parameters = new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("sourceEventId", intent.sourceEventId())
                .addValue("sourceEventType", intent.sourceEventType())
                .addValue("aggregateId", intent.aggregateId())
                .addValue("referenceType", intent.businessReferenceType())
                .addValue("referenceId", intent.businessReferenceId())
                .addValue("recipientType", intent.recipientType())
                .addValue("recipientId", intent.recipientId())
                .addValue("templateCode", intent.templateCode())
                .addValue("payload", objectMapper.writeValueAsString(intent.payload()))
                .addValue("createdAt", intent.createdAt());
        return jdbc.update(INSERT, parameters) == 1;
    }

    private NotificationRecord map(ResultSet row, int rowNumber) throws SQLException {
        var intent = new OutcomeNotificationIntent(
                row.getObject("source_event_id", UUID.class),
                row.getString("source_event_type"),
                row.getString("aggregate_id"),
                row.getString("business_reference_type"),
                row.getObject("business_reference_id", UUID.class),
                row.getString("recipient_type"),
                row.getString("recipient_id"),
                row.getString("template_code"),
                objectMapper.readValue(row.getString("payload"), STRING_MAP),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
        return new NotificationRecord(row.getObject("id", UUID.class), intent);
    }
}
