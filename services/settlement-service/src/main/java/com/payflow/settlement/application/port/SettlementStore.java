package com.payflow.settlement.application.port;

import com.payflow.settlement.application.*;
import com.payflow.settlement.domain.SettlementContribution;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementStore {

    boolean recordInbox(SettlementFact fact, Instant processedAt);

    boolean insertFinancialFact(SettlementFact fact, Instant receivedAt);

    ContributionResult applyContribution(
            UUID eventId, SettlementContribution contribution, LocalDate businessDate, Instant now);

    void recordDuplicateBusinessFact(SettlementFact fact, LocalDate businessDate, Instant now);

    List<SettlementBatchView> calculate(
            LocalDate settlementDate, String actorId, String correlationId, Instant now);

    CompletionResult complete(
            UUID batchId, String actorId, String correlationId, Instant now);

    ReconciliationRunResult reconcile(
            LocalDate settlementDate, String actorId, String correlationId, Instant now);

    PageResult<SettlementBatchView> findBatches(
            UUID merchantId, LocalDate from, LocalDate to, int page, int size);

    Optional<SettlementBatchView> findBatch(UUID merchantId, UUID batchId);

    List<SettlementItemView> findItems(UUID batchId);

    PageResult<ReconciliationIssueView> findIssues(
            LocalDate settlementDate, String status, int page, int size);

    enum ContributionResult {
        APPLIED,
        BUSINESS_DUPLICATE,
        LATE
    }

    record CompletionResult(SettlementBatchView batch, boolean newlyCompleted) {
    }
}
