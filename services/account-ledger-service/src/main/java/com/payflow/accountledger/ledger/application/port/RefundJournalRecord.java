package com.payflow.accountledger.ledger.application.port;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Durable business-idempotency projection for one refund reversal. */
public record RefundJournalRecord(
        UUID refundId,
        UUID paymentId,
        UUID merchantId,
        UUID sourceAccountId,
        UUID journalId,
        BigDecimal amount,
        String currency) {

    public RefundJournalRecord {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        Objects.requireNonNull(journalId, "journalId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }
}
