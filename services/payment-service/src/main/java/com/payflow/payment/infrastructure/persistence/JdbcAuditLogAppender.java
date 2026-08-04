package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.audit.AuditRecord;
import com.payflow.payment.application.port.AuditLogAppender;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Serializes only {@link AuditRecord}'s typed facts; no arbitrary JSON API exists. */
@Component
class JdbcAuditLogAppender implements AuditLogAppender {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcAuditLogAppender(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditRecord record) {
        jdbcTemplate.update(
                "INSERT INTO payment.audit_records (audit_id, action, resource_type, resource_id,"
                        + " actor_subject, decision_code, correlation_id, before_data, after_data,"
                        + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
                record.auditId(),
                record.action(),
                record.resourceType(),
                record.resourceId(),
                record.actorSubject(),
                record.decisionCode(),
                record.correlationId(),
                objectMapper.writeValueAsString(record.before()),
                objectMapper.writeValueAsString(record.after()),
                record.occurredAt().atOffset(ZoneOffset.UTC));
    }
}
