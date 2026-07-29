package com.payflow.payment.application.exception;

import java.util.UUID;

/** Another worker advanced the same durable workflow from the version this worker loaded. */
public final class ConcurrentSagaUpdateException extends PaymentApplicationException {

    public ConcurrentSagaUpdateException(String aggregateType, UUID aggregateId, long version) {
        super("Concurrent " + aggregateType + " update for " + aggregateId + " at version " + version);
    }

    public ConcurrentSagaUpdateException(
            String aggregateType, UUID aggregateId, long version, Throwable cause) {
        super(
                "Concurrent " + aggregateType + " update for " + aggregateId + " at version " + version,
                cause);
    }
}
