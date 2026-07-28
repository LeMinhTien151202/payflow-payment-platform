package com.payflow.risk.application.event;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.events.risk.RiskEvents;
import com.payflow.events.risk.RiskLevelValue;
import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import com.payflow.risk.domain.model.RiskAssessment;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Maps Risk-owned domain output to the versioned wire contract from ADR-016. */
public final class RiskAssessmentEventFactory {

    public static final String PRODUCER = "risk-service";

    public EventEnvelope<RiskAssessmentCompletedData> completed(
            UUID eventId,
            EventEnvelope<PaymentCreatedData> cause,
            RiskAssessment assessment,
            Instant occurredAt) {

        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requirePaymentCreated(cause);
        requireSamePaymentContext(cause.data(), assessment);
        // Payment and Risk own different clocks. Logical order comes from causationId; rejecting a
        // valid assessment because Risk's wall clock is slightly behind Payment's would be unsafe.

        RiskAssessmentCompletedData data = new RiskAssessmentCompletedData(
                assessment.paymentId(),
                RiskDecisionValue.valueOf(assessment.decision().name()),
                assessment.score(),
                RiskLevelValue.valueOf(assessment.level().name()),
                assessment.matchedRules().stream().map(Enum::name).toList(),
                assessment.policyVersion());

        return EventEnvelope.causedBy(
                eventId,
                RiskEvents.RISK_ASSESSMENT_COMPLETED,
                assessment.paymentId().toString(),
                cause,
                PRODUCER,
                occurredAt,
                data);
    }

    private static void requirePaymentCreated(EventEnvelope<PaymentCreatedData> cause) {
        if (!PaymentEvents.PAYMENT_CREATED.name().equals(cause.eventType())
                || PaymentEvents.PAYMENT_CREATED.version() != cause.eventVersion()
                || !PaymentEvents.AGGREGATE_TYPE.equals(cause.aggregateType())) {
            throw new RiskInvariantViolationException(
                    "risk assessment must be caused by payment.created v1");
        }
        if (!cause.data().paymentId().toString().equals(cause.aggregateId())) {
            throw new RiskInvariantViolationException(
                    "payment.created aggregateId does not match payload paymentId");
        }
    }

    private static void requireSamePaymentContext(
            PaymentCreatedData payment, RiskAssessment assessment) {
        if (!payment.paymentId().equals(assessment.paymentId())
                || !payment.customerId().equals(assessment.customerId())
                || !payment.merchantId().equals(assessment.merchantId())) {
            throw new RiskInvariantViolationException(
                    "risk assessment identity does not match payment.created");
        }
    }
}
