package com.payflow.risk.infrastructure.messaging;

import com.payflow.risk.application.outbox.ClaimedOutboxEvent;
import com.payflow.risk.application.port.OutboxTransport;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class KafkaOutboxTransport implements OutboxTransport {

    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    KafkaOutboxTransport(KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    @Override
    public void publish(ClaimedOutboxEvent event) throws Exception {
        var record = new ProducerRecord<String, String>(
                event.topic(), event.aggregateId(), event.payload());
        event.headers().forEach((name, value) -> record.headers()
                .add(name, value.getBytes(StandardCharsets.UTF_8)));
        try {
            kafka.send(record)
                    .get(properties.deliveryTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failed;
        }
    }
}
