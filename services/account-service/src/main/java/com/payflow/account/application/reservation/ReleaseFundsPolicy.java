package com.payflow.account.application.reservation;

import com.payflow.account.domain.exception.AccountInvariantViolationException;
import com.payflow.account.domain.model.Account;
import com.payflow.account.domain.model.Reservation;
import com.payflow.account.domain.model.ReservationStatus;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import java.time.Instant;
import java.util.Objects;

/** Pure pre-ledger compensation policy; it never releases CAPTURED or EXPIRED reservations. */
public final class ReleaseFundsPolicy {

    public FundsReleasedResult release(
            Account account,
            Reservation reservation,
            AccountReleaseRequestedData command,
            Instant occurredAt) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireSameIntent(account, reservation, command);

        if (reservation.status() == ReservationStatus.RELEASED) {
            return released(reservation, command.reasonCode(), occurredAt, true);
        }
        if (reservation.status() != ReservationStatus.ACTIVE) {
            throw new AccountInvariantViolationException(
                    "release compensation cannot transition reservation from "
                            + reservation.status());
        }

        account.release(reservation, occurredAt);
        return released(reservation, command.reasonCode(), occurredAt, false);
    }

    private static FundsReleasedResult released(
            Reservation reservation, String reasonCode, Instant at, boolean duplicate) {
        return new FundsReleasedResult(
                reservation,
                new AccountFundsReleasedData(
                        reservation.paymentId(),
                        reservation.accountId(),
                        reservation.id(),
                        reservation.amount().amount(),
                        reservation.amount().currency(),
                        reasonCode,
                        at),
                duplicate);
    }

    private static void requireSameIntent(
            Account account,
            Reservation reservation,
            AccountReleaseRequestedData command) {
        boolean same = account.id().equals(command.accountId())
                && reservation.accountId().equals(command.accountId())
                && reservation.paymentId().equals(command.paymentId())
                && reservation.id().equals(command.reservationId())
                && reservation.amount().amount().compareTo(command.amount()) == 0
                && reservation.amount().currency().equals(command.currency());
        if (!same) {
            throw new AccountInvariantViolationException(
                    "release command does not match account reservation intent");
        }
    }
}

