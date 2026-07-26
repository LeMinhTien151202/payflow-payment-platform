package com.payflow.error;

import com.payflow.observability.CorrelationId;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds the PayFlow extension of RFC 9457 Problem Details.
 *
 * <p>Every error response carries four extension members beyond the RFC:
 *
 * <ul>
 *   <li>{@code code} — a stable business/platform code clients can branch on
 *   <li>{@code correlationId} — the id needed to find the request in logs and traces
 *   <li>{@code timestamp} — when the error body was built
 *   <li>{@code fieldErrors} — per-field validation failures, empty for errors that are not about a field
 * </ul>
 *
 * <p>{@code fieldErrors} is always present, even when empty. A client that has to distinguish "absent" from
 * "empty" before it can read a list is being asked to handle two shapes for one meaning.
 *
 * <p>{@code detail} is caller-facing text only. Stack traces, SQL, driver messages, and internal
 * hostnames must never reach it.
 */
public final class ProblemDetails {

    /** Extension member holding the stable error code. */
    public static final String FIELD_CODE = "code";

    /** Extension member holding the correlation id for support and log lookup. */
    public static final String FIELD_CORRELATION_ID = "correlationId";

    /** Extension member holding the instant the error body was built. */
    public static final String FIELD_TIMESTAMP = "timestamp";

    /** Extension member holding the per-field validation failures. */
    public static final String FIELD_FIELD_ERRORS = "fieldErrors";

    private ProblemDetails() {
    }

    /**
     * Creates a Problem Details body with the PayFlow extension members set.
     *
     * @param status HTTP status for the response
     * @param code stable error code clients may branch on
     * @param detail safe, caller-facing explanation; must not contain internal detail
     * @param correlationId correlation id of the current request, may be {@code null}
     */
    public static ProblemDetail of(
            HttpStatus status, ErrorCode code, String detail, String correlationId) {

        return enrich(ProblemDetail.forStatusAndDetail(status, detail), code, correlationId);
    }

    /**
     * Adds the PayFlow extension members to a Problem Details body that Spring already created,
     * for example from {@code ResponseEntityExceptionHandler}.
     */
    public static ProblemDetail enrich(
            ProblemDetail problem, ErrorCode code, String correlationId) {

        problem.setProperty(FIELD_CODE, code.code());
        if (CorrelationId.isSafe(correlationId)) {
            problem.setProperty(FIELD_CORRELATION_ID, correlationId);
        }
        problem.setProperty(FIELD_TIMESTAMP, timestamp());
        problem.setProperty(FIELD_FIELD_ERRORS, List.of());
        return problem;
    }

    /**
     * Replaces the {@code fieldErrors} member. Returns the same body, so it chains onto
     * {@link #of(HttpStatus, ErrorCode, String, String)}.
     */
    public static ProblemDetail withFieldErrors(
            ProblemDetail problem, List<FieldViolation> violations) {

        problem.setProperty(FIELD_FIELD_ERRORS, List.copyOf(violations));
        return problem;
    }

    /**
     * Read from the system clock rather than an injected one.
     *
     * <p>This is a diagnostic — it tells an operator when the failure was rendered — and no invariant, stored
     * value, or business decision depends on it. The injected {@code Clock} exists for timestamps that end up
     * in the database or in an event, where a test has to be able to fix time; threading it into the error
     * path would mean giving every component that can fail a constructor dependency to produce a field
     * nothing asserts on.
     */
    private static Instant timestamp() {
        return Instant.now();
    }
}
