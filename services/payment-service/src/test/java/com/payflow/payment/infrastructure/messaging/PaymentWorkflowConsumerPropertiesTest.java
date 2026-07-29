package com.payflow.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentWorkflowConsumerPropertiesTest {

    @Test
    void rejectsUnboundedOrBusyLoopConfiguration() {
        assertThatThrownBy(() -> new PaymentWorkflowConsumerProperties(Duration.ZERO, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentWorkflowConsumerProperties(Duration.ofSeconds(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentWorkflowConsumerProperties(Duration.ofSeconds(1), 101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
