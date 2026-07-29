package com.payflow.payment.application.inbox;

/** Result returned to the Kafka boundary after the local consumer transaction commits. */
public enum EventProcessingResult {
    PROCESSED,
    DUPLICATE
}
