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
 * The {@code payment.idempotency_records} row.
 *
 * <p>{@code responseBody} holds the stored response as JSON text. Keeping it as text rather than a mapped
 * object is what makes a replay return the bytes the original request produced: if it were deserialised into
 * a DTO and re-serialised, adding a field to that DTO would change what old rows replay as.
 */
@Entity
@Table(name = "idempotency_records", schema = "payment")
class IdempotencyRecordEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "scope", nullable = false, length = 100)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
        // Required by JPA.
    }

    static IdempotencyRecordEntity completed(
            UUID id,
            String scope,
            String idempotencyKey,
            String requestHash,
            UUID resourceId,
            int responseStatus,
            String responseBody,
            Instant expiresAt,
            Instant createdAt) {

        IdempotencyRecordEntity entity = new IdempotencyRecordEntity();
        entity.id = id;
        entity.scope = scope;
        entity.idempotencyKey = idempotencyKey;
        entity.requestHash = requestHash;
        entity.resourceId = resourceId;
        entity.responseStatus = responseStatus;
        entity.responseBody = responseBody;
        entity.status = IdempotencyStatus.COMPLETED;
        entity.expiresAt = expiresAt;
        entity.createdAt = createdAt;
        return entity;
    }

    String requestHash() {
        return requestHash;
    }

    UUID resourceId() {
        return resourceId;
    }

    Integer responseStatus() {
        return responseStatus;
    }

    String responseBody() {
        return responseBody;
    }

    IdempotencyStatus status() {
        return status;
    }
}
