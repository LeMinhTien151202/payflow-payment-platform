package com.payflow.error;

/**
 * Platform-level error codes that are part of the public API contract.
 *
 * <p>These codes are stable: clients branch on them, so renaming one is a breaking API change.
 * The HTTP status alone is not a contract — {@code 409} can mean several different things — which is
 * why every error response also carries a code.
 *
 * <p>Only cross-cutting platform concerns belong here. Business codes such as
 * {@code ACCOUNT_INSUFFICIENT_FUNDS} are owned by the service that owns the invariant and must not
 * be centralised into this shared library. Those implement {@link ErrorCode} in their own service.
 */
public enum PayFlowErrorCode implements ErrorCode {

    /** Authentication missing, malformed, or expired. Maps to 401. */
    AUTH_UNAUTHENTICATED("AUTH_UNAUTHENTICATED"),

    /** Authenticated but lacking the required scope, role, or resource ownership. Maps to 403. */
    AUTH_FORBIDDEN("AUTH_FORBIDDEN"),

    /** Request failed boundary validation. Maps to 400. */
    REQUEST_VALIDATION_FAILED("REQUEST_VALIDATION_FAILED"),

    /** Requested resource does not exist, or is not visible to this caller. Maps to 404. */
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND"),

    /** Unexpected server-side failure. Maps to 500 and never carries internal detail. */
    INTERNAL_ERROR("INTERNAL_ERROR");

    private final String code;

    PayFlowErrorCode(String code) {
        this.code = code;
    }

    /** The wire value placed in the {@code code} member of the Problem Details body. */
    @Override
    public String code() {
        return code;
    }
}
