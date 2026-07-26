package com.payflow.payment.infrastructure.persistence;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Identifies which database constraint a failed write violated.
 *
 * <p>Needed because a single insert can violate more than one unique index, and the right answer differs by
 * index: {@code uq_idempotency_records_scope_key} means "replay the other request's response",
 * {@code uq_payments_merchant_reference} means "409, you already charged for that order". A caller that only
 * knew a constraint had failed would have to pick one and be wrong half the time.
 *
 * <p>The name is the contract. It is why every constraint and index in {@code V2__payment_intake.sql} is named
 * explicitly instead of left to PostgreSQL's default naming, and why renaming one is a code change.
 */
final class ConstraintViolations {

    /** Bounds the walk. A cause chain this deep is a cycle, and the answer either way is "not that one". */
    private static final int MAX_DEPTH = 16;

    private ConstraintViolations() {
    }

    /**
     * Whether {@code failure}, or anything that caused it, is a violation of {@code constraintName}.
     *
     * <p>Walks the cause chain because the interesting exception is never the one that was thrown: JPA wraps
     * Hibernate, which wraps the driver's {@code SQLException}, and only Hibernate's layer knows the name.
     */
    static boolean violated(Throwable failure, String constraintName) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }
}
