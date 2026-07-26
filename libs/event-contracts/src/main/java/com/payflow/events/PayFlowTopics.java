package com.payflow.events;

/**
 * Kafka topic names, exactly as fixed by spec 8.1.
 *
 * <p>One topic per bounded context, with {@code eventType} distinguishing events inside it. The
 * {@code .v1} suffix versions the <em>topic</em>, which is the escape hatch for a change no
 * {@code eventVersion} bump can absorb; routine schema evolution stays on the same topic.
 *
 * <p>These strings are hardcoded rather than configurable. A topic name that differs between
 * environments turns "the consumer never received it" into an unfalsifiable problem, and a typo in a
 * producer would silently create a second topic — except that
 * {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE=false} in {@code docker-compose.yml} makes it fail loudly
 * instead. Declaring the topic is the producing service's job, via its own {@code NewTopic} bean;
 * this class only fixes the names.
 */
public final class PayFlowTopics {

    /** Produced by payment-service: payment lifecycle and the commands it orchestrates. */
    public static final String PAYMENT_EVENTS = "payflow.payment.events.v1";

    /** Produced by account-service. Phase 1B. */
    public static final String ACCOUNT_EVENTS = "payflow.account.events.v1";

    /** Produced by ledger-service. Phase 1B. */
    public static final String LEDGER_EVENTS = "payflow.ledger.events.v1";

    /** Produced by risk-service. Phase 1B. */
    public static final String RISK_EVENTS = "payflow.risk.events.v1";

    /** Produced by payment-service for the refund flow. Phase 2. */
    public static final String REFUND_EVENTS = "payflow.refund.events.v1";

    /** Commands, not events: notification-service is the only consumer. Phase 2. */
    public static final String NOTIFICATION_COMMANDS = "payflow.notification.commands.v1";

    /** Produced by settlement-service. Phase 3. */
    public static final String SETTLEMENT_EVENTS = "payflow.settlement.events.v1";

    /**
     * Terminal destination for messages a consumer could not process. Phase 2 — until then, a
     * permanently failing outbox row ends at {@code FAILED} instead (ADR-014).
     */
    public static final String DEAD_LETTER = "payflow.dead-letter.v1";

    private PayFlowTopics() {
    }
}
