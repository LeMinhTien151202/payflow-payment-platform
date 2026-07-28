package com.payflow.risk.domain.model;

import com.payflow.risk.domain.exception.RiskInvariantViolationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Immutable inputs already collected for one deterministic risk evaluation. */
public record RiskEvaluationContext(
        UUID paymentId,
        UUID customerId,
        UUID merchantId,
        BigDecimal amount,
        String currency,
        int paymentCountLastMinute,
        BigDecimal totalAmountLastHour,
        boolean newDevice,
        int failedPaymentsLastTenMinutes,
        boolean merchantSuspicious,
        boolean ipCountryChanged) {

    public RiskEvaluationContext {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(currency, "currency");
        if (!"VND".equals(currency)) {
            throw new RiskInvariantViolationException("risk policy v1 supports VND only");
        }
        amount = monetary(amount, true, "amount");
        totalAmountLastHour = monetary(totalAmountLastHour, false, "totalAmountLastHour");
        if (paymentCountLastMinute < 0) {
            throw new RiskInvariantViolationException(
                    "paymentCountLastMinute must not be negative");
        }
        if (failedPaymentsLastTenMinutes < 0) {
            throw new RiskInvariantViolationException(
                    "failedPaymentsLastTenMinutes must not be negative");
        }
    }

    private static BigDecimal monetary(BigDecimal value, boolean positive, String field) {
        Objects.requireNonNull(value, field);
        if (value.scale() > 4) {
            throw new RiskInvariantViolationException(field + " scale must not exceed 4");
        }
        BigDecimal normalized = value.setScale(4, RoundingMode.UNNECESSARY);
        if (normalized.precision() > 19) {
            throw new RiskInvariantViolationException(field + " exceeds NUMERIC(19,4)");
        }
        if (positive ? normalized.signum() <= 0 : normalized.signum() < 0) {
            throw new RiskInvariantViolationException(
                    field + (positive ? " must be greater than zero" : " must not be negative"));
        }
        return normalized;
    }
}
