package com.payflow.ledger.infrastructure.messaging;

import com.payflow.events.EventEnvelope;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.refund.RefundRequestedData;
import com.payflow.ledger.application.handler.HandlePostPaymentRequestedHandler;
import com.payflow.ledger.application.handler.HandleRefundRequestedHandler;
import com.payflow.ledger.application.inbox.EventProcessingResult;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Routes only commands owned by the Ledger bounded context. */
@Component
class LedgerWorkflowEventRouter {
    private static final TypeReference<Map<String,Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<LedgerPostPaymentRequestedData>> POST_PAYMENT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<RefundRequestedData>> REFUND = new TypeReference<>() {};
    private final ObjectMapper objectMapper;
    private final HandlePostPaymentRequestedHandler paymentHandler;
    private final HandleRefundRequestedHandler refundHandler;

    LedgerWorkflowEventRouter(ObjectMapper objectMapper,
            HandlePostPaymentRequestedHandler paymentHandler,
            HandleRefundRequestedHandler refundHandler) {
        this.objectMapper = objectMapper;
        this.paymentHandler = paymentHandler;
        this.refundHandler = refundHandler;
    }

    RouteResult route(String kafkaKey, String payload) {
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("Kafka event payload must not be blank");
        Object rawType = objectMapper.readValue(payload, JSON_OBJECT).get("eventType");
        if (!(rawType instanceof String eventType) || eventType.isBlank()) {
            throw new IllegalArgumentException("Kafka eventType must not be blank");
        }
        EventProcessingResult result = switch (eventType) {
            case "ledger.post-payment.requested" -> paymentHandler.handle(keyed(kafkaKey, objectMapper.readValue(payload, POST_PAYMENT)));
            case "refund.requested" -> refundHandler.handle(keyed(kafkaKey, objectMapper.readValue(payload, REFUND)));
            default -> null;
        };
        return result == null ? RouteResult.IGNORED : RouteResult.valueOf(result.name());
    }

    private static <T> EventEnvelope<T> keyed(String kafkaKey, EventEnvelope<T> event) {
        Objects.requireNonNull(event, "event");
        if (kafkaKey == null || !kafkaKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException("Kafka key must equal envelope aggregateId for payment ordering");
        }
        return event;
    }
    enum RouteResult { PROCESSED, DUPLICATE, BUSINESS_DUPLICATE, IGNORED }
}
