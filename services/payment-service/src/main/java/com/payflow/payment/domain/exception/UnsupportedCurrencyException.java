package com.payflow.payment.domain.exception;

/** A syntactically valid currency the platform has not been built to handle. */
public final class UnsupportedCurrencyException extends PaymentDomainException {

    private final String currency;

    public UnsupportedCurrencyException(String currency) {
        super("currency not supported: " + currency);
        this.currency = currency;
    }

    public String currency() {
        return currency;
    }
}
