package com.payflow.risk.infrastructure.messaging;

import com.payflow.risk.application.handler.PublishOutboxHandler;
import com.payflow.risk.application.port.OutboxLeaseStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
        Gauge.builder("payflow.risk.outbox.pending.age.seconds", pendingAgeMillis,
                        value -> value.get() / 1000.0)
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval:500ms}")
    void poll() {
        var result = publisher.publishAvailable(owner.value());
        increment("payflow.risk.outbox.published", result.published());
        increment("payflow.risk.outbox.publish.failed", result.publishFailed());
        increment("payflow.risk.outbox.reclaimed", result.reclaimed());
        increment("payflow.risk.outbox.failed.terminal", result.terminalFailed());
        increment("payflow.risk.outbox.claim.lost", result.lostClaims());
        pendingAgeMillis.set(Math.round(store.oldestPendingAgeSeconds() * 1000));
    }

    private void increment(String name, int amount) {
        if (amount > 0) {
            meters.counter(name).increment(amount);
        }
    }
}
