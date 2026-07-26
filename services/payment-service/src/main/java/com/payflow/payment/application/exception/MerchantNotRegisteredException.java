package com.payflow.payment.application.exception;

import java.util.UUID;

/**
 * The authenticated caller presented a merchant id that the catalog does not contain.
 *
 * <p>This is not a "resource not found": nothing was looked up on the caller's behalf. The token asserts
 * a merchant identity that this platform has no record of, which means the identity provider and the
 * merchant catalog disagree. The API boundary answers 403 rather than 404, because the caller is not
 * entitled to know which merchant ids exist.
 */
public final class MerchantNotRegisteredException extends PaymentApplicationException {

    private final UUID merchantId;

    public MerchantNotRegisteredException(UUID merchantId) {
        super("merchant " + merchantId + " is not registered in the merchant catalog");
        this.merchantId = merchantId;
    }

    /** For the server-side log line. Never returned to the caller. */
    public UUID merchantId() {
        return merchantId;
    }
}
