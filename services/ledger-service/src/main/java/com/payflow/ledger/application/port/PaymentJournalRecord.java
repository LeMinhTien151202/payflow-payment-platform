package com.payflow.ledger.application.port;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Durable identity used to distinguish payment-command redelivery from an intent conflict. */
public record PaymentJournalRecord(
        UUID paymentId,
        UUID customerId,
        UUID merchantId,
        UUID journalId,
        BigDecimal amount,
        String currency) {

    public PaymentJournalRecord {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }
}
