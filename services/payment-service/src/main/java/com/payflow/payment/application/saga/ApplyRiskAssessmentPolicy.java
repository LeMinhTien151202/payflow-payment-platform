package com.payflow.payment.application.saga;

import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.payment.application.exception.RiskAssessmentPaymentMismatchException;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentRiskAction;
import com.payflow.payment.domain.model.PaymentRiskDecision;
import java.time.Instant;
import java.util.Objects;

/**
 * Maps the versioned ADR-016 contract into Payment-owned domain language.
 *
 * <p>This policy performs no persistence, inbox insert or outbox append. The future Kafka consumer
 * must wrap all three in one local transaction after OD-007 is resolved.
 */
public final class ApplyRiskAssessmentPolicy {

    public static final String RISK_REJECTED_FAILURE_CODE = "RISK_REJECTED";

    public PaymentRiskAction apply(
            Payment payment, RiskAssessmentCompletedData assessment, Instant processedAt) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(processedAt, "processedAt");
        if (!payment.id().equals(assessment.paymentId())) {
            throw new RiskAssessmentPaymentMismatchException(
                    payment.id(), assessment.paymentId());
        }

        // Use Payment Service's local processing clock, not the producer's occurredAt. Comparing wall
        // clocks across services would let harmless clock skew move payment.updatedAt backwards.
        return payment.applyRiskDecision(map(assessment.decision()), processedAt);
    }

    private static PaymentRiskDecision map(RiskDecisionValue decision) {
        return switch (decision) {
            case APPROVED -> PaymentRiskDecision.APPROVED;
            case REVIEW_REQUIRED -> PaymentRiskDecision.REVIEW_REQUIRED;
            case REJECTED -> PaymentRiskDecision.REJECTED;
        };
    }

}
