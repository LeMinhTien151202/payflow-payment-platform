package com.payflow.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentSucceededV2Data;
import java.math.BigDecimal;
import com.payflow.notification.application.inbox.EventProcessingResult;
import com.payflow.notification.application.notification.CreateOutcomeNotificationHandler;
import com.payflow.notification.application.notification.OutcomeNotificationFactory;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationOutcomeEventRouterTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CreateOutcomeNotificationHandler handler =
            mock(CreateOutcomeNotificationHandler.class);
    private final NotificationOutcomeEventRouter router = new NotificationOutcomeEventRouter(
            mapper, new OutcomeNotificationFactory(), handler);

    @Test
    void routesOwnedOutcomeAndChecksKafkaKey() {
        var event = paymentFailed();
        when(handler.handle(any())).thenReturn(EventProcessingResult.PROCESSED);

        assertThat(router.route(event.aggregateId(), mapper.writeValueAsString(event)))
                .isEqualTo(NotificationOutcomeEventRouter.RouteResult.PROCESSED);
        verify(handler).handle(any());
    }

    @Test
    void routesPaymentSucceededV2WithoutReinterpretingFeeSnapshot() {
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-08T02:00:00Z");
        var event = EventEnvelope.of(UUID.randomUUID(), PaymentEvents.PAYMENT_SUCCEEDED_V2,
                paymentId.toString(), "notification-v2", "payment-service", now,
                new PaymentSucceededV2Data(paymentId, UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("100.0000"), "VND", "fee-v1",
                        new BigDecimal("0.020000"), new BigDecimal("2.0000"),
                        "VND", "HALF_EVEN", now));
        when(handler.handle(any())).thenReturn(EventProcessingResult.PROCESSED);

        assertThat(router.route(paymentId.toString(), mapper.writeValueAsString(event)))
                .isEqualTo(NotificationOutcomeEventRouter.RouteResult.PROCESSED);
        verify(handler).handle(any());
    }

    @Test
    void ignoresUnownedEventWithoutCallingHandler() {
        String payload = mapper.writeValueAsString(Map.of("eventType", "payment.created"));
        assertThat(router.route(UUID.randomUUID().toString(), payload))
                .isEqualTo(NotificationOutcomeEventRouter.RouteResult.IGNORED);
        verifyNoInteractions(handler);
    }

    @Test
    void rejectsWrongKafkaKey() {
        var event = paymentFailed();
        assertThatThrownBy(() -> router.route(
                        UUID.randomUUID().toString(), mapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kafka key");
        verifyNoInteractions(handler);
    }

    private static EventEnvelope<PaymentFailedData> paymentFailed() {
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-30T02:00:00Z");
        return EventEnvelope.of(UUID.randomUUID(), PaymentEvents.PAYMENT_FAILED,
                paymentId.toString(), "notification-router", "payment-service", now,
                new PaymentFailedData(paymentId, "FAILED", now));
    }
}
