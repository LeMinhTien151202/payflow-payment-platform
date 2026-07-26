package com.payflow.events;

import com.payflow.observability.CorrelationId;

/**
 * Kafka header names carried alongside every PayFlow event.
 *
 * <p>Every one of these values is also inside the envelope body. The duplication is the point: a
 * consumer can log, filter, or route on them without deserialising the payload, and an operator
 * inspecting a topic with a console consumer can see what a message is without a schema. The body
 * stays authoritative — a consumer must never trust a header over the envelope it wraps, since
 * headers are not covered by any schema check.
 */
public final class EventHeaders {

    /**
     * Correlation id, using the same name as the HTTP header so one value is greppable across the
     * whole request path.
     */
    public static final String CORRELATION_ID = CorrelationId.HEADER;

    /** Deduplication key. A consumer inbox can reject a duplicate before parsing the body. */
    public static final String EVENT_ID = "X-Event-Id";

    /** Contract name, so a consumer can skip an event type it does not handle cheaply. */
    public static final String EVENT_TYPE = "X-Event-Type";

    /** Schema version of the payload. */
    public static final String EVENT_VERSION = "X-Event-Version";

    private EventHeaders() {
    }
}
