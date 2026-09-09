package com.payflow.events.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementCompletedDataTest {

    @Test
    void acceptsExactGrossRefundFeeNetFormula() {
        var data = data("500000", "200000", "6000", "294000");

        assertThat(data.netAmount()).isEqualByComparingTo("294000.0000");
        assertThat(SettlementEvents.SETTLEMENT_COMPLETED.name()).isEqualTo("settlement.completed");
    }

    @Test
    void supportsNegativeDailyNetWithoutClamping() {
        assertThat(data("0", "200000", "-4000", "-196000").netAmount())
                .isEqualByComparingTo("-196000");
    }

    @Test
    void rejectsInconsistentNet() {
        assertThatThrownBy(() -> data("500000", "200000", "6000", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("netAmount");
    }

    private static SettlementCompletedData data(String gross, String refund, String fee, String net) {
        return new SettlementCompletedData(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                LocalDate.parse("2026-08-08"),
                "VND",
                new BigDecimal(gross),
                new BigDecimal(refund),
                new BigDecimal(fee),
                new BigDecimal(net),
                1,
                1,
                Instant.parse("2026-08-08T03:00:00Z"));
    }
}
