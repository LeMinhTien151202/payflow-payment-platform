package com.payflow.risk.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.events.EventHeaders;
import com.payflow.risk.application.outbox.ClaimedOutboxEvent;
import com.payflow.risk.application.outbox.OutboxPublishPolicy;
import com.payflow.risk.application.port.OutboxLeaseStore;
import com.payflow.risk.application.port.OutboxTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishOutboxHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private final OutboxLeaseStore store = mock(OutboxLeaseStore.class);
    private final OutboxTransport transport = mock(OutboxTransport.class);
    private final OutboxPublishPolicy policy =
            new OutboxPublishPolicy(10, Duration.ofMinutes(2), 3, Duration.ofMinutes(5));
    private final PublishOutboxHandler handler = new PublishOutboxHandler(
            store, transport, policy, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void marksBrokerAcknowledgedEventPublished() throws Exception {
        ClaimedOutboxEvent event = event("payment-1", 1);
        when(store.claim("owner", policy.lease(), policy.batchSize())).thenReturn(List.of(event));
        when(store.markPublished(event.eventId(), "owner")).thenReturn(true);

        var result = handler.publishAvailable("owner");

        verify(transport).publish(event);
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.publishFailed()).isZero();
    }

    @Test
    void retriesFailureAndKeepsLaterEventForSameAggregateOrdered() throws Exception {
        ClaimedOutboxEvent first = event("payment-1", 1);
        ClaimedOutboxEvent second = event("payment-1", 1);
        when(store.claim("owner", policy.lease(), policy.batchSize()))
                .thenReturn(List.of(first, second));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(transport).publish(first);
        when(store.markRetry(first.eventId(), "owner", NOW.plusSeconds(2),
                        "IllegalStateException: broker unavailable"))
                .thenReturn(true);
        when(store.markRetry(second.eventId(), "owner", NOW.plusSeconds(2),
                        "BlockedByEarlierAggregateEvent"))
                .thenReturn(true);

        var result = handler.publishAvailable("owner");

        assertThat(result.publishFailed()).isEqualTo(1);
        verify(store).markRetry(second.eventId(), "owner", NOW.plusSeconds(2),
                "BlockedByEarlierAggregateEvent");
    }

    @Test
    void terminallyFailsAtConfiguredAttemptLimit() throws Exception {
        ClaimedOutboxEvent event = event("payment-1", 3);
        when(store.claim("owner", policy.lease(), policy.batchSize())).thenReturn(List.of(event));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(transport).publish(event);
        when(store.markFailed(event.eventId(), "owner",
                        "IllegalStateException: broker unavailable"))
                .thenReturn(true);

        assertThat(handler.publishAvailable("owner").terminalFailed()).isEqualTo(1);
    }

    private static ClaimedOutboxEvent event(String aggregateId, int attempt) {
        return new ClaimedOutboxEvent(
                UUID.randomUUID(), aggregateId, "payflow.risk.events.v1", "{}",
                Map.of(EventHeaders.CORRELATION_ID, "risk-outbox-test"),
                attempt, NOW, false);
    }
}
