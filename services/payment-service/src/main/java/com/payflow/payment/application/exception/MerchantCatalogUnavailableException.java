package com.payflow.payment.application.exception;

/** Merchant policy cannot be read safely, so payment intake fails closed with a retryable 503. */
public final class MerchantCatalogUnavailableException extends PaymentApplicationException {
    public MerchantCatalogUnavailableException(Throwable cause) {
        super("merchant catalog is temporarily unavailable", cause);
    }
}
