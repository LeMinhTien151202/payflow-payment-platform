package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.accountledger.account.application.handler.HandleRefundCreditRequestedHandler;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.ledger.application.handler.HandleRefundRequestedHandler;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.refund.RefundRequestedData;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Deserializes only refund commands owned by the Account and Ledger boundaries. */
@Component
class RefundWorkflowEventRouter {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<RefundRequestedData>> REFUND_REQUESTED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountRefundCreditRequestedData>>
            REFUND_CREDIT_REQUESTED = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final HandleRefundRequestedHandler ledgerHandler;
    private final HandleRefundCreditRequestedHandler accountHandler;

    RefundWorkflowEventRouter(
            ObjectMapper objectMapper,
            HandleRefundRequestedHandler ledgerHandler,
            HandleRefundCreditRequestedHandler accountHandler) {
        this.objectMapper = objectMapper;
        this.ledgerHandler = ledgerHandler;
        this.accountHandler = accountHandler;
    }

    RouteResult route(String kafkaKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Kafka event payload must not be blank");
        }
        Object rawType = objectMapper.readValue(payload, JSON_OBJECT).get("eventType");
        if (!(rawType instanceof String eventType) || eventType.isBlank()) {
            throw new IllegalArgumentException("Kafka eventType must not be blank");
        }
        EventProcessingResult result = switch (eventType) {
            case "refund.requested" -> ledgerHandler.handle(
                    keyed(kafkaKey, objectMapper.readValue(payload, REFUND_REQUESTED)));
            case "account.refund-credit.requested" -> accountHandler.handle(
                    keyed(kafkaKey, objectMapper.readValue(payload, REFUND_CREDIT_REQUESTED)));
            default -> null;
        };
        return result == null ? RouteResult.IGNORED : RouteResult.valueOf(result.name());
    }

    private static <T> EventEnvelope<T> keyed(String kafkaKey, EventEnvelope<T> event) {
        Objects.requireNonNull(event, "event");
        if (kafkaKey == null || !kafkaKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException(
                    "Kafka key must equal envelope aggregateId for refund ordering");
        }
        return event;
    }

    enum RouteResult {
        PROCESSED,
        DUPLICATE,
        BUSINESS_DUPLICATE,
        IGNORED
    }
}
