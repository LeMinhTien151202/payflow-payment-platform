package com.payflow.risk.application.port;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record RiskSignalSnapshot(
        int paymentCountLastMinute,
        BigDecimal totalAmountLastHour,
        boolean newDevice,
        int failedPaymentsLastTenMinutes,
        boolean merchantSuspicious,
        boolean ipCountryChanged) {

    public RiskSignalSnapshot {
        Objects.requireNonNull(totalAmountLastHour, "totalAmountLastHour");
        if (paymentCountLastMinute < 0 || failedPaymentsLastTenMinutes < 0) {
            throw new IllegalArgumentException("risk signal counters must not be negative");
        }
        if (totalAmountLastHour.scale() > 4 || totalAmountLastHour.signum() < 0) {
            throw new IllegalArgumentException("hourly amount must be non-negative at scale 4");
        }
        totalAmountLastHour = totalAmountLastHour.setScale(4, RoundingMode.UNNECESSARY);
    }
}
