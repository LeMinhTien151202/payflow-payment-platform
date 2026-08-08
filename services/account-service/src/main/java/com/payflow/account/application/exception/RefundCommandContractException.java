package com.payflow.account.application.exception;

/** A consumed refund command disagrees with its versioned envelope identity. */
public final class RefundCommandContractException extends RuntimeException {
    public RefundCommandContractException(String message) {
        super(message);
    }
}
