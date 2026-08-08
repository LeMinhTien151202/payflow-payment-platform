package com.payflow.account.application.reservation;

import com.payflow.events.account.AccountFundsReservationFailedData;
import java.util.Objects;

/** Stable business rejection that can be published without leaking internal exception text. */
public record FundsReservationFailedResult(AccountFundsReservationFailedData eventData)
        implements ReserveFundsResult {

    public FundsReservationFailedResult {
        Objects.requireNonNull(eventData, "eventData");
    }
}
