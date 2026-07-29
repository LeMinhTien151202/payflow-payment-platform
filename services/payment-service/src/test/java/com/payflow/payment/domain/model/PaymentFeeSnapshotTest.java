package com.payflow.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentFeeSnapshotTest {

    @Test
    @DisplayName("fee calculation freezes policy, rate, currency and HALF_UP result")
    void calculatesImmutableFeeFact() {
        FeePolicySnapshot policy =
                new FeePolicySnapshot("MERCHANT_STANDARD_V3", new BigDecimal("0.025"), RoundingMode.HALF_UP);

        PaymentFeeSnapshot snapshot = PaymentFeeSnapshot.calculate(policy, Money.of("123.4567", "VND"));

        assertThat(snapshot.policyVersion()).isEqualTo("MERCHANT_STANDARD_V3");
        assertThat(snapshot.appliedRate()).isEqualByComparingTo("0.025000");
        assertThat(snapshot.feeAmount()).isEqualTo(Money.of("3.0864", "VND"));
        assertThat(snapshot.roundingMode()).isEqualTo(RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("rate cannot be negative, above one, over-scale or use another rounding rule")
    void rejectsUnsupportedPolicy() {
        assertThatThrownBy(() -> policy("-0.000001", RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy("1.000001", RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy("0.1234567", RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy("0.02", RoundingMode.HALF_EVEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cumulative allocation absorbs rounding and full refund reverses exact original fee")
    void cumulativeReversalHasNoRoundingDrift() {
        PaymentFeeSnapshot fee =
                PaymentFeeSnapshot.calculate(policy("0.033333", RoundingMode.HALF_UP), Money.of("3", "VND"));

        Money first = fee.reversalDelta(Money.of("3", "VND"), Money.of("1", "VND"), Money.zero("VND"));
        Money second = fee.reversalDelta(Money.of("3", "VND"), Money.of("2", "VND"), first);
        Money reversedBeforeFinal = first.plus(second);
        Money finalDelta =
                fee.reversalDelta(Money.of("3", "VND"), Money.of("3", "VND"), reversedBeforeFinal);

        assertThat(first).isEqualTo(Money.of("0.0333", "VND"));
        assertThat(second).isEqualTo(Money.of("0.0334", "VND"));
        assertThat(reversedBeforeFinal.plus(finalDelta)).isEqualTo(fee.feeAmount());
    }

    private static FeePolicySnapshot policy(String rate, RoundingMode roundingMode) {
        return new FeePolicySnapshot("POLICY_V1", new BigDecimal(rate), roundingMode);
    }
}
