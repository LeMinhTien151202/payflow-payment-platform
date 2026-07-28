package com.payflow.risk.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskEvaluationContextTest {

    @Test
    void normalizesMoneyToDatabaseScale() {
        RiskEvaluationContext context = valid(new BigDecimal("1"), "VND", 0, BigDecimal.ZERO, 0);

        assertThat(context.amount()).isEqualTo(new BigDecimal("1.0000"));
        assertThat(context.totalAmountLastHour()).isEqualTo(new BigDecimal("0.0000"));
    }

    @Test
    void rejectsInvalidMoneyCurrencyAndCounters() {
        assertThatThrownBy(() -> valid(BigDecimal.ZERO, "VND", 0, BigDecimal.ZERO, 0))
                .isInstanceOf(RiskInvariantViolationException.class);
        assertThatThrownBy(() -> valid(new BigDecimal("1.00001"), "VND", 0, BigDecimal.ZERO, 0))
                .isInstanceOf(RiskInvariantViolationException.class);
        assertThatThrownBy(() -> valid(BigDecimal.ONE, "USD", 0, BigDecimal.ZERO, 0))
                .isInstanceOf(RiskInvariantViolationException.class);
        assertThatThrownBy(() -> valid(BigDecimal.ONE, "VND", -1, BigDecimal.ZERO, 0))
                .isInstanceOf(RiskInvariantViolationException.class);
        assertThatThrownBy(() -> valid(BigDecimal.ONE, "VND", 0, new BigDecimal("-1"), 0))
                .isInstanceOf(RiskInvariantViolationException.class);
        assertThatThrownBy(() -> valid(BigDecimal.ONE, "VND", 0, BigDecimal.ZERO, -1))
                .isInstanceOf(RiskInvariantViolationException.class);
    }

    private static RiskEvaluationContext valid(
            BigDecimal amount,
            String currency,
            int countLastMinute,
            BigDecimal totalLastHour,
            int failedLastTenMinutes) {
        return new RiskEvaluationContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                amount,
                currency,
                countLastMinute,
                totalLastHour,
                false,
                failedLastTenMinutes,
                false,
                false);
    }
}
