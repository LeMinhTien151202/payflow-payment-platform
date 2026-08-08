package com.payflow.reporting.infrastructure.messaging;
import com.payflow.events.PayFlowTopics;
import com.payflow.reporting.application.ReportingProjectionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.*;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="payflow.reporting-consumer.enabled",havingValue="true",matchIfMissing=true)
final class ReportingKafkaListener{
 private final ReportingProjectionHandler handler;
 ReportingKafkaListener(ReportingProjectionHandler handler){this.handler=handler;}
 @KafkaListener(id="reporting-payment",groupId="reporting-projection-v1",topics=PayFlowTopics.PAYMENT_EVENTS,
  autoStartup="${payflow.reporting-consumer.enabled:true}")
 void payment(String payload,@Header(name=KafkaHeaders.RECEIVED_KEY,required=false)String key,Acknowledgment ack){consume(key,payload,ack);}
 @KafkaListener(id="reporting-refund",groupId="reporting-projection-v1",topics=PayFlowTopics.REFUND_EVENTS,
  autoStartup="${payflow.reporting-consumer.enabled:true}")
 void refund(String payload,@Header(name=KafkaHeaders.RECEIVED_KEY,required=false)String key,Acknowledgment ack){consume(key,payload,ack);}
 private void consume(String key,String payload,Acknowledgment ack){handler.handle(key,payload);ack.acknowledge();}
}
