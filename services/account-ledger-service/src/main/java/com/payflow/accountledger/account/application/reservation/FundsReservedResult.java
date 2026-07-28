package com.payflow.accountledger.account.application.reservation;

import com.payflow.accountledger.account.domain.model.Reservation;
import com.payflow.events.account.AccountFundsReservedData;
import java.util.Objects;

/** Successful reserve result; duplicate means the existing reservation was reused without mutation. */
public record FundsReservedResult(
        Reservation reservation, AccountFundsReservedData eventData, boolean duplicate)
        implements ReserveFundsResult {

    public FundsReservedResult {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(eventData, "eventData");
    }
}
