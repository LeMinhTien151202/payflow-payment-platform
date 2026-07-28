package com.payflow.events.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskAssessmentCompletedDataTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");

    @Test
    void acceptsBothScoreBoundaries() {
        assertThat(data(0, List.of()).score()).isZero();
        assertThat(data(100, List.of("AMOUNT_HIGH")).score()).isEqualTo(100);
    }

    @Test
    void rejectsScoreOutsidePublishedRange() {
        assertThatThrownBy(() -> data(-1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 100");
        assertThatThrownBy(() -> data(101, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 100");
    }

    @Test
    void copiesMatchedRulesAndKeepsTheirOrder() {
        List<String> source = new ArrayList<>(List.of("NEW_DEVICE", "IP_CHANGE"));

        RiskAssessmentCompletedData data = data(30, source);
        source.clear();

        assertThat(data.matchedRules()).containsExactly("NEW_DEVICE", "IP_CHANGE");
        assertThatThrownBy(() -> data.matchedRules().add("AMOUNT_HIGH"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankOrDuplicateRuleCodes() {
        assertThatThrownBy(() -> data(10, List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> data(20, List.of("NEW_DEVICE", "NEW_DEVICE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void rejectsMissingIdentityDecisionLevelAndPolicyVersion() {
        assertThatThrownBy(() -> new RiskAssessmentCompletedData(
                        null,
                        RiskDecisionValue.APPROVED,
                        0,
                        RiskLevelValue.LOW,
                        List.of(),
                        "risk-v1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paymentId");
        assertThatThrownBy(() -> new RiskAssessmentCompletedData(
                        PAYMENT_ID, null, 0, RiskLevelValue.LOW, List.of(), "risk-v1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("decision");
        assertThatThrownBy(() -> new RiskAssessmentCompletedData(
                        PAYMENT_ID,
                        RiskDecisionValue.APPROVED,
                        0,
                        null,
                        List.of(),
                        "risk-v1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("level");
        assertThatThrownBy(() -> new RiskAssessmentCompletedData(
                        PAYMENT_ID,
                        RiskDecisionValue.APPROVED,
                        0,
                        RiskLevelValue.LOW,
                        List.of(),
                        " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyVersion");
    }

    private static RiskAssessmentCompletedData data(int score, List<String> rules) {
        return new RiskAssessmentCompletedData(
                PAYMENT_ID,
                RiskDecisionValue.APPROVED,
                score,
                RiskLevelValue.LOW,
                rules,
                "risk-v1");
    }
}
