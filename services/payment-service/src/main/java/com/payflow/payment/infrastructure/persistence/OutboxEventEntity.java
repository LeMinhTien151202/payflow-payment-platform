package com.payflow.payment.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code payment.outbox_events} row.
 *
 * <p>{@code payload} is the finished envelope as it will go on the wire, serialised once at insert time. A
 * republish after a crash therefore sends identical bytes under the same {@code eventId}, which is the
 * property every consumer inbox depends on to recognise a duplicate (ADR-014).
 *
 * <p>The lease columns are mapped but never set here. Only the publisher claims a row, and it does so with a
 * conditional UPDATE rather than through this entity, because {@code FOR UPDATE SKIP LOCKED} is not something
 * JPA can express.
 */
@Entity
@Table(name = "outbox_events", schema = "payment")
class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false)
    private String headers;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lock_owner", length = 100)
    private String lockOwner;

    @Column(name = "lock_until")
    private Instant lockUntil;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEventEntity() {
        // Required by JPA.
    }

    /**
     * A row the publisher may pick up immediately.
     *
     * <p>{@code nextAttemptAt} is the insert time rather than a delay: the point of the outbox is that the
     * event leaves as soon as the transaction that produced it commits.
     */
    static OutboxEventEntity pending(
            UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            String topic,
            String payload,
            String headers,
            Instant createdAt) {

        OutboxEventEntity entity = new OutboxEventEntity();
        entity.id = id;
        entity.aggregateType = aggregateType;
        entity.aggregateId = aggregateId;
        entity.eventType = eventType;
        entity.eventVersion = eventVersion;
        entity.topic = topic;
        entity.payload = payload;
        entity.headers = headers;
        entity.status = OutboxStatus.PENDING;
        entity.attemptCount = 0;
        entity.nextAttemptAt = createdAt;
        entity.createdAt = createdAt;
        return entity;
    }
}
