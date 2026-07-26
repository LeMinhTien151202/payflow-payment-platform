package com.payflow.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {

    @Test
    @DisplayName("lease must exceed Kafka delivery timeout so a live send cannot be reclaimed")
    void rejectsLeaseNotGreaterThanDeliveryTimeout() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                new OutboxProperties(
                                        true,
                                        Duration.ofMillis(500),
                                        100,
                                        Duration.ofSeconds(30),
                                        10,
                                        Duration.ofSeconds(300),
                                        Duration.ofSeconds(30)))
                .withMessageContaining("lease must be greater");
    }
}
