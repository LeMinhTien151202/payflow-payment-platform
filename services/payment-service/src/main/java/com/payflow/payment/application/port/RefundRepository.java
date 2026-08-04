package com.payflow.payment.application.port;

import com.payflow.payment.domain.model.Refund;
import java.util.Optional;
import java.util.UUID;

/** Local persistence boundary for the Refund aggregate. */
public interface RefundRepository {

    void save(Refund refund);

    /** Merchant-scoped read; an absent or cross-merchant refund produces the same empty result. */
    Optional<Refund> find(UUID refundId, UUID paymentId, UUID merchantId);

    /** Called after the owning Payment row is locked; locks the Refund row for outcome processing. */
    Optional<Refund> findForWorkflow(UUID refundId);

    void updateWorkflow(Refund refund);
}
