package com.payflow.settlement.application;

public enum SettlementProcessingResult {
    APPLIED,
    DUPLICATE_EVENT,
    DUPLICATE_BUSINESS_FACT,
    LATE_EVENT,
    IGNORED
}
