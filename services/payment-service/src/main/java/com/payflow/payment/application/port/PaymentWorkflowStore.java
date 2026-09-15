package com.payflow.payment.application.port;

import com.payflow.payment.application.saga.VersionedPayment;
import java.util.Optional;
import java.util.UUID;

/** Internal Payment workflow access; public merchant reads remain merchant-scoped. */
public interface PaymentWorkflowStore {

    Optional<VersionedPayment> findForWorkflow(UUID paymentId);

    /** Locks the merchant-owned row so cancel cannot race past a financial Saga transition. */
    Optional<VersionedPayment> findForCancellation(UUID paymentId, UUID merchantId);

    void updateWorkflow(VersionedPayment payment);
}
