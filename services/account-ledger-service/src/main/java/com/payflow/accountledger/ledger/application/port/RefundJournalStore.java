package com.payflow.accountledger.ledger.application.port;

import com.payflow.accountledger.ledger.domain.model.Journal;
import com.payflow.events.refund.RefundRequestedData;
import java.util.Optional;
import java.util.UUID;

public interface RefundJournalStore {
    Optional<RefundJournalRecord> findByRefundId(UUID refundId);

    void save(Journal journal, RefundRequestedData request);
}
