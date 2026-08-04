package com.payflow.payment.application.handler;

import com.payflow.payment.application.RefundDetail;
import com.payflow.payment.application.exception.RefundNotFoundException;
import com.payflow.payment.application.port.RefundRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a refund only when refund, parent payment and authenticated merchant all match. */
@Service
public class GetRefundHandler {

    private final RefundRepository refunds;

    public GetRefundHandler(RefundRepository refunds) {
        this.refunds = refunds;
    }

    @Transactional(readOnly = true)
    public RefundDetail handle(UUID refundId, UUID paymentId, UUID merchantId) {
        return refunds.find(refundId, paymentId, merchantId)
                .map(RefundDetail::of)
                .orElseThrow(() -> new RefundNotFoundException(refundId, paymentId, merchantId));
    }
}
