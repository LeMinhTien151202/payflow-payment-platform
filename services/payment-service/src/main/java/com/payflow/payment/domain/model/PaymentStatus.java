package com.payflow.payment.domain.model;

import static java.util.Map.entry;

import java.util.Map;
import java.util.Set;

/**
 * The payment lifecycle, and the only transitions it permits.
 *
 * <p>Transcribed from the state diagram in spec 7.4. The table is written out rather than derived,
 * because the value of a state machine is that the illegal moves are stated somewhere a reviewer can
 * read — {@code RESERVING_FUNDS -> CANCELLED} is absent on purpose, and its absence is only
 * meaningful if the whole set is explicit.
 *
 * <p>Every legal move appears here exactly once. Anything not listed is refused by
 * {@link Payment#transitionTo}, which is what stops a retried message or a mis-sequenced Saga reply
 * from moving a settled payment back into flight.
 */
public enum PaymentStatus {

    /** Accepted and persisted. The only state a payment can be created in. */
    CREATED,

    /** Handed to risk assessment; no funds touched. */
    RISK_CHECKING,

    /** Risk refused the payment. Terminal. */
    RISK_REJECTED,

    /** Risk passed; a balance reservation has been requested. */
    RESERVING_FUNDS,

    /** Funds reserved; capture and ledger posting in progress. */
    PROCESSING,

    /** Automated processing stopped; operations must resolve the durable Saga facts. ADR-018. */
    MANUAL_REVIEW_REQUIRED,

    /** Money moved. Not terminal: a refund can still follow. */
    SUCCEEDED,

    /** Failed after risk passed. Terminal. */
    FAILED,

    /** Cancelled before funds were committed. Terminal. */
    CANCELLED,

    /** Some of the amount refunded; more may still be. */
    PARTIALLY_REFUNDED,

    /** Fully refunded. Terminal. */
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS =
            Map.ofEntries(
                    entry(CREATED, Set.of(RISK_CHECKING, CANCELLED)),
                    entry(
                            RISK_CHECKING,
                            Set.of(RISK_REJECTED, RESERVING_FUNDS, CANCELLED, MANUAL_REVIEW_REQUIRED)),
                    entry(RESERVING_FUNDS, Set.of(PROCESSING, FAILED, MANUAL_REVIEW_REQUIRED)),
                    entry(PROCESSING, Set.of(SUCCEEDED, FAILED, MANUAL_REVIEW_REQUIRED)),
                    entry(
                            MANUAL_REVIEW_REQUIRED,
                            Set.of(RISK_REJECTED, RESERVING_FUNDS, PROCESSING, FAILED)),
                    entry(SUCCEEDED, Set.of(PARTIALLY_REFUNDED, REFUNDED)),
                    entry(PARTIALLY_REFUNDED, Set.of(REFUNDED)),
                    entry(RISK_REJECTED, Set.of()),
                    entry(FAILED, Set.of()),
                    entry(CANCELLED, Set.of()),
                    entry(REFUNDED, Set.of()));

    /** The state every payment starts in. */
    public static PaymentStatus initial() {
        return CREATED;
    }

    public Set<PaymentStatus> allowedTargets() {
        return ALLOWED_TRANSITIONS.get(this);
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return allowedTargets().contains(target);
    }

    /**
     * A state with nowhere left to go.
     *
     * <p>Derived from the table rather than declared separately: two lists of the same fact drift, and
     * the one that drifts is always the one nobody tests. Note that {@code SUCCEEDED} is not terminal
     * — a completed payment is still refundable.
     */
    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
