package com.payflow.reporting.api;

final class ReportingApiException extends RuntimeException {
    private final String code;
    private final int status;

    ReportingApiException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() {
        return code;
    }

    int status() {
        return status;
    }
}
