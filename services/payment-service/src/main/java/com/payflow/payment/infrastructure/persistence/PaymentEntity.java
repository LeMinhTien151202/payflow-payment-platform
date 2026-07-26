package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code payment.payments} row.
 *
 * <p>Separate from {@link Payment} rather than annotating the aggregate. The aggregate has no {@code version}
 * field, keeps its recorded status changes in a list JPA has no column for, and validates itself in a
 * constructor JPA is not allowed to use. Mapping it directly would mean giving it a no-arg constructor and
 * mutable fields — which is to say, removing the reason it exists.
 *
 * <p>{@code metadata} is a JSON string here, not a {@code Map}. The adapter serialises it with the
 * application's {@code ObjectMapper}, so what reaches the column is decided by code that can be read, rather
 * than by whichever JSON format mapper Hibernate happens to resolve.
 */
@Entity
@Table(name = "payments", schema = "payment")
class PaymentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "merchant_reference", nullable = false, length = 100)
    private String merchantReference;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private PaymentStatus status;

    @Column(name = "description", length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock. Unused in Phase 1A, which only inserts, and present so that the first Phase 1B state
     * transition cannot be written without one.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentEntity() {
        // Required by JPA.
    }

    /**
     * @param metadataJson the aggregate's metadata already serialised, or {@code null} when it is empty —
     *     an empty JSON object and no metadata are the same fact, and NULL is the one the column comment
     *     describes
     */
    static PaymentEntity from(Payment payment, String metadataJson) {
        PaymentEntity entity = new PaymentEntity();
        entity.id = payment.id();
        entity.merchantId = payment.merchantId();
        entity.customerId = payment.customerId();
        entity.sourceAccountId = payment.sourceAccountId();
        entity.merchantReference = payment.merchantReference();
        entity.idempotencyKey = payment.idempotencyKey();
        entity.amount = payment.amount().amount();
        entity.currency = payment.amount().currency();
        entity.status = payment.status();
        entity.description = payment.description();
        entity.metadata = metadataJson;
        entity.createdAt = payment.createdAt();
        entity.updatedAt = payment.updatedAt();
        return entity;
    }

    /**
     * Rebuilds the aggregate.
     *
     * <p>Goes through {@link PaymentIntake}, which re-validates what it was given. That is deliberate: a row
     * that no longer satisfies the intake rules — a non-positive amount, a currency the platform has stopped
     * supporting — is corrupt data, and finding out when it is read is better than passing it on.
     *
     * @param metadata the {@code metadata} column already deserialised, empty when the column is NULL
     */
    Payment toPayment(Map<String, String> metadata) {
        return Payment.rehydrate(
                id,
                merchantId,
                new PaymentIntake(
                        id,
                        customerId,
                        sourceAccountId,
                        merchantReference,
                        idempotencyKey,
                        new Money(amount, currency),
                        description,
                        metadata,
                        createdAt),
                status,
                updatedAt);
    }

    UUID id() {
        return id;
    }

    /** The raw JSON, for the adapter to deserialise with the application's {@code ObjectMapper}. */
    String metadata() {
        return metadata;
    }
}
