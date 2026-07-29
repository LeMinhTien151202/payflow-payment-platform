package com.payflow.accountledger.account.application.reservation;

import com.payflow.accountledger.account.domain.model.Reservation;
import com.payflow.events.account.AccountFundsCapturedData;
import java.util.Objects;

/** Committed capture result; duplicate means no second balance mutation or outcome event. */
public record FundsCapturedResult(
        Reservation reservation, AccountFundsCapturedData eventData, boolean duplicate) {

    public FundsCapturedResult {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(eventData, "eventData");
    }
}
