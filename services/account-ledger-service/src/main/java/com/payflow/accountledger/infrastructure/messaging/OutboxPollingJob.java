package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.accountledger.application.handler.PublishOutboxHandler;
import com.payflow.accountledger.application.outbox.OutboxBatchResult;
import com.payflow.accountledger.application.port.OutboxLeaseStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fixed-delay trigger and Micrometer observation around the application publisher. */
@Component
@ConditionalOnProperty(prefix = "payflow.outbox", name = "enabled", matchIfMissing = true)
class OutboxPollingJob {

    private final PublishOutboxHandler publisher;
    private final OutboxLeaseStore store;
    private final OutboxPublisherOwner owner;
    private final MeterRegistry meters;
    private final AtomicLong pendingAgeMillis = new AtomicLong();

    OutboxPollingJob(
            PublishOutboxHandler publisher,
            OutboxLeaseStore store,
            OutboxPublisherOwner owner,
            MeterRegistry meters) {
        this.publisher = publisher;
        this.store = store;
        this.owner = owner;
        this.meters = meters;
        Gauge.builder(
                        "payflow.outbox.pending.age.seconds",
                        pendingAgeMillis,
                        value -> value.get() / 1000.0)
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval:500ms}")
    void poll() {
        OutboxBatchResult result = publisher.publishAvailable(owner.value());
        increment("payflow.outbox.published", result.published());
        increment("payflow.outbox.publish.failed", result.publishFailed());
        increment("payflow.outbox.reclaimed", result.reclaimed());
        increment("payflow.outbox.failed.terminal", result.terminalFailed());
        increment("payflow.outbox.claim.lost", result.lostClaims());
        pendingAgeMillis.set(Math.round(store.oldestPendingAgeSeconds() * 1000));
    }

    private void increment(String metric, int amount) {
        if (amount > 0) {
            meters.counter(metric).increment(amount);
        }
    }
}
