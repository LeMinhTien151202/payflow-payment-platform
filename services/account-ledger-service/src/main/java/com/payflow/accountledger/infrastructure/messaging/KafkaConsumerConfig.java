package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.events.PayFlowTopics;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.util.backoff.FixedBackOff;

/** Bounded retry prevents a poison refund command from blocking a partition indefinitely. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
class KafkaConsumerConfig {

    @Bean
    NewTopic accountEventsTopic() {
        return TopicBuilder.name(PayFlowTopics.ACCOUNT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic ledgerEventsTopic() {
        return TopicBuilder.name(PayFlowTopics.LEDGER_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(PayFlowTopics.DEAD_LETTER).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler accountLedgerErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
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
