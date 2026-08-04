package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL proof that typed operations evidence is append-only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditSchemaIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("audit rows insert but cannot be updated or deleted")
    void auditIsAppendOnly() {
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payment.audit_records (audit_id, action, resource_type, resource_id,"
                        + " actor_subject, decision_code, correlation_id, before_data, after_data,"
                        + " occurred_at) VALUES (?, 'MANUAL_REVIEW_RESOLVED', 'PAYMENT', ?,"
                        + " 'operator-subject', 'RETRY_CAPTURE', 'audit-test-1', '{}'::jsonb,"
                        + " '{}'::jsonb, now())",
                auditId,
                UUID.randomUUID());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM payment.audit_records WHERE audit_id = ?",
                        Long.class,
                        auditId))
                .isOne();
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE payment.audit_records SET decision_code = 'FAIL_PAYMENT'"
                                + " WHERE audit_id = ?",
                        auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM payment.audit_records WHERE audit_id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
