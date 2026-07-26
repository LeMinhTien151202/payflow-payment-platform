package com.payflow.accountledger.account.domain.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** A business rejection: reserving the requested amount would make available balance negative. */
public final class InsufficientFundsException extends AccountInvariantViolationException {

    public InsufficientFundsException(UUID accountId, BigDecimal available, BigDecimal requested) {
        super(
                "account "
                        + accountId
                        + " has insufficient available balance: available="
                        + available
                        + ", requested="
                        + requested);
    }
}
