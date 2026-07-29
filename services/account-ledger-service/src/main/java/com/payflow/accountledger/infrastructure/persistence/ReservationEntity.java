package com.payflow.accountledger.infrastructure.persistence;

import com.payflow.accountledger.account.domain.model.Money;
import com.payflow.accountledger.account.domain.model.Reservation;
import com.payflow.accountledger.account.domain.model.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "balance_reservations", schema = "account")
class ReservationEntity {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected ReservationEntity() {}

    static ReservationEntity from(Reservation reservation) {
        ReservationEntity entity = new ReservationEntity();
        entity.id = reservation.id();
        entity.paymentId = reservation.paymentId();
        entity.accountId = reservation.accountId();
        entity.amount = reservation.amount().amount();
        entity.currency = reservation.amount().currency();
        entity.status = reservation.status();
        entity.createdAt = reservation.createdAt();
        entity.expiresAt = reservation.expiresAt();
        entity.completedAt = reservation.completedAt();
        return entity;
    }

    Reservation toReservation() {
        return Reservation.rehydrate(
                id,
                paymentId,
                accountId,
                new Money(amount, currency),
                status,
                createdAt,
                expiresAt,
                completedAt);
    }

    void apply(Reservation reservation) {
        if (!id.equals(reservation.id())) {
            throw new IllegalArgumentException("cannot apply a different Reservation aggregate");
        }
        status = reservation.status();
        completedAt = reservation.completedAt();
    }
}
