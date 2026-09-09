package com.payflow.settlement.application.port;

import com.payflow.settlement.application.outbox.ClaimedOutboxEvent;

public interface OutboxTransport { void publish(ClaimedOutboxEvent event) throws Exception; }
