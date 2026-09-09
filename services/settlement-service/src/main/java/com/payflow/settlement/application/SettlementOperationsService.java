package com.payflow.settlement.application;

import com.payflow.events.PayFlowTopics;
import com.payflow.events.settlement.SettlementCompletedData;
import com.payflow.events.settlement.SettlementEvents;
import com.payflow.settlement.application.port.OutboxAppender;
import com.payflow.settlement.application.port.SettlementStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class SettlementOperationsService {

    private final SettlementStore store;
    private final OutboxAppender outbox;
    private final SettlementMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public SettlementOperationsService(
            SettlementStore store,
            OutboxAppender outbox,
            SettlementMetrics metrics,
            Clock clock,
            TransactionTemplate transactions) {
        this.store = store;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
        this.transactions = transactions;
    }

    public List<SettlementBatchView> calculate(
            LocalDate date, String actorId, String correlationId) {
        requireOperationContext(actorId, correlationId);
        return Objects.requireNonNull(transactions.execute(status ->
                store.calculate(date, actorId, correlationId, clock.instant())));
    }

    public SettlementBatchView complete(
            UUID batchId, String actorId, String correlationId) {
        requireOperationContext(actorId, correlationId);
        var result = Objects.requireNonNull(transactions.execute(status -> {
            var completed = store.complete(batchId, actorId, correlationId, clock.instant());
            if (completed.newlyCompleted()) {
                SettlementBatchView batch = completed.batch();
                outbox.append(
                        SettlementEvents.SETTLEMENT_COMPLETED,
                        PayFlowTopics.SETTLEMENT_EVENTS,
                        batch.id().toString(),
                        correlationId,
                        batch.completedAt(),
                        new SettlementCompletedData(
                                batch.id(),
                                batch.merchantId(),
                                batch.settlementDate(),
                                batch.currency(),
                                batch.grossAmount(),
                                batch.refundAmount(),
                                batch.feeAmount(),
                                batch.netAmount(),
                                batch.transactionCount(),
                                batch.refundCount(),
                                batch.completedAt()));
            }
            return completed;
        }));
        if (result.newlyCompleted()) {
            metrics.batchCompleted();
        }
        return result.batch();
    }

    public ReconciliationRunResult reconcile(
            LocalDate date, String actorId, String correlationId) {
        requireOperationContext(actorId, correlationId);
        var result = Objects.requireNonNull(transactions.execute(status ->
                store.reconcile(date, actorId, correlationId, clock.instant())));
        metrics.reconciliationIssues(result.openIssueCount());
        return result;
    }

    private static void requireOperationContext(String actorId, String correlationId) {
        if (actorId == null || actorId.isBlank() || actorId.length() > 255) {
            throw new IllegalArgumentException("actor subject is required");
        }
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 100) {
            throw new IllegalArgumentException("safe correlation id is required");
        }
    }
}
