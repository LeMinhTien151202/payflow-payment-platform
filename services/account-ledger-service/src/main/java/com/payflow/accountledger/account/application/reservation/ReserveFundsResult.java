package com.payflow.accountledger.account.application.reservation;

/** Exhaustive result of the pure Account reserve use-case policy. */
public sealed interface ReserveFundsResult
        permits FundsReservedResult, FundsReservationFailedResult {
}
