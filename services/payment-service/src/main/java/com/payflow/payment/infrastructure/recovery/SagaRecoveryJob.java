package com.payflow.payment.infrastructure.recovery;

import com.payflow.payment.application.handler.RecoverOverdueSagasHandler;
import com.payflow.payment.application.saga.SagaRecoveryBatchResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler shell around the application recovery transaction. */
@Component
@ConditionalOnProperty(
        prefix = "payflow.saga-recovery", name = "enabled", matchIfMissing = true)
class SagaRecoveryJob {

    private final RecoverOverdueSagasHandler handler;
    private final MeterRegistry meters;

    SagaRecoveryJob(RecoverOverdueSagasHandler handler, MeterRegistry meters) {
        this.handler = handler;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${payflow.saga-recovery.poll-interval:1s}")
    void poll() {
        SagaRecoveryBatchResult result = handler.recoverDue();
        increment("payflow.saga.recovery.retried", result.retried());
        increment("payflow.saga.recovery.compensating", result.compensating());
        increment("payflow.saga.recovery.manual_review", result.manualReview());
        increment("payflow.saga.recovery.concurrent", result.concurrentUpdates());
    }

    private void increment(String metric, int amount) {
        if (amount > 0) {
            meters.counter(metric).increment(amount);
        }
    }
}
