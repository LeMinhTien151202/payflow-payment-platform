package com.payflow.accountledger.application.port;

import com.payflow.accountledger.application.inbox.IncomingEventIdentity;

public interface ProcessedEventStore {
    boolean recordIfNew(IncomingEventIdentity event);
}
