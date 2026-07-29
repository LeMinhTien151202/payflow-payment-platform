package com.payflow.payment.domain.model;

import com.payflow.payment.domain.exception.UnexpectedRefundStatusException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Refund aggregate. Payment owns capacity; this aggregate owns one refund's lifecycle. */
public final class Refund {

    public static final int MAX_REASON_LENGTH = 500;
    public static final int MAX_ACTOR_ID_LENGTH = 255;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    public static final int MAX_FAILURE_CODE_LENGTH = 100;

    private final UUID id;
    private final UUID paymentId;
    private final UUID merchantId;
    private final String idempotencyKey;
    private final Money amount;
    private final String reason;
    private final String requestedBy;
    private final Instant createdAt;

    private RefundStatus status;
    private Money feeReversalAmount;
    private String failureCode;
    private Instant updatedAt;
    private Instant completedAt;

    private Refund(
            UUID id,
            UUID paymentId,
            UUID merchantId,
            String idempotencyKey,
            Money amount,
            String reason,
            String requestedBy,
            RefundStatus status,
            Money feeReversalAmount,
            String failureCode,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.idempotencyKey = boundedRequired(idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH, "idempotencyKey");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.reason = boundedOptional(reason, MAX_REASON_LENGTH, "reason");
        this.requestedBy = boundedRequired(requestedBy, MAX_ACTOR_ID_LENGTH, "requestedBy");
        this.status = Objects.requireNonNull(status, "status");
        this.feeReversalAmount = feeReversalAmount;
        this.failureCode = boundedOptional(failureCode, MAX_FAILURE_CODE_LENGTH, "failureCode");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.completedAt = completedAt;

        if (!amount.isPositive()) {
            throw new IllegalArgumentException("refund amount must be positive");
        }
        validateTerminalFacts();
    }

    public static Refund create(
            UUID id,
            UUID paymentId,
            UUID merchantId,
            String idempotencyKey,
            Money amount,
            String reason,
            String requestedBy,
            Instant createdAt) {
        return new Refund(
                id,
                paymentId,
                merchantId,
                idempotencyKey,
                amount,
                reason,
                requestedBy,
                RefundStatus.CREATED,
                null,
                null,
                createdAt,
                createdAt,
                null);
    }

    public static Refund rehydrate(
            UUID id,
            UUID paymentId,
            UUID merchantId,
            String idempotencyKey,
            Money amount,
            String reason,
            String requestedBy,
            RefundStatus status,
            Money feeReversalAmount,
            String failureCode,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
        return new Refund(
                id,
                paymentId,
                merchantId,
                idempotencyKey,
                amount,
                reason,
                requestedBy,
                status,
                feeReversalAmount,
                failureCode,
                createdAt,
                updatedAt,
                completedAt);
    }

    public void startProcessing(Instant at) {
        transitionTo(RefundStatus.PROCESSING, at);
    }

    public void succeed(Money feeReversal, Instant at) {
        Objects.requireNonNull(feeReversal, "feeReversal");
        if (!feeReversal.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("fee reversal currency differs from refund currency");
        }
        transitionTo(RefundStatus.SUCCEEDED, at);
        feeReversalAmount = feeReversal;
        completedAt = at;
    }

    public void fail(String code, Instant at) {
        String validatedCode = boundedRequired(code, MAX_FAILURE_CODE_LENGTH, "failureCode");
        transitionTo(RefundStatus.FAILED, at);
        failureCode = validatedCode;
        completedAt = at;
    }

    private void transitionTo(RefundStatus target, Instant at) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");
        if (!status.canTransitionTo(target)) {
            throw new UnexpectedRefundStatusException(id, status, target);
        }
        status = target;
        updatedAt = at;
    }

    private void validateTerminalFacts() {
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("refund updatedAt precedes createdAt");
        }
        boolean terminal = status == RefundStatus.SUCCEEDED || status == RefundStatus.FAILED;
        if (terminal != (completedAt != null)) {
            throw new IllegalArgumentException("refund completion timestamp does not match status");
        }
        if ((status == RefundStatus.SUCCEEDED) != (feeReversalAmount != null)) {
            throw new IllegalArgumentException("fee reversal must exist only for succeeded refund");
        }
        if ((status == RefundStatus.FAILED) != (failureCode != null)) {
            throw new IllegalArgumentException("failure code must exist only for failed refund");
        }
    }

    private static String boundedRequired(String value, int max, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1.." + max + " characters");
        }
        return value;
    }

    private static String boundedOptional(String value, int max, String name) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(name + " must be at most " + max + " characters");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Money amount() {
        return amount;
    }

    public String reason() {
        return reason;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public RefundStatus status() {
        return status;
    }

    public Money feeReversalAmount() {
        return feeReversalAmount;
    }

    public String failureCode() {
        return failureCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
