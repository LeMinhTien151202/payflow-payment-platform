package com.payflow.payment.application.operations;

/** Explicit operations decisions accepted by the manual-review endpoint. */
public enum ManualReviewDecision {
    APPROVE_RISK,
    REJECT_RISK,
    RETRY_CURRENT_STEP
}
