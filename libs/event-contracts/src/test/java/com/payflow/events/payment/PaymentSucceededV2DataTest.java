package com.payflow.events.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PaymentSucceededV2DataTest {

    @Test
    void publishesCompleteImmutableFeeEconomics() {
        var data = data("500000", "10000");
        var envelope = EventEnvelope.of(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                PaymentEvents.PAYMENT_SUCCEEDED_V2,
                data.paymentId().toString(),
                "correlation-phase3",
                "payment-service",
                data.completedAt(),
                data);

        var json = new ObjectMapper().writeValueAsString(envelope);

        assertThat(envelope.eventVersion()).isEqualTo(2);
        assertThat(json).contains("\"feePolicyVersion\":\"fee-v3\"");
        assertThat(json).contains("\"feeAmount\":10000.0000");
    }

    @Test
    void rejectsFeeThatExceedsGross() {
        assertThatThrownBy(() -> data("100", "101"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feeAmount");
    }

    private static PaymentSucceededV2Data data(String amount, String fee) {
        return new PaymentSucceededV2Data(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                new BigDecimal(amount),
                "VND",
                "fee-v3",
                new BigDecimal("0.020000"),
                new BigDecimal(fee),
                "VND",
                "HALF_UP",
                Instant.parse("2026-08-08T01:00:00Z"));
    }
}
