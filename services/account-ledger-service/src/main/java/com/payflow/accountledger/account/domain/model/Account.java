package com.payflow.accountledger.account.domain.model;

import com.payflow.accountledger.account.domain.exception.AccountInvariantViolationException;
import com.payflow.accountledger.account.domain.exception.InsufficientFundsException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Account balance aggregate core.
 *
 * <p>This class is deliberately not advertised as a concurrency boundary. A future PostgreSQL
 * adapter must lock the account row or use a conditional atomic update while persisting the account,
 * reservation and outbox event in one local transaction.
 */
public final class Account {

    private final UUID id;
    private final String currency;
    private Money availableBalance;
    private Money reservedBalance;
    private AccountStatus status;

    private Account(
            UUID id,
            String currency,
            Money availableBalance,
            Money reservedBalance,
            AccountStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.availableBalance = Objects.requireNonNull(availableBalance, "availableBalance");
        this.reservedBalance = Objects.requireNonNull(reservedBalance, "reservedBalance");
        this.status = Objects.requireNonNull(status, "status");
        requireAccountCurrency(availableBalance);
        requireAccountCurrency(reservedBalance);
    }

    public static Account open(UUID id, Money openingBalance) {
        Objects.requireNonNull(openingBalance, "openingBalance");
        return new Account(
                id,
                openingBalance.currency(),
                openingBalance,
                Money.zero(openingBalance.currency()),
                AccountStatus.ACTIVE);
    }

    /** Rebuilds a persisted account without replaying historical balance mutations. */
    public static Account rehydrate(
            UUID id,
            String currency,
            Money availableBalance,
            Money reservedBalance,
            AccountStatus status) {
        return new Account(id, currency, availableBalance, reservedBalance, status);
    }

    public Reservation reserve(
            UUID reservationId,
            UUID paymentId,
            Money amount,
            Instant occurredAt,
            Instant expiresAt) {
        requireActiveForNewReservation();
        Objects.requireNonNull(amount, "amount").requirePositive();
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(occurredAt)) {
            throw new AccountInvariantViolationException(
                    "reservation expiry must be after creation");
        }
        requireAccountCurrency(amount);
        if (availableBalance.isLessThan(amount)) {
            throw new InsufficientFundsException(id, availableBalance.amount(), amount.amount());
        }

        availableBalance = availableBalance.subtract(amount);
        reservedBalance = reservedBalance.add(amount);
        return Reservation.active(
                reservationId, paymentId, id, amount, occurredAt, expiresAt);
    }

    public boolean capture(Reservation reservation, Instant occurredAt) {
        requireOwnedReservation(reservation);
        if (reservation.status() == ReservationStatus.CAPTURED) {
            return false;
        }
        requireActiveReservation(reservation, ReservationStatus.CAPTURED);
        if (reservation.isExpiredAt(occurredAt)) {
            throw new AccountInvariantViolationException(
                    "reservation deadline has passed and cannot be captured");
        }
        ensureReservedBalanceCovers(reservation.amount());

        reservation.transitionTo(ReservationStatus.CAPTURED, occurredAt);
        reservedBalance = reservedBalance.subtract(reservation.amount());
        return true;
    }

    public boolean release(Reservation reservation, Instant occurredAt) {
        return returnReservation(reservation, ReservationStatus.RELEASED, occurredAt);
    }

    public boolean expire(Reservation reservation, Instant occurredAt) {
        requireOwnedReservation(reservation);
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (occurredAt.isBefore(reservation.expiresAt())) {
            throw new AccountInvariantViolationException(
                    "reservation cannot expire before its deadline");
        }
        return returnReservation(reservation, ReservationStatus.EXPIRED, occurredAt);
    }

    /** Refund credits are allowed while frozen so customer funds are not trapped. */
    public void creditRefund(Money amount) {
        Objects.requireNonNull(amount, "amount").requirePositive();
        requireAccountCurrency(amount);
        if (status == AccountStatus.CLOSED) {
            throw new AccountInvariantViolationException(
                    "closed account cannot receive an automatic refund credit");
        }
        availableBalance = availableBalance.add(amount);
    }

    public void freeze() {
        if (status == AccountStatus.CLOSED) {
            throw new AccountInvariantViolationException("closed account cannot be frozen");
        }
        status = AccountStatus.FROZEN;
    }

    public void close() {
        if (reservedBalance.amount().signum() != 0) {
            throw new AccountInvariantViolationException(
                    "account with active reserved balance cannot be closed");
        }
        status = AccountStatus.CLOSED;
    }

    private boolean returnReservation(
            Reservation reservation, ReservationStatus target, Instant occurredAt) {
        requireOwnedReservation(reservation);
        if (reservation.status() == target) {
            return false;
        }
        requireActiveReservation(reservation, target);
        ensureReservedBalanceCovers(reservation.amount());

        reservation.transitionTo(target, occurredAt);
        reservedBalance = reservedBalance.subtract(reservation.amount());
        availableBalance = availableBalance.add(reservation.amount());
        return true;
    }

    private void requireActiveForNewReservation() {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountInvariantViolationException(
                    "account must be ACTIVE to reserve funds; current status=" + status);
        }
    }

    private void requireOwnedReservation(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        if (!id.equals(reservation.accountId())) {
            throw new AccountInvariantViolationException("reservation belongs to another account");
        }
        requireAccountCurrency(reservation.amount());
    }

    private static void requireActiveReservation(
            Reservation reservation, ReservationStatus target) {
        if (reservation.status() != ReservationStatus.ACTIVE) {
            throw new AccountInvariantViolationException(
                    "reservation cannot transition from "
                            + reservation.status()
                            + " to "
                            + target);
        }
    }

    private void ensureReservedBalanceCovers(Money amount) {
        if (reservedBalance.isLessThan(amount)) {
            throw new AccountInvariantViolationException(
                    "reserved balance is lower than the reservation amount");
        }
    }

    private void requireAccountCurrency(Money money) {
        if (!currency.equals(money.currency())) {
            throw new AccountInvariantViolationException(
                    "account currency " + currency + " does not match " + money.currency());
        }
    }

    public UUID id() {
        return id;
    }

    public String currency() {
        return currency;
    }

    public Money availableBalance() {
        return availableBalance;
    }

    public Money reservedBalance() {
        return reservedBalance;
    }

    public AccountStatus status() {
        return status;
    }
}
