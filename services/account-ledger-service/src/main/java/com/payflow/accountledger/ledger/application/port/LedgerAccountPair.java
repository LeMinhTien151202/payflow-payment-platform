package com.payflow.accountledger.ledger.application.port;

import java.util.Objects;
import java.util.UUID;

public record LedgerAccountPair(UUID merchantAccountId, UUID customerAccountId) {
    public LedgerAccountPair {
        Objects.requireNonNull(merchantAccountId, "merchantAccountId");
        Objects.requireNonNull(customerAccountId, "customerAccountId");
    }
}
