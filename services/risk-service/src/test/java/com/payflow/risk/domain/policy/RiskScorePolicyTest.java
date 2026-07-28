package com.payflow.risk.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import com.payflow.risk.domain.model.RiskDecision;
import com.payflow.risk.domain.model.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class RiskScorePolicyTest {

    @ParameterizedTest
    @CsvSource({
        "0,LOW,APPROVED",
        "19,LOW,APPROVED",
        "20,MEDIUM,APPROVED",
        "39,MEDIUM,APPROVED",
        "40,HIGH,REVIEW_REQUIRED",
        "69,HIGH,REVIEW_REQUIRED",
        "70,CRITICAL,REJECTED",
        "100,CRITICAL,REJECTED"
    })
    void classifiesEveryBandBoundary(
            int score, RiskLevel expectedLevel, RiskDecision expectedDecision) {
        RiskClassification classification = RiskScorePolicy.classify(score);

        assertThat(classification.level()).isEqualTo(expectedLevel);
        assertThat(classification.decision()).isEqualTo(expectedDecision);
    }

    @Test
    void saturatesRawScoreAtOneHundred() {
        assertThat(RiskScorePolicy.normalize(0)).isZero();
        assertThat(RiskScorePolicy.normalize(99)).isEqualTo(99);
        assertThat(RiskScorePolicy.normalize(100)).isEqualTo(100);
        assertThat(RiskScorePolicy.normalize(210)).isEqualTo(100);
    }

    @Test
    void rejectsNegativeRawAndOutOfRangeNormalizedScore() {
        assertThatThrownBy(() -> RiskScorePolicy.normalize(-1))
                .isInstanceOf(RiskInvariantViolationException.class);
        assertThatThrownBy(() -> RiskScorePolicy.classify(101))
                .isInstanceOf(RiskInvariantViolationException.class);
    }
}
