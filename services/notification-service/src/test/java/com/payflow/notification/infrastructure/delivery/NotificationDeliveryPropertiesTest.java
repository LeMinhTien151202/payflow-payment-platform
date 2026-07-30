package com.payflow.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationDeliveryPropertiesTest {

    @Test
    void leaseMustExceedProviderTimeout() {
        assertThatThrownBy(() -> new NotificationDeliveryProperties(
                10, Duration.ofSeconds(5), Duration.ofSeconds(5), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed provider-timeout");
    }

    @Test
    void attemptsAreBounded() {
        assertThatThrownBy(() -> new NotificationDeliveryProperties(
                10, Duration.ofSeconds(30), Duration.ofSeconds(5), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
    }
}
