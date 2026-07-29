package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.application.port.RefundPaymentStore;
import com.payflow.payment.application.CreateRefundResult;
import com.payflow.payment.application.command.CreateRefundCommand;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.handler.CreateRefundHandler;
import com.payflow.payment.application.handler.HandleRefundWorkflowEventHandler;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.inbox.EventProcessingResult;
import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerRefundPostedData;
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
    @Autowired private CreateRefundHandler createRefund;
    @Autowired private HandleRefundWorkflowEventHandler refundWorkflow;

    @Test
    @DisplayName("refund financial outcomes persist inbox, durable facts, capacity and outbox")
    void workflowCommitsJournalThenCreditAndDeduplicatesRedelivery() {
        UUID paymentId = insertSucceededPayment("100.0000");
        UUID merchantId = merchantId(paymentId);
        UUID accountId = accountId(paymentId);
        CreateRefundResult accepted = createRefund.handle(new CreateRefundCommand(
                merchantId,
                "test-merchant-actor",
                paymentId,
                "refund-" + UUID.randomUUID(),
                new java.math.BigDecimal("40.0000"),
                null));
        UUID refundId = accepted.refund().refundId();
        UUID journalId = UUID.randomUUID();
        UUID creditId = UUID.randomUUID();
        var posted = envelope(
                LedgerEvents.REFUND_POSTED,
                paymentId,
                new LedgerRefundPostedData(
                        refundId,
                        paymentId,
                        journalId,
                        accountId,
                        new java.math.BigDecimal("40.0000"),
                        "VND"));
        var credited = envelope(
                AccountEvents.REFUND_CREDITED,
                paymentId,
                new AccountRefundCreditedData(
                        refundId,
                        paymentId,
                        accountId,
                        journalId,
                        creditId,
                        new java.math.BigDecimal("40.0000"),
                        "VND"));

        assertThat(refundWorkflow.handleLedgerRefundPosted(posted))
                .isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(refundWorkflow.handleAccountRefundCredited(credited))
                .isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(refundWorkflow.handleAccountRefundCredited(credited))
                .isEqualTo(EventProcessingResult.DUPLICATE);

        var refund = jdbc.queryForMap(
                "SELECT status, ledger_journal_id, account_credit_id, fee_reversal_amount"
                        + " FROM payment.refunds WHERE id = ?",
                refundId);
        assertThat(refund.get("status")).isEqualTo("SUCCEEDED");
        assertThat(refund.get("ledger_journal_id")).isEqualTo(journalId);
        assertThat(refund.get("account_credit_id")).isEqualTo(creditId);
        assertThat((java.math.BigDecimal) refund.get("fee_reversal_amount"))
                .isEqualByComparingTo("0.8000");
        assertThat(jdbc.queryForObject(
                        "SELECT total_refunded_amount FROM payment.payments WHERE id = ?",
                        java.math.BigDecimal.class,
                        paymentId))
                .isEqualByComparingTo("40.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.outbox_events"
                                + " WHERE aggregate_id = ? AND event_type IN"
                                + " ('account.refund-credit.requested', 'refund.succeeded')",
                        Integer.class,
                        paymentId.toString()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("refund success outbox failure rolls back inbox, credit fact and capacity completion")
    void workflowRollsBackAllCreditOutcomeFactsWhenOutboxFails() {
        UUID paymentId = insertSucceededPayment("100.0000");
        UUID merchantId = merchantId(paymentId);
        UUID accountId = accountId(paymentId);
        UUID refundId = createRefund.handle(new CreateRefundCommand(
                        merchantId,
                        "test-merchant-actor",
                        paymentId,
                        "refund-" + UUID.randomUUID(),
                        new java.math.BigDecimal("40.0000"),
                        null))
                .refund()
                .refundId();
        UUID journalId = UUID.randomUUID();
        refundWorkflow.handleLedgerRefundPosted(envelope(
                LedgerEvents.REFUND_POSTED,
                paymentId,
                new LedgerRefundPostedData(
                        refundId,
                        paymentId,
                        journalId,
                        accountId,
                        new java.math.BigDecimal("40.0000"),
                        "VND")));
        UUID creditEventId = UUID.randomUUID();
        var credited = EventEnvelope.of(
                creditEventId,
                AccountEvents.REFUND_CREDITED,
                paymentId.toString(),
                "corr-refund-workflow-it",
                "account-ledger-service",
                Instant.now(),
                new AccountRefundCreditedData(
                        refundId,
                        paymentId,
                        accountId,
                        journalId,
                        UUID.randomUUID(),
                        new java.math.BigDecimal("40.0000"),
                        "VND"));
        jdbc.execute("""
                CREATE FUNCTION payment.test_reject_refund_success_outbox() RETURNS TRIGGER
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'refund.succeeded' THEN
                        RAISE EXCEPTION 'injected refund success outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_refund_success_outbox
                BEFORE INSERT ON payment.outbox_events
                FOR EACH ROW EXECUTE FUNCTION payment.test_reject_refund_success_outbox()
                """);

        try {
            assertThatThrownBy(() -> refundWorkflow.handleAccountRefundCredited(credited))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute(
                    "DROP TRIGGER trg_test_reject_refund_success_outbox ON payment.outbox_events");
            jdbc.execute("DROP FUNCTION payment.test_reject_refund_success_outbox()");
        }

        var refund = jdbc.queryForMap(
                "SELECT status, account_credit_id FROM payment.refunds WHERE id = ?", refundId);
        assertThat(refund.get("status")).isEqualTo("PROCESSING");
        assertThat(refund.get("account_credit_id")).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_refund_amount FROM payment.payments WHERE id = ?",
                        java.math.BigDecimal.class,
                        paymentId))
                .isEqualByComparingTo("40.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.processed_events"
                                + " WHERE event_id = ? AND consumer_name = ?",
                        Integer.class,
                        creditEventId,
                        "payment-refund-orchestrator-v1"))
                .isZero();
    }

    @Test
    @DisplayName("refund, capacity, idempotency and outbox commit atomically and replay once")
    void intakeCommitsOneLogicalRefundAndReplays() {
        UUID paymentId = insertSucceededPayment("100.0000");
        UUID merchantId = merchantId(paymentId);
        String key = "refund-" + UUID.randomUUID();
        CreateRefundCommand command = new CreateRefundCommand(
                merchantId,
                "test-merchant-actor",
                paymentId,
                key,
                new java.math.BigDecimal("60.0000"),
                "test return");

        CreateRefundResult first = createRefund.handle(command);
        CreateRefundResult replay = createRefund.handle(command);

        assertThat(first).isInstanceOf(CreateRefundResult.Accepted.class);
        assertThat(replay).isInstanceOf(CreateRefundResult.Replayed.class);
        assertThat(replay.refund()).isEqualTo(first.refund());
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_refund_amount FROM payment.payments WHERE id = ?",
                        java.math.BigDecimal.class,
                        paymentId))
                .isEqualByComparingTo("60.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.refunds WHERE payment_id = ?",
                        Integer.class,
                        paymentId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.idempotency_records"
                                + " WHERE scope = ? AND idempotency_key = ?",
                        Integer.class,
                        IdempotencyScope.createRefund(merchantId),
                        key))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.outbox_events"
                                + " WHERE aggregate_id = ? AND event_type = 'refund.requested'",
                        Integer.class,
                        paymentId.toString()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("same refund key with a different payload conflicts without another write")
    void intakeRejectsDifferentPayloadForSameKey() {
        UUID paymentId = insertSucceededPayment("100.0000");
        UUID merchantId = merchantId(paymentId);
        String key = "refund-" + UUID.randomUUID();
        CreateRefundCommand first = new CreateRefundCommand(
                merchantId,
                "test-merchant-actor",
                paymentId,
                key,
                new java.math.BigDecimal("60.0000"),
                null);
        createRefund.handle(first);

        assertThatThrownBy(() -> createRefund.handle(new CreateRefundCommand(
                        merchantId,
                        "test-merchant-actor",
                        paymentId,
                        key,
                        new java.math.BigDecimal("61.0000"),
                        null)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_refund_amount FROM payment.payments WHERE id = ?",
                        java.math.BigDecimal.class,
                        paymentId))
                .isEqualByComparingTo("60.0000");
    }

    @Test
    @DisplayName("outbox failure rolls back refund, capacity and idempotency together")
    void intakeRollsBackEveryLocalFactWhenOutboxCannotCommit() {
        UUID paymentId = insertSucceededPayment("100.0000");
        UUID merchantId = merchantId(paymentId);
        String key = "refund-" + UUID.randomUUID();
        jdbc.execute("""
                CREATE FUNCTION payment.test_reject_refund_outbox() RETURNS TRIGGER
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'refund.requested' THEN
                        RAISE EXCEPTION 'injected refund outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_refund_outbox
                BEFORE INSERT ON payment.outbox_events
                FOR EACH ROW EXECUTE FUNCTION payment.test_reject_refund_outbox()
                """);

        try {
            assertThatThrownBy(() -> createRefund.handle(new CreateRefundCommand(
                            merchantId,
                            "test-merchant-actor",
                            paymentId,
                            key,
                            new java.math.BigDecimal("60.0000"),
                            null)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_reject_refund_outbox ON payment.outbox_events");
            jdbc.execute("DROP FUNCTION payment.test_reject_refund_outbox()");
        }

        assertThat(jdbc.queryForObject(
                        "SELECT reserved_refund_amount FROM payment.payments WHERE id = ?",
                        java.math.BigDecimal.class,
                        paymentId))
                .isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.refunds WHERE payment_id = ?",
                        Integer.class,
                        paymentId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment.idempotency_records"
                                + " WHERE scope = ? AND idempotency_key = ?",
                        Integer.class,
                        IdempotencyScope.createRefund(merchantId),
                        key))
                .isZero();
    }

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

    private UUID accountId(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT source_account_id FROM payment.payments WHERE id = ?",
                UUID.class,
                paymentId);
    }

    private static <T> EventEnvelope<T> envelope(EventType type, UUID paymentId, T data) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                type,
                paymentId.toString(),
                "corr-refund-workflow-it",
                "account-ledger-service",
                Instant.now(),
                data);
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
