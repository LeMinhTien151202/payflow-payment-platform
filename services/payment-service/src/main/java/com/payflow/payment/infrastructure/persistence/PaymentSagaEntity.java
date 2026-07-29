package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Persistence-owned representation of {@code payment.payment_sagas}. */
@Entity
@Table(name = "payment_sagas", schema = "payment")
class PaymentSagaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 50)
    private PaymentSagaStep currentStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentSagaStatus status;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentSagaEntity() {
        // Required by JPA.
    }

    static PaymentSagaEntity from(PaymentSaga saga) {
        PaymentSagaEntity entity = new PaymentSagaEntity();
        entity.id = saga.id();
        entity.paymentId = saga.paymentId();
        entity.apply(saga);
        entity.createdAt = saga.createdAt();
        return entity;
    }

    void apply(PaymentSaga saga) {
        if (id != null && (!id.equals(saga.id()) || !paymentId.equals(saga.paymentId()))) {
            throw new IllegalArgumentException("cannot apply a different Payment Saga aggregate");
        }
        currentStep = saga.currentStep();
        status = saga.status();
        deadlineAt = saga.deadlineAt();
        retryCount = saga.retryCount();
        lastErrorCode = saga.lastErrorCode();
        reservationId = saga.reservationId();
        journalId = saga.journalId();
        updatedAt = saga.updatedAt();
    }

    PaymentSaga toSaga() {
        return PaymentSaga.rehydrate(
                id,
                paymentId,
                currentStep,
                status,
                deadlineAt,
                retryCount,
                lastErrorCode,
                reservationId,
                journalId,
                createdAt,
                updatedAt);
    }

    UUID id() {
        return id;
    }

    UUID paymentId() {
        return paymentId;
    }

    long version() {
        return version;
    }
}
