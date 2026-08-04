package com.payflow.payment;

import static com.payflow.payment.application.CreatePaymentCommands.MERCHANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.application.handler.ResolveManualReviewHandler;
import com.payflow.payment.application.operations.ManualReviewDecision;
import com.payflow.payment.application.operations.ResolveManualReviewCommand;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL proof that resolution state, outbox command and append-only audit are one transaction. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "payflow.outbox.enabled=false",
            "payflow.saga-recovery.enabled=false"
        })
class ManualReviewResolutionPersistenceIT extends AbstractPostgresIT {

    @Autowired ResolveManualReviewHandler handler;
    @Autowired JdbcTemplate jdbc;

    @Test
    void riskApprovalPersistsStateCommandHistoryAndTypedAuditTogether() {
        UUID paymentId = insertRiskReview();

        var result = handler.handle(command(paymentId));

        assertThat(result.paymentStatus().name()).isEqualTo("RESERVING_FUNDS");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM payment.payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("RESERVING_FUNDS");
        assertThat(jdbc.queryForObject(
                        "SELECT current_step || ':' || status FROM payment.payment_sagas WHERE payment_id = ?",
                        String.class, paymentId))
                .isEqualTo("RESERVE_FUNDS:RUNNING");
        assertThat(count("payment.outbox_events", paymentId, "aggregate_id"))
                .isEqualTo(1);
        assertThat(count("payment.audit_records", paymentId, "resource_id"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT after_data->>'paymentStatus' FROM payment.audit_records WHERE resource_id = ?",
                        String.class, paymentId))
                .isEqualTo("RESERVING_FUNDS");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.payment_status_history"
                                + " WHERE payment_id = ? AND from_status = 'MANUAL_REVIEW_REQUIRED'"
                                + " AND to_status = 'RESERVING_FUNDS'",
                        Integer.class, paymentId))
                .isEqualTo(1);
    }

    @Test
    void auditInsertFailureRollsBackPaymentSagaHistoryAndOutbox() {
        UUID paymentId = insertRiskReview();
        jdbc.execute("""
                CREATE FUNCTION payment.test_reject_operations_audit() RETURNS TRIGGER
                    LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected operations audit failure';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_operations_audit
                    BEFORE INSERT ON payment.audit_records
                    FOR EACH ROW EXECUTE FUNCTION payment.test_reject_operations_audit()
                """);
        try {
            assertThatThrownBy(() -> handler.handle(command(paymentId)))
                    .isInstanceOf(DataAccessException.class);

            assertThat(jdbc.queryForObject(
                            "SELECT status FROM payment.payments WHERE id = ?", String.class, paymentId))
                    .isEqualTo("MANUAL_REVIEW_REQUIRED");
            assertThat(jdbc.queryForObject(
                            "SELECT current_step || ':' || status FROM payment.payment_sagas WHERE payment_id = ?",
                            String.class, paymentId))
                    .isEqualTo("RISK_ASSESSMENT:MANUAL_REVIEW_REQUIRED");
            assertThat(count("payment.outbox_events", paymentId, "aggregate_id")).isZero();
            assertThat(count("payment.audit_records", paymentId, "resource_id")).isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM payment.payment_status_history WHERE payment_id = ?",
                            Integer.class, paymentId))
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_test_reject_operations_audit ON payment.audit_records");
            jdbc.execute("DROP FUNCTION IF EXISTS payment.test_reject_operations_audit()");
        }
    }

    private ResolveManualReviewCommand command(UUID paymentId) {
        return new ResolveManualReviewCommand(
                paymentId,
                ManualReviewDecision.APPROVE_RISK,
                "OPS_VERIFIED_RISK_APPROVAL",
                "operations-integration-test",
                "ops-it-" + paymentId);
    }

    private UUID insertRiskReview() {
        UUID paymentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment.payments (id, merchant_id, customer_id, source_account_id,"
                        + " merchant_reference, idempotency_key, amount, currency, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 500000, 'VND',"
                        + " 'MANUAL_REVIEW_REQUIRED', now() - interval '2 minutes',"
                        + " now() - interval '1 minute')",
                paymentId,
                MERCHANT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "OPS-REF-" + paymentId,
                "OPS-KEY-" + paymentId);
        jdbc.update(
                "INSERT INTO payment.payment_sagas (id, payment_id, current_step, status,"
                        + " deadline_at, retry_count, last_error_code, created_at, updated_at)"
                        + " VALUES (?, ?, 'RISK_ASSESSMENT', 'MANUAL_REVIEW_REQUIRED',"
                        + " now() - interval '1 minute', 3, 'RISK_REVIEW_REQUIRED',"
                        + " now() - interval '2 minutes', now() - interval '1 minute')",
                UUID.randomUUID(), paymentId);
        return paymentId;
    }

    private int count(String table, UUID id, String column) {
        // Table/column are constants owned by this test; the UUID remains a bind parameter.
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, "aggregate_id".equals(column) ? id.toString() : id);
        return count == null ? 0 : count;
    }
}
