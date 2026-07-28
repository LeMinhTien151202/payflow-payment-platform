package com.payflow.events.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RiskEventsTest {

    @Test
    void assessmentCompletedIdentityIsLockedByAdr016() {
        assertThat(RiskEvents.RISK_ASSESSMENT_COMPLETED.name())
                .isEqualTo("risk.assessment.completed");
        assertThat(RiskEvents.RISK_ASSESSMENT_COMPLETED.version()).isEqualTo(1);
        assertThat(RiskEvents.RISK_ASSESSMENT_COMPLETED.aggregateType()).isEqualTo("PAYMENT");
    }
}
