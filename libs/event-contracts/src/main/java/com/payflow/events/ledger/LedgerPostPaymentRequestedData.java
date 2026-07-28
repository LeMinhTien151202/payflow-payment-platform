package com.payflow.events.ledger;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload of {@code ledger.post-payment.requested} v1.
 *
 * <p>Customer and merchant identities let Ledger resolve its own ledger accounts. Payment never
 * passes a Ledger-owned account id or reaches into Ledger persistence.
 */
public record LedgerPostPaymentRequestedData(
        UUID paymentId,
        UUID customerId,
        UUID merchantId,
        BigDecimal amount,
        String currency) {

    public static final int MONEY_SCALE = 4;

    public LedgerPostPaymentRequestedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(merchantId, "merchantId is required");
        Objects.requireNonNull(amount, "amount is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (amount.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(
                    "amount scale " + amount.scale() + " exceeds " + MONEY_SCALE);
        }
        amount = amount.setScale(MONEY_SCALE);
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter uppercase ISO-4217 code");
        }
    }
}
