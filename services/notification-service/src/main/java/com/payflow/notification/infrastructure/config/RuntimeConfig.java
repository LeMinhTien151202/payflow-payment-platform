package com.payflow.notification.infrastructure.config;

import com.payflow.notification.application.notification.OutcomeNotificationFactory;
import com.payflow.notification.application.delivery.NotificationDeliveryPolicy;
import com.payflow.notification.application.port.EmailDeliveryPort;
import com.payflow.notification.infrastructure.delivery.InMemoryEmailDeliveryAdapter;
import com.payflow.notification.infrastructure.delivery.NotificationDeliveryProperties;
import com.payflow.notification.infrastructure.delivery.WebhookProperties;
import com.payflow.notification.application.webhook.*;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({NotificationDeliveryProperties.class,WebhookProperties.class})
class RuntimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OutcomeNotificationFactory outcomeNotificationFactory() {
        return new OutcomeNotificationFactory();
    }

    @Bean
    EmailDeliveryPort emailDeliveryPort(Clock clock) {
        return new InMemoryEmailDeliveryAdapter(clock);
    }

    @Bean
    NotificationDeliveryPolicy notificationDeliveryPolicy(
            NotificationDeliveryProperties properties) {
        return new NotificationDeliveryPolicy(properties.batchSize(), properties.lease(),
                properties.providerTimeout(), properties.maxAttempts());
    }

    @Bean WebhookRetryPolicy webhookRetryPolicy(){return new WebhookRetryPolicy();}
    @Bean WebhookTransport webhookTransport(RestClient.Builder builder,WebhookProperties properties,ObjectMapper json,Clock clock){
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.httpTimeout());
        requests.setReadTimeout(properties.httpTimeout());
        return new com.payflow.notification.infrastructure.delivery.HttpWebhookTransport(
                builder.requestFactory(requests).build(), properties, json, clock);
    }
}
