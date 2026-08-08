package com.payflow.ledger.application.exception;

/** Rejects a command whose envelope identity and typed payload do not describe one intent. */
public class WorkflowCommandContractException extends RuntimeException {

    public WorkflowCommandContractException(String message) {
        super(message);
    }
}
