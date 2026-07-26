package com.payflow.payment.application.port;

import com.payflow.events.EventType;
import java.time.Instant;
import java.util.UUID;

/**
 * Hands an event to the outbox, inside the caller's transaction.
 *
 * <p>The only way this service produces an event. AGENTS.md section 6 forbids publishing to Kafka from a
 * business path: a send that happens while the transaction is still open can succeed and then be rolled
 * back, which publishes a payment that does not exist.
 *
 * <p>The application layer names the event type and the topic because both are contract decisions — which
 * fact is being announced, and who is expected to hear it. What the adapter owns is everything about the
 * row: the id, the envelope assembled around {@code data}, the headers, the initial status, and the first
 * attempt time.
 */
public interface OutboxAppender {

    /**
     * Appends one event.
     *
     * @param type contract name, version, and aggregate kind
     * @param topic destination, from {@code PayFlowTopics}
     * @param aggregateId the aggregate this event is about; also the Kafka key, which is what keeps events
     *     for one payment in order (spec 8.3)
     * @param occurredAt when the business fact happened, from the caller's {@code Clock} — not when the
     *     row was written and not when it will be published
     * @param data the versioned payload, owned by this service
     * @return the assigned {@code eventId}, which is the row id and the consumer deduplication key
     */
    <T> UUID append(EventType type, String topic, String aggregateId, Instant occurredAt, T data);
}
