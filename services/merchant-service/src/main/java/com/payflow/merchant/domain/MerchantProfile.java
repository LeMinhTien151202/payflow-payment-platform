package com.payflow.merchant.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MerchantProfile(UUID id, String code, String name, MerchantStatus status,
        String defaultCurrency, BigDecimal feeRate, BigDecimal maxTransactionAmount,
        Instant createdAt, Instant updatedAt, long version) {
    public MerchantProfile {
        Objects.requireNonNull(id); Objects.requireNonNull(code); Objects.requireNonNull(name);
        Objects.requireNonNull(status); Objects.requireNonNull(defaultCurrency);
        Objects.requireNonNull(feeRate); Objects.requireNonNull(maxTransactionAmount);
        if (code.isBlank() || name.isBlank()) throw new IllegalArgumentException("Merchant code and name are required");
        if (!"VND".equals(defaultCurrency)) throw new IllegalArgumentException("Only VND is supported");
        if (feeRate.signum()<0 || feeRate.compareTo(BigDecimal.ONE)>0) throw new IllegalArgumentException("Fee rate must be between 0 and 1");
        if (maxTransactionAmount.signum()<=0) throw new IllegalArgumentException("Merchant limit must be positive");
    }
}
