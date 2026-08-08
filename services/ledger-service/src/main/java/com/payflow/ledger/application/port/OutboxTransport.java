package com.payflow.ledger.application.port;

import com.payflow.ledger.application.outbox.ClaimedOutboxEvent;

/** Network boundary that sends one serialized outbox event and waits for broker acknowledgement. */
public interface OutboxTransport {

    void publish(ClaimedOutboxEvent event) throws Exception;
}
