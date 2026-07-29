package com.payflow.payment.application.saga;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SagaRecoverySettingsTest {

    @Test
    void rejectsUnboundedOrNonPositiveSettings() {
        assertThatThrownBy(() -> new SagaRecoverySettings(Duration.ZERO, 3, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SagaRecoverySettings(Duration.ofSeconds(1), -1, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SagaRecoverySettings(Duration.ofSeconds(1), 3, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
