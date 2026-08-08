package com.payflow.account.application.reservation;

import com.payflow.account.domain.exception.AccountInvariantViolationException;
import com.payflow.account.domain.exception.InsufficientFundsException;
import com.payflow.account.domain.model.Account;
import com.payflow.account.domain.model.AccountStatus;
import com.payflow.account.domain.model.Money;
import com.payflow.account.domain.model.Reservation;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReserveRequestedData;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure reserve policy used inside Account's future inbox + balance + reservation + outbox transaction.
 */
public final class ReserveFundsPolicy {

    public static final String INSUFFICIENT_FUNDS = "ACCOUNT_INSUFFICIENT_FUNDS";
    public static final String ACCOUNT_FROZEN = "ACCOUNT_FROZEN";
    public static final String ACCOUNT_CLOSED = "ACCOUNT_CLOSED";
    public static final String DEADLINE_EXPIRED = "ACCOUNT_RESERVATION_DEADLINE_EXPIRED";

    public ReserveFundsResult reserve(
            Account account,
            Reservation existing,
            AccountReserveRequestedData command,
            UUID reservationId,
            Instant occurredAt) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireAccountIntent(account, command);

        if (existing != null) {
            requireSameIntent(existing, command);
            return reserved(existing, true);
        }
        if (!occurredAt.isBefore(command.expiresAt())) {
            return failed(command, DEADLINE_EXPIRED);
        }
        if (account.status() == AccountStatus.FROZEN) {
            return failed(command, ACCOUNT_FROZEN);
        }
        if (account.status() == AccountStatus.CLOSED) {
            return failed(command, ACCOUNT_CLOSED);
        }

        try {
            Reservation reservation = account.reserve(
                    reservationId,
                    command.paymentId(),
                    new Money(command.amount(), command.currency()),
                    occurredAt,
                    command.expiresAt());
            return reserved(reservation, false);
        } catch (InsufficientFundsException insufficient) {
            return failed(command, INSUFFICIENT_FUNDS);
        }
    }

    private static FundsReservedResult reserved(Reservation reservation, boolean duplicate) {
        return new FundsReservedResult(
                reservation,
                new AccountFundsReservedData(
                        reservation.paymentId(),
                        reservation.accountId(),
                        reservation.id(),
                        reservation.amount().amount(),
                        reservation.amount().currency()),
                duplicate);
    }

    private static FundsReservationFailedResult failed(
            AccountReserveRequestedData command, String reasonCode) {
        return new FundsReservationFailedResult(new AccountFundsReservationFailedData(
                command.paymentId(), command.accountId(), reasonCode));
    }

    private static void requireAccountIntent(
            Account account, AccountReserveRequestedData command) {
        if (!account.id().equals(command.accountId())) {
            throw new AccountInvariantViolationException(
                    "reserve command belongs to another account");
        }
        if (!account.currency().equals(command.currency())) {
            throw new AccountInvariantViolationException(
                    "reserve command currency does not match account");
        }
    }

    private static void requireSameIntent(
            Reservation existing, AccountReserveRequestedData command) {
        boolean same = existing.paymentId().equals(command.paymentId())
                && existing.accountId().equals(command.accountId())
                && existing.amount().amount().compareTo(command.amount()) == 0
                && existing.amount().currency().equals(command.currency())
                && existing.expiresAt().equals(command.expiresAt());
        if (!same) {
            throw new AccountInvariantViolationException(
                    "existing reservation does not match duplicate reserve intent");
        }
    }
}
