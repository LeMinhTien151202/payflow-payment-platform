package com.payflow.risk.application.port;

import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.risk.domain.model.RiskAssessment;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RiskAssessmentRecord(
        UUID id,
        PaymentCreatedData payment,
        RiskSignalSnapshot signals,
        RiskAssessment assessment,
        Instant assessedAt) {

    public RiskAssessmentRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(signals, "signals");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(assessedAt, "assessedAt");
        if (!payment.paymentId().equals(assessment.paymentId())) {
            throw new IllegalArgumentException("assessment must belong to payment");
        }
    }
}
