package com.payflow.accountledger.ledger.application.port;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountDirectory {
    Optional<LedgerAccountPair> findRefundAccounts(
            UUID merchantId, UUID sourceAccountId, String currency);
}
