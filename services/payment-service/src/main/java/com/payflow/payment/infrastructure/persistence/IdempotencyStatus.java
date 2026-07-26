package com.payflow.payment.infrastructure.persistence;

/**
 * State of an {@code idempotency_records} row, matching the {@code idempotency_records_status_known} check.
 *
 * <p>Infrastructure, not domain: it describes a row's completeness, not anything about a payment. There is no
 * {@code IN_PROGRESS} member because the record is written in the transaction that creates the payment, so a
 * request that fails leaves no row at all — see the header comment in {@code V2__payment_intake.sql}.
 */
enum IdempotencyStatus {

    /** A response is stored and can be replayed. */
    COMPLETED,

    /**
     * Reserved by the schema and unused in Phase 1A. A rejected request does not consume its key, because
     * forcing a client to mint a new one after fixing a typo buys no safety.
     */
    FAILED
}
