package com.payflow.settlement.application;

import com.payflow.settlement.application.port.SettlementStore;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class SettlementQueryService {

    private final SettlementStore store;

    public SettlementQueryService(SettlementStore store) {
        this.store = store;
    }

    public PageResult<SettlementBatchView> batches(
            UUID merchantId, LocalDate from, LocalDate to, int page, int size) {
        if (merchantId == null || from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("merchantId and valid inclusive date range are required");
        }
        validatePage(page, size);
        return store.findBatches(merchantId, from, to, page, size);
    }

    public SettlementBatchDetail batch(UUID merchantId, UUID batchId) {
        var batch = store.findBatch(merchantId, batchId)
                .orElseThrow(() -> new SettlementNotFoundException(batchId));
        List<SettlementItemView> items = store.findItems(batchId);
        return new SettlementBatchDetail(batch, items);
    }

    public PageResult<ReconciliationIssueView> issues(
            LocalDate date, String status, int page, int size) {
        if (date == null || !("OPEN".equals(status) || "RESOLVED".equals(status))) {
            throw new IllegalArgumentException("date and status OPEN/RESOLVED are required");
        }
        validatePage(page, size);
        return store.findIssues(date, status, page, size);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and 100");
        }
    }

    public record SettlementBatchDetail(
            SettlementBatchView batch, List<SettlementItemView> items) {
        public SettlementBatchDetail {
            items = List.copyOf(items);
        }
    }
}
