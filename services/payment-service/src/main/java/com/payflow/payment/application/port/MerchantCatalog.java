package com.payflow.payment.application.port;

import com.payflow.payment.domain.model.MerchantSnapshot;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the merchant catalog.
 *
 * <p>An outbound port, even though the catalog currently lives in a schema this service owns. Spec 7.3
 * calls the merchant catalog a module destined to become its own service; going through a port now
 * means that split replaces one adapter instead of every call site.
 *
 * <p>Returns a snapshot rather than an entity, so a payment decision cannot be affected by the
 * merchant record changing underneath it.
 */
public interface MerchantCatalog {

    /** The merchant, or empty when no merchant with that id is registered. */
    Optional<MerchantSnapshot> findById(UUID merchantId);
}
