package com.payflow.notification.application.port;

import com.payflow.notification.application.inbox.IncomingEventIdentity;

public interface ProcessedEventStore {
    boolean recordIfNew(IncomingEventIdentity event);
}
