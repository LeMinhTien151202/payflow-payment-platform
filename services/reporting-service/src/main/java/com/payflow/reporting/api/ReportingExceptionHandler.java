package com.payflow.reporting.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ReportingExceptionHandler {

    @ExceptionHandler(ReportingApiException.class)
    ProblemDetail business(ReportingApiException error) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(error.status()), error.getMessage());
        detail.setType(URI.create("urn:payflow:problem:" + error.code().toLowerCase()));
        detail.setProperty("code", error.code());
        return detail;
    }

    @ExceptionHandler({IllegalArgumentException.class})
    ProblemDetail badRequest(IllegalArgumentException error) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
        detail.setType(URI.create("urn:payflow:problem:reporting-invalid-request"));
        detail.setProperty("code", "REPORTING_INVALID_REQUEST");
        return detail;
    }
}
