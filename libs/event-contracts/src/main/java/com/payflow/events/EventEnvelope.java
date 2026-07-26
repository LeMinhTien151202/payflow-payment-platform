package com.payflow.events;

import com.payflow.observability.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The single envelope every PayFlow Kafka message uses, per spec 8.2.
 *
 * <p>Component names are the wire format. Renaming one is a breaking change for every consumer, so
 * they are not refactored for style. There are no serialisation annotations on purpose: the JSON
 * shape is a contract that happens to be produced by Jackson, not a Jackson mapping that happens to
 * be published.
 *
 * <p><strong>{@code eventId} is generated when the outbox row is inserted, not when the message is
 * published.</strong> ADR-014 depends on this: a republish after a crash must carry the same
 * {@code eventId} as the send that may already have reached the broker, otherwise the consumer inbox
 * cannot recognise the duplicate and the whole at-least-once chain is worthless.
 *
 * @param eventId stable identity of this event, and the deduplication key for every consumer
 * @param eventType contract name, for example {@code payment.created}
 * @param eventVersion schema version of {@code data}
 * @param aggregateType aggregate kind, for example {@code PAYMENT}
 * @param aggregateId aggregate instance; also the Kafka key, which is what keeps per-aggregate
 *     ordering inside a partition (spec 8.3)
 * @param correlationId ties this event back to the HTTP request that caused it
 * @param causationId {@code eventId} of the event that caused this one, {@code null} when the cause
 *     was an inbound request rather than another event
 * @param producer service that wrote the outbox row
 * @param occurredAt when the business fact happened, not when it was published
 * @param data the versioned payload; its schema is owned by the producing service
 * @param <T> payload type
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        String aggregateId,
        String correlationId,
        String causationId,
        String producer,
        Instant occurredAt,
        T data) {

    /** Matches {@code outbox_events.aggregate_id}, which holds a UUID string in every current use. */
    public static final int MAX_AGGREGATE_ID_LENGTH = 100;

    /** Matches the producer column width used by the outbox and inbox tables. */
    public static final int MAX_PRODUCER_LENGTH = 100;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1, was " + eventVersion);
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (aggregateId.length() > MAX_AGGREGATE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "aggregateId exceeds " + MAX_AGGREGATE_ID_LENGTH + " characters");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("producer is required");
        }
        if (producer.length() > MAX_PRODUCER_LENGTH) {
            throw new IllegalArgumentException(
                    "producer exceeds " + MAX_PRODUCER_LENGTH + " characters");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(data, "data is required");

        // The correlation id reaches here from an HTTP header that a client controls, and from here
        // it goes into a persisted row, a log line, and a Kafka header. Rejecting rather than
        // sanitising is deliberate: by this point the request filter has already replaced anything
        // unsafe, so an unsafe value means a code path bypassed the filter.
        if (!CorrelationId.isSafe(correlationId)) {
            throw new IllegalArgumentException("correlationId is missing or not safe to propagate");
        }
        if (causationId != null && !CorrelationId.isSafe(causationId)) {
            throw new IllegalArgumentException("causationId is not safe to propagate");
        }
    }

    /**
     * Builds an envelope for a business fact caused by an inbound request rather than another event.
     *
     * @param eventId identity to assign; the caller supplies it because it must be generated once,
     *     at outbox insert time, and reused by every republish
     */
    public static <T> EventEnvelope<T> of(
            UUID eventId,
            EventType type,
            String aggregateId,
            String correlationId,
            String producer,
            Instant occurredAt,
            T data) {

        return new EventEnvelope<>(
                eventId,
                type.name(),
                type.version(),
                type.aggregateType(),
                aggregateId,
                correlationId,
                null,
                producer,
                occurredAt,
                data);
    }

    /**
     * Builds an envelope for a business fact caused by consuming another event, recording that cause
     * in {@code causationId}. Without this link a Saga is only traceable by correlation id, which
     * tells you the request but not the chain of steps inside it.
     */
    public static <T> EventEnvelope<T> causedBy(
            UUID eventId,
            EventType type,
            String aggregateId,
            EventEnvelope<?> cause,
            String producer,
            Instant occurredAt,
            T data) {

        return new EventEnvelope<>(
                eventId,
                type.name(),
                type.version(),
                type.aggregateType(),
                aggregateId,
                cause.correlationId(),
                cause.eventId().toString(),
                producer,
                occurredAt,
                data);
    }
}
