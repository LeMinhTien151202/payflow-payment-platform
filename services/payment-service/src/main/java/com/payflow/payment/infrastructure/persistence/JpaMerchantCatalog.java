package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.port.MerchantCatalog;
import com.payflow.payment.domain.model.MerchantSnapshot;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the merchant catalog from the {@code merchant} schema this service still owns.
 *
 * <p>{@code readOnly} is set for the benefit of the plain-read case: the merchant lookup also happens inside
 * the payment transaction, where this annotation simply joins the existing one rather than opening a second.
 */
@Component
class JpaMerchantCatalog implements MerchantCatalog {

    private final EntityManager entityManager;

    JpaMerchantCatalog(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MerchantSnapshot> findById(UUID merchantId) {
        return Optional.ofNullable(entityManager.find(MerchantEntity.class, merchantId))
                .map(MerchantEntity::toSnapshot);
    }
}
