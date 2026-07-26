package com.payflow.payment.infrastructure.persistence;

/**
 * State of an {@code outbox_events} row, matching the {@code outbox_events_status_known} check and the
 * lifecycle in docs/adr/ADR-014.
 *
 * <p>{@code PROCESSING} is the only state that carries a lease, and the database enforces that as a
 * biconditional: a row in this state without {@code lock_owner} and {@code lock_until} could never be
 * reclaimed, which is the stuck-row failure OD-008 was opened about.
 */
enum OutboxStatus {

    /** Written by the business transaction, waiting for the publisher. */
    PENDING,

    /** Claimed by a publisher instance until its lease expires. */
    PROCESSING,

    /** Acknowledged by the broker. Terminal. */
    PUBLISHED,

    /** Gave up after the retry budget. Terminal until an operator intervenes. */
    FAILED
}
