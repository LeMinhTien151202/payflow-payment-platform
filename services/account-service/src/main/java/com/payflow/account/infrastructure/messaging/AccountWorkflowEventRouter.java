package com.payflow.account.infrastructure.messaging;

import com.payflow.account.application.handler.HandleCaptureFundsRequestedHandler;
import com.payflow.account.application.handler.HandleRefundCreditRequestedHandler;
import com.payflow.account.application.handler.HandleReleaseFundsRequestedHandler;
import com.payflow.account.application.handler.HandleReserveFundsRequestedHandler;
import com.payflow.account.application.inbox.EventProcessingResult;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Routes only commands owned by the Account bounded context. */
@Component
class AccountWorkflowEventRouter {
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountReserveRequestedData>> RESERVE = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountCaptureRequestedData>> CAPTURE = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountReleaseRequestedData>> RELEASE = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountRefundCreditRequestedData>> REFUND_CREDIT = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final HandleReserveFundsRequestedHandler reserveHandler;
    private final HandleCaptureFundsRequestedHandler captureHandler;
    private final HandleReleaseFundsRequestedHandler releaseHandler;
    private final HandleRefundCreditRequestedHandler refundCreditHandler;

    AccountWorkflowEventRouter(
            ObjectMapper objectMapper,
            HandleReserveFundsRequestedHandler reserveHandler,
            HandleCaptureFundsRequestedHandler captureHandler,
            HandleReleaseFundsRequestedHandler releaseHandler,
            HandleRefundCreditRequestedHandler refundCreditHandler) {
        this.objectMapper = objectMapper;
        this.reserveHandler = reserveHandler;
        this.captureHandler = captureHandler;
        this.releaseHandler = releaseHandler;
        this.refundCreditHandler = refundCreditHandler;
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
            case "account.reserve.requested" -> reserveHandler.handle(keyed(kafkaKey, objectMapper.readValue(payload, RESERVE)));
            case "account.capture.requested" -> captureHandler.handle(keyed(kafkaKey, objectMapper.readValue(payload, CAPTURE)));
            case "account.release.requested" -> releaseHandler.handle(keyed(kafkaKey, objectMapper.readValue(payload, RELEASE)));
            case "account.refund-credit.requested" -> refundCreditHandler.handle(keyed(kafkaKey, objectMapper.readValue(payload, REFUND_CREDIT)));
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
