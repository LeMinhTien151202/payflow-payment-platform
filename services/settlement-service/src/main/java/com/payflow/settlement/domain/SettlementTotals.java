package com.payflow.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/** Exact daily totals; fee and net may be negative for refund-heavy business dates. */
public record SettlementTotals(
        BigDecimal grossAmount,
        BigDecimal refundAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        int transactionCount,
        int refundCount) {

    public SettlementTotals {
        grossAmount = money(grossAmount, "grossAmount");
        refundAmount = money(refundAmount, "refundAmount");
        feeAmount = money(feeAmount, "feeAmount");
        netAmount = money(netAmount, "netAmount");
        if (grossAmount.signum() < 0 || refundAmount.signum() < 0) {
            throw new IllegalArgumentException("gross and refund totals must be non-negative");
        }
        if (transactionCount < 0 || refundCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        if (grossAmount.subtract(refundAmount).subtract(feeAmount).compareTo(netAmount) != 0) {
            throw new IllegalArgumentException("net total must equal gross - refund - fee");
        }
    }

    public static SettlementTotals calculate(Collection<SettlementContribution> items) {
        Objects.requireNonNull(items, "items");
        BigDecimal gross = zero();
        BigDecimal refund = zero();
        BigDecimal fee = zero();
        int payments = 0;
        int refunds = 0;
        for (SettlementContribution item : items) {
            gross = gross.add(item.grossAmount());
            refund = refund.add(item.refundAmount());
            fee = fee.add(item.feeAmount());
            if (item.referenceType() == SettlementReferenceType.PAYMENT) {
                payments++;
            } else {
                refunds++;
            }
        }
        return new SettlementTotals(
                gross,
                refund,
                fee,
                gross.subtract(refund).subtract(fee),
                payments,
                refunds);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(4);
    }

    private static BigDecimal money(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.scale() > 4) {
            throw new IllegalArgumentException(field + " scale must be <= 4");
        }
        return value.setScale(4, RoundingMode.UNNECESSARY);
    }
}
