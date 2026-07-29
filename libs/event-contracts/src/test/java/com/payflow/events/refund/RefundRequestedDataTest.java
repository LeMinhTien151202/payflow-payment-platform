package com.payflow.events.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class RefundRequestedDataTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID REFUND_ID =
            UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");
    private static final Instant AT = Instant.parse("2026-07-29T09:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    @Test
    void serializesExactV1ShapeAndUsesPaymentOrderingAggregate() {
        RefundRequestedData data = data("200000");
        EventEnvelope<RefundRequestedData> envelope = EventEnvelope.of(
                UUID.randomUUID(),
                RefundEvents.REFUND_REQUESTED,
                PAYMENT_ID.toString(),
                "refund-contract-test",
                "payment-service",
                AT,
                data);

        String json = mapper.writeValueAsString(envelope);
        var tree = mapper.readTree(json);

        assertThat(tree.get("eventType").stringValue()).isEqualTo("refund.requested");
        assertThat(tree.get("eventVersion").intValue()).isEqualTo(1);
        assertThat(tree.get("aggregateType").stringValue()).isEqualTo("PAYMENT");
        assertThat(tree.get("aggregateId").stringValue()).isEqualTo(PAYMENT_ID.toString());
        assertThat(tree.get("data").propertyNames())
                .containsExactlyInAnyOrder(
                        "refundId",
                        "paymentId",
                        "merchantId",
                        "customerId",
                        "accountId",
                        "amount",
                        "currency",
                        "requestedAt");
        assertThat(json).contains("\"amount\":200000.0000").doesNotContain("reason");

        EventEnvelope<RefundRequestedData> roundTrip = mapper.readValue(
                json, new TypeReference<EventEnvelope<RefundRequestedData>>() {});
        assertThat(roundTrip).isEqualTo(envelope);
    }

    @Test
    void rejectsNonPositiveOrOverScaleMoney() {
        assertThatThrownBy(() -> data("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> data("1.00001")).isInstanceOf(IllegalArgumentException.class);
    }

    private static RefundRequestedData data(String amount) {
        return new RefundRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95"),
                UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3"),
                new BigDecimal(amount),
                "VND",
                AT);
    }
}
