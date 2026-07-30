package com.payflow.risk.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.risk.application.handler.HandlePaymentCreatedHandler;
import com.payflow.risk.application.inbox.EventProcessingResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RiskPaymentEventRouterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HandlePaymentCreatedHandler handler = mock(HandlePaymentCreatedHandler.class);
    private final RiskPaymentEventRouter router = new RiskPaymentEventRouter(mapper, handler);

    @Test
    void routesOnlyPaymentCreatedAndChecksKafkaKey() {
        EventEnvelope<PaymentCreatedData> event = event();
        when(handler.handle(event)).thenReturn(EventProcessingResult.PROCESSED);

        assertThat(router.route(event.aggregateId(), mapper.writeValueAsString(event)))
                .isEqualTo(RiskPaymentEventRouter.RouteResult.PROCESSED);
        verify(handler).handle(event);
    }

    @Test
    void ignoresUnownedPaymentEvent() {
        String payload = mapper.writeValueAsString(Map.of("eventType", "payment.succeeded"));
        assertThat(router.route(UUID.randomUUID().toString(), payload))
                .isEqualTo(RiskPaymentEventRouter.RouteResult.IGNORED);
        verifyNoInteractions(handler);
    }

    @Test
    void rejectsWrongKafkaKey() {
        EventEnvelope<PaymentCreatedData> event = event();
        assertThatThrownBy(() -> router.route(
                        UUID.randomUUID().toString(), mapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kafka key");
        verifyNoInteractions(handler);
    }

    private static EventEnvelope<PaymentCreatedData> event() {
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-30T02:00:00Z");
        var data = new PaymentCreatedData(paymentId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("50.0000"), "VND", now);
        return EventEnvelope.of(UUID.randomUUID(), PaymentEvents.PAYMENT_CREATED,
                paymentId.toString(), "risk-router-test", "payment-service", now, data);
    }
}
