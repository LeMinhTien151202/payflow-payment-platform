package com.payflow.payment.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import com.payflow.payment.application.outbox.OutboxPublishPolicy;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Infrastructure wiring for the Phase 1A polling publisher. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxMessagingConfig {

    @Bean
    OutboxPublishPolicy outboxPublishPolicy(OutboxProperties properties) {
        return new OutboxPublishPolicy(
                properties.batchSize(),
                properties.lease(),
                properties.maxAttempts(),
                properties.maxBackoff());
    }

    @Bean
    OutboxPublisherOwner outboxPublisherOwner() {
        return new OutboxPublisherOwner("payment-service:" + UUID.randomUUID());
    }

    /** Auto-create is disabled at the broker; the producer owns declaration of its topic. */
    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PayFlowTopics.PAYMENT_EVENTS).partitions(3).replicas(1).build();
    }

    /** Consumer recovery publishes the original failed record here after bounded attempts. */
    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(PayFlowTopics.DEAD_LETTER).partitions(3).replicas(1).build();
    }
}
