package com.payflow.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class PaymentWorkflowKafkaListenerTest {

    private final PaymentWorkflowEventRouter router = mock(PaymentWorkflowEventRouter.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final PaymentWorkflowKafkaListener listener =
            new PaymentWorkflowKafkaListener(router, metrics);

    @Test
    void acknowledgesOnlyAfterRouterReturnsSuccessfully() {
        when(router.route("payment-1", "payload"))
                .thenReturn(PaymentWorkflowEventRouter.RouteResult.PROCESSED);

        listener.onRiskEvent("payload", "payment-1", acknowledgment);

        verify(acknowledgment).acknowledge();
        assertThat(metrics.get("payflow.payment.workflow.consumer")
                        .tag("outcome", "processed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void propagatesFailureWithoutAcknowledgingSoErrorHandlerCanRetry() {
        when(router.route("payment-1", "payload"))
                .thenThrow(new IllegalStateException("transaction rolled back"));

        assertThatThrownBy(() -> listener.onLedgerEvent("payload", "payment-1", acknowledgment))
                .isInstanceOf(IllegalStateException.class);

        verify(acknowledgment, never()).acknowledge();
        assertThat(metrics.get("payflow.payment.workflow.consumer")
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
