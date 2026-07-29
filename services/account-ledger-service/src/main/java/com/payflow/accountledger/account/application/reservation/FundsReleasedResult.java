package com.payflow.accountledger.account.application.reservation;

import com.payflow.accountledger.account.domain.model.Reservation;
import com.payflow.events.account.AccountFundsReleasedData;
import java.util.Objects;

/** Committed release result; duplicate means no balance mutation and no second outcome event. */
public record FundsReleasedResult(
        Reservation reservation, AccountFundsReleasedData eventData, boolean duplicate) {

    public FundsReleasedResult {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(eventData, "eventData");
    }
}

