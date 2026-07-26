package com.payflow.payment.application.handler;

import com.payflow.payment.application.PaymentDetail;
import com.payflow.payment.application.exception.PaymentNotFoundException;
import com.payflow.payment.application.port.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one payment on behalf of the merchant that owns it.
 *
 * <p>Thin, and still a use case rather than a controller calling the repository. The merchant scope check is
 * an authorization decision, and AGENTS.md section 8 puts those at the application boundary — leaving it to
 * the controller would mean the rule is enforced by whichever caller remembers it.
 *
 * <p>{@code @Transactional} here, unlike {@link CreatePaymentHandler}: a read has no unique-constraint race to
 * recover from, so there is nothing that needs the boundary to sit inside the method.
 */
@Service
public class GetPaymentHandler {

    private final PaymentRepository payments;

    public GetPaymentHandler(PaymentRepository payments) {
        this.payments = payments;
    }

    /**
     * @param merchantId the merchant the caller's token authenticated, never a value from the request
     * @throws PaymentNotFoundException if no such payment exists, or it belongs to another merchant
     */
    @Transactional(readOnly = true)
    public PaymentDetail handle(UUID paymentId, UUID merchantId) {
        return payments
                .find(paymentId, merchantId)
                .map(PaymentDetail::of)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId, merchantId));
    }
}
