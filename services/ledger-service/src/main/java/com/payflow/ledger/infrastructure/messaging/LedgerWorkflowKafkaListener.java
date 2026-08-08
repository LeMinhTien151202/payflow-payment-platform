package com.payflow.ledger.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Acknowledges Ledger commands only after the local inbox/journal/outbox transaction commits. */
@Component
@ConditionalOnProperty(name="payflow.ledger-consumer.enabled", havingValue="true", matchIfMissing=true)
class LedgerWorkflowKafkaListener {
    static final String GROUP_ID = "ledger-workflow-v1";
    private final LedgerWorkflowEventRouter router;
    private final MeterRegistry metrics;
    LedgerWorkflowKafkaListener(LedgerWorkflowEventRouter router, MeterRegistry metrics) {
        this.router=router; this.metrics=metrics;
    }
    @KafkaListener(id="ledger-payment-commands", groupId=GROUP_ID, topics=PayFlowTopics.PAYMENT_EVENTS,
            autoStartup="${payflow.ledger-consumer.enabled:true}")
    void onPaymentEvent(String payload,
            @Header(name=KafkaHeaders.RECEIVED_KEY, required=false) String key,
            Acknowledgment ack) { consume(key,payload,ack); }
    @KafkaListener(id="ledger-refund-commands", groupId=GROUP_ID, topics=PayFlowTopics.REFUND_EVENTS,
            autoStartup="${payflow.ledger-consumer.enabled:true}")
    void onRefundEvent(String payload,
            @Header(name=KafkaHeaders.RECEIVED_KEY, required=false) String key,
            Acknowledgment ack) { consume(key,payload,ack); }
    private void consume(String key,String payload,Acknowledgment ack) {
        try {
            var result=router.route(key,payload);
            metrics.counter("payflow.ledger.workflow.consumer","outcome",result.name().toLowerCase()).increment();
            ack.acknowledge();
        } catch (RuntimeException failure) {
            metrics.counter("payflow.ledger.workflow.consumer","outcome","failed").increment();
            throw failure;
        }
    }
}
