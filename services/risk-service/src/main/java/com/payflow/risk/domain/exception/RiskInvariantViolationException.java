package com.payflow.risk.domain.exception;

/** Raised when a risk evaluation input or result falls outside policy v1 invariants. */
public final class RiskInvariantViolationException extends RuntimeException {

    public RiskInvariantViolationException(String message) {
        super(message);
    }
}
