package com.payflow.payment.domain.model;

import static com.payflow.payment.domain.model.PaymentStatus.CANCELLED;
import static com.payflow.payment.domain.model.PaymentStatus.CREATED;
import static com.payflow.payment.domain.model.PaymentStatus.FAILED;
import static com.payflow.payment.domain.model.PaymentStatus.PARTIALLY_REFUNDED;
import static com.payflow.payment.domain.model.PaymentStatus.PROCESSING;
import static com.payflow.payment.domain.model.PaymentStatus.REFUNDED;
import static com.payflow.payment.domain.model.PaymentStatus.RESERVING_FUNDS;
import static com.payflow.payment.domain.model.PaymentStatus.RISK_CHECKING;
import static com.payflow.payment.domain.model.PaymentStatus.RISK_REJECTED;
import static com.payflow.payment.domain.model.PaymentStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the state machine against an independent transcription of the diagram in spec 7.4.
 *
 * <p>The expected edges below are written from the spec, not read from {@link PaymentStatus}. A test
 * that derived its expectations from the implementation would pass no matter what the implementation
 * said, which is the usual way a state machine ends up with an edge nobody meant to add.
 */
class PaymentStatusTest {

    /** Every transition the spec diagram draws, and only those. */
    private static final Set<List<PaymentStatus>> SPECIFIED_EDGES =
            Set.of(
                    List.of(CREATED, RISK_CHECKING),
                    List.of(CREATED, CANCELLED),
                    List.of(RISK_CHECKING, RISK_REJECTED),
                    List.of(RISK_CHECKING, RESERVING_FUNDS),
                    List.of(RISK_CHECKING, CANCELLED),
                    List.of(RESERVING_FUNDS, PROCESSING),
                    List.of(RESERVING_FUNDS, FAILED),
                    List.of(PROCESSING, SUCCEEDED),
                    List.of(PROCESSING, FAILED),
                    List.of(SUCCEEDED, PARTIALLY_REFUNDED),
                    List.of(SUCCEEDED, REFUNDED),
                    List.of(PARTIALLY_REFUNDED, REFUNDED));

    /**
     * All 100 ordered pairs, so an extra edge is as visible as a missing one. Checking only the legal
     * moves would let {@code RESERVING_FUNDS -> CANCELLED} be added without a single test turning red,
     * and that particular edge would mean cancelling a payment whose funds are already reserved.
     */
    @Test
    @DisplayName("the transition table is exactly the one in spec 7.4, in both directions")
    void transitionTableMatchesTheSpec() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                boolean specified = SPECIFIED_EDGES.contains(List.of(from, to));

                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s must be %s", from, to, specified ? "allowed" : "refused")
                        .isEqualTo(specified);
            }
        }
    }

    @Test
    @DisplayName("no status may transition to itself")
    void noSelfTransition() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.canTransitionTo(status)).as("%s -> itself", status).isFalse();
        }
    }

    @Test
    @DisplayName("the terminal states are the four with no outgoing edge")
    void terminalStatesAreDerivedFromTheTable() {
        Map<PaymentStatus, Boolean> terminal =
                Map.of(
                        CREATED, false,
                        RISK_CHECKING, false,
                        RESERVING_FUNDS, false,
                        PROCESSING, false,
                        SUCCEEDED, false,
                        PARTIALLY_REFUNDED, false,
                        RISK_REJECTED, true,
                        FAILED, true,
                        CANCELLED, true,
                        REFUNDED, true);

        terminal.forEach(
                (status, expected) ->
                        assertThat(status.isTerminal()).as("%s terminal", status).isEqualTo(expected));
    }

    /**
     * Worth its own assertion because it is the one people get wrong: a payment that has succeeded is
     * finished as far as the customer is concerned, and still has two refund transitions ahead of it.
     */
    @Test
    @DisplayName("SUCCEEDED is not terminal")
    void succeededIsNotTerminal() {
        assertThat(SUCCEEDED.isTerminal()).isFalse();
        assertThat(SUCCEEDED.allowedTargets()).containsExactlyInAnyOrder(PARTIALLY_REFUNDED, REFUNDED);
    }

    @Test
    @DisplayName("every status has an entry in the table, so no lookup can fail")
    void everyStatusIsCovered() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.allowedTargets()).as("%s has no entry", status).isNotNull();
        }
    }

    @Test
    @DisplayName("a payment starts in CREATED")
    void initialStatusIsCreated() {
        assertThat(PaymentStatus.initial()).isEqualTo(CREATED);
    }
}
