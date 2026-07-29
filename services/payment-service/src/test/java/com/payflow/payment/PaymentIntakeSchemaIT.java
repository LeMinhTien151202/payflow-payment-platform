package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the constraints in {@code V2__payment_intake.sql} actually reject what they claim to.
 *
 * <p>Deliberately written against JDBC rather than the domain or a repository. A constraint is a
 * property of the database, and a test that reached it through Java code would pass just as happily if
 * the constraint were missing and the Java check were doing all the work. What is being verified here
 * is the last line of defence: what survives when the application layer is wrong.
 *
 * <p>Requires Docker — see {@link AbstractPostgresIT}.
 *
 * <p>No transaction wrapper and no cleanup: every row uses fresh identifiers, and each failing
 * statement rolls back on its own. Wrapping these in a test transaction would mean the first expected
 * violation aborts it and every later statement fails for the wrong reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentIntakeSchemaIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Nested
    @DisplayName("payments")
    class Payments {

        @Test
        @DisplayName("a non-positive amount cannot be stored")
        void amountMustBePositive() {
            assertViolates("payments_amount_positive", () -> insertPayment("0"));
            assertViolates("payments_amount_positive", () -> insertPayment("-0.0001"));
        }

        @Test
        @DisplayName("a currency outside the supported set cannot be stored")
        void currencyMustBeSupported() {
            assertViolates(
                    "payments_currency_supported",
                    () -> insertPayment(newPaymentId(), randomMerchant(), "100", "USD", "CREATED"));
        }

        @Test
        @DisplayName("an unrecognised status cannot be stored")
        void statusMustBeKnown() {
            assertViolates(
                    "payments_status_known",
                    () ->
                            insertPayment(
                                    newPaymentId(), randomMerchant(), "100", "VND", "IN_PROGRESS"));
        }

        @Test
        @DisplayName("MANUAL_REVIEW_REQUIRED is a persisted non-terminal payment status")
        void manualReviewStatusIsAccepted() {
            UUID payment = newPaymentId();

            insertPayment(
                    payment,
                    randomMerchant(),
                    "100",
                    "VND",
                    "MANUAL_REVIEW_REQUIRED");
            insertHistory(payment, "PROCESSING", "MANUAL_REVIEW_REQUIRED");
            insertHistory(payment, "MANUAL_REVIEW_REQUIRED", "FAILED");

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT status FROM payment.payments WHERE id = ?",
                            String.class,
                            payment))
                    .isEqualTo("MANUAL_REVIEW_REQUIRED");
        }

        /**
         * The reason {@code PaymentCreatedData} and the domain {@code Money} both reject a scale
         * greater than four instead of leaving it to the column.
         *
         * <p>PostgreSQL does not refuse the extra digits — it rounds them away and reports success. A
         * payment for 100.00005 would be stored as 100.0001 and every later total would be built on
         * money the customer never agreed to, with nothing in any log to say so.
         */
        @Test
        @DisplayName("NUMERIC(19,4) silently rounds extra scale, so the application must reject it")
        void extraScaleIsRoundedNotRejected() {
            UUID id = newPaymentId();
            insertPayment(id, randomMerchant(), "100.00005", "VND", "CREATED");

            BigDecimal stored =
                    jdbcTemplate.queryForObject(
                            "SELECT amount FROM payment.payments WHERE id = ?", BigDecimal.class, id);

            assertThat(stored).isEqualByComparingTo("100.0001");
            assertThat(stored.scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("one merchant cannot reuse a merchant_reference")
        void merchantReferenceIsUniquePerMerchant() {
            UUID merchant = randomMerchant();
            String reference = "REF-" + UUID.randomUUID();

            insertPayment(newPaymentId(), merchant, reference, newKey(), "100", "VND", "CREATED");

            assertViolates(
                    "uq_payments_merchant_reference",
                    () ->
                            insertPayment(
                                    newPaymentId(), merchant, reference, newKey(), "100", "VND",
                                    "CREATED"));
        }

        @Test
        @DisplayName("two merchants may use the same merchant_reference independently")
        void merchantReferenceIsScopedToOneMerchant() {
            String reference = "REF-" + UUID.randomUUID();

            insertPayment(
                    newPaymentId(), randomMerchant(), reference, newKey(), "100", "VND", "CREATED");
            insertPayment(
                    newPaymentId(), randomMerchant(), reference, newKey(), "100", "VND", "CREATED");
        }

        /**
         * The structural half of the idempotency guarantee. {@code idempotency_records} is what serves
         * the replay; this index is what makes a second payment impossible even if that table is
         * bypassed or wrong.
         */
        @Test
        @DisplayName("one merchant cannot produce two payments from one idempotency key")
        void idempotencyKeyIsUniquePerMerchant() {
            UUID merchant = randomMerchant();
            String key = newKey();

            insertPayment(newPaymentId(), merchant, newReference(), key, "100", "VND", "CREATED");

            assertViolates(
                    "uq_payments_merchant_idempotency_key",
                    () ->
                            insertPayment(
                                    newPaymentId(), merchant, newReference(), key, "100", "VND",
                                    "CREATED"));
        }

        @Test
        @DisplayName("metadata must be a JSON object, not an array or a bare scalar")
        void metadataMustBeAnObject() {
            assertViolates("payments_metadata_is_object", () -> insertPaymentWithMetadata("[1,2]"));
            assertViolates("payments_metadata_is_object", () -> insertPaymentWithMetadata("\"x\""));

            insertPaymentWithMetadata("{\"orderId\":\"A-1\"}");
        }
    }

    @Nested
    @DisplayName("payment_status_history")
    class StatusHistory {

        @Test
        @DisplayName("history cannot reference a payment that does not exist")
        void requiresAnExistingPayment() {
            assertViolates(
                    "fk_payment_status_history_payment",
                    () -> insertHistory(UUID.randomUUID(), null, "CREATED"));
        }

        @Test
        @DisplayName("a row that records no movement is rejected")
        void mustRecordAnActualTransition() {
            UUID payment = insertPayment("100");

            assertViolates(
                    "payment_status_history_is_a_transition",
                    () -> insertHistory(payment, "CREATED", "CREATED"));
        }

        @Test
        @DisplayName("the first row may have no previous status")
        void allowsAnInitialRow() {
            UUID payment = insertPayment("100");

            insertHistory(payment, null, "CREATED");
        }

        @Test
        @DisplayName("history rejects an unrecognised previous status")
        void previousStatusMustBeKnown() {
            UUID payment = insertPayment("100");

            assertViolates(
                    "payment_status_history_from_status_known",
                    () -> insertHistory(payment, "UNKNOWN_PREVIOUS_STATE", "FAILED"));
        }

        /**
         * The audit trail's whole value is that it cannot be quietly corrected. A test that only
         * checked the insert path would leave the interesting half unverified.
         */
        @Test
        @DisplayName("an existing history row can be neither updated nor deleted")
        void isAppendOnly() {
            UUID payment = insertPayment("100");
            UUID historyId = insertHistory(payment, null, "CREATED");

            assertThatThrownBy(
                            () ->
                                    jdbcTemplate.update(
                                            "UPDATE payment.payment_status_history"
                                                    + " SET to_status = 'SUCCEEDED' WHERE id = ?",
                                            historyId))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");

            assertThatThrownBy(
                            () ->
                                    jdbcTemplate.update(
                                            "DELETE FROM payment.payment_status_history WHERE id = ?",
                                            historyId))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");

            assertThat(
                            jdbcTemplate.queryForObject(
                                    "SELECT to_status FROM payment.payment_status_history WHERE id = ?",
                                    String.class,
                                    historyId))
                    .isEqualTo("CREATED");
        }
    }

    @Nested
    @DisplayName("payment_sagas")
    class PaymentSagas {

        @Test
        @DisplayName("POST_LEDGER cannot be persisted without the committed reservation fact")
        void postLedgerRequiresReservationFact() {
            UUID payment = insertPayment("100");

            assertViolates(
                    "payment_sagas_reservation_fact_required",
                    () -> insertSaga(
                            payment, "POST_LEDGER", "RUNNING", null, null));
        }

        @Test
        @DisplayName("automatic release is forbidden after a posted journal fact")
        void releaseCannotCoexistWithJournalFact() {
            UUID payment = insertPayment("100");

            assertViolates(
                    "payment_sagas_no_release_after_journal",
                    () -> insertSaga(
                            payment,
                            "RELEASE_FUNDS",
                            "COMPENSATING",
                            UUID.randomUUID(),
                            UUID.randomUUID()));
        }

        @Test
        @DisplayName("one payment owns exactly one durable Saga")
        void paymentOwnsOneSaga() {
            UUID payment = insertPayment("100");
            insertSaga(payment, "RISK_ASSESSMENT", "RUNNING", null, null);

            assertViolates(
                    "uq_payment_sagas_payment",
                    () -> insertSaga(payment, "RISK_ASSESSMENT", "RUNNING", null, null));
        }

        @Test
        @DisplayName("due and manual-review scheduler indexes remain partial")
        void schedulerIndexesArePartial() {
            assertThat(indexDefinition("idx_payment_sagas_due"))
                    .contains("deadline_at")
                    .contains("WHERE")
                    .contains("RUNNING")
                    .contains("COMPENSATING");
            assertThat(indexDefinition("idx_payment_sagas_manual_review"))
                    .contains("updated_at")
                    .contains("WHERE")
                    .contains("MANUAL_REVIEW_REQUIRED");
        }

        @Test
        @DisplayName("optimistic version starts at zero")
        void optimisticVersionDefaultsToZero() {
            UUID payment = insertPayment("100");
            UUID saga = insertSaga(payment, "RISK_ASSESSMENT", "RUNNING", null, null);

            assertThat(jdbcTemplate.queryForObject(
                            "SELECT version FROM payment.payment_sagas WHERE id = ?",
                            Long.class,
                            saga))
                    .isZero();
        }

        private String indexDefinition(String indexName) {
            List<String> definitions = jdbcTemplate.queryForList(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = 'payment'"
                            + " AND indexname = ?",
                    String.class,
                    indexName);

            assertThat(definitions).as("index %s must exist", indexName).hasSize(1);
            return definitions.getFirst();
        }
    }

    @Nested
    @DisplayName("idempotency_records")
    class IdempotencyRecords {

        @Test
        @DisplayName("a key is unique within its scope")
        void keyIsUniqueWithinScope() {
            String scope = "merchant-a:POST /api/v1/payments";
            String key = newKey();

            insertIdempotencyRecord(scope, key);

            assertViolates(
                    "uq_idempotency_records_scope_key", () -> insertIdempotencyRecord(scope, key));
        }

        @Test
        @DisplayName("the same key in a different scope is a different request")
        void scopeSeparatesKeys() {
            String key = newKey();

            insertIdempotencyRecord("merchant-a:POST /api/v1/payments", key);
            insertIdempotencyRecord("merchant-b:POST /api/v1/payments", key);
        }

        /**
         * A COMPLETED record with nothing to return would answer a retry with a 200 and an empty body
         * — a silent wrong answer rather than a visible failure.
         */
        @Test
        @DisplayName("a COMPLETED record without a stored response is rejected")
        void completedRecordMustBeReplayable() {
            assertViolates(
                    "idempotency_records_completed_is_replayable",
                    () ->
                            jdbcTemplate.update(
                                    "INSERT INTO payment.idempotency_records (id, scope,"
                                            + " idempotency_key, request_hash, status, expires_at,"
                                            + " created_at) VALUES (?, ?, ?, ?, 'COMPLETED',"
                                            + " now() + interval '1 day', now())",
                                    UUID.randomUUID(),
                                    "merchant-a:POST /api/v1/payments",
                                    newKey(),
                                    "hash"));
        }
    }

    @Nested
    @DisplayName("outbox_events")
    class OutboxEvents {

        @Test
        @DisplayName("PROCESSING without a lease is rejected: nothing could ever reclaim it")
        void processingRequiresALease() {
            assertViolates(
                    "outbox_events_lease_matches_status",
                    () -> insertOutbox("PROCESSING", false, false));
        }

        @Test
        @DisplayName("a lease on a row nobody is publishing is rejected")
        void pendingMustNotHoldALease() {
            assertViolates(
                    "outbox_events_lease_matches_status", () -> insertOutbox("PENDING", true, false));
        }

        @Test
        @DisplayName("PUBLISHED without published_at, and published_at without PUBLISHED, are rejected")
        void publishedAtTracksStatus() {
            assertViolates(
                    "outbox_events_published_at_matches_status",
                    () -> insertOutbox("PUBLISHED", false, false));
            assertViolates(
                    "outbox_events_published_at_matches_status",
                    () -> insertOutbox("PENDING", false, true));
        }

        @Test
        @DisplayName("the three legal states insert cleanly")
        void legalStatesAreAccepted() {
            insertOutbox("PENDING", false, false);
            insertOutbox("PROCESSING", true, false);
            insertOutbox("PUBLISHED", false, true);
        }

        /**
         * ADR-014's claim query depends on these being partial. A plain composite index would still
         * answer the query and still pass a functional test, while growing with the PUBLISHED rows that
         * make up almost the whole table.
         */
        @Test
        @DisplayName("the claim and reclaim indexes exist and are partial")
        void claimIndexesArePartial() {
            assertThat(indexDefinition("idx_outbox_events_dispatch"))
                    .contains("next_attempt_at")
                    .contains("WHERE");
            assertThat(indexDefinition("idx_outbox_events_stale"))
                    .contains("lock_until")
                    .contains("WHERE");
            assertThat(indexDefinition("idx_outbox_events_aggregate")).contains("aggregate_id");
        }

        private String indexDefinition(String indexName) {
            List<String> definitions =
                    jdbcTemplate.queryForList(
                            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'payment'"
                                    + " AND indexname = ?",
                            String.class,
                            indexName);

            assertThat(definitions).as("index %s must exist", indexName).hasSize(1);
            return definitions.getFirst();
        }
    }

    @Nested
    @DisplayName("merchants")
    class Merchants {

        @Test
        @DisplayName("a merchant code cannot be reused")
        void codeIsUnique() {
            String code = "MCH-" + UUID.randomUUID();

            insertMerchant(code, "ACTIVE", "1000");

            assertViolates("uq_merchants_code", () -> insertMerchant(code, "ACTIVE", "1000"));
        }

        @Test
        @DisplayName("an unrecognised merchant status is rejected")
        void statusMustBeKnown() {
            assertViolates(
                    "merchants_status_known",
                    () -> insertMerchant("MCH-" + UUID.randomUUID(), "ENABLED", "1000"));
        }

        @Test
        @DisplayName("a transaction limit of zero is rejected: it is a misconfiguration, not a block")
        void limitMustBePositive() {
            assertViolates(
                    "merchants_limit_positive",
                    () -> insertMerchant("MCH-" + UUID.randomUUID(), "ACTIVE", "0"));
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private static void assertViolates(String constraintName, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraintName);
    }

    private UUID insertPayment(String amount) {
        UUID id = newPaymentId();
        insertPayment(id, randomMerchant(), amount, "VND", "CREATED");
        return id;
    }

    private void insertPayment(
            UUID id, UUID merchantId, String amount, String currency, String status) {
        insertPayment(id, merchantId, newReference(), newKey(), amount, currency, status);
    }

    private void insertPayment(
            UUID id,
            UUID merchantId,
            String reference,
            String idempotencyKey,
            String amount,
            String currency,
            String status) {
        jdbcTemplate.update(
                "INSERT INTO payment.payments (id, merchant_id, customer_id, source_account_id,"
                        + " merchant_reference, idempotency_key, amount, currency, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?, ?,"
                        + " now(), now())",
                id,
                merchantId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                reference,
                idempotencyKey,
                amount,
                currency,
                status);
    }

    private void insertPaymentWithMetadata(String metadataJson) {
        jdbcTemplate.update(
                "INSERT INTO payment.payments (id, merchant_id, customer_id, source_account_id,"
                        + " merchant_reference, idempotency_key, amount, currency, status, metadata,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 100, 'VND', 'CREATED',"
                        + " ?::jsonb, now(), now())",
                newPaymentId(),
                randomMerchant(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                newReference(),
                newKey(),
                metadataJson);
    }

    private UUID insertHistory(UUID paymentId, String fromStatus, String toStatus) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payment.payment_status_history (id, payment_id, from_status, to_status,"
                        + " occurred_at) VALUES (?, ?, ?, ?, now())",
                id,
                paymentId,
                fromStatus,
                toStatus);
        return id;
    }

    private void insertIdempotencyRecord(String scope, String key) {
        jdbcTemplate.update(
                "INSERT INTO payment.idempotency_records (id, scope, idempotency_key, request_hash,"
                        + " resource_id, response_status, response_body, status, expires_at,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, 202, '{}'::jsonb, 'COMPLETED',"
                        + " now() + interval '1 day', now())",
                UUID.randomUUID(),
                scope,
                key,
                "hash-" + key,
                UUID.randomUUID());
    }

    private UUID insertSaga(
            UUID paymentId,
            String step,
            String status,
            UUID reservationId,
            UUID journalId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payment.payment_sagas (id, payment_id, current_step, status,"
                        + " deadline_at, reservation_id, journal_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, now() + interval '30 seconds', ?, ?, now(), now())",
                id,
                paymentId,
                step,
                status,
                reservationId,
                journalId);
        return id;
    }

    /**
     * The timestamps are computed in SQL rather than passed as parameters, so the test does not also
     * depend on how the driver maps a Java time type onto {@code timestamptz}. What is under test is a
     * constraint, and a mapping surprise would look like a constraint failure.
     */
    private void insertOutbox(String status, boolean leased, boolean published) {
        jdbcTemplate.update(
                "INSERT INTO payment.outbox_events (id, aggregate_type, aggregate_id, event_type,"
                        + " event_version, topic, payload, headers, status, next_attempt_at,"
                        + " lock_owner, lock_until, created_at, published_at)"
                        + " VALUES (?, 'PAYMENT', ?, 'payment.created', 1,"
                        + " 'payflow.payment.events.v1', '{}'::jsonb, '{}'::jsonb, ?, now(),"
                        + " CASE WHEN ? THEN 'payment-service:test' END,"
                        + " CASE WHEN ? THEN now() + interval '120 seconds' END,"
                        + " now(),"
                        + " CASE WHEN ? THEN now() END)",
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                status,
                leased,
                leased,
                published);
    }

    private void insertMerchant(String code, String status, String limit) {
        jdbcTemplate.update(
                "INSERT INTO merchant.merchants (id, code, name, status, default_currency,"
                        + " max_transaction_amount, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'VND', ?::numeric, now(), now())",
                UUID.randomUUID(),
                code,
                "Fixture " + code,
                status,
                limit);
    }

    private static UUID newPaymentId() {
        return UUID.randomUUID();
    }

    private static UUID randomMerchant() {
        return UUID.randomUUID();
    }

    private static String newReference() {
        return "REF-" + UUID.randomUUID();
    }

    private static String newKey() {
        return "KEY-" + UUID.randomUUID();
    }
}
