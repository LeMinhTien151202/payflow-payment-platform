package com.payflow.risk.domain.policy;

import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import com.payflow.risk.domain.model.RiskDecision;
import com.payflow.risk.domain.model.RiskLevel;

/** Score normalization and fixed decision/level bands accepted in ADR-015. */
public final class RiskScorePolicy {

    private static final int MAX_SCORE = 100;

    private RiskScorePolicy() {}

    public static int normalize(int rawScore) {
        if (rawScore < 0) {
            throw new RiskInvariantViolationException("raw score must not be negative");
        }
        return Math.min(rawScore, MAX_SCORE);
    }

    public static RiskClassification classify(int normalizedScore) {
        if (normalizedScore < 0 || normalizedScore > MAX_SCORE) {
            throw new RiskInvariantViolationException("score must be between 0 and 100");
        }
        if (normalizedScore <= 19) {
            return new RiskClassification(RiskLevel.LOW, RiskDecision.APPROVED);
        }
        if (normalizedScore <= 39) {
            return new RiskClassification(RiskLevel.MEDIUM, RiskDecision.APPROVED);
        }
        if (normalizedScore <= 69) {
            return new RiskClassification(RiskLevel.HIGH, RiskDecision.REVIEW_REQUIRED);
        }
        return new RiskClassification(RiskLevel.CRITICAL, RiskDecision.REJECTED);
    }
}
