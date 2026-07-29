package com.payflow.payment;

import static com.payflow.payment.application.CreatePaymentCommands.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.events.risk.RiskEvents;
import com.payflow.events.risk.RiskLevelValue;
import com.payflow.payment.application.CreatePaymentResult;
import com.payflow.payment.application.handler.CreatePaymentHandler;
import com.payflow.payment.application.handler.HandlePaymentWorkflowEventHandler;
import com.payflow.payment.application.inbox.EventProcessingResult;
import com.payflow.payment.application.exception.FundsReservationMismatchException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL proof that inbox, workflow mutation and caused outbox share one transaction. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "payflow.outbox.enabled=false",
            "payflow.saga-recovery.enabled=false"
        })
class PaymentWorkflowConsumerIT extends AbstractPostgresIT {

    @Autowired
    private CreatePaymentHandler createPayment;

    @Autowired
    private HandlePaymentWorkflowEventHandler workflow;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void newEventCommitsInboxPaymentSagaAndCausallyLinkedOutboxOnce() {
        UUID paymentId = createPayment();
        UUID eventId = UUID.randomUUID();
        var event = riskApproved(eventId, paymentId);

        assertThat(workflow.handleRiskAssessment(event)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(workflow.handleRiskAssessment(event)).isEqualTo(EventProcessingResult.DUPLICATE);

        assertThat(count(
                        "SELECT count(*) FROM payment.processed_events WHERE event_id = ?",
                        eventId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM payment.payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("RESERVING_FUNDS");
        assertThat(jdbc.queryForObject(
                        "SELECT current_step FROM payment.payment_sagas WHERE payment_id = ?",
                        String.class,
                        paymentId))
                .isEqualTo("RESERVE_FUNDS");
        assertThat(count(
                        "SELECT count(*) FROM payment.outbox_events"
                                + " WHERE aggregate_id = ? AND event_type = ?",
                        paymentId.toString(),
                        AccountEvents.RESERVE_REQUESTED.name()))
                .isEqualTo(1);
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM payment.outbox_events"
                        + " WHERE aggregate_id = ? AND event_type = ?",
                String.class,
                paymentId.toString(),
                AccountEvents.RESERVE_REQUESTED.name());
        assertThat(payload)
                .contains("\"correlationId\": \"corr-postgres-workflow\"")
                .contains("\"causationId\": \"" + eventId + "\"");
    }

    @Test
    void businessFailureRollsBackInboxAndLeavesWorkflowAndOutboxUnchanged() {
        UUID paymentId = createPayment();
        workflow.handleRiskAssessment(riskApproved(UUID.randomUUID(), paymentId));
        UUID failedEventId = UUID.randomUUID();
        int beforeOutbox = count(
                "SELECT count(*) FROM payment.outbox_events WHERE aggregate_id = ?",
                paymentId.toString());
        var mismatch = EventEnvelope.of(
                failedEventId,
                AccountEvents.FUNDS_RESERVATION_FAILED,
                paymentId.toString(),
                "corr-postgres-workflow",
                "account-ledger-service",
                Instant.now(),
                new AccountFundsReservationFailedData(
                        paymentId, UUID.randomUUID(), "ACCOUNT_INSUFFICIENT_FUNDS"));

        assertThatThrownBy(() -> workflow.handleFundsReservationFailed(mismatch))
                .isInstanceOf(FundsReservationMismatchException.class);

        assertThat(count(
                        "SELECT count(*) FROM payment.processed_events WHERE event_id = ?",
                        failedEventId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM payment.payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("RESERVING_FUNDS");
        assertThat(jdbc.queryForObject(
                        "SELECT current_step FROM payment.payment_sagas WHERE payment_id = ?",
                        String.class,
                        paymentId))
                .isEqualTo("RESERVE_FUNDS");
        assertThat(count(
                        "SELECT count(*) FROM payment.outbox_events WHERE aggregate_id = ?",
                        paymentId.toString()))
                .isEqualTo(beforeOutbox);
    }

    private UUID createPayment() {
        String suffix = UUID.randomUUID().toString();
        CreatePaymentResult result = createPayment.handle(request()
                .key("KEY-" + suffix)
                .reference("REF-" + suffix)
                .build());
        return result.payment().paymentId();
    }

    private static EventEnvelope<RiskAssessmentCompletedData> riskApproved(
            UUID eventId, UUID paymentId) {
        return EventEnvelope.of(
                eventId,
                RiskEvents.RISK_ASSESSMENT_COMPLETED,
                paymentId.toString(),
                "corr-postgres-workflow",
                "risk-service",
                Instant.now(),
                new RiskAssessmentCompletedData(
                        paymentId,
                        RiskDecisionValue.APPROVED,
                        10,
                        RiskLevelValue.LOW,
                        List.of(),
                        "rules-v1"));
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }
}
