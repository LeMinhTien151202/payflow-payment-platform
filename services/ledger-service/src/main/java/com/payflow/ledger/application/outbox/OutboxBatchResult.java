package com.payflow.ledger.application.outbox;

/** Observable outcome of one Account-Ledger outbox polling cycle. */
public record OutboxBatchResult(
        int claimed, int reclaimed, int published, int publishFailed, int terminalFailed, int lostClaims) {

    public static OutboxBatchResult empty() {
        return new OutboxBatchResult(0, 0, 0, 0, 0, 0);
    }
}
