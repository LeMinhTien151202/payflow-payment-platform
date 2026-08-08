package com.payflow.account.application.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.account.application.outbox.ClaimedOutboxEvent;
import com.payflow.account.application.outbox.OutboxBatchResult;
import com.payflow.account.application.outbox.OutboxPublishPolicy;
import com.payflow.account.application.port.OutboxLeaseStore;
import com.payflow.account.application.port.OutboxTransport;
import com.payflow.events.EventHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Docker-free policy tests; PostgreSQL claim SQL and Kafka acknowledgement remain integration gates. */
class PublishOutboxHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private static final String OWNER = "account-service:publisher-1";

    private final FakeStore store = new FakeStore();
    private final FakeTransport transport = new FakeTransport();

    @Test
    @DisplayName("a claimed row is sent then conditionally marked published")
    void publishesAndMarks() {
        ClaimedOutboxEvent event = event("payment-1", 1, false, 0);
        store.claimed.add(event);

        OutboxBatchResult result = handler(10).publishAvailable(OWNER);

        assertThat(transport.sent).containsExactly(event);
        assertThat(store.published).containsExactly(event.eventId());
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.publishFailed()).isZero();
    }

    @Test
    @DisplayName("a transient failure returns the row to pending with exponential backoff")
    void retriesWithBackoff() {
        ClaimedOutboxEvent event = event("payment-1", 3, false, 0);
        store.claimed.add(event);
        transport.fail.add(event.eventId());

        OutboxBatchResult result = handler(10).publishAvailable(OWNER);

        FakeStore.Retry retry = store.retried.getFirst();
        assertThat(retry.eventId()).isEqualTo(event.eventId());
        assertThat(retry.nextAttemptAt()).isEqualTo(NOW.plusSeconds(8));
        assertThat(retry.error()).startsWith("IllegalStateException: broker unavailable");
        assertThat(retry.error()).doesNotContain(event.payload());
        assertThat(result.publishFailed()).isEqualTo(1);
        assertThat(result.terminalFailed()).isZero();
    }

    @Test
    @DisplayName("the maximum attempt moves a poison row to terminal FAILED")
    void marksTerminalFailure() {
        ClaimedOutboxEvent event = event("payment-1", 10, false, 0);
        store.claimed.add(event);
        transport.fail.add(event.eventId());

        OutboxBatchResult result = handler(10).publishAvailable(OWNER);

        assertThat(store.failed).containsExactly(event.eventId());
        assertThat(store.retried).isEmpty();
        assertThat(result.terminalFailed()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed event blocks later events of the same aggregate")
    void blocksLaterEventOfSameAggregate() {
        ClaimedOutboxEvent first = event("payment-1", 1, false, 0);
        ClaimedOutboxEvent second = event("payment-1", 1, false, 1);
        store.claimed.addAll(List.of(first, second));
        transport.fail.add(first.eventId());

        handler(10).publishAvailable(OWNER);

        assertThat(transport.sent).containsExactly(first);
        assertThat(store.retried).hasSize(2);
        assertThat(store.retried.get(1).eventId()).isEqualTo(second.eventId());
        assertThat(store.retried.get(1).error()).isEqualTo("BlockedByEarlierAggregateEvent");
        assertThat(store.retried.get(1).nextAttemptAt())
                .isEqualTo(store.retried.get(0).nextAttemptAt());
    }

    @Test
    @DisplayName("a failed aggregate does not stop an unrelated aggregate")
    void continuesOtherAggregate() {
        ClaimedOutboxEvent first = event("payment-1", 1, false, 0);
        ClaimedOutboxEvent other = event("payment-2", 1, false, 1);
        store.claimed.addAll(List.of(first, other));
        transport.fail.add(first.eventId());

        OutboxBatchResult result = handler(10).publishAvailable(OWNER);

        assertThat(transport.sent).containsExactly(first, other);
        assertThat(store.published).containsExactly(other.eventId());
        assertThat(result.published()).isEqualTo(1);
    }

    @Test
    @DisplayName("reclaimed rows and a lost conditional mark are observable")
    void reportsRecoverySignals() {
        ClaimedOutboxEvent reclaimed = event("payment-1", 2, true, 0);
        store.claimed.add(reclaimed);
        store.losePublishedMark = true;

        OutboxBatchResult result = handler(10).publishAvailable(OWNER);

        assertThat(result.reclaimed()).isEqualTo(1);
        assertThat(result.lostClaims()).isEqualTo(1);
        assertThat(result.published()).isZero();
    }

    @Test
    @DisplayName("stored error detail is capped at the database column width")
    void capsSafeError() {
        String value = PublishOutboxHandler.safeError(new IllegalStateException("x".repeat(800)));
        assertThat(value).hasSize(500).startsWith("IllegalStateException: ");
    }

    private PublishOutboxHandler handler(int maxAttempts) {
        return new PublishOutboxHandler(
                store,
                transport,
                new OutboxPublishPolicy(
                        100,
                        Duration.ofSeconds(120),
                        maxAttempts,
                        Duration.ofSeconds(300)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ClaimedOutboxEvent event(
            String aggregateId, int attempts, boolean reclaimed, long secondsAfterFirst) {
        UUID id = UUID.randomUUID();
        return new ClaimedOutboxEvent(
                id,
                aggregateId,
                "payflow.account.events.v1",
                "{\"eventId\":\"" + id + "\"}",
                Map.of(
                        EventHeaders.EVENT_ID,
                        id.toString(),
                        EventHeaders.CORRELATION_ID,
                        "outbox-test-1"),
                attempts,
                NOW.plusSeconds(secondsAfterFirst),
                reclaimed);
    }

    private static final class FakeTransport implements OutboxTransport {
        private final List<ClaimedOutboxEvent> sent = new ArrayList<>();
        private final Set<UUID> fail = new HashSet<>();

        @Override
        public void publish(ClaimedOutboxEvent event) {
            sent.add(event);
            if (fail.contains(event.eventId())) {
                throw new IllegalStateException("broker unavailable");
            }
        }
    }

    private static final class FakeStore implements OutboxLeaseStore {
        record Retry(UUID eventId, Instant nextAttemptAt, String error) {}

        private final List<ClaimedOutboxEvent> claimed = new ArrayList<>();
        private final List<UUID> published = new ArrayList<>();
        private final List<Retry> retried = new ArrayList<>();
        private final List<UUID> failed = new ArrayList<>();
        private boolean losePublishedMark;

        @Override
        public List<ClaimedOutboxEvent> claim(String owner, Duration lease, int batchSize) {
            return List.copyOf(claimed);
        }

        @Override
        public boolean markPublished(UUID eventId, String owner) {
            if (losePublishedMark) {
                return false;
            }
            published.add(eventId);
            return true;
        }

        @Override
        public boolean markRetry(
                UUID eventId, String owner, Instant nextAttemptAt, String safeError) {
            retried.add(new Retry(eventId, nextAttemptAt, safeError));
            return true;
        }

        @Override
        public boolean markFailed(UUID eventId, String owner, String safeError) {
            failed.add(eventId);
            return true;
        }

        @Override
        public double oldestPendingAgeSeconds() {
            return 0;
        }
    }
}
