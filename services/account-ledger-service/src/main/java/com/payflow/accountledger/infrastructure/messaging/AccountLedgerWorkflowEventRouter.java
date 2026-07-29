package com.payflow.accountledger.infrastructure.messaging;

import com.payflow.accountledger.account.application.handler.HandleCaptureFundsRequestedHandler;
import com.payflow.accountledger.account.application.handler.HandleRefundCreditRequestedHandler;
import com.payflow.accountledger.account.application.handler.HandleReleaseFundsRequestedHandler;
import com.payflow.accountledger.account.application.handler.HandleReserveFundsRequestedHandler;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.ledger.application.handler.HandlePostPaymentRequestedHandler;
import com.payflow.accountledger.ledger.application.handler.HandleRefundRequestedHandler;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.refund.RefundRequestedData;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Deserializes every Payment/Refund command owned by Account or Ledger in the MVP deployable. */
@Component
class AccountLedgerWorkflowEventRouter {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountReserveRequestedData>> RESERVE =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountCaptureRequestedData>> CAPTURE =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountReleaseRequestedData>> RELEASE =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<LedgerPostPaymentRequestedData>> POST_PAYMENT =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<RefundRequestedData>> REFUND_REQUESTED =
            new TypeReference<>() {};
    private static final TypeReference<EventEnvelope<AccountRefundCreditRequestedData>>
            REFUND_CREDIT_REQUESTED = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final HandleReserveFundsRequestedHandler reserveHandler;
    private final HandleCaptureFundsRequestedHandler captureHandler;
    private final HandleReleaseFundsRequestedHandler releaseHandler;
    private final HandlePostPaymentRequestedHandler paymentJournalHandler;
    private final HandleRefundRequestedHandler refundJournalHandler;
    private final HandleRefundCreditRequestedHandler refundCreditHandler;

    AccountLedgerWorkflowEventRouter(
            ObjectMapper objectMapper,
            HandleReserveFundsRequestedHandler reserveHandler,
            HandleCaptureFundsRequestedHandler captureHandler,
            HandleReleaseFundsRequestedHandler releaseHandler,
            HandlePostPaymentRequestedHandler paymentJournalHandler,
            HandleRefundRequestedHandler refundJournalHandler,
            HandleRefundCreditRequestedHandler refundCreditHandler) {
        this.objectMapper = objectMapper;
        this.reserveHandler = reserveHandler;
        this.captureHandler = captureHandler;
        this.releaseHandler = releaseHandler;
        this.paymentJournalHandler = paymentJournalHandler;
        this.refundJournalHandler = refundJournalHandler;
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
            case "account.reserve.requested" -> reserveHandler.handle(
                    keyed(kafkaKey, objectMapper.readValue(payload, RESERVE)));
            case "account.capture.requested" -> captureHandler.handle(
                    keyed(kafkaKey, objectMapper.readValue(payload, CAPTURE)));
            case "account.release.requested" -> releaseHandler.handle(
                    keyed(kafkaKey, objectMapper.readValue(payload, RELEASE)));
            case "ledger.post-payment.requested" -> paymentJournalHandler.handle(
                    keyed(kafkaKey, objectMapper.readValue(payload, POST_PAYMENT)));
            case "refund.requested" -> refundJournalHandler.handle(
                    keyed(kafkaKey, objectMapper.readValue(payload, REFUND_REQUESTED)));
            case "account.refund-credit.requested" -> refundCreditHandler.handle(
                    keyed(kafkaKey, objectMapper.readValue(payload, REFUND_CREDIT_REQUESTED)));
            default -> null;
        };
        return result == null ? RouteResult.IGNORED : RouteResult.valueOf(result.name());
    }

    private static <T> EventEnvelope<T> keyed(String kafkaKey, EventEnvelope<T> event) {
        Objects.requireNonNull(event, "event");
        if (kafkaKey == null || !kafkaKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException(
                    "Kafka key must equal envelope aggregateId for payment ordering");
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
