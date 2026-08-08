package com.payflow.account.application.handler;

import com.payflow.account.application.outbox.ClaimedOutboxEvent;
import com.payflow.account.application.outbox.OutboxBatchResult;
import com.payflow.account.application.outbox.OutboxPublishPolicy;
import com.payflow.account.application.port.OutboxLeaseStore;
import com.payflow.account.application.port.OutboxTransport;
import com.payflow.events.EventHeaders;
import com.payflow.observability.CorrelationId;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Executes ADR-014's protocol without holding a database transaction over broker I/O. */
@Service
public class PublishOutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(PublishOutboxHandler.class);
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
        List<ClaimedOutboxEvent> events = store.claim(owner, policy.lease(), policy.batchSize());
        if (events.isEmpty()) {
            return OutboxBatchResult.empty();
        }

        int reclaimed = (int) events.stream().filter(ClaimedOutboxEvent::reclaimed).count();
        int published = 0;
        int failed = 0;
        int terminal = 0;
        int lostClaims = 0;
        Map<String, Instant> blockedAggregates = new HashMap<>();

        for (ClaimedOutboxEvent event : events) {
            if (blockedAggregates.containsKey(event.aggregateId())) {
                Instant retryAt = blockedAggregates.get(event.aggregateId());
                if (!store.markRetry(
                        event.eventId(), owner, retryAt, "BlockedByEarlierAggregateEvent")) {
                    lostClaims++;
                }
                continue;
            }

            String previousCorrelationId = bindCorrelationId(event);
            try {
                transport.publish(event);
                if (store.markPublished(event.eventId(), owner)) {
                    published++;
                } else {
                    lostClaims++;
                    log.warn("outbox publish acknowledgement lost claim eventId={}", event.eventId());
                }
            } catch (Exception failure) {
                failed++;
                String safeError = safeError(failure);
                Instant retryAt = clock.instant().plus(policy.backoffFor(event.attemptCount()));
                blockedAggregates.put(event.aggregateId(), retryAt);

                if (event.attemptCount() >= policy.maxAttempts()) {
                    if (store.markFailed(event.eventId(), owner, safeError)) {
                        terminal++;
                    } else {
                        lostClaims++;
                    }
                } else if (!store.markRetry(event.eventId(), owner, retryAt, safeError)) {
                    lostClaims++;
                }
            } finally {
                restoreCorrelationId(previousCorrelationId);
            }
        }

        return new OutboxBatchResult(
                events.size(), reclaimed, published, failed, terminal, lostClaims);
    }

    /** Exception type plus bounded message only; never payload, headers, or a stack trace. */
    static String safeError(Exception failure) {
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        String value = message == null || message.isBlank() ? type : type + ": " + message;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String bindCorrelationId(ClaimedOutboxEvent event) {
        String previous = MDC.get(CorrelationId.MDC_KEY);
        String correlationId = event.headers().get(EventHeaders.CORRELATION_ID);
        if (CorrelationId.isSafe(correlationId)) {
            MDC.put(CorrelationId.MDC_KEY, correlationId);
        } else {
            MDC.remove(CorrelationId.MDC_KEY);
        }
        return previous;
    }

    private static void restoreCorrelationId(String previous) {
        if (previous == null) {
            MDC.remove(CorrelationId.MDC_KEY);
        } else {
            MDC.put(CorrelationId.MDC_KEY, previous);
        }
    }
}
