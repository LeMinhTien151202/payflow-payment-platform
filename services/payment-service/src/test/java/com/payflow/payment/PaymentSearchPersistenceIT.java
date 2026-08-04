package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.payment.application.PaymentSearchQuery;
import com.payflow.payment.application.PaymentSearchResult;
import com.payflow.payment.application.handler.SearchPaymentsHandler;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL proof of merchant isolation, filters, pagination and the Phase 2 search index. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentSearchPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private SearchPaymentsHandler searchPayments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("search is merchant-scoped, newest-first and uses stable pagination")
    void searchesWithinOneMerchant() {
        UUID merchant = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        UUID oldest = insertPayment(merchant, "FAILED", "2026-07-01T09:00:00Z");
        UUID middle = insertPayment(merchant, "SUCCEEDED", "2026-07-01T10:00:00Z");
        UUID newest = insertPayment(merchant, "SUCCEEDED", "2026-07-01T11:00:00Z");
        insertPayment(otherMerchant, "SUCCEEDED", "2026-07-01T12:00:00Z");

        PaymentSearchResult firstPage = searchPayments.handle(
                new PaymentSearchQuery(merchant, null, null, null, 0, 2));
        PaymentSearchResult secondPage = searchPayments.handle(
                new PaymentSearchQuery(merchant, null, null, null, 1, 2));

        assertThat(firstPage.items()).extracting(item -> item.paymentId())
                .containsExactly(newest, middle);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(secondPage.items()).extracting(item -> item.paymentId())
                .containsExactly(oldest);
    }

    @Test
    @DisplayName("status and half-open time filters are applied by PostgreSQL")
    void filtersByStatusAndTimeRange() {
        UUID merchant = UUID.randomUUID();
        UUID inclusive = insertPayment(merchant, "SUCCEEDED", "2026-07-01T10:00:00Z");
        insertPayment(merchant, "FAILED", "2026-07-01T10:30:00Z");
        insertPayment(merchant, "SUCCEEDED", "2026-07-01T11:00:00Z");

        PaymentSearchResult result = searchPayments.handle(new PaymentSearchQuery(
                merchant,
                PaymentStatus.SUCCEEDED,
                Instant.parse("2026-07-01T10:00:00Z"),
                Instant.parse("2026-07-01T11:00:00Z"),
                0,
                20));

        assertThat(result.items()).extracting(item -> item.paymentId())
                .containsExactly(inclusive);
        assertThat(result.totalElements()).isOne();
    }

    @Test
    @DisplayName("migration provides the status-filtered merchant search index")
    void searchIndexExists() {
        List<String> definitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'payment' AND indexname = ?",
                String.class,
                "idx_payments_merchant_status_created_id");

        assertThat(definitions).singleElement().asString()
                .contains("merchant_id", "status", "created_at DESC", "id DESC");
    }

    private UUID insertPayment(UUID merchantId, String status, String createdAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime timestamp =
                OffsetDateTime.ofInstant(Instant.parse(createdAt), ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO payment.payments (id, merchant_id, customer_id, source_account_id,"
                        + " merchant_reference, idempotency_key, amount, currency, status,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 100.0000, 'VND', ?, ?, ?)",
                id,
                merchantId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "SEARCH-REF-" + id,
                "SEARCH-KEY-" + id,
                status,
                timestamp,
                timestamp);
        return id;
    }
}
