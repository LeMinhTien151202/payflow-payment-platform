package com.payflow.settlement.application.outbox;

public record OutboxBatchResult(int claimed, int reclaimed, int published, int publishFailed,
        int terminalFailed, int lostClaims) {
    public static OutboxBatchResult empty() { return new OutboxBatchResult(0, 0, 0, 0, 0, 0); }
}
