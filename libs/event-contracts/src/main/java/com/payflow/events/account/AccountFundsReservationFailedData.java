package com.payflow.events.account;

import java.util.Objects;
import java.util.UUID;

/** Payload of {@code account.funds-reservation-failed} v1. */
public record AccountFundsReservationFailedData(
        UUID paymentId, UUID accountId, String reasonCode) {

    public static final int MAX_REASON_CODE_LENGTH = 100;

    public AccountFundsReservationFailedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        if (reasonCode == null
                || reasonCode.isBlank()
                || reasonCode.length() > MAX_REASON_CODE_LENGTH
                || !reasonCode.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "reasonCode must be an uppercase stable code of at most "
                            + MAX_REASON_CODE_LENGTH
                            + " characters");
        }
    }
}
