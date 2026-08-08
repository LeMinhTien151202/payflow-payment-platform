package com.payflow.ledger.application.port;

import com.payflow.ledger.domain.model.Journal;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJournalStore {

    Optional<PaymentJournalRecord> findByPaymentId(UUID paymentId);

    void save(Journal journal, LedgerPostPaymentRequestedData request);
}
