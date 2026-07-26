package com.payflow.accountledger.ledger.domain.exception;

/** Raised when a proposed journal cannot be posted without violating ledger invariants. */
public class JournalInvariantViolationException extends RuntimeException {

    public JournalInvariantViolationException(String message) {
        super(message);
    }
}
