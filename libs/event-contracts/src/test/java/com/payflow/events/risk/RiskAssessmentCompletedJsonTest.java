package com.payflow.events.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.events.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class RiskAssessmentCompletedJsonTest {

    private static final UUID EVENT_ID =
            UUID.fromString("31734b31-8e75-4570-bdc6-979fa02ab446");
    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");

    private final JsonMapper mapper =
            JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @Test
    void serializesTheExactV1PayloadMembersAndEnumValues() {
        String json = mapper.writeValueAsString(envelope());
        var data = mapper.readTree(json).get("data");

        assertThat(data.propertyNames())
                .containsExactlyInAnyOrder(
                        "paymentId",
                        "decision",
                        "score",
                        "level",
                        "matchedRules",
                        "policyVersion");
        assertThat(data.get("decision").stringValue()).isEqualTo("REVIEW_REQUIRED");
        assertThat(data.get("level").stringValue()).isEqualTo("HIGH");
        assertThat(data.get("matchedRules").get(0).stringValue()).isEqualTo("VELOCITY_1M");
    }

    @Test
    void roundTripPreservesEnvelopeAndPayload() {
        EventEnvelope<RiskAssessmentCompletedData> expected = envelope();
        String json = mapper.writeValueAsString(expected);

        EventEnvelope<RiskAssessmentCompletedData> actual = mapper.readValue(
                json, new TypeReference<EventEnvelope<RiskAssessmentCompletedData>>() {});

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.eventType()).isEqualTo("risk.assessment.completed");
        assertThat(actual.eventVersion()).isEqualTo(1);
        assertThat(actual.aggregateId()).isEqualTo(PAYMENT_ID.toString());
    }

    private static EventEnvelope<RiskAssessmentCompletedData> envelope() {
        return new EventEnvelope<>(
                EVENT_ID,
                RiskEvents.RISK_ASSESSMENT_COMPLETED.name(),
                RiskEvents.RISK_ASSESSMENT_COMPLETED.version(),
                RiskEvents.AGGREGATE_TYPE,
                PAYMENT_ID.toString(),
                "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
                "51734b31-8e75-4570-bdc6-979fa02ab447",
                "risk-service",
                Instant.parse("2026-07-28T08:00:00Z"),
                new RiskAssessmentCompletedData(
                        PAYMENT_ID,
                        RiskDecisionValue.REVIEW_REQUIRED,
                        40,
                        RiskLevelValue.HIGH,
                        List.of("VELOCITY_1M"),
                        "risk-v1"));
    }
}
