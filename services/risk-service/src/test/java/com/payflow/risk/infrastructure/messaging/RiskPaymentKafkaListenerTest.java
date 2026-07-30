package com.payflow.risk.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class RiskPaymentKafkaListenerTest {

    private final RiskPaymentEventRouter router = mock(RiskPaymentEventRouter.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final RiskPaymentKafkaListener listener =
            new RiskPaymentKafkaListener(router, new SimpleMeterRegistry());

    @Test
    void acknowledgesOnlyAfterSuccessfulProcessing() {
        when(router.route("payment-1", "payload"))
                .thenReturn(RiskPaymentEventRouter.RouteResult.PROCESSED);
        listener.onPaymentEvent("payload", "payment-1", acknowledgment);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void leavesOffsetUnacknowledgedOnFailure() {
        when(router.route("payment-1", "payload"))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        assertThatThrownBy(() -> listener.onPaymentEvent("payload", "payment-1", acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        verify(acknowledgment, never()).acknowledge();
    }
}
