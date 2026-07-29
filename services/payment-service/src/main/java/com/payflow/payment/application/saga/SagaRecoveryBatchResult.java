package com.payflow.payment.application.saga;

/** Observable outcome of one bounded deadline scan. */
public record SagaRecoveryBatchResult(
        int candidates,
        int retried,
        int compensating,
        int manualReview,
        int noAction,
        int concurrentUpdates) {

    public SagaRecoveryBatchResult {
        if (candidates < 0
                || retried < 0
                || compensating < 0
                || manualReview < 0
                || noAction < 0
                || concurrentUpdates < 0) {
            throw new IllegalArgumentException("Saga recovery counts cannot be negative");
        }
        if (retried + compensating + manualReview + noAction + concurrentUpdates != candidates) {
            throw new IllegalArgumentException("Saga recovery outcomes must equal candidates");
        }
    }

    public static SagaRecoveryBatchResult empty() {
        return new SagaRecoveryBatchResult(0, 0, 0, 0, 0, 0);
    }
}
