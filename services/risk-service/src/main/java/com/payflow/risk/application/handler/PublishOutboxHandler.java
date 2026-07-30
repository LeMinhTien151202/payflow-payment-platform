package com.payflow.risk.application.handler;

import com.payflow.events.EventHeaders;
import com.payflow.observability.CorrelationId;
import com.payflow.risk.application.outbox.ClaimedOutboxEvent;
import com.payflow.risk.application.outbox.OutboxBatchResult;
import com.payflow.risk.application.outbox.OutboxPublishPolicy;
import com.payflow.risk.application.port.OutboxLeaseStore;
import com.payflow.risk.application.port.OutboxTransport;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** ADR-014 lease publisher; broker I/O happens after the short claim transaction commits. */
@Service
public class PublishOutboxHandler {

    private static final int MAX_ERROR_LENGTH = 500;
    private final OutboxLeaseStore store;
    private final OutboxTransport transport;
    private final OutboxPublishPolicy policy;
    private final Clock clock;

    public PublishOutboxHandler(
            OutboxLeaseStore store,
            OutboxTransport transport,
            OutboxPublishPolicy policy,
            Clock clock) {
        this.store = store;
        this.transport = transport;
        this.policy = policy;
        this.clock = clock;
    }

    public OutboxBatchResult publishAvailable(String owner) {
        var events = store.claim(owner, policy.lease(), policy.batchSize());
        if (events.isEmpty()) {
            return OutboxBatchResult.empty();
        }
        int reclaimed = (int) events.stream().filter(ClaimedOutboxEvent::reclaimed).count();
        int published = 0;
        int failed = 0;
        int terminal = 0;
        int lost = 0;
        Map<String, Instant> blocked = new HashMap<>();

        for (ClaimedOutboxEvent event : events) {
            if (blocked.containsKey(event.aggregateId())) {
                if (!store.markRetry(
                        event.eventId(), owner, blocked.get(event.aggregateId()),
                        "BlockedByEarlierAggregateEvent")) {
                    lost++;
                }
                continue;
            }
            String previous = bindCorrelation(event);
            try {
                transport.publish(event);
                if (store.markPublished(event.eventId(), owner)) {
                    published++;
                } else {
                    lost++;
                }
            } catch (Exception failure) {
                failed++;
                Instant retryAt = clock.instant().plus(policy.backoffFor(event.attemptCount()));
                blocked.put(event.aggregateId(), retryAt);
                if (event.attemptCount() >= policy.maxAttempts()) {
                    if (store.markFailed(event.eventId(), owner, safeError(failure))) {
                        terminal++;
                    } else {
                        lost++;
                    }
                } else if (!store.markRetry(
                        event.eventId(), owner, retryAt, safeError(failure))) {
                    lost++;
                }
            } finally {
                restoreCorrelation(previous);
            }
        }
        return new OutboxBatchResult(
                events.size(), reclaimed, published, failed, terminal, lost);
    }

    static String safeError(Exception failure) {
        String type = failure.getClass().getSimpleName();
        String value = failure.getMessage() == null || failure.getMessage().isBlank()
                ? type
                : type + ": " + failure.getMessage();
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String bindCorrelation(ClaimedOutboxEvent event) {
        String previous = MDC.get(CorrelationId.MDC_KEY);
        String value = event.headers().get(EventHeaders.CORRELATION_ID);
        if (CorrelationId.isSafe(value)) {
            MDC.put(CorrelationId.MDC_KEY, value);
        } else {
            MDC.remove(CorrelationId.MDC_KEY);
        }
        return previous;
    }

    private static void restoreCorrelation(String previous) {
        if (previous == null) {
            MDC.remove(CorrelationId.MDC_KEY);
        } else {
            MDC.put(CorrelationId.MDC_KEY, previous);
        }
    }
}
