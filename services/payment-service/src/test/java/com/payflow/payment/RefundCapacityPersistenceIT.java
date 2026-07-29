package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.application.port.RefundPaymentStore;
import com.payflow.payment.domain.exception.RefundCapacityExceededException;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL evidence prepared for ADR-019/020. Requires Docker; no-docker only compiles it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefundCapacityPersistenceIT extends AbstractPostgresIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefundPaymentStore payments;
    @Autowired private TransactionTemplate transactions;

    @Test
    @DisplayName("database refuses succeeded plus reserved refund above original amount")
    void databaseGuardsCapacity() {
        UUID paymentId = insertSucceededPayment("100.0000");

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payment.payments"
                                + " SET total_refunded_amount = 60.0000, reserved_refund_amount = 50.0000"
                                + " WHERE id = ?",
                        paymentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_refund_capacity_not_exceeded");
    }

    @Test
    @DisplayName("FOR UPDATE makes concurrent refund intake observe committed reserved capacity")
    void rowLockPreventsConcurrentOversubscription() throws Exception {
        UUID paymentId = insertSucceededPayment("100.0000");
        UUID merchantId = merchantId(paymentId);
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> transactions.execute(status -> {
                Payment payment = payments.findForRefund(paymentId, merchantId).orElseThrow();
                payment.reserveRefund(Money.of("80", "VND"), Instant.now());
                firstHasLock.countDown();
                await(allowFirstCommit);
                payments.updateRefundState(payment);
                return true;
            }));

            assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = executor.submit(() -> transactions.execute(status -> {
                Payment payment = payments.findForRefund(paymentId, merchantId).orElseThrow();
                try {
                    payment.reserveRefund(Money.of("30", "VND"), Instant.now());
                    payments.updateRefundState(payment);
                    return true;
                } catch (RefundCapacityExceededException expected) {
                    return false;
                }
            }));

            // The second transaction cannot decide from the stale zero reservation while the first
            // owns the row. It completes only after the committed 80 is visible.
            assertThat(second.isDone()).isFalse();
            allowFirstCommit.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
        }

        assertThat(jdbc.queryForObject(
                        "SELECT reserved_refund_amount FROM payment.payments WHERE id = ?",
                        java.math.BigDecimal.class,
                        paymentId))
                .isEqualByComparingTo("80.0000");
    }

    @Test
    @DisplayName("fee snapshot and reversal constraints reject impossible history")
    void databaseGuardsFeeFacts() {
        UUID paymentId = insertSucceededPayment("100.0000");

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payment.payments SET fee_amount = 2.0000,"
                                + " total_fee_reversed_amount = 2.0001 WHERE id = ?",
                        paymentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_fee_reversal_valid");

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE payment.payments SET fee_currency = 'USD' WHERE id = ?",
                        paymentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payments_fee_currency_matches");
    }

    private UUID insertSucceededPayment(String amount) {
        UUID id = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment.payments (id, merchant_id, customer_id, source_account_id,"
                        + " merchant_reference, idempotency_key, amount, currency, status,"
                        + " fee_policy_version, applied_fee_rate, fee_amount, fee_currency,"
                        + " fee_rounding_mode, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'VND', 'SUCCEEDED',"
                        + " 'TEST_FEE_V1', 0.020000, CAST(? AS NUMERIC) * 0.020000, 'VND',"
                        + " 'HALF_UP', now(), now())",
                id,
                merchant,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "REF-" + id,
                "KEY-" + id,
                new java.math.BigDecimal(amount),
                new java.math.BigDecimal(amount));
        return id;
    }

    private UUID merchantId(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT merchant_id FROM payment.payments WHERE id = ?", UUID.class, paymentId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrent transaction");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating test", interrupted);
        }
    }
}
