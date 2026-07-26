package com.payflow.payment.application.port;

import com.payflow.payment.application.outbox.ClaimedOutboxEvent;

/** Network boundary that sends one already-serialized event and waits for broker acknowledgement. */
public interface OutboxTransport {

    void publish(ClaimedOutboxEvent event) throws Exception;
}
