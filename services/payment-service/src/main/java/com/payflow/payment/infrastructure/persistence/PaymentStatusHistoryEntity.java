package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.domain.model.PaymentStatusChange;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code payment.payment_status_history} row.
 *
 * <p>{@code paymentId} is a plain UUID, not a {@code @ManyToOne}. Nothing here ever navigates to the payment,
 * and an association would let a lazy-loading bug turn writing a history row into loading an aggregate.
 *
 * <p>The database refuses UPDATE and DELETE on this table via a trigger. Hibernate is therefore never asked
 * to do either: the id is assigned, the row is inserted once, and the entity is never modified after that.
 */
@Entity
@Table(name = "payment_status_history", schema = "payment")
class PaymentStatusHistoryEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    /** Null marks the entry that records the payment coming into existence. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 40)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 40)
    private PaymentStatus toStatus;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PaymentStatusHistoryEntity() {
        // Required by JPA.
    }

    /**
     * @param id a surrogate key with no business meaning, so it is generated here rather than passed through
     *     the application layer
     */
    static PaymentStatusHistoryEntity from(UUID id, UUID paymentId, PaymentStatusChange change) {
        PaymentStatusHistoryEntity entity = new PaymentStatusHistoryEntity();
        entity.id = id;
        entity.paymentId = paymentId;
        entity.fromStatus = change.from();
        entity.toStatus = change.to();
        entity.reasonCode = change.reasonCode();
        entity.occurredAt = change.occurredAt();
        return entity;
    }
}
