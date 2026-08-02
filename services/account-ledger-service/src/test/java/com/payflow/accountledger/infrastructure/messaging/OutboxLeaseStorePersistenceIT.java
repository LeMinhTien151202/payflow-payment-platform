package com.payflow.accountledger.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.accountledger.AbstractPostgresIT;
import com.payflow.accountledger.application.outbox.ClaimedOutboxEvent;
import com.payflow.accountledger.application.port.OutboxLeaseStore;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL lease and ownership evidence prepared for the later Docker verification phase. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxLeaseStorePersistenceIT extends AbstractPostgresIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxLeaseStore store;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM account_ledger.outbox_events");
    }

    @Test
    void claimsPendingRowAndOnlyTheLeaseOwnerCanMarkItPublished() {
        UUID eventId = insert("PENDING", 0, null);

        List<ClaimedOutboxEvent> claimed =
                store.claim("account-ledger-service:one", Duration.ofMinutes(2), 10);

        assertThat(claimed).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(eventId);
            assertThat(event.attemptCount()).isEqualTo(1);
            assertThat(event.reclaimed()).isFalse();
        });
        assertThat(store.markPublished(eventId, "account-ledger-service:other")).isFalse();
        assertThat(store.markPublished(eventId, "account-ledger-service:one")).isTrue();
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @Test
    void reclaimsExpiredLeaseButLeavesAnActiveLeaseAlone() {
        UUID expired = insert("PROCESSING", 1, Instant.now().minusSeconds(5));
        UUID active = insert("PROCESSING", 1, Instant.now().plusSeconds(60));

        List<ClaimedOutboxEvent> claimed =
                store.claim("account-ledger-service:recovery", Duration.ofMinutes(2), 10);

        assertThat(claimed).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(expired);
            assertThat(event.attemptCount()).isEqualTo(2);
            assertThat(event.reclaimed()).isTrue();
        });
        assertThat(status(active)).isEqualTo("PROCESSING");
    }

    private UUID insert(String status, int attempts, Instant lockUntil) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO account_ledger.outbox_events (
                    id, aggregate_type, aggregate_id, event_type, event_version,
                    topic, payload, headers, status, attempt_count, next_attempt_at,
                    lock_owner, lock_until, created_at)
                VALUES (?, 'Account', ?, 'account.refund-credited', 1,
                    'payflow.account.events.v1', CAST(? AS jsonb), CAST(? AS jsonb),
                    ?, ?, clock_timestamp() - interval '1 second', ?, ?, clock_timestamp())
                """,
                eventId,
                "payment-" + eventId,
                "{\"eventId\":\"" + eventId + "\"}",
                "{\"eventId\":\"" + eventId + "\"}",
                status,
                attempts,
                "PROCESSING".equals(status) ? "previous-owner" : null,
                // The driver cannot infer a SQL type for Instant, exactly as production code hit.
                lockUntil == null ? null : lockUntil.atOffset(ZoneOffset.UTC));
        return eventId;
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM account_ledger.outbox_events WHERE id = ?",
                String.class,
                eventId);
    }
}
