package com.payflow.risk.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.PayFlowTopics;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.events.risk.RiskEvents;
import com.payflow.events.risk.RiskLevelValue;
import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import com.payflow.risk.domain.model.RiskAssessment;
import com.payflow.risk.domain.model.RiskDecision;
import com.payflow.risk.domain.model.RiskLevel;
import com.payflow.risk.domain.model.RiskRuleCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskAssessmentEventFactoryTest {

    private static final UUID PAYMENT_EVENT_ID =
            UUID.fromString("51734b31-8e75-4570-bdc6-979fa02ab447");
    private static final UUID RISK_EVENT_ID =
            UUID.fromString("31734b31-8e75-4570-bdc6-979fa02ab446");
    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID MERCHANT_ID =
            UUID.fromString("2f1c7a30-0b5e-4a4e-9a94-6a1a1cbb1111");
    private static final UUID CUSTOMER_ID =
            UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final Instant PAYMENT_CREATED_AT = Instant.parse("2026-07-28T08:00:00Z");
    private static final Instant ASSESSED_AT = Instant.parse("2026-07-28T08:00:01Z");
    private static final String CORRELATION_ID = "0a1b2c3d-4e5f-6789-abcd-ef0123456789";

    private final RiskAssessmentEventFactory factory = new RiskAssessmentEventFactory();

    @Test
    void createsVersionedRiskEventWithPaymentKeyAndCausation() {
        EventEnvelope<RiskAssessmentCompletedData> event =
                factory.completed(RISK_EVENT_ID, paymentCreated(), assessment(), ASSESSED_AT);

        assertThat(PayFlowTopics.RISK_EVENTS).isEqualTo("payflow.risk.events.v1");
        assertThat(event.eventId()).isEqualTo(RISK_EVENT_ID);
        assertThat(event.eventType()).isEqualTo("risk.assessment.completed");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.aggregateType()).isEqualTo("PAYMENT");
        assertThat(event.aggregateId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(event.causationId()).isEqualTo(PAYMENT_EVENT_ID.toString());
        assertThat(event.producer()).isEqualTo("risk-service");
        assertThat(event.occurredAt()).isEqualTo(ASSESSED_AT);
    }

    @Test
    void mapsDomainValuesWithoutSharingRiskDomainTypesOnTheWire() {
        RiskAssessmentCompletedData data =
                factory.completed(RISK_EVENT_ID, paymentCreated(), assessment(), ASSESSED_AT).data();

        assertThat(data.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(data.decision()).isEqualTo(RiskDecisionValue.REVIEW_REQUIRED);
        assertThat(data.score()).isEqualTo(40);
        assertThat(data.level()).isEqualTo(RiskLevelValue.HIGH);
        assertThat(data.matchedRules())
                .containsExactly("VELOCITY_1M", "IP_CHANGE");
        assertThat(data.policyVersion()).isEqualTo("risk-v1");
    }

    @Test
    void mapsEveryPublishedDecisionAndLevelByStableEnumName() {
        for (RiskDecision decision : RiskDecision.values()) {
            for (RiskLevel level : RiskLevel.values()) {
                RiskAssessment assessment = new RiskAssessment(
                        PAYMENT_ID,
                        CUSTOMER_ID,
                        MERCHANT_ID,
                        "risk-v1",
                        0,
                        level,
                        decision,
                        List.of());

                RiskAssessmentCompletedData data = factory
                        .completed(RISK_EVENT_ID, paymentCreated(), assessment, ASSESSED_AT)
                        .data();

                assertThat(data.decision().name()).isEqualTo(decision.name());
                assertThat(data.level().name()).isEqualTo(level.name());
            }
        }
    }

    @Test
    void refusesAnAssessmentForDifferentPaymentCustomerOrMerchant() {
        assertContextMismatch(new RiskAssessment(
                UUID.randomUUID(),
                CUSTOMER_ID,
                MERCHANT_ID,
                "risk-v1",
                0,
                RiskLevel.LOW,
                RiskDecision.APPROVED,
                List.of()));
        assertContextMismatch(new RiskAssessment(
                PAYMENT_ID,
                UUID.randomUUID(),
                MERCHANT_ID,
                "risk-v1",
                0,
                RiskLevel.LOW,
                RiskDecision.APPROVED,
                List.of()));
        assertContextMismatch(new RiskAssessment(
                PAYMENT_ID,
                CUSTOMER_ID,
                UUID.randomUUID(),
                "risk-v1",
                0,
                RiskLevel.LOW,
                RiskDecision.APPROVED,
                List.of()));
    }

    @Test
    void refusesWrongCauseContract() {
        EventEnvelope<PaymentCreatedData> wrongCause = EventEnvelope.of(
                PAYMENT_EVENT_ID,
                new EventType("payment.other", 1, "PAYMENT"),
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "payment-service",
                PAYMENT_CREATED_AT,
                paymentCreated().data());

        assertThatThrownBy(
                        () -> factory.completed(RISK_EVENT_ID, wrongCause, assessment(), ASSESSED_AT))
                .isInstanceOf(RiskInvariantViolationException.class)
                .hasMessageContaining("payment.created v1");
    }

    @Test
    void refusesCauseWhoseAggregateKeyDoesNotMatchItsPayload() {
        EventEnvelope<PaymentCreatedData> wrongKey = new EventEnvelope<>(
                PAYMENT_EVENT_ID,
                PaymentEvents.PAYMENT_CREATED.name(),
                PaymentEvents.PAYMENT_CREATED.version(),
                PaymentEvents.AGGREGATE_TYPE,
                UUID.randomUUID().toString(),
                CORRELATION_ID,
                null,
                "payment-service",
                PAYMENT_CREATED_AT,
                paymentCreated().data());

        assertThatThrownBy(
                        () -> factory.completed(RISK_EVENT_ID, wrongKey, assessment(), ASSESSED_AT))
                .isInstanceOf(RiskInvariantViolationException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    void causationNotCrossServiceWallClockDefinesLogicalOrder() {
        Instant skewedRiskClock = PAYMENT_CREATED_AT.minusSeconds(30);

        EventEnvelope<RiskAssessmentCompletedData> event = factory.completed(
                RISK_EVENT_ID, paymentCreated(), assessment(), skewedRiskClock);

        assertThat(event.occurredAt()).isEqualTo(skewedRiskClock);
        assertThat(event.causationId()).isEqualTo(PAYMENT_EVENT_ID.toString());
    }

    private void assertContextMismatch(RiskAssessment mismatched) {
        assertThatThrownBy(
                        () -> factory.completed(RISK_EVENT_ID, paymentCreated(), mismatched, ASSESSED_AT))
                .isInstanceOf(RiskInvariantViolationException.class)
                .hasMessageContaining("does not match");
    }

    private static RiskAssessment assessment() {
        return new RiskAssessment(
                PAYMENT_ID,
                CUSTOMER_ID,
                MERCHANT_ID,
                "risk-v1",
                40,
                RiskLevel.HIGH,
                RiskDecision.REVIEW_REQUIRED,
                List.of(RiskRuleCode.VELOCITY_1M, RiskRuleCode.IP_CHANGE));
    }

    private static EventEnvelope<PaymentCreatedData> paymentCreated() {
        return EventEnvelope.of(
                PAYMENT_EVENT_ID,
                PaymentEvents.PAYMENT_CREATED,
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "payment-service",
                PAYMENT_CREATED_AT,
                new PaymentCreatedData(
                        PAYMENT_ID,
                        MERCHANT_ID,
                        CUSTOMER_ID,
                        ACCOUNT_ID,
                        new BigDecimal("500000"),
                        "VND",
                        PAYMENT_CREATED_AT));
    }
}
