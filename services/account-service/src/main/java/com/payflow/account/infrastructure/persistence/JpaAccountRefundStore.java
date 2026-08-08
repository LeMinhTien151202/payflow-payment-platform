package com.payflow.account.infrastructure.persistence;

import com.payflow.account.application.port.AccountRefundStore;
import com.payflow.account.domain.model.Account;
import com.payflow.account.domain.model.RefundCredit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaAccountRefundStore implements AccountRefundStore {

    private final EntityManager entityManager;

    JpaAccountRefundStore(EntityManager entityManager) {
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
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RefundCredit> findCreditByRefundId(UUID refundId) {
        return entityManager
                .createQuery(
                        "select c from RefundCreditEntity c where c.refundId = :refundId",
                        RefundCreditEntity.class)
                .setParameter("refundId", refundId)
                .getResultList()
                .stream()
                .findFirst()
                .map(RefundCreditEntity::toCredit);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateAccount(Account account) {
        AccountEntity entity = entityManager.find(AccountEntity.class, account.id());
        if (entity == null) {
            throw new IllegalStateException("locked account disappeared before refund update");
        }
        entity.apply(account);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveCredit(RefundCredit credit) {
        entityManager.persist(RefundCreditEntity.from(credit));
        entityManager.flush();
    }
}
