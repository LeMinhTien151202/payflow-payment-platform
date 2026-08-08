package com.payflow.account.domain.exception;

/** Raised when an account or reservation operation would break a financial invariant. */
public class AccountInvariantViolationException extends RuntimeException {

    public AccountInvariantViolationException(String message) {
        super(message);
    }
}
