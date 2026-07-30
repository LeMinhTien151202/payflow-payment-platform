package com.payflow.risk.infrastructure.messaging;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.risk.application.handler.HandlePaymentCreatedHandler;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class RiskPaymentEventRouter {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<PaymentCreatedData>> PAYMENT_CREATED =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final HandlePaymentCreatedHandler handler;

    RiskPaymentEventRouter(ObjectMapper objectMapper, HandlePaymentCreatedHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    RouteResult route(String kafkaKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Kafka event payload must not be blank");
        }
        Object rawType = objectMapper.readValue(payload, JSON_OBJECT).get("eventType");
        if (!(rawType instanceof String eventType) || eventType.isBlank()) {
            throw new IllegalArgumentException("Kafka eventType must not be blank");
        }
        if (!"payment.created".equals(eventType)) {
            return RouteResult.IGNORED;
        }
        EventEnvelope<PaymentCreatedData> event = objectMapper.readValue(payload, PAYMENT_CREATED);
        Objects.requireNonNull(event, "event");
        if (kafkaKey == null || !kafkaKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException(
                    "Kafka key must equal envelope aggregateId for payment ordering");
        }
        return RouteResult.valueOf(handler.handle(event).name());
    }

    enum RouteResult {
        PROCESSED,
        DUPLICATE,
        BUSINESS_DUPLICATE,
        IGNORED
    }
}
