package com.payflow.accountledger.account.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable idempotency fact for one committed refund credit. */
public record RefundCredit(
        UUID id,
        UUID refundId,
        UUID paymentId,
        UUID accountId,
        UUID journalId,
        Money amount,
        Instant creditedAt) {

    public RefundCredit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(amount, "amount").requirePositive();
        Objects.requireNonNull(creditedAt, "creditedAt");
    }
}
