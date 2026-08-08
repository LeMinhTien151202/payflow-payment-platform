package com.payflow.reporting.infrastructure.messaging;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;
@Configuration(proxyBeanMethods=false)
class KafkaConsumerConfig{
 @Bean CommonErrorHandler reportingErrorHandler(KafkaTemplate<String,String> template){
  var recoverer=new DeadLetterPublishingRecoverer(template,(r,e)->new TopicPartition("payflow.dead-letter.v1",r.partition()));
  return new DefaultErrorHandler(recoverer,new FixedBackOff(1000,3));
 }
}
