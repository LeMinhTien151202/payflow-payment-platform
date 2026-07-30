package com.payflow.risk.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
class RiskPaymentKafkaListener {

    static final String GROUP_ID = "risk-payment-created-v1";

    private final RiskPaymentEventRouter router;
    private final MeterRegistry metrics;

    RiskPaymentKafkaListener(RiskPaymentEventRouter router, MeterRegistry metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @KafkaListener(
            id = "risk-payment-events",
            groupId = GROUP_ID,
            topics = PayFlowTopics.PAYMENT_EVENTS,
            autoStartup = "${payflow.risk-consumer.enabled:true}")
    void onPaymentEvent(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        try {
            var result = router.route(key, payload);
            metrics.counter(
                            "payflow.risk.payment.consumer",
                            "outcome",
                            result.name().toLowerCase())
                    .increment();
            acknowledgment.acknowledge();
        } catch (RuntimeException failure) {
            metrics.counter("payflow.risk.payment.consumer", "outcome", "failed")
                    .increment();
            throw failure;
        }
    }
}
