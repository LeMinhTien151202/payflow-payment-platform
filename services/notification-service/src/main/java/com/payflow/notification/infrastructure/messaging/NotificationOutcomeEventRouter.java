package com.payflow.notification.infrastructure.messaging;

import com.payflow.events.EventEnvelope;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.events.payment.PaymentSucceededV2Data;
import com.payflow.events.refund.RefundFailedData;
import com.payflow.events.refund.RefundSucceededData;
import com.payflow.notification.application.notification.CreateOutcomeNotificationHandler;
import com.payflow.notification.application.notification.OutcomeNotificationFactory;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class NotificationOutcomeEventRouter {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<PaymentSucceededData>> PAYMENT_SUCCEEDED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<PaymentSucceededV2Data>> PAYMENT_SUCCEEDED_V2 =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<PaymentFailedData>> PAYMENT_FAILED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<RefundSucceededData>> REFUND_SUCCEEDED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<RefundFailedData>> REFUND_FAILED =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final OutcomeNotificationFactory factory;
    private final CreateOutcomeNotificationHandler handler;

    NotificationOutcomeEventRouter(
            ObjectMapper objectMapper,
            OutcomeNotificationFactory factory,
            CreateOutcomeNotificationHandler handler) {
        this.objectMapper = objectMapper;
        this.factory = factory;
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
        return switch (eventType) {
            case "payment.succeeded" -> routePaymentSucceeded(kafkaKey, payload);
            case "payment.failed" -> handle(kafkaKey,
                    objectMapper.readValue(payload, PAYMENT_FAILED), EventKind.PAYMENT_FAILED);
            case "refund.succeeded" -> handle(kafkaKey,
                    objectMapper.readValue(payload, REFUND_SUCCEEDED), EventKind.REFUND_SUCCEEDED);
            case "refund.failed" -> handle(kafkaKey,
                    objectMapper.readValue(payload, REFUND_FAILED), EventKind.REFUND_FAILED);
            default -> RouteResult.IGNORED;
        };
    }

    private RouteResult routePaymentSucceeded(String kafkaKey, String payload) {
        Object rawVersion = objectMapper.readValue(payload, JSON_OBJECT).get("eventVersion");
        if (!(rawVersion instanceof Number version)) {
            throw new IllegalArgumentException("Kafka eventVersion must be numeric");
        }
        return switch (version.intValue()) {
            case 1 -> handle(kafkaKey,
                    objectMapper.readValue(payload, PAYMENT_SUCCEEDED), EventKind.PAYMENT_SUCCEEDED_V1);
            case 2 -> handle(kafkaKey,
                    objectMapper.readValue(payload, PAYMENT_SUCCEEDED_V2), EventKind.PAYMENT_SUCCEEDED_V2);
            default -> throw new IllegalArgumentException(
                    "Unsupported payment.succeeded version: " + version.intValue());
        };
    }

    private RouteResult handle(String kafkaKey, EventEnvelope<?> event, EventKind kind) {
        Objects.requireNonNull(event, "event");
        if (kafkaKey == null || !kafkaKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException("Kafka key must equal envelope aggregateId for ordering");
        }
        var intent = switch (kind) {
            case PAYMENT_SUCCEEDED_V1 -> factory.paymentSucceededV1(cast(event));
            case PAYMENT_SUCCEEDED_V2 -> factory.paymentSucceededV2(cast(event));
            case PAYMENT_FAILED -> factory.paymentFailed(cast(event));
            case REFUND_SUCCEEDED -> factory.refundSucceeded(cast(event));
            case REFUND_FAILED -> factory.refundFailed(cast(event));
        };
        return RouteResult.valueOf(handler.handle(intent).name());
    }

    @SuppressWarnings("unchecked")
    private static <T> EventEnvelope<T> cast(EventEnvelope<?> event) {
        return (EventEnvelope<T>) event;
    }

    private enum EventKind {
        PAYMENT_SUCCEEDED_V1,
        PAYMENT_SUCCEEDED_V2,
        PAYMENT_FAILED,
        REFUND_SUCCEEDED,
        REFUND_FAILED
    }

    enum RouteResult {
        PROCESSED,
        DUPLICATE,
        BUSINESS_DUPLICATE,
        IGNORED
    }
}
