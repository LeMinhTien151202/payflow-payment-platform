package com.payflow.accountledger.application.exception;

/** Signals missing durable state required to apply an otherwise valid workflow command. */
public class WorkflowDataException extends RuntimeException {

    public WorkflowDataException(String resource, Object id) {
        super(resource + " not found for workflow identity " + id);
    }
}
