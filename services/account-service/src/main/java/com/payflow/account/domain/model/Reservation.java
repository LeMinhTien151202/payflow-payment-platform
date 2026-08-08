package com.payflow.account.domain.model;

import com.payflow.account.domain.exception.AccountInvariantViolationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable hold for one payment. The database adapter must also enforce a unique payment id; this
 * object protects valid terminal transitions without pretending to prove database concurrency.
 */
public final class Reservation {

    private final UUID id;
    private final UUID paymentId;
    private final UUID accountId;
    private final Money amount;
    private final Instant createdAt;
    private final Instant expiresAt;
    private ReservationStatus status;
    private Instant completedAt;

    private Reservation(
            UUID id,
            UUID paymentId,
            UUID accountId,
            Money amount,
            ReservationStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant completedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.amount = Objects.requireNonNull(amount, "amount").requirePositive();
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new AccountInvariantViolationException(
                    "reservation expiry must be after creation");
        }
        this.completedAt = completedAt;
    }

    static Reservation active(
            UUID id,
            UUID paymentId,
            UUID accountId,
            Money amount,
            Instant createdAt,
            Instant expiresAt) {
        return new Reservation(
                id,
                paymentId,
                accountId,
                amount,
                ReservationStatus.ACTIVE,
                createdAt,
                expiresAt,
                null);
    }

    /** Rebuilds a durable reservation without replaying a balance mutation. */
    public static Reservation rehydrate(
            UUID id,
            UUID paymentId,
            UUID accountId,
            Money amount,
            ReservationStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant completedAt) {
        if (status == ReservationStatus.ACTIVE && completedAt != null) {
            throw new AccountInvariantViolationException(
                    "active reservation cannot have a completion timestamp");
        }
        if (status != ReservationStatus.ACTIVE && completedAt == null) {
            throw new AccountInvariantViolationException(
                    "terminal reservation requires a completion timestamp");
        }
        return new Reservation(
                id, paymentId, accountId, amount, status, createdAt, expiresAt, completedAt);
    }

    boolean transitionTo(ReservationStatus target, Instant at) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");
        if (target == ReservationStatus.ACTIVE) {
            throw new AccountInvariantViolationException("reservation cannot transition back to ACTIVE");
        }
        if (status == target) {
            return false;
        }
        if (status != ReservationStatus.ACTIVE) {
            throw new AccountInvariantViolationException(
                    "reservation cannot transition from " + status + " to " + target);
        }
        if (at.isBefore(createdAt)) {
            throw new AccountInvariantViolationException("completion cannot precede reservation creation");
        }
        status = target;
        completedAt = at;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID accountId() {
        return accountId;
    }

    public Money amount() {
        return amount;
    }

    public ReservationStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean isExpiredAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return !at.isBefore(expiresAt);
    }

    public Instant completedAt() {
        return completedAt;
    }
}
