package com.payflow.risk.domain.model;

import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable result of policy v1 evaluation. Score is normalized; matched rules explain it. */
public record RiskAssessment(
        UUID paymentId,
        UUID customerId,
        UUID merchantId,
        String policyVersion,
        int score,
        RiskLevel level,
        RiskDecision decision,
        List<RiskRuleCode> matchedRules) {

    public RiskAssessment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(matchedRules, "matchedRules");
        if (policyVersion.isBlank() || policyVersion.length() > 30) {
            throw new RiskInvariantViolationException(
                    "policyVersion must be non-blank and at most 30 characters");
        }
        if (score < 0 || score > 100) {
            throw new RiskInvariantViolationException("normalized score must be between 0 and 100");
        }
        matchedRules = List.copyOf(matchedRules);
    }
}
