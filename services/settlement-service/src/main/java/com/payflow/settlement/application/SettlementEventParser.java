package com.payflow.settlement.application;

import com.payflow.observability.CorrelationId;
import com.payflow.settlement.domain.SettlementContribution;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parses only the immutable facts needed by ADR-025 and rejects unsupported relevant versions. */
@Component
public final class SettlementEventParser {

    private final ObjectMapper json;

    public SettlementEventParser(ObjectMapper json) {
        this.json = json;
    }

    public Optional<SettlementFact> parse(String kafkaKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Kafka payload must not be blank");
        }
        JsonNode envelope = json.readTree(payload);
        String aggregateId = text(envelope, "aggregateId");
        if (kafkaKey == null || !kafkaKey.equals(aggregateId)) {
            throw new IllegalArgumentException("Kafka key must equal aggregateId");
        }
        String eventType = text(envelope, "eventType");
        int version = envelope.required("eventVersion").asInt();
        UUID eventId = UUID.fromString(text(envelope, "eventId"));
        String correlationId = text(envelope, "correlationId");
        if (!CorrelationId.isSafe(correlationId)) {
            throw new IllegalArgumentException("correlationId is unsafe");
        }
        Instant occurredAt = Instant.parse(text(envelope, "occurredAt"));
        JsonNode data = envelope.required("data");

        return switch (eventType) {
            case "payment.succeeded" -> Optional.of(paymentSucceeded(
                    eventId, version, aggregateId, correlationId, occurredAt, data));
            case "refund.succeeded" -> Optional.of(refundSucceeded(
                    eventId, version, aggregateId, correlationId, occurredAt, data));
            case "ledger.payment-posted" -> Optional.of(simpleFact(
                    eventId, eventType, version, 1, aggregateId, correlationId,
                    SettlementFactType.LEDGER_PAYMENT_POSTED,
                    uuid(data, "paymentId"), uuid(data, "paymentId"), decimal(data, "amount"),
                    data.required("currency").stringValue(), occurredAt));
            case "ledger.refund-posted" -> Optional.of(simpleFact(
                    eventId, eventType, version, 1, aggregateId, correlationId,
                    SettlementFactType.LEDGER_REFUND_POSTED,
                    uuid(data, "refundId"), uuid(data, "paymentId"), decimal(data, "amount"),
                    data.required("currency").stringValue(), occurredAt));
            case "account.funds-captured" -> Optional.of(simpleFact(
                    eventId, eventType, version, 1, aggregateId, correlationId,
                    SettlementFactType.ACCOUNT_FUNDS_CAPTURED,
                    uuid(data, "paymentId"), uuid(data, "paymentId"), decimal(data, "amount"),
                    data.required("currency").stringValue(), occurredAt));
            case "account.refund-credited" -> Optional.of(simpleFact(
                    eventId, eventType, version, 1, aggregateId, correlationId,
                    SettlementFactType.ACCOUNT_REFUND_CREDITED,
                    uuid(data, "refundId"), uuid(data, "paymentId"), decimal(data, "amount"),
                    data.required("currency").stringValue(), occurredAt));
            default -> Optional.empty();
        };
    }

    private static SettlementFact paymentSucceeded(
            UUID eventId,
            int version,
            String aggregateId,
            String correlationId,
            Instant occurredAt,
            JsonNode data) {
        requireVersion("payment.succeeded", version, 2);
        UUID paymentId = uuid(data, "paymentId");
        requireAggregate(aggregateId, paymentId);
        UUID merchantId = uuid(data, "merchantId");
        BigDecimal amount = decimal(data, "amount");
        BigDecimal fee = decimal(data, "feeAmount");
        String currency = data.required("currency").stringValue();
        String feeCurrency = data.required("feeCurrency").stringValue();
        if (!currency.equals(feeCurrency)) {
            throw new IllegalArgumentException("feeCurrency must equal payment currency");
        }
        return new SettlementFact(
                eventId,
                "payment.succeeded",
                version,
                aggregateId,
                correlationId,
                SettlementFactType.PAYMENT_SUCCEEDED,
                paymentId,
                paymentId,
                merchantId,
                amount,
                fee,
                currency,
                occurredAt,
                SettlementContribution.payment(paymentId, merchantId, amount, fee, currency, occurredAt));
    }

    private static SettlementFact refundSucceeded(
            UUID eventId,
            int version,
            String aggregateId,
            String correlationId,
            Instant occurredAt,
            JsonNode data) {
        requireVersion("refund.succeeded", version, 1);
        UUID refundId = uuid(data, "refundId");
        UUID paymentId = uuid(data, "paymentId");
        requireAggregate(aggregateId, paymentId);
        UUID merchantId = uuid(data, "merchantId");
        BigDecimal amount = decimal(data, "amount");
        BigDecimal feeReversal = decimal(data, "feeReversalAmount");
        String currency = data.required("currency").stringValue();
        return new SettlementFact(
                eventId,
                "refund.succeeded",
                version,
                aggregateId,
                correlationId,
                SettlementFactType.REFUND_SUCCEEDED,
                refundId,
                paymentId,
                merchantId,
                amount,
                feeReversal,
                currency,
                occurredAt,
                SettlementContribution.refund(
                        refundId, paymentId, merchantId, amount, feeReversal, currency, occurredAt));
    }

    private static SettlementFact simpleFact(
            UUID eventId,
            String eventType,
            int version,
            int expectedVersion,
            String aggregateId,
            String correlationId,
            SettlementFactType factType,
            UUID referenceId,
            UUID paymentId,
            BigDecimal amount,
            String currency,
            Instant occurredAt) {
        requireVersion(eventType, version, expectedVersion);
        requireAggregate(aggregateId, paymentId);
        return new SettlementFact(
                eventId,
                eventType,
                version,
                aggregateId,
                correlationId,
                factType,
                referenceId,
                paymentId,
                null,
                amount,
                BigDecimal.ZERO,
                currency,
                occurredAt,
                null);
    }

    private static void requireVersion(String type, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalArgumentException(
                    "Settlement requires " + type + " v" + expected + ", received v" + actual);
        }
    }

    private static void requireAggregate(String aggregateId, UUID paymentId) {
        if (!paymentId.toString().equals(aggregateId)) {
            throw new IllegalArgumentException("aggregateId must equal paymentId");
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.required(field).stringValue();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(text(node, field));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            return new BigDecimal(value.stringValue());
        }
        throw new IllegalArgumentException(field + " must be numeric");
    }
}
