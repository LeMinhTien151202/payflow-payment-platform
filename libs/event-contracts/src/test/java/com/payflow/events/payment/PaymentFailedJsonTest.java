package com.payflow.events.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.events.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class PaymentFailedJsonTest {

    private static final UUID EVENT_ID =
            UUID.fromString("61734b31-8e75-4570-bdc6-979fa02ab448");
    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final Instant FAILED_AT = Instant.parse("2026-07-28T08:00:02Z");

    private final JsonMapper mapper =
            JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @Test
    void serializesTheExactV1PayloadAndRoundTrips() {
        EventEnvelope<PaymentFailedData> expected = EventEnvelope.of(
                EVENT_ID,
                PaymentEvents.PAYMENT_FAILED,
                PAYMENT_ID.toString(),
                "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
                "payment-service",
                FAILED_AT,
                new PaymentFailedData(PAYMENT_ID, "RISK_REJECTED", FAILED_AT));

        String json = mapper.writeValueAsString(expected);
        var data = mapper.readTree(json).get("data");

        assertThat(data.propertyNames())
                .containsExactlyInAnyOrder("paymentId", "failureCode", "failedAt");
        assertThat(data.get("failureCode").stringValue()).isEqualTo("RISK_REJECTED");
        assertThat(data.get("failedAt").stringValue()).isEqualTo("2026-07-28T08:00:02Z");

        EventEnvelope<PaymentFailedData> actual = mapper.readValue(
                json, new TypeReference<EventEnvelope<PaymentFailedData>>() {});
        assertThat(actual).isEqualTo(expected);
    }
}
