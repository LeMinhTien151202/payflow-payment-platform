package com.payflow.events;

/**
 * Identity of an event contract: its name, its schema version, and the aggregate kind it describes.
 *
 * <p>These three values always change together and are always wrong together, so they travel as one
 * value instead of three loose strings. {@link EventEnvelope} takes eight string-ish arguments; if
 * name, version, and aggregate type were passed separately, transposing two of them would compile
 * and produce a valid-looking event on the wrong contract.
 *
 * <p>Bounds match the {@code outbox_events} column widths from spec 8.6. Failing here rather than at
 * insert time matters more than it looks: the outbox insert shares the business transaction, so a
 * column-width violation would roll back the payment itself and surface as a database error rather
 * than as the contract mistake it is.
 *
 * @param name wire value of {@code eventType}, for example {@code payment.created}
 * @param version schema version of this contract, starting at 1 and incremented on a breaking change
 * @param aggregateType aggregate kind, for example {@code PAYMENT}
 */
public record EventType(String name, int version, String aggregateType) {

    /** Matches {@code outbox_events.event_type VARCHAR(150)}. */
    public static final int MAX_NAME_LENGTH = 150;

    /** Matches {@code outbox_events.aggregate_type VARCHAR(100)}. */
    public static final int MAX_AGGREGATE_TYPE_LENGTH = 100;

    public EventType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("event type name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "event type name exceeds " + MAX_NAME_LENGTH + " characters: " + name.length());
        }
        if (version < 1) {
            throw new IllegalArgumentException("event version must be at least 1, was " + version);
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregate type is required");
        }
        if (aggregateType.length() > MAX_AGGREGATE_TYPE_LENGTH) {
            throw new IllegalArgumentException(
                    "aggregate type exceeds "
                            + MAX_AGGREGATE_TYPE_LENGTH
                            + " characters: "
                            + aggregateType.length());
        }
    }
}
