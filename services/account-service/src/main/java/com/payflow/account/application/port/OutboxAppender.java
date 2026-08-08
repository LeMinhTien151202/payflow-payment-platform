package com.payflow.account.application.port;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import java.time.Instant;
import java.util.UUID;

public interface OutboxAppender {
    <T> UUID appendCausedBy(
            EventType type,
            String topic,
            String aggregateId,
            Instant occurredAt,
            T data,
            EventEnvelope<?> cause);
}
