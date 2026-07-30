package com.payflow.risk.application.port;

import com.payflow.risk.application.outbox.ClaimedOutboxEvent;

public interface OutboxTransport {
    void publish(ClaimedOutboxEvent event) throws Exception;
}
