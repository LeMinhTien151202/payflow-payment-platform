package com.payflow.account.infrastructure.persistence;

import com.payflow.account.application.port.AccountReservationStore;
import com.payflow.account.domain.model.Account;
import com.payflow.account.domain.model.Reservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaAccountReservationStore implements AccountReservationStore {

    private final EntityManager entityManager;

    JpaAccountReservationStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Account> findAccountForUpdate(UUID accountId) {
        return entityManager
                .createQuery("select a from AccountEntity a where a.id = :id", AccountEntity.class)
                .setParameter("id", accountId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .stream()
                .findFirst()
                .map(AccountEntity::toAccount);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Reservation> findReservationByPaymentId(UUID paymentId) {
        return entityManager
                .createQuery(
                        "select r from ReservationEntity r where r.paymentId = :paymentId",
                        ReservationEntity.class)
                .setParameter("paymentId", paymentId)
                .getResultList()
                .stream()
                .findFirst()
                .map(ReservationEntity::toReservation);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Reservation> findReservationForUpdate(UUID reservationId) {
        return entityManager
                .createQuery(
                        "select r from ReservationEntity r where r.id = :id",
                        ReservationEntity.class)
                .setParameter("id", reservationId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .stream()
                .findFirst()
                .map(ReservationEntity::toReservation);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateAccount(Account account) {
        AccountEntity entity = entityManager.find(AccountEntity.class, account.id());
        if (entity == null) {
            throw new IllegalStateException("locked account disappeared before balance update");
        }
        entity.apply(account);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveReservation(Reservation reservation) {
        entityManager.persist(ReservationEntity.from(reservation));
        entityManager.flush();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateReservation(Reservation reservation) {
        ReservationEntity entity = entityManager.find(ReservationEntity.class, reservation.id());
        if (entity == null) {
            throw new IllegalStateException("locked reservation disappeared before update");
        }
        entity.apply(reservation);
    }
}
