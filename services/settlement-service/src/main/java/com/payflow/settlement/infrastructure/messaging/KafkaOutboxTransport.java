package com.payflow.settlement.infrastructure.messaging;
import com.payflow.settlement.application.outbox.ClaimedOutboxEvent;
import com.payflow.settlement.application.port.OutboxTransport;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
@Component final class KafkaOutboxTransport implements OutboxTransport {
 private final KafkaTemplate<String,String> kafka; private final OutboxProperties properties;
 KafkaOutboxTransport(KafkaTemplate<String,String> kafka,OutboxProperties properties){this.kafka=kafka;this.properties=properties;}
 public void publish(ClaimedOutboxEvent event)throws Exception{
  var record=new ProducerRecord<String,String>(event.topic(),event.aggregateId(),event.payload());
  event.headers().forEach((k,v)->record.headers().add(k,v.getBytes(StandardCharsets.UTF_8)));
  try{kafka.send(record).get(properties.deliveryTimeout().toMillis(),TimeUnit.MILLISECONDS);}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
 }
}
