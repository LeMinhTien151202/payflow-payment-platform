package com.payflow.notification.infrastructure.delivery;

import com.payflow.notification.application.delivery.NotificationDeliveryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payflow.notification-delivery")
public record NotificationDeliveryProperties(
        int batchSize, Duration lease, Duration providerTimeout, int maxAttempts) {

    public NotificationDeliveryProperties {
        new NotificationDeliveryPolicy(batchSize, lease, providerTimeout, maxAttempts);
    }
}
