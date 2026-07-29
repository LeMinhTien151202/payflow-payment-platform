package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Acknowledges only after the handler's local transaction has returned successfully. */
@Component
class RefundWorkflowKafkaListener {

    static final String GROUP_ID = "account-ledger-refund-v1";

    private final RefundWorkflowEventRouter router;
    private final MeterRegistry metrics;

    RefundWorkflowKafkaListener(RefundWorkflowEventRouter router, MeterRegistry metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @KafkaListener(
            id = "ledger-refund-requests",
            groupId = GROUP_ID,
            topics = PayFlowTopics.REFUND_EVENTS,
            autoStartup = "${payflow.refund-consumer.enabled:true}")
    void onRefundEvent(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(key, payload, acknowledgment);
    }

    @KafkaListener(
            id = "account-refund-credit-requests",
            groupId = GROUP_ID,
            topics = PayFlowTopics.PAYMENT_EVENTS,
            autoStartup = "${payflow.refund-consumer.enabled:true}")
    void onPaymentEvent(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(key, payload, acknowledgment);
    }

    private void consume(String key, String payload, Acknowledgment acknowledgment) {
        try {
            RefundWorkflowEventRouter.RouteResult result = router.route(key, payload);
            metrics.counter(
                            "payflow.account_ledger.refund.consumer",
                            "outcome",
                            result.name().toLowerCase())
                    .increment();
            acknowledgment.acknowledge();
        } catch (RuntimeException failure) {
            metrics.counter("payflow.account_ledger.refund.consumer", "outcome", "failed")
                    .increment();
            throw failure;
        }
    }
}
