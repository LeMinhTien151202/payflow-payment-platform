package com.payflow.ledger.application.port;

import com.payflow.ledger.application.inbox.IncomingEventIdentity;

public interface ProcessedEventStore {
    boolean recordIfNew(IncomingEventIdentity event);
}
