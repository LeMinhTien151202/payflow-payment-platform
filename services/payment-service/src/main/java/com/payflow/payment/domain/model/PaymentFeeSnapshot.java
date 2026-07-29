package com.payflow.payment.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Fee facts frozen on a payment and never recalculated from current merchant configuration. */
public record PaymentFeeSnapshot(
        String policyVersion,
        BigDecimal appliedRate,
        Money feeAmount,
        RoundingMode roundingMode) {

    public PaymentFeeSnapshot {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(appliedRate, "appliedRate");
        Objects.requireNonNull(feeAmount, "feeAmount");
        Objects.requireNonNull(roundingMode, "roundingMode");

        // Reuse the policy validation so persistence rehydration cannot admit a different contract.
        FeePolicySnapshot validated =
                new FeePolicySnapshot(policyVersion, appliedRate, roundingMode);
        appliedRate = validated.rate();
        if (roundingMode != FeePolicySnapshot.REQUIRED_ROUNDING) {
            throw new IllegalArgumentException("unsupported fee rounding mode: " + roundingMode);
        }
    }

    public static PaymentFeeSnapshot calculate(FeePolicySnapshot policy, Money grossAmount) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(grossAmount, "grossAmount");

        BigDecimal calculated =
                grossAmount
                        .amount()
                        .multiply(policy.rate())
                        .setScale(Money.SCALE, policy.roundingMode());
        return new PaymentFeeSnapshot(
                policy.policyVersion(),
                policy.rate(),
                new Money(calculated, grossAmount.currency()),
                policy.roundingMode());
    }

    /**
     * Returns only this refund's reversal delta using ADR-019 cumulative allocation.
     */
    public Money reversalDelta(
            Money originalAmount,
            Money cumulativeSucceededRefund,
            Money previousCumulativeReversal) {

        requireCurrency(originalAmount);
        requireCurrency(cumulativeSucceededRefund);
        requireCurrency(previousCumulativeReversal);
        if (cumulativeSucceededRefund.isGreaterThan(originalAmount)) {
            throw new IllegalArgumentException("cumulative refund exceeds original amount");
        }
        if (previousCumulativeReversal.isGreaterThan(feeAmount)) {
            throw new IllegalArgumentException("previous fee reversal exceeds original fee");
        }

        BigDecimal target =
                cumulativeSucceededRefund.equals(originalAmount)
                        ? feeAmount.amount()
                        : feeAmount
                                .amount()
                                .multiply(cumulativeSucceededRefund.amount())
                                .divide(
                                        originalAmount.amount(),
                                        Money.SCALE,
                                        roundingMode);

        if (target.compareTo(previousCumulativeReversal.amount()) < 0) {
            throw new IllegalArgumentException("cumulative fee reversal cannot move backwards");
        }
        return new Money(
                target.subtract(previousCumulativeReversal.amount()), feeAmount.currency());
    }

    private void requireCurrency(Money money) {
        Objects.requireNonNull(money, "money");
        if (!feeAmount.currency().equals(money.currency())) {
            throw new IllegalArgumentException(
                    "fee currency " + feeAmount.currency() + " differs from " + money.currency());
        }
    }
}
