package com.payflow.payment.domain.exception;

import com.payflow.payment.domain.model.MerchantStatus;
import java.util.UUID;

/**
 * The merchant exists but is not in a state that may take money.
 *
 * <p>Separate from "merchant not found" on purpose: the two mean different things to whoever is
 * looking, even though the API may well choose to answer both the same way so that a caller cannot
 * probe which merchant ids exist.
 */
public final class MerchantNotAcceptingPaymentsException extends PaymentDomainException {

    private final UUID merchantId;
    private final MerchantStatus status;

    public MerchantNotAcceptingPaymentsException(UUID merchantId, MerchantStatus status) {
        super("merchant " + merchantId + " cannot accept payments while " + status);
        this.merchantId = merchantId;
        this.status = status;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public MerchantStatus status() {
        return status;
    }
}
