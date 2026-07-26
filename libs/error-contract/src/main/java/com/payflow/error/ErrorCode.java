package com.payflow.error;

/**
 * A stable error code that appears in the {@code code} member of a Problem Details body.
 *
 * <p>This interface exists so that {@link ProblemDetails} can accept codes it does not know about.
 * {@link PayFlowErrorCode} holds the cross-cutting platform codes; a business code such as
 * {@code PAYMENT_DUPLICATE_REFERENCE} belongs to the service that owns the invariant, and the module map
 * forbids collecting those here. Without an abstraction, a service would have to choose between adding its
 * codes to this shared enum and not using the shared builder at all.
 *
 * <p>Implementations are normally enums, whose constant names then double as the wire values. Renaming one is
 * a breaking API change, because clients branch on it.
 */
public interface ErrorCode {

    /** The wire value placed in the {@code code} member. Stable; part of the public API contract. */
    String code();
}
