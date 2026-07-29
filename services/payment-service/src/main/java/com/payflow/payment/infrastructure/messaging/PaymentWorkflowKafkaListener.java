package com.payflow.payment.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Thin Kafka boundary: route, then acknowledge only after the local transaction returned. */
@Component
class PaymentWorkflowKafkaListener {

    static final String GROUP_ID = "payment-saga-orchestrator-v1";
    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorkflowKafkaListener.class);

    private final PaymentWorkflowEventRouter router;
    private final MeterRegistry metrics;

    PaymentWorkflowKafkaListener(PaymentWorkflowEventRouter router, MeterRegistry metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @KafkaListener(
            id = "payment-risk-events",
            groupId = GROUP_ID,
            topics = PayFlowTopics.RISK_EVENTS,
            autoStartup = "${payflow.workflow-consumer.enabled:true}")
    void onRiskEvent(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(key, payload, acknowledgment);
    }

    @KafkaListener(
            id = "payment-account-events",
            groupId = GROUP_ID,
            topics = PayFlowTopics.ACCOUNT_EVENTS,
            autoStartup = "${payflow.workflow-consumer.enabled:true}")
    void onAccountEvent(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(key, payload, acknowledgment);
    }

    @KafkaListener(
            id = "payment-ledger-events",
            groupId = GROUP_ID,
            topics = PayFlowTopics.LEDGER_EVENTS,
            autoStartup = "${payflow.workflow-consumer.enabled:true}")
    void onLedgerEvent(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(key, payload, acknowledgment);
    }

    private void consume(String key, String payload, Acknowledgment acknowledgment) {
        try {
            PaymentWorkflowEventRouter.RouteResult result = router.route(key, payload);
            metrics.counter(
                            "payflow.payment.workflow.consumer",
                            "outcome",
                            result.name().toLowerCase())
                    .increment();
            acknowledgment.acknowledge();
            if (result == PaymentWorkflowEventRouter.RouteResult.IGNORED) {
                LOG.debug("Ignored an event not owned by the Payment Saga consumer");
            }
        } catch (RuntimeException failure) {
            metrics.counter("payflow.payment.workflow.consumer", "outcome", "failed")
                    .increment();
            throw failure;
        }
    }
}
