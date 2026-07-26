package com.payflow.payment.infrastructure.web;

import com.payflow.error.PayFlowErrorCode;
import com.payflow.error.ProblemDetails;
import com.payflow.error.FieldViolation;
import com.payflow.payment.api.PaymentErrorCode;
import com.payflow.payment.api.exception.IdempotencyKeyRequiredException;
import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.exception.DuplicateMerchantReferenceException;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.exception.MerchantNotRegisteredException;
import com.payflow.payment.application.exception.PaymentApplicationException;
import com.payflow.payment.application.exception.PaymentNotFoundException;
import com.payflow.payment.domain.exception.CurrencyNotAcceptedException;
import com.payflow.payment.domain.exception.MerchantNotAcceptingPaymentsException;
import com.payflow.payment.domain.exception.PaymentDomainException;
import com.payflow.payment.domain.exception.PaymentLimitExceededException;
import com.payflow.payment.domain.exception.UnsupportedCurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to the PayFlow Problem Details contract.
 *
 * <p>Two rules from AGENTS.md section 7 drive this class: every error carries a stable {@code code}
 * clients can branch on, and no response leaks a stack trace, SQL, or other internal detail. The
 * unexpected-failure handler therefore logs the cause server-side and returns a fixed message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Adds the PayFlow extension members to the bodies Spring builds for framework exceptions, so a
     * validation failure looks like every other PayFlow error.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ResponseEntity<Object> response =
                super.handleExceptionInternal(ex, body, headers, status, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ProblemDetails.enrich(problem, codeFor(ex, status), correlationId(request));
            if (ex instanceof MethodArgumentNotValidException invalid) {
                ProblemDetails.withFieldErrors(problem, fieldViolations(invalid));
            }
        }
        return response;
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    ResponseEntity<ProblemDetail> handleMissingIdempotencyKey(
            IdempotencyKeyRequiredException ex, HttpServletRequest request) {

        return problem(
                HttpStatus.BAD_REQUEST,
                PaymentErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                "The Idempotency-Key header is required.",
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        return problem(
                HttpStatus.FORBIDDEN,
                PayFlowErrorCode.AUTH_FORBIDDEN,
                "Access to this resource is not permitted.",
                request);
    }

    @ExceptionHandler(PaymentApplicationException.class)
    ResponseEntity<ProblemDetail> handleApplicationFailure(
            PaymentApplicationException ex, HttpServletRequest request) {

        if (ex instanceof PaymentNotFoundException) {
            return problem(
                    HttpStatus.NOT_FOUND,
                    PaymentErrorCode.PAYMENT_NOT_FOUND,
                    "The payment was not found.",
                    request);
        }
        if (ex instanceof MerchantNotRegisteredException) {
            return problem(
                    HttpStatus.FORBIDDEN,
                    PayFlowErrorCode.AUTH_FORBIDDEN,
                    "Access to this resource is not permitted.",
                    request);
        }
        if (ex instanceof DuplicateMerchantReferenceException) {
            return problem(
                    HttpStatus.CONFLICT,
                    PaymentErrorCode.PAYMENT_DUPLICATE_REFERENCE,
                    "The merchant reference has already been used.",
                    request);
        }
        if (ex instanceof IdempotencyConflictException) {
            return problem(
                    HttpStatus.CONFLICT,
                    PaymentErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST,
                    "The idempotency key was already used with a different request.",
                    request);
        }
        if (ex instanceof ConcurrentIdempotentRequestException) {
            return problem(
                    HttpStatus.CONFLICT,
                    PaymentErrorCode.IDEMPOTENCY_KEY_REQUEST_IN_PROGRESS,
                    "The request is still being processed. Retry with the same idempotency key.",
                    request);
        }
        return unexpected(ex, request);
    }

    @ExceptionHandler(PaymentDomainException.class)
    ResponseEntity<ProblemDetail> handleDomainFailure(
            PaymentDomainException ex, HttpServletRequest request) {

        if (ex instanceof MerchantNotAcceptingPaymentsException) {
            return problem(
                    HttpStatus.CONFLICT,
                    PaymentErrorCode.PAYMENT_MERCHANT_NOT_ACCEPTING,
                    "The merchant cannot accept payments in its current state.",
                    request);
        }
        if (ex instanceof PaymentLimitExceededException) {
            return problem(
                    HttpStatus.BAD_REQUEST,
                    PaymentErrorCode.PAYMENT_LIMIT_EXCEEDED,
                    "The amount exceeds the merchant's per-payment limit.",
                    request);
        }
        if (ex instanceof UnsupportedCurrencyException) {
            return problem(
                    HttpStatus.BAD_REQUEST,
                    PaymentErrorCode.PAYMENT_CURRENCY_NOT_SUPPORTED,
                    "The currency is not supported.",
                    request);
        }
        if (ex instanceof CurrencyNotAcceptedException) {
            return problem(
                    HttpStatus.BAD_REQUEST,
                    PaymentErrorCode.PAYMENT_CURRENCY_NOT_ACCEPTED,
                    "The merchant does not accept the requested currency.",
                    request);
        }
        return unexpected(ex, request);
    }

    /** Method-contract failures reached only when a boundary validation path was bypassed. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleInvalidArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        return problem(
                HttpStatus.BAD_REQUEST,
                PayFlowErrorCode.REQUEST_VALIDATION_FAILED,
                "The request is invalid.",
                request);
    }

    /** Catch-all so an unexpected failure still produces the documented error shape. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        return unexpected(ex, request);
    }

    private ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);

        // Logged at error with the correlation id so the operator can find this exact request; the
        // client receives none of this text.
        log.error("unhandled exception path={}", request.getRequestURI(), ex);

        ProblemDetail problem =
                ProblemDetails.of(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        PayFlowErrorCode.INTERNAL_ERROR,
                        "The request could not be processed.",
                        correlationId);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            com.payflow.error.ErrorCode code,
            String detail,
            HttpServletRequest request) {

        ProblemDetail body =
                ProblemDetails.of(status, code, detail, CorrelationIdFilter.current(request));
        return ResponseEntity.status(status).body(body);
    }

    private static List<FieldViolation> fieldViolations(MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), safeMessage(error.getDefaultMessage())))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
    }

    private static String safeMessage(String message) {
        return message == null ? "is invalid" : message;
    }

    private static PayFlowErrorCode codeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof MethodArgumentNotValidException) {
            return PayFlowErrorCode.REQUEST_VALIDATION_FAILED;
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return PayFlowErrorCode.RESOURCE_NOT_FOUND;
        }
        if (status.is4xxClientError()) {
            return PayFlowErrorCode.REQUEST_VALIDATION_FAILED;
        }
        return PayFlowErrorCode.INTERNAL_ERROR;
    }

    private static String correlationId(WebRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE, WebRequest.SCOPE_REQUEST);
        return value instanceof String id ? id : null;
    }
}
