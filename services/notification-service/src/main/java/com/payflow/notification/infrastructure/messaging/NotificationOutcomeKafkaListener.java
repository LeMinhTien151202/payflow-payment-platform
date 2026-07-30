package com.payflow.notification.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
class NotificationOutcomeKafkaListener {

    static final String GROUP_ID = "notification-outcome-v1";

    private final NotificationOutcomeEventRouter router;
    private final MeterRegistry metrics;

    NotificationOutcomeKafkaListener(
            NotificationOutcomeEventRouter router, MeterRegistry metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @KafkaListener(
            id = "notification-payment-outcomes",
            groupId = GROUP_ID,
            topics = PayFlowTopics.PAYMENT_EVENTS,
            autoStartup = "${payflow.notification-consumer.enabled:true}")
    void onPaymentOutcome(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(payload, key, acknowledgment, "payment");
    }

    @KafkaListener(
            id = "notification-refund-outcomes",
            groupId = GROUP_ID,
            topics = PayFlowTopics.REFUND_EVENTS,
            autoStartup = "${payflow.notification-consumer.enabled:true}")
    void onRefundOutcome(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(payload, key, acknowledgment, "refund");
    }

    private void consume(
            String payload, String key, Acknowledgment acknowledgment, String source) {
        try {
            var result = router.route(key, payload);
            metrics.counter("payflow.notification.consumer", "source", source,
                    "outcome", result.name().toLowerCase()).increment();
            acknowledgment.acknowledge();
        } catch (RuntimeException failure) {
            metrics.counter("payflow.notification.consumer", "source", source,
                    "outcome", "failed").increment();
            throw failure;
        }
    }
}
