package com.payflow.accountledger.ledger.application.payment;

import com.payflow.accountledger.ledger.domain.model.EntryDirection;
import com.payflow.accountledger.ledger.domain.model.Journal;
import com.payflow.accountledger.ledger.domain.model.LedgerEntry;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates the balanced principal-only PAYMENT_CAPTURE journal used by event contract v1. */
public final class PaymentJournalFactory {

    public static final String REFERENCE_TYPE = "PAYMENT";
    public static final String JOURNAL_TYPE = "PAYMENT_CAPTURE";

    public Journal post(
            UUID journalId,
            UUID customerLedgerAccountId,
            UUID merchantLedgerAccountId,
            UUID debitEntryId,
            UUID creditEntryId,
            LedgerPostPaymentRequestedData request,
            Instant createdAt) {
        Objects.requireNonNull(request, "request");
        return Journal.post(
                journalId,
                REFERENCE_TYPE,
                request.paymentId(),
                JOURNAL_TYPE,
                "Payment principal capture",
                List.of(
                        new LedgerEntry(
                                debitEntryId,
                                customerLedgerAccountId,
                                EntryDirection.DEBIT,
                                request.amount(),
                                request.currency()),
                        new LedgerEntry(
                                creditEntryId,
                                merchantLedgerAccountId,
                                EntryDirection.CREDIT,
                                request.amount(),
                                request.currency())),
                createdAt,
                createdAt);
    }
}
