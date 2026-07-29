package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.accountledger.application.outbox.ClaimedOutboxEvent;
import com.payflow.accountledger.application.port.OutboxTransport;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Sends immutable outbox bytes and waits for the broker acknowledgement. */
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
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.topic(), event.aggregateId(), event.payload());
        event.headers()
                .forEach(
                        (name, value) ->
                                record.headers()
                                        .add(name, value.getBytes(StandardCharsets.UTF_8)));

        try {
            kafka.send(record)
                    .get(properties.deliveryTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failedSend) {
            Throwable cause = failedSend.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw failedSend;
        } catch (TimeoutException timeout) {
            // A timeout is ambiguous. Stable event ids plus consumer inboxes make a retry safe.
            throw timeout;
        }
    }
}
