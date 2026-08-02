package com.payflow.payment.infrastructure.messaging;

import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountFundsCapturedData;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.ledger.LedgerRefundPostingFailedData;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.payment.application.handler.HandlePaymentWorkflowEventHandler;
import com.payflow.payment.application.handler.HandleRefundWorkflowEventHandler;
import com.payflow.payment.application.inbox.EventProcessingResult;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Deserializes only the Payment-owned event types and delegates all state changes to the use case. */
@Component
class PaymentWorkflowEventRouter {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<RiskAssessmentCompletedData>> RISK_ASSESSMENT =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountFundsReservedData>> FUNDS_RESERVED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountFundsReservationFailedData>>
            FUNDS_RESERVATION_FAILED = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountFundsCapturedData>> FUNDS_CAPTURED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountFundsReleasedData>> FUNDS_RELEASED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<LedgerPaymentPostedData>> LEDGER_POSTED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<LedgerPaymentPostingFailedData>>
            LEDGER_POSTING_FAILED = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<LedgerRefundPostedData>> LEDGER_REFUND_POSTED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<LedgerRefundPostingFailedData>>
            LEDGER_REFUND_POSTING_FAILED = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountRefundCreditedData>> REFUND_CREDITED =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final HandlePaymentWorkflowEventHandler handler;
    private final HandleRefundWorkflowEventHandler refundHandler;

    PaymentWorkflowEventRouter(
            ObjectMapper objectMapper,
            HandlePaymentWorkflowEventHandler handler,
            HandleRefundWorkflowEventHandler refundHandler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
        this.refundHandler = refundHandler;
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
            case "risk.assessment.completed" -> handler.handleRiskAssessment(
                    keyed(kafkaKey, objectMapper.readValue(payload, RISK_ASSESSMENT)));
            case "account.funds-reserved" -> handler.handleFundsReserved(
                    keyed(kafkaKey, objectMapper.readValue(payload, FUNDS_RESERVED)));
            case "account.funds-reservation-failed" -> handler.handleFundsReservationFailed(
                    keyed(kafkaKey, objectMapper.readValue(payload, FUNDS_RESERVATION_FAILED)));
            case "account.funds-captured" -> handler.handleFundsCaptured(
                    keyed(kafkaKey, objectMapper.readValue(payload, FUNDS_CAPTURED)));
            case "account.funds-released" -> handler.handleFundsReleased(
                    keyed(kafkaKey, objectMapper.readValue(payload, FUNDS_RELEASED)));
            case "ledger.payment-posted" -> handler.handleLedgerPosted(
                    keyed(kafkaKey, objectMapper.readValue(payload, LEDGER_POSTED)));
            case "ledger.payment-posting-failed" -> handler.handleLedgerPostingFailed(
                    keyed(kafkaKey, objectMapper.readValue(payload, LEDGER_POSTING_FAILED)));
            case "ledger.refund-posted" -> refundHandler.handleLedgerRefundPosted(
                    keyed(kafkaKey, objectMapper.readValue(payload, LEDGER_REFUND_POSTED)));
            case "ledger.refund-posting-failed" -> refundHandler.handleLedgerRefundPostingFailed(
                    keyed(kafkaKey, objectMapper.readValue(payload, LEDGER_REFUND_POSTING_FAILED)));
            case "account.refund-credited" -> refundHandler.handleAccountRefundCredited(
                    keyed(kafkaKey, objectMapper.readValue(payload, REFUND_CREDITED)));
            default -> null;
        };
        return result == null ? RouteResult.IGNORED : RouteResult.valueOf(result.name());
    }

    private static <T> EventEnvelope<T> keyed(String kafkaKey, EventEnvelope<T> event) {
        Objects.requireNonNull(event, "event");
        if (kafkaKey == null || !kafkaKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException(
                    "Kafka key must equal envelope aggregateId for Payment workflow ordering");
        }
        return event;
    }

    enum RouteResult {
        PROCESSED,
        DUPLICATE,
        IGNORED
    }
}
