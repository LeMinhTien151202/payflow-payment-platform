package com.payflow.events.ledger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Definitive or retry-exhausted outcome of {@code ledger.post-payment.requested} v1. */
public record LedgerPaymentPostingFailedData(
        UUID paymentId, String failureCode, Instant failedAt) {

    public LedgerPaymentPostingFailedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        if (failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new IllegalArgumentException(
                    "failureCode must be an uppercase stable code of at most 100 characters");
        }
        Objects.requireNonNull(failedAt, "failedAt is required");
    }
}

