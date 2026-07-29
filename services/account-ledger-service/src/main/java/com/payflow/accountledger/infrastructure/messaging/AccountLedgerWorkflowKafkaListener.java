package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Acknowledges Account/Ledger commands only after their local transaction returns successfully. */
@Component
class AccountLedgerWorkflowKafkaListener {

    static final String GROUP_ID = "account-ledger-workflow-v1";

    private final AccountLedgerWorkflowEventRouter router;
    private final MeterRegistry metrics;

    AccountLedgerWorkflowKafkaListener(
            AccountLedgerWorkflowEventRouter router, MeterRegistry metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @KafkaListener(
            id = "account-ledger-refund-requests",
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
            id = "account-ledger-payment-commands",
            groupId = GROUP_ID,
            topics = PayFlowTopics.PAYMENT_EVENTS,
            autoStartup = "${payflow.payment-consumer.enabled:true}")
    void onPaymentEvent(
            String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {
        consume(key, payload, acknowledgment);
    }

    private void consume(String key, String payload, Acknowledgment acknowledgment) {
        try {
            AccountLedgerWorkflowEventRouter.RouteResult result = router.route(key, payload);
            metrics.counter(
                            "payflow.account_ledger.workflow.consumer",
                            "outcome",
                            result.name().toLowerCase())
                    .increment();
            acknowledgment.acknowledge();
        } catch (RuntimeException failure) {
            metrics.counter(
                            "payflow.account_ledger.workflow.consumer", "outcome", "failed")
                    .increment();
            throw failure;
        }
    }
}
