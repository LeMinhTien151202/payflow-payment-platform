package com.payflow.settlement.infrastructure.messaging;
import com.payflow.events.PayFlowTopics;
import com.payflow.settlement.application.HandleSettlementEventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.*;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="payflow.settlement-consumer.enabled",havingValue="true",matchIfMissing=true)
final class SettlementKafkaListener {
 static final String GROUP="settlement-financial-facts-v1"; private final HandleSettlementEventHandler handler;
 SettlementKafkaListener(HandleSettlementEventHandler handler){this.handler=handler;}
 @KafkaListener(id="settlement-payment",groupId=GROUP,topics=PayFlowTopics.PAYMENT_EVENTS,autoStartup="${payflow.settlement-consumer.enabled:true}")
 void payment(String p,@Header(name=KafkaHeaders.RECEIVED_KEY,required=false)String k,Acknowledgment a){consume(k,p,a);}
 @KafkaListener(id="settlement-refund",groupId=GROUP,topics=PayFlowTopics.REFUND_EVENTS,autoStartup="${payflow.settlement-consumer.enabled:true}")
 void refund(String p,@Header(name=KafkaHeaders.RECEIVED_KEY,required=false)String k,Acknowledgment a){consume(k,p,a);}
 @KafkaListener(id="settlement-account",groupId=GROUP,topics=PayFlowTopics.ACCOUNT_EVENTS,autoStartup="${payflow.settlement-consumer.enabled:true}")
 void account(String p,@Header(name=KafkaHeaders.RECEIVED_KEY,required=false)String k,Acknowledgment a){consume(k,p,a);}
 @KafkaListener(id="settlement-ledger",groupId=GROUP,topics=PayFlowTopics.LEDGER_EVENTS,autoStartup="${payflow.settlement-consumer.enabled:true}")
 void ledger(String p,@Header(name=KafkaHeaders.RECEIVED_KEY,required=false)String k,Acknowledgment a){consume(k,p,a);}
 private void consume(String k,String p,Acknowledgment a){handler.handle(k,p);a.acknowledge();}
}
