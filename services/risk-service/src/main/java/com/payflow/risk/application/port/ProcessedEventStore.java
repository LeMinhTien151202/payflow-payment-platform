package com.payflow.risk.application.port;

import com.payflow.risk.application.inbox.IncomingEventIdentity;

public interface ProcessedEventStore {
    boolean recordIfNew(IncomingEventIdentity event);
}
