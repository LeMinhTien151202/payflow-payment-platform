package com.payflow.risk.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
class KafkaConsumerConfig {

    @Bean
    NewTopic riskEventsTopic() {
        return TopicBuilder.name(PayFlowTopics.RISK_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(PayFlowTopics.DEAD_LETTER).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler riskErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, failure) ->
                        new TopicPartition(PayFlowTopics.DEAD_LETTER, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));
        handler.setCommitRecovered(true);
        return handler;
    }
}
