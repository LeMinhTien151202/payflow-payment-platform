package com.payflow.payment.domain.model;

import java.util.Set;

/** Refund lifecycle fixed by spec 7.4. */
public enum RefundStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED;

    public boolean canTransitionTo(RefundStatus target) {
        return switch (this) {
            case CREATED -> target == PROCESSING || target == FAILED;
            case PROCESSING -> target == SUCCEEDED || target == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
    }

    public Set<RefundStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> Set.of(PROCESSING, FAILED);
            case PROCESSING -> Set.of(SUCCEEDED, FAILED);
            case SUCCEEDED, FAILED -> Set.of();
        };
    }
}
