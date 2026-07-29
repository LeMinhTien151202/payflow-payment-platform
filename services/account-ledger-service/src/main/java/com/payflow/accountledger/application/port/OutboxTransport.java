package com.payflow.accountledger.application.port;

import com.payflow.accountledger.application.outbox.ClaimedOutboxEvent;

/** Network boundary that sends one serialized outbox event and waits for broker acknowledgement. */
public interface OutboxTransport {

    void publish(ClaimedOutboxEvent event) throws Exception;
}
