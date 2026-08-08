package com.payflow.account.application.reservation;

import com.payflow.account.domain.exception.AccountInvariantViolationException;
import com.payflow.account.domain.model.Account;
import com.payflow.account.domain.model.Reservation;
import com.payflow.account.domain.model.ReservationStatus;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountFundsCapturedData;
import java.time.Instant;
import java.util.Objects;

/** Pure post-ledger capture policy fixed by ADR-011. */
public final class CaptureFundsPolicy {

    public FundsCapturedResult capture(
            Account account,
            Reservation reservation,
            AccountCaptureRequestedData command,
            Instant occurredAt) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireSameIntent(account, reservation, command);

        if (reservation.status() == ReservationStatus.CAPTURED) {
            return captured(reservation, reservation.completedAt(), true);
        }
        if (reservation.status() != ReservationStatus.ACTIVE) {
            throw new AccountInvariantViolationException(
                    "capture cannot transition reservation from " + reservation.status());
        }

        account.capture(reservation, occurredAt);
        return captured(reservation, occurredAt, false);
    }

    private static FundsCapturedResult captured(
            Reservation reservation, Instant capturedAt, boolean duplicate) {
        return new FundsCapturedResult(
                reservation,
                new AccountFundsCapturedData(
                        reservation.paymentId(),
                        reservation.accountId(),
                        reservation.id(),
                        reservation.amount().amount(),
                        reservation.amount().currency(),
                        capturedAt),
                duplicate);
    }

    private static void requireSameIntent(
            Account account,
            Reservation reservation,
            AccountCaptureRequestedData command) {
        boolean same = account.id().equals(command.accountId())
                && reservation.accountId().equals(command.accountId())
                && reservation.paymentId().equals(command.paymentId())
                && reservation.id().equals(command.reservationId())
                && reservation.amount().amount().compareTo(command.amount()) == 0
                && reservation.amount().currency().equals(command.currency());
        if (!same) {
            throw new AccountInvariantViolationException(
                    "capture command does not match account reservation intent");
        }
    }
}
