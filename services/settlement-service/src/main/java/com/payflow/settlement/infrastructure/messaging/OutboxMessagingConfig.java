package com.payflow.settlement.infrastructure.messaging;
import com.payflow.settlement.application.outbox.OutboxPublishPolicy;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration(proxyBeanMethods=false) @EnableScheduling @EnableConfigurationProperties(OutboxProperties.class)
class OutboxMessagingConfig {
 @Bean OutboxPublishPolicy policy(OutboxProperties p){return new OutboxPublishPolicy(p.batchSize(),p.lease(),p.maxAttempts(),p.maxBackoff());}
 @Bean OutboxPublisherOwner owner(){return new OutboxPublisherOwner("settlement-service:"+UUID.randomUUID());}
}
