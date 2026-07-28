package com.payflow.risk.domain.policy;

import com.payflow.risk.domain.model.RiskAssessment;
import com.payflow.risk.domain.model.RiskEvaluationContext;
import com.payflow.risk.domain.model.RiskRuleCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stateless implementation of the seven deterministic rules from spec §7.7. */
public final class RiskRuleEngine {

    public static final String POLICY_VERSION = "risk-v1";

    private static final BigDecimal HIGH_AMOUNT = new BigDecimal("10000000.0000");
    private static final BigDecimal HIGH_HOURLY_TOTAL = new BigDecimal("30000000.0000");

    public RiskAssessment evaluate(RiskEvaluationContext context) {
        Objects.requireNonNull(context, "context");
        List<RiskRuleCode> matched = new ArrayList<>();

        match(
                context.amount().compareTo(HIGH_AMOUNT) >= 0,
                RiskRuleCode.AMOUNT_HIGH,
                matched);
        match(context.paymentCountLastMinute() > 5, RiskRuleCode.VELOCITY_1M, matched);
        match(
                context.totalAmountLastHour().compareTo(HIGH_HOURLY_TOTAL) > 0,
                RiskRuleCode.VELOCITY_1H,
                matched);
        match(context.newDevice(), RiskRuleCode.NEW_DEVICE, matched);
        match(
                context.failedPaymentsLastTenMinutes() >= 3,
                RiskRuleCode.FAILED_BURST,
                matched);
        match(context.merchantSuspicious(), RiskRuleCode.MERCHANT_SUSPICIOUS, matched);
        match(context.ipCountryChanged(), RiskRuleCode.IP_CHANGE, matched);

        int rawScore = matched.stream().mapToInt(RiskRuleCode::points).sum();
        int score = RiskScorePolicy.normalize(rawScore);
        RiskClassification classification = RiskScorePolicy.classify(score);

        return new RiskAssessment(
                context.paymentId(),
                context.customerId(),
                context.merchantId(),
                POLICY_VERSION,
                score,
                classification.level(),
                classification.decision(),
                matched);
    }

    private static void match(
            boolean condition, RiskRuleCode code, List<RiskRuleCode> matchedRules) {
        if (condition) {
            matchedRules.add(code);
        }
    }
}
