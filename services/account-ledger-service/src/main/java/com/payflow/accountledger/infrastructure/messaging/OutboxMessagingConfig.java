package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.accountledger.application.outbox.OutboxPublishPolicy;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Infrastructure wiring for the Account-Ledger polling publisher. */
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
        return new OutboxPublisherOwner("account-ledger-service:" + UUID.randomUUID());
    }
}
