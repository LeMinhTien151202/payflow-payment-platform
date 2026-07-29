package com.payflow.payment.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Bounded retry and terminal DLT behavior for Payment workflow poison/transient failures. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(PaymentWorkflowConsumerProperties.class)
class PaymentWorkflowConsumerConfig {

    @Bean
    DefaultErrorHandler paymentWorkflowErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            PaymentWorkflowConsumerProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, failure) ->
                        new TopicPartition(PayFlowTopics.DEAD_LETTER, record.partition()));
        recoverer.setFailIfSendResultIsError(true);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(
                        properties.retryBackoff().toMillis(), properties.maxRetries()));
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }
}
