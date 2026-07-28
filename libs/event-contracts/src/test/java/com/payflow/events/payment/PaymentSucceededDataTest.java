package com.payflow.events.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class PaymentSucceededDataTest {

    private static final UUID ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-28T09:00:04Z");

    @Test
    void definesAndNormalizesTheV1Contract() {
        var data = succeeded("500000", "VND");

        assertThat(PaymentEvents.PAYMENT_SUCCEEDED.name()).isEqualTo("payment.succeeded");
        assertThat(PaymentEvents.PAYMENT_SUCCEEDED.version()).isEqualTo(1);
        assertThat(data.amount()).isEqualTo(new BigDecimal("500000.0000"));
    }

    @Test
    void rejectsInvalidMoney() {
        assertThatThrownBy(() -> succeeded("-1", "VND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> succeeded("1", "vn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-4217");
    }

    @Test
    void serializesExactPayloadAndRoundTrips() {
        var expected = EventEnvelope.of(
                UUID.fromString("61734b31-8e75-4570-bdc6-979fa02ab448"),
                PaymentEvents.PAYMENT_SUCCEEDED,
                ID.toString(),
                "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
                "payment-service",
                COMPLETED_AT,
                succeeded("500000", "VND"));
        JsonMapper mapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        String json = mapper.writeValueAsString(expected);
        assertThat(mapper.readTree(json).get("data").propertyNames())
                .containsExactlyInAnyOrder(
                        "paymentId", "merchantId", "customerId", "amount", "currency", "completedAt");
        EventEnvelope<PaymentSucceededData> actual = mapper.readValue(
                json, new TypeReference<EventEnvelope<PaymentSucceededData>>() {});
        assertThat(actual).isEqualTo(expected);
    }

    private static PaymentSucceededData succeeded(String amount, String currency) {
        return new PaymentSucceededData(
                ID, ID, ID, new BigDecimal(amount), currency, COMPLETED_AT);
    }
}
