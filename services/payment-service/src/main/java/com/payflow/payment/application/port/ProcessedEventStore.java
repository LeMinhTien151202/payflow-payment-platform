package com.payflow.payment.application.port;

import com.payflow.payment.application.inbox.IncomingEventIdentity;

/**
 * Durable inbox gate. Implementations return {@code true} only to the transaction that won the
 * right to apply this event's business effect.
 */
public interface ProcessedEventStore {

    boolean recordIfNew(IncomingEventIdentity event);
}

