package com.payflow.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class NotificationOutcomeKafkaListenerTest {

    private final NotificationOutcomeEventRouter router = mock(NotificationOutcomeEventRouter.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final NotificationOutcomeKafkaListener listener =
            new NotificationOutcomeKafkaListener(router, new SimpleMeterRegistry());

    @Test
    void acknowledgesOnlyAfterCommittedProcessing() {
        when(router.route("payment-1", "payload"))
                .thenReturn(NotificationOutcomeEventRouter.RouteResult.PROCESSED);
        listener.onPaymentOutcome("payload", "payment-1", acknowledgment);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void leavesOffsetUnacknowledgedOnFailure() {
        when(router.route("payment-1", "payload"))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> listener.onPaymentOutcome("payload", "payment-1", acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        verify(acknowledgment, never()).acknowledge();
    }
}
