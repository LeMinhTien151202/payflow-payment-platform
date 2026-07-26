package com.payflow.payment.domain.exception;

/**
 * The currency is supported by the platform but not by this merchant.
 *
 * <p>Distinct from {@link UnsupportedCurrencyException}: that one means the platform cannot handle the
 * currency at all, this one means the merchant is not set up for it. With VND as the only supported
 * currency the second case is unreachable, and the check exists anyway so that widening the supported
 * set does not silently start accepting payments in a currency a merchant never agreed to.
 */
public final class CurrencyNotAcceptedException extends PaymentDomainException {

    private final String requested;
    private final String merchantCurrency;

    public CurrencyNotAcceptedException(String requested, String merchantCurrency) {
        super("merchant settles in " + merchantCurrency + ", not " + requested);
        this.requested = requested;
        this.merchantCurrency = merchantCurrency;
    }

    public String requested() {
        return requested;
    }

    public String merchantCurrency() {
        return merchantCurrency;
    }
}
