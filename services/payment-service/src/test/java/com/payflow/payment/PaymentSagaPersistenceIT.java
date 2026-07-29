package com.payflow.payment;

import static com.payflow.payment.application.CreatePaymentCommands.MERCHANT_ID;
import static com.payflow.payment.application.CreatePaymentCommands.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.application.CreatePaymentResult;
import com.payflow.payment.application.exception.ConcurrentSagaUpdateException;
import com.payflow.payment.application.handler.CreatePaymentHandler;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.saga.VersionedPaymentSaga;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL proof for Saga creation, due selection and optimistic recovery ownership. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "payflow.outbox.enabled=false",
            "payflow.saga-recovery.enabled=false"
        })
class PaymentSagaPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private CreatePaymentHandler createPayment;

    @Autowired
    private PaymentSagaStore sagas;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void acceptingPaymentPersistsExactlyOneInitialSaga() {
        String suffix = UUID.randomUUID().toString();
        CreatePaymentResult result = createPayment.handle(request()
                .key("KEY-" + suffix)
                .reference("REF-" + suffix)
                .build());

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM payment.payment_sagas"
                        + " WHERE payment_id = ? AND current_step = 'RISK_ASSESSMENT'"
                        + " AND status = 'RUNNING' AND version = 0",
                Integer.class,
                result.payment().paymentId());

        assertThat(count).isEqualTo(1);
    }

    @Test
    void staleSagaVersionCannotCommitASecondRecoveryDecision() {
        UUID paymentId = insertPayment();
        UUID sagaId = insertSaga(paymentId, Instant.now().minusSeconds(1), "RUNNING");
        VersionedPaymentSaga first = load(sagaId);
        VersionedPaymentSaga stale = load(sagaId);
        Instant changedAt = first.saga().updatedAt().plusSeconds(1);

        first.saga().recordRetry("RISK_TIMEOUT", changedAt.plusSeconds(30), changedAt);
        stale.saga().recordRetry("RISK_TIMEOUT", changedAt.plusSeconds(30), changedAt);
        transactions.executeWithoutResult(status -> sagas.update(first));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> sagas.update(stale)))
                .isInstanceOf(ConcurrentSagaUpdateException.class);

        VersionedPaymentSaga stored = load(sagaId);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.saga().retryCount()).isEqualTo(1);
    }

    @Test
    void dueScanIsOrderedBoundedAndExcludesFutureOrManualWork() {
        // An old isolated window keeps rows from other shared-container test classes out of this scan.
        Instant now = Instant.parse("2000-01-01T00:00:00Z");
        UUID first = insertSaga(insertPayment(), now.minusSeconds(20), "RUNNING");
        UUID second = insertSaga(insertPayment(), now.minusSeconds(10), "COMPENSATING");
        insertSaga(insertPayment(), now.plusSeconds(10), "RUNNING");
        insertSaga(insertPayment(), now.minusSeconds(30), "MANUAL_REVIEW_REQUIRED");

        List<UUID> due = sagas.findDueIds(now, 2);

        assertThat(due).containsExactly(first, second);
    }

    private VersionedPaymentSaga load(UUID sagaId) {
        return transactions.execute(status -> sagas.find(sagaId).orElseThrow());
    }

    private UUID insertPayment() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment.payments (id, merchant_id, customer_id, source_account_id,"
                        + " merchant_reference, idempotency_key, amount, currency, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 100, 'VND',"
                        + " 'RISK_CHECKING', now() - interval '2 minutes',"
                        + " now() - interval '2 minutes')",
                id,
                MERCHANT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "KEY-" + UUID.randomUUID());
        return id;
    }

    private UUID insertSaga(UUID paymentId, Instant deadline, String status) {
        UUID id = UUID.randomUUID();
        String step = "COMPENSATING".equals(status) ? "RELEASE_FUNDS" : "RISK_ASSESSMENT";
        UUID reservationId = "COMPENSATING".equals(status) ? UUID.randomUUID() : null;
        jdbc.update(
                "INSERT INTO payment.payment_sagas (id, payment_id, current_step, status,"
                        + " deadline_at, retry_count, reservation_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 0, ?, now() - interval '2 minutes',"
                        + " now() - interval '2 minutes')",
                id,
                paymentId,
                step,
                status,
                deadline,
                reservationId);
        return id;
    }
}
