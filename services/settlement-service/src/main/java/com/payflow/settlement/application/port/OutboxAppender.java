package com.payflow.settlement.application.port;

import com.payflow.events.EventType;
import java.time.Instant;

public interface OutboxAppender {

    void append(
            EventType type,
            String topic,
            String aggregateId,
            String correlationId,
            Instant occurredAt,
            Object data);
}
