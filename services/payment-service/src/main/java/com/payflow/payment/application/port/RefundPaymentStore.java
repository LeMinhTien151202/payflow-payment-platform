package com.payflow.payment.application.port;

import com.payflow.payment.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;

/** Payment access used by refund intake while holding the financial boundary row lock. ADR-020. */
public interface RefundPaymentStore {

    /** Must be called inside a local transaction; locks only the merchant-scoped payment row. */
    Optional<Payment> findForRefund(UUID paymentId, UUID merchantId);

    /** Locks the Payment row before a refund outcome loads its Refund row. */
    Optional<Payment> findForRefundWorkflow(UUID paymentId);

    /** Persists capacity/status/history before the transaction releases the row lock. */
    void updateRefundState(Payment payment);
}
