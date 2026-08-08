package com.payflow.notification.api;

import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class WebhookOperationExceptionHandler {

    @ExceptionHandler(WebhookOperationException.class)
    ProblemDetail handle(WebhookOperationException error) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(error.status()), error.getMessage());
        detail.setType(URI.create("urn:payflow:problem:" + error.code().toLowerCase()));
        detail.setProperty("code", error.code());
        return detail;
    }
}
