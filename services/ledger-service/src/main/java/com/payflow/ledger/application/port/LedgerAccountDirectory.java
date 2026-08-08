package com.payflow.ledger.application.port;

import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountDirectory {
    Optional<LedgerAccountPair> findPaymentAccounts(
            UUID customerId, UUID merchantId, String currency);

    Optional<LedgerAccountPair> findRefundAccounts(
            UUID merchantId, UUID sourceAccountId, String currency);
}
