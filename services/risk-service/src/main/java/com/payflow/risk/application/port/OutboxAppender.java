package com.payflow.risk.application.port;

import com.payflow.events.EventEnvelope;

public interface OutboxAppender {
    void append(String topic, EventEnvelope<?> event);
}
