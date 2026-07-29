package com.payflow.payment.application.port;

import com.payflow.payment.application.saga.VersionedPayment;
import java.util.Optional;
import java.util.UUID;

/** Internal Payment workflow access; public merchant reads remain merchant-scoped. */
public interface PaymentWorkflowStore {

    Optional<VersionedPayment> findForWorkflow(UUID paymentId);

    void updateWorkflow(VersionedPayment payment);
}
