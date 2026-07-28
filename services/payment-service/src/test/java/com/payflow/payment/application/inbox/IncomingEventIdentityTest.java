package com.payflow.payment.application.inbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncomingEventIdentityTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void acceptsStableConsumerIdentityWithoutPersistingPayload() {
        new IncomingEventIdentity(
                UUID.randomUUID(),
                "payment-risk-assessment-v1",
                "risk.assessment.completed",
                UUID.randomUUID().toString(),
                NOW);
    }

    @Test
    void rejectsInstanceSpecificOrUnsafeConsumerName() {
        assertThatThrownBy(() -> new IncomingEventIdentity(
                        UUID.randomUUID(),
                        "Payment Consumer/pod-1",
                        "risk.assessment.completed",
                        UUID.randomUUID().toString(),
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumerName");
    }

    @Test
    void rejectsBlankOrOversizedWireIdentity() {
        assertThatThrownBy(() -> new IncomingEventIdentity(
                        UUID.randomUUID(),
                        "payment-risk-assessment-v1",
                        " ",
                        UUID.randomUUID().toString(),
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");

        assertThatThrownBy(() -> new IncomingEventIdentity(
                        UUID.randomUUID(),
                        "payment-risk-assessment-v1",
                        "risk.assessment.completed",
                        "a".repeat(101),
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId");
    }
}

