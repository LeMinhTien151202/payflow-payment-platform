package com.payflow.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the serialised envelope matches spec 8.2 field for field.
 *
 * <p>This is the only place the wire format is actually verified rather than described. A rename or a
 * reordering of record components changes what consumers receive, and nothing else in the build would
 * notice.
 *
 * <p>The mapper is configured the way a service must configure it, so the test fails if that
 * configuration turns out to be insufficient: ISO-8601 timestamps rather than epoch numbers, and
 * plain-notation {@code BigDecimal} so a money value never reaches a consumer as {@code 5E+5}.
 */
class EventEnvelopeJsonTest {

    private final JsonMapper mapper =
            JsonMapper.builder()
                    .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build();

    private static final UUID EVENT_ID = UUID.fromString("31734b31-8e75-4570-bdc6-979fa02ab446");
    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");

    private static EventEnvelope<PaymentCreatedData> envelope() {
        PaymentCreatedData data =
                new PaymentCreatedData(
                        PAYMENT_ID,
                        UUID.fromString("2f1c7a30-0b5e-4a4e-9a94-6a1a1cbb1111"),
                        UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95"),
                        UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3"),
                        new BigDecimal("500000"),
                        "VND",
                        Instant.parse("2026-07-24T03:00:00Z"));

        return EventEnvelope.of(
                EVENT_ID,
                PaymentEvents.PAYMENT_CREATED,
                PAYMENT_ID.toString(),
                "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
                "payment-service",
                Instant.parse("2026-07-24T03:00:00Z"),
                data);
    }

    @Test
    @DisplayName("the envelope serialises to exactly the ten members named in spec 8.2")
    void envelopeHasExactlyTheSpecifiedMembers() {
        String json = mapper.writeValueAsString(envelope());

        assertThat(mapper.readTree(json).propertyNames())
                .containsExactlyInAnyOrder(
                        "eventId",
                        "eventType",
                        "eventVersion",
                        "aggregateType",
                        "aggregateId",
                        "correlationId",
                        "causationId",
                        "producer",
                        "occurredAt",
                        "data");
    }

    @Test
    @DisplayName("occurredAt is an ISO-8601 string, not an epoch number")
    void timestampIsIso8601() {
        String json = mapper.writeValueAsString(envelope());

        assertThat(mapper.readTree(json).get("occurredAt").stringValue())
                .isEqualTo("2026-07-24T03:00:00Z");
    }

    @Test
    @DisplayName("amount is plain notation with scale 4, never scientific notation")
    void amountIsPlainWithFixedScale() {
        String json = mapper.writeValueAsString(envelope());

        // The raw text matters, not the parsed value: a consumer in another language reads the
        // characters. "5E+5" is a valid JSON number and a broken money value.
        assertThat(json).contains("\"amount\":500000.0000");
    }

    @Test
    @DisplayName("the payload data members match spec 8.4 payment.created")
    void payloadMatchesSpec() {
        String json = mapper.writeValueAsString(envelope());

        assertThat(mapper.readTree(json).get("data").propertyNames())
                .containsExactlyInAnyOrder(
                        "paymentId",
                        "merchantId",
                        "customerId",
                        "sourceAccountId",
                        "amount",
                        "currency",
                        "createdAt");
    }

    @Test
    @DisplayName("a round trip preserves the amount exactly, including its scale")
    void roundTripPreservesAmount() {
        String json = mapper.writeValueAsString(envelope());

        EventEnvelope<PaymentCreatedData> back =
                mapper.readValue(json, new TypeReference<EventEnvelope<PaymentCreatedData>>() {});

        assertThat(back).isEqualTo(envelope());
        assertThat(back.data().amount()).isEqualByComparingTo("500000");
        assertThat(back.data().amount().scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("a null causationId is still present as a member, so the shape never varies")
    void nullCausationIdIsStillSerialised() {
        String json = mapper.writeValueAsString(envelope());

        assertThat(mapper.readTree(json).get("causationId").isNull()).isTrue();
    }
}
