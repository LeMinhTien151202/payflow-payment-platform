package com.payflow.risk.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.risk.domain.model.RiskAssessment;
import com.payflow.risk.domain.model.RiskDecision;
import com.payflow.risk.domain.model.RiskEvaluationContext;
import com.payflow.risk.domain.model.RiskLevel;
import com.payflow.risk.domain.model.RiskRuleCode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskRuleEngineTest {

    private final RiskRuleEngine engine = new RiskRuleEngine();

    @Test
    void safePaymentIsApprovedWithZeroScore() {
        RiskAssessment assessment = engine.evaluate(context().build());

        assertThat(assessment.score()).isZero();
        assertThat(assessment.policyVersion()).isEqualTo("risk-v1");
        assertThat(assessment.level()).isEqualTo(RiskLevel.LOW);
        assertThat(assessment.decision()).isEqualTo(RiskDecision.APPROVED);
        assertThat(assessment.matchedRules()).isEmpty();
    }

    @Test
    void amountHighMatchesAtTheInclusiveBoundary() {
        assertThat(engine.evaluate(context().amount("9999999.9999").build()).matchedRules())
                .doesNotContain(RiskRuleCode.AMOUNT_HIGH);

        RiskAssessment atBoundary = engine.evaluate(context().amount("10000000").build());
        assertThat(atBoundary.score()).isEqualTo(30);
        assertThat(atBoundary.matchedRules()).containsExactly(RiskRuleCode.AMOUNT_HIGH);
    }

    @Test
    void velocityOneMinuteMatchesOnlyAfterFivePayments() {
        assertThat(engine.evaluate(context().paymentCountLastMinute(5).build()).matchedRules())
                .doesNotContain(RiskRuleCode.VELOCITY_1M);

        RiskAssessment sixth = engine.evaluate(context().paymentCountLastMinute(6).build());
        assertThat(sixth.score()).isEqualTo(40);
        assertThat(sixth.decision()).isEqualTo(RiskDecision.REVIEW_REQUIRED);
    }

    @Test
    void hourlyAmountMatchesOnlyWhenStrictlyGreaterThanThirtyMillion() {
        assertThat(engine.evaluate(context().totalAmountLastHour("30000000").build()).matchedRules())
                .doesNotContain(RiskRuleCode.VELOCITY_1H);
        assertThat(
                        engine.evaluate(context().totalAmountLastHour("30000000.0001").build())
                                .matchedRules())
                .containsExactly(RiskRuleCode.VELOCITY_1H);
    }

    @Test
    void failedBurstMatchesAtThreePriorFailures() {
        assertThat(engine.evaluate(context().failedPaymentsLastTenMinutes(2).build()).matchedRules())
                .doesNotContain(RiskRuleCode.FAILED_BURST);
        assertThat(engine.evaluate(context().failedPaymentsLastTenMinutes(3).build()).matchedRules())
                .containsExactly(RiskRuleCode.FAILED_BURST);
    }

    @Test
    void booleanSignalsKeepTheirPublishedWeights() {
        RiskAssessment assessment =
                engine.evaluate(
                        context()
                                .newDevice(true)
                                .merchantSuspicious(true)
                                .ipCountryChanged(true)
                                .build());

        assertThat(assessment.score()).isEqualTo(80);
        assertThat(assessment.matchedRules())
                .containsExactly(
                        RiskRuleCode.NEW_DEVICE,
                        RiskRuleCode.MERCHANT_SUSPICIOUS,
                        RiskRuleCode.IP_CHANGE);
        assertThat(assessment.decision()).isEqualTo(RiskDecision.REJECTED);
    }

    @Test
    void allRulesSaturateAtOneHundredAndKeepDeterministicOrder() {
        RiskEvaluationContext input =
                context()
                        .amount("10000000")
                        .paymentCountLastMinute(6)
                        .totalAmountLastHour("30000000.0001")
                        .newDevice(true)
                        .failedPaymentsLastTenMinutes(3)
                        .merchantSuspicious(true)
                        .ipCountryChanged(true)
                        .build();

        RiskAssessment assessment = engine.evaluate(input);

        assertThat(assessment.score()).isEqualTo(100);
        assertThat(assessment.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(assessment.decision()).isEqualTo(RiskDecision.REJECTED);
        assertThat(assessment.matchedRules()).containsExactly(RiskRuleCode.values());
        assertThat(engine.evaluate(input)).isEqualTo(assessment);
    }

    private static ContextBuilder context() {
        return new ContextBuilder();
    }

    private static final class ContextBuilder {
        private final UUID paymentId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        private final UUID customerId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        private final UUID merchantId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        private BigDecimal amount = new BigDecimal("500000");
        private int paymentCountLastMinute = 1;
        private BigDecimal totalAmountLastHour = new BigDecimal("500000");
        private boolean newDevice;
        private int failedPaymentsLastTenMinutes;
        private boolean merchantSuspicious;
        private boolean ipCountryChanged;

        ContextBuilder amount(String value) {
            amount = new BigDecimal(value);
            return this;
        }

        ContextBuilder paymentCountLastMinute(int value) {
            paymentCountLastMinute = value;
            return this;
        }

        ContextBuilder totalAmountLastHour(String value) {
            totalAmountLastHour = new BigDecimal(value);
            return this;
        }

        ContextBuilder newDevice(boolean value) {
            newDevice = value;
            return this;
        }

        ContextBuilder failedPaymentsLastTenMinutes(int value) {
            failedPaymentsLastTenMinutes = value;
            return this;
        }

        ContextBuilder merchantSuspicious(boolean value) {
            merchantSuspicious = value;
            return this;
        }

        ContextBuilder ipCountryChanged(boolean value) {
            ipCountryChanged = value;
            return this;
        }

        RiskEvaluationContext build() {
            return new RiskEvaluationContext(
                    paymentId,
                    customerId,
                    merchantId,
                    amount,
                    "VND",
                    paymentCountLastMinute,
                    totalAmountLastHour,
                    newDevice,
                    failedPaymentsLastTenMinutes,
                    merchantSuspicious,
                    ipCountryChanged);
        }
    }
}
