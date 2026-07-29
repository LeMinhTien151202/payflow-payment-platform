package com.payflow.accountledger.ledger.application.refund;

import com.payflow.accountledger.ledger.domain.model.EntryDirection;
import com.payflow.accountledger.ledger.domain.model.Journal;
import com.payflow.accountledger.ledger.domain.model.LedgerEntry;
import com.payflow.events.refund.RefundRequestedData;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates the immutable principal-only reversal journal fixed by ADR-021. */
public final class RefundJournalFactory {

    public static final String REFERENCE_TYPE = "REFUND";
    public static final String JOURNAL_TYPE = "REFUND_REVERSAL";

    public Journal post(
            UUID journalId,
            UUID merchantLedgerAccountId,
            UUID customerLedgerAccountId,
            UUID debitEntryId,
            UUID creditEntryId,
            RefundRequestedData request,
            Instant createdAt) {
        Objects.requireNonNull(request, "request");
        return Journal.post(
                journalId,
                REFERENCE_TYPE,
                request.refundId(),
                JOURNAL_TYPE,
                "Refund principal reversal",
                List.of(
                        new LedgerEntry(
                                debitEntryId,
                                merchantLedgerAccountId,
                                EntryDirection.DEBIT,
                                request.amount(),
                                request.currency()),
                        new LedgerEntry(
                                creditEntryId,
                                customerLedgerAccountId,
                                EntryDirection.CREDIT,
                                request.amount(),
                                request.currency())),
                // Both timestamps come from Ledger's local clock. Causation, not wall-clock
                // comparison across services, establishes that this journal follows the request.
                createdAt,
                createdAt);
    }
}
