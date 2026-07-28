package com.payflow.events.risk;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code risk.assessment.completed} v1, fixed by ADR-016. */
public record RiskAssessmentCompletedData(
        UUID paymentId,
        RiskDecisionValue decision,
        int score,
        RiskLevelValue level,
        List<String> matchedRules,
        String policyVersion) {

    public static final int MAX_POLICY_VERSION_LENGTH = 30;

    public RiskAssessmentCompletedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(decision, "decision is required");
        Objects.requireNonNull(level, "level is required");
        Objects.requireNonNull(matchedRules, "matchedRules is required");
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100, was " + score);
        }
        if (policyVersion == null
                || policyVersion.isBlank()
                || policyVersion.length() > MAX_POLICY_VERSION_LENGTH) {
            throw new IllegalArgumentException(
                    "policyVersion must be non-blank and at most "
                            + MAX_POLICY_VERSION_LENGTH
                            + " characters");
        }

        for (String rule : matchedRules) {
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException("matchedRules must not contain blank values");
            }
        }
        if (new HashSet<>(matchedRules).size() != matchedRules.size()) {
            throw new IllegalArgumentException("matchedRules must not contain duplicates");
        }
        matchedRules = List.copyOf(matchedRules);
    }
}
