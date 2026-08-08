package com.payflow.account.application.port;

import com.payflow.account.application.inbox.IncomingEventIdentity;

public interface ProcessedEventStore {
    boolean recordIfNew(IncomingEventIdentity event);
}
