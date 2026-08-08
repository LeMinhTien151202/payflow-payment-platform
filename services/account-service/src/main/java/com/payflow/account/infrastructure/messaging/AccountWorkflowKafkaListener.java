package com.payflow.account.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Acknowledges Account commands only after the local inbox/business/outbox transaction commits. */
@Component
@ConditionalOnProperty(name = "payflow.account-consumer.enabled", havingValue = "true", matchIfMissing = true)
class AccountWorkflowKafkaListener {
    static final String GROUP_ID = "account-workflow-v1";
    private final AccountWorkflowEventRouter router;
    private final MeterRegistry metrics;

    AccountWorkflowKafkaListener(AccountWorkflowEventRouter router, MeterRegistry metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @KafkaListener(id = "account-payment-commands", groupId = GROUP_ID,
            topics = PayFlowTopics.PAYMENT_EVENTS,
            autoStartup = "${payflow.account-consumer.enabled:true}")
    void onPaymentEvent(String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(key, payload, acknowledgment);
    }

    private void consume(String key, String payload, Acknowledgment acknowledgment) {
        try {
            AccountWorkflowEventRouter.RouteResult result = router.route(key, payload);
            metrics.counter("payflow.account.workflow.consumer", "outcome", result.name().toLowerCase()).increment();
            acknowledgment.acknowledge();
        } catch (RuntimeException failure) {
            metrics.counter("payflow.account.workflow.consumer", "outcome", "failed").increment();
            throw failure;
        }
    }
}
