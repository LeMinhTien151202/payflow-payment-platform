package com.payflow.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookPropertiesTest {

    @Test
    void enabledDeliveryRequiresAnEndpointAndSigningSecret() {
        assertThatThrownBy(() -> new WebhookProperties(
                true, Duration.ofSeconds(1), 25, Duration.ofSeconds(30),
                null, "", Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint URI and signing secret");
    }

    @Test
    void disabledDeliveryAllowsPlaceholderConfiguration() {
        new WebhookProperties(
                false, Duration.ofSeconds(1), 25, Duration.ofSeconds(30),
                URI.create("http://localhost:8090/payflow-webhook"),
                "local-disabled", Duration.ofSeconds(5), false);
    }
}
