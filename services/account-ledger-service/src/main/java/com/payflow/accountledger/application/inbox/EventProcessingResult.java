package com.payflow.accountledger.application.inbox;

/** Distinguishes transport redelivery from a duplicate business intent with a new event id. */
public enum EventProcessingResult {
    PROCESSED,
    DUPLICATE,
    BUSINESS_DUPLICATE
}
