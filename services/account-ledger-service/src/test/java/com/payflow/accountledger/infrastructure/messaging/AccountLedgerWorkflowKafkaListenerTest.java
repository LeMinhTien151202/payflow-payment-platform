package com.payflow.accountledger.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class AccountLedgerWorkflowKafkaListenerTest {

    private final AccountLedgerWorkflowEventRouter router =
            mock(AccountLedgerWorkflowEventRouter.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final AccountLedgerWorkflowKafkaListener listener =
            new AccountLedgerWorkflowKafkaListener(router, new SimpleMeterRegistry());

    @Test
    void acknowledgesOnlyAfterSuccessfulTransactionResult() {
        when(router.route("payment-1", "payload"))
                .thenReturn(AccountLedgerWorkflowEventRouter.RouteResult.PROCESSED);

        listener.onPaymentEvent("payload", "payment-1", acknowledgment);

        verify(acknowledgment).acknowledge();
    }

    @Test
    void leavesOffsetUnacknowledgedWhenHandlerFails() {
        when(router.route("payment-1", "payload"))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() ->
                        listener.onRefundEvent("payload", "payment-1", acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        verify(acknowledgment, never()).acknowledge();
    }
}
