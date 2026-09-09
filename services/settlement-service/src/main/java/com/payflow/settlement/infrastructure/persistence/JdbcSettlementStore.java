package com.payflow.settlement.infrastructure.persistence;

import com.payflow.settlement.application.*;
import com.payflow.settlement.application.port.SettlementStore;
import com.payflow.settlement.domain.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
final class JdbcSettlementStore implements SettlementStore {

    private static final String CONSUMER_NAME = "settlement-financial-facts-v1";
    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private final JdbcClient jdbc;
    private final ZoneId businessZone;

    JdbcSettlementStore(JdbcClient jdbc, SettlementRuntimeProperties properties) {
        this.jdbc = jdbc;
        this.businessZone = properties.businessZone();
    }

    @Override
    public boolean recordInbox(SettlementFact fact, Instant processedAt) {
        return jdbc.sql("""
                insert into settlement_runtime.processed_events(
                  event_id,consumer_name,event_type,aggregate_id,processed_at)
                values(:event,:consumer,:type,:aggregate,:processed)
                on conflict(event_id,consumer_name) do nothing
                """)
                .param("event", fact.eventId())
                .param("consumer", CONSUMER_NAME)
                .param("type", fact.eventType())
                .param("aggregate", fact.aggregateId())
                .param("processed", utc(processedAt))
                .update() == 1;
    }

    @Override
    public boolean insertFinancialFact(SettlementFact fact, Instant receivedAt) {
        return jdbc.sql("""
                insert into settlement.financial_facts(
                  event_id,event_type,event_version,fact_type,reference_id,payment_id,merchant_id,
                  amount,fee_amount,currency,occurred_at,received_at)
                values(:event,:eventType,:version,:factType,:reference,:payment,cast(:merchant as uuid),
                  :amount,:fee,:currency,:occurred,:received)
                on conflict(fact_type,reference_id) do nothing
                """)
                .param("event", fact.eventId())
                .param("eventType", fact.eventType())
                .param("version", fact.eventVersion())
                .param("factType", fact.factType().name())
                .param("reference", fact.referenceId())
                .param("payment", fact.paymentId())
                .param("merchant", fact.merchantId() == null ? null : fact.merchantId().toString())
                .param("amount", fact.amount())
                .param("fee", fact.feeAmount())
                .param("currency", fact.currency())
                .param("occurred", utc(fact.occurredAt()))
                .param("received", utc(receivedAt))
                .update() == 1;
    }

    @Override
    public ContributionResult applyContribution(
            UUID eventId, SettlementContribution item, LocalDate businessDate, Instant now) {
        UUID proposedBatchId = UUID.randomUUID();
        jdbc.sql("""
                insert into settlement.settlement_batches(
                  id,merchant_id,settlement_date,currency,gross_amount,refund_amount,fee_amount,net_amount,
                  transaction_count,refund_count,status,created_at,version)
                values(:id,:merchant,:date,:currency,0,0,0,0,0,0,'OPEN',:now,0)
                on conflict(merchant_id,settlement_date,currency) do nothing
                """)
                .param("id", proposedBatchId)
                .param("merchant", item.merchantId())
                .param("date", businessDate)
                .param("currency", item.currency())
                .param("now", utc(now))
                .update();

        BatchIdentity batch = jdbc.sql("""
                select id,status from settlement.settlement_batches
                where merchant_id=:merchant and settlement_date=:date and currency=:currency
                for update
                """)
                .param("merchant", item.merchantId())
                .param("date", businessDate)
                .param("currency", item.currency())
                .query((rs, row) -> new BatchIdentity(
                        rs.getObject("id", UUID.class), SettlementStatus.valueOf(rs.getString("status"))))
                .single();

        if (batch.status() != SettlementStatus.OPEN) {
            upsertIssue(
                    "LATE_SETTLEMENT_EVENT:" + item.referenceType() + ":" + item.referenceId(),
                    "LATE_SETTLEMENT_EVENT",
                    item.referenceId(),
                    item.paymentId(),
                    item.merchantId(),
                    businessDate,
                    item.currency(),
                    item.currency(),
                    item.referenceType() == SettlementReferenceType.PAYMENT
                            ? item.grossAmount() : item.refundAmount(),
                    null,
                    null,
                    now);
            return ContributionResult.LATE;
        }

        int inserted = jdbc.sql("""
                insert into settlement.settlement_items(
                  id,batch_id,event_id,reference_type,reference_id,payment_id,gross_amount,refund_amount,
                  fee_amount,net_amount,currency,occurred_at,created_at)
                values(:id,:batch,:event,:type,:reference,:payment,:gross,:refund,:fee,:net,:currency,:occurred,:now)
                on conflict(reference_type,reference_id) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("batch", batch.id())
                .param("event", eventId)
                .param("type", item.referenceType().name())
                .param("reference", item.referenceId())
                .param("payment", item.paymentId())
                .param("gross", item.grossAmount())
                .param("refund", item.refundAmount())
                .param("fee", item.feeAmount())
                .param("net", item.netAmount())
                .param("currency", item.currency())
                .param("occurred", utc(item.occurredAt()))
                .param("now", utc(now))
                .update();
        if (inserted == 0) {
            return ContributionResult.BUSINESS_DUPLICATE;
        }

        jdbc.sql("""
                update settlement.settlement_batches set
                  gross_amount=gross_amount+:gross,
                  refund_amount=refund_amount+:refund,
                  fee_amount=fee_amount+:fee,
                  net_amount=net_amount+:net,
                  transaction_count=transaction_count+:paymentCount,
                  refund_count=refund_count+:refundCount,
                  version=version+1
                where id=:id and status='OPEN'
                """)
                .param("gross", item.grossAmount())
                .param("refund", item.refundAmount())
                .param("fee", item.feeAmount())
                .param("net", item.netAmount())
                .param("paymentCount", item.referenceType() == SettlementReferenceType.PAYMENT ? 1 : 0)
                .param("refundCount", item.referenceType() == SettlementReferenceType.REFUND ? 1 : 0)
                .param("id", batch.id())
                .update();
        return ContributionResult.APPLIED;
    }

    @Override
    public void recordDuplicateBusinessFact(
            SettlementFact fact, LocalDate businessDate, Instant now) {
        upsertIssue(
                "DUPLICATE_BUSINESS_FACT:" + fact.factType() + ":" + fact.referenceId(),
                "DUPLICATE_BUSINESS_FACT",
                fact.referenceId(),
                fact.paymentId(),
                fact.merchantId(),
                businessDate,
                fact.currency(),
                fact.currency(),
                fact.amount(),
                fact.amount(),
                null,
                now);
    }

    @Override
    public List<SettlementBatchView> calculate(
            LocalDate settlementDate, String actorId, String correlationId, Instant now) {
        List<UUID> ids = jdbc.sql("""
                select id from settlement.settlement_batches
                where settlement_date=:date order by merchant_id,currency for update
                """)
                .param("date", settlementDate)
                .query(UUID.class)
                .list();
        List<SettlementBatchView> results = new ArrayList<>();
        for (UUID id : ids) {
            SettlementBatchView before = findBatchAny(id);
            if (before.status() == SettlementStatus.READY
                    || before.status() == SettlementStatus.COMPLETED) {
                results.add(before);
                continue;
            }
            jdbc.sql("""
                    update settlement.settlement_batches set status='CALCULATING',version=version+1
                    where id=:id and status in ('OPEN','FAILED')
                    """)
                    .param("id", id)
                    .update();
            TotalsRow actual = totals(id);
            boolean matches = totalsMatch(before, actual);
            SettlementStatus target = matches ? SettlementStatus.READY : SettlementStatus.FAILED;
            jdbc.sql("""
                    update settlement.settlement_batches set status=:status,
                      calculated_at=:now,version=version+1 where id=:id
                    """)
                    .param("status", target.name())
                    .param("now", utc(now))
                    .param("id", id)
                    .update();
            SettlementBatchView after = findBatchAny(id);
            insertAudit(actorId, "SETTLEMENT_CALCULATE", "SETTLEMENT_BATCH", id,
                    matches ? "TOTALS_VERIFIED" : "TOTALS_MISMATCH",
                    before.status(), target, after, correlationId, now);
            if (!matches) {
                upsertIssue(
                        "BATCH_TOTAL_MISMATCH:" + id,
                        "BATCH_TOTAL_MISMATCH",
                        id,
                        id,
                        before.merchantId(),
                        settlementDate,
                        before.currency(),
                        before.currency(),
                        before.netAmount(),
                        actual.net(),
                        null,
                        now);
            }
            results.add(after);
        }
        return List.copyOf(results);
    }

    @Override
    public CompletionResult complete(
            UUID batchId, String actorId, String correlationId, Instant now) {
        SettlementBatchView before = jdbc.sql("""
                select * from settlement.settlement_batches where id=:id for update
                """)
                .param("id", batchId)
                .query(JdbcSettlementStore::batch)
                .optional()
                .orElseThrow(() -> new SettlementNotFoundException(batchId));
        if (before.status() == SettlementStatus.COMPLETED) {
            return new CompletionResult(before, false);
        }
        if (before.status() != SettlementStatus.READY) {
            throw new SettlementStateException(
                    "Settlement batch must be READY before completion, was " + before.status());
        }
        jdbc.sql("""
                update settlement.settlement_batches set status='COMPLETED',completed_at=:now,
                  version=version+1 where id=:id and status='READY'
                """)
                .param("now", utc(now))
                .param("id", batchId)
                .update();
        SettlementBatchView after = findBatchAny(batchId);
        insertAudit(actorId, "SETTLEMENT_COMPLETE", "SETTLEMENT_BATCH", batchId,
                "MOCK_PAYOUT_COMPLETED", before.status(), after.status(), after, correlationId, now);
        return new CompletionResult(after, true);
    }

    @Override
    public ReconciliationRunResult reconcile(
            LocalDate settlementDate, String actorId, String correlationId, Instant now) {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                insert into settlement.reconciliation_runs(
                  id,settlement_date,actor_id,correlation_id,status,started_at)
                values(:id,:date,:actor,:correlation,'RUNNING',:now)
                """)
                .param("id", runId)
                .param("date", settlementDate)
                .param("actor", actorId)
                .param("correlation", correlationId)
                .param("now", utc(now))
                .update();

        List<ItemForReconciliation> items = reconciliationItems(settlementDate);
        for (ItemForReconciliation item : items) {
            if (item.type() == SettlementReferenceType.PAYMENT) {
                compareFact(item, SettlementFactType.LEDGER_PAYMENT_POSTED, "MISSING_LEDGER_PAYMENT", runId, now);
                compareFact(item, SettlementFactType.ACCOUNT_FUNDS_CAPTURED, "MISSING_ACCOUNT_CAPTURE", runId, now);
            } else {
                compareFact(item, SettlementFactType.LEDGER_REFUND_POSTED, "MISSING_LEDGER_REFUND", runId, now);
                compareFact(item, SettlementFactType.ACCOUNT_REFUND_CREDITED, "MISSING_ACCOUNT_REFUND_CREDIT", runId, now);
            }
        }
        detectUnsettledFacts(settlementDate, runId, now);
        detectBatchTotalIssues(settlementDate, runId, now);

        int resolved = jdbc.sql("""
                update settlement.reconciliation_issues set status='RESOLVED',resolved_at=:now
                where settlement_date=:date and status='OPEN'
                  and issue_type<>'DUPLICATE_BUSINESS_FACT'
                  and (last_seen_run_id is null or last_seen_run_id<>:run)
                """)
                .param("now", utc(now))
                .param("date", settlementDate)
                .param("run", runId)
                .update();
        int open = jdbc.sql("""
                select count(*) from settlement.reconciliation_issues
                where settlement_date=:date and status='OPEN'
                """)
                .param("date", settlementDate)
                .query(Integer.class)
                .single();
        jdbc.sql("""
                update settlement.reconciliation_runs set status='COMPLETED',checked_items=:checked,
                  open_issue_count=:open,resolved_issue_count=:resolved,completed_at=:now where id=:id
                """)
                .param("checked", items.size())
                .param("open", open)
                .param("resolved", resolved)
                .param("now", utc(now))
                .param("id", runId)
                .update();
        insertAudit(actorId, "RECONCILIATION_RUN", "RECONCILIATION_RUN", runId,
                open == 0 ? "MATCHED" : "ISSUES_DETECTED", null, null, null, correlationId, now);
        return new ReconciliationRunResult(runId, settlementDate, items.size(), open, resolved);
    }

    @Override
    public PageResult<SettlementBatchView> findBatches(
            UUID merchantId, LocalDate from, LocalDate to, int page, int size) {
        List<SettlementBatchView> content = jdbc.sql("""
                select * from settlement.settlement_batches
                where merchant_id=:merchant and settlement_date between :from and :to
                order by settlement_date desc,id limit :limit offset :offset
                """)
                .param("merchant", merchantId)
                .param("from", from)
                .param("to", to)
                .param("limit", size)
                .param("offset", page * size)
                .query(JdbcSettlementStore::batch)
                .list();
        long total = jdbc.sql("""
                select count(*) from settlement.settlement_batches
                where merchant_id=:merchant and settlement_date between :from and :to
                """)
                .param("merchant", merchantId)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .single();
        return new PageResult<>(content, page, size, total);
    }

    @Override
    public Optional<SettlementBatchView> findBatch(UUID merchantId, UUID batchId) {
        return jdbc.sql("""
                select * from settlement.settlement_batches where id=:id and merchant_id=:merchant
                """)
                .param("id", batchId)
                .param("merchant", merchantId)
                .query(JdbcSettlementStore::batch)
                .optional();
    }

    @Override
    public List<SettlementItemView> findItems(UUID batchId) {
        return jdbc.sql("""
                select * from settlement.settlement_items where batch_id=:batch order by occurred_at,id
                """)
                .param("batch", batchId)
                .query((rs, row) -> new SettlementItemView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        SettlementReferenceType.valueOf(rs.getString("reference_type")),
                        rs.getObject("reference_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("refund_amount"),
                        rs.getBigDecimal("fee_amount"),
                        rs.getBigDecimal("net_amount"),
                        rs.getString("currency"),
                        instant(rs, "occurred_at")))
                .list();
    }

    @Override
    public PageResult<ReconciliationIssueView> findIssues(
            LocalDate settlementDate, String status, int page, int size) {
        List<ReconciliationIssueView> content = jdbc.sql("""
                select * from settlement.reconciliation_issues
                where settlement_date=:date and status=:status
                order by last_detected_at desc,id limit :limit offset :offset
                """)
                .param("date", settlementDate)
                .param("status", status)
                .param("limit", size)
                .param("offset", page * size)
                .query(JdbcSettlementStore::issue)
                .list();
        long total = jdbc.sql("""
                select count(*) from settlement.reconciliation_issues
                where settlement_date=:date and status=:status
                """)
                .param("date", settlementDate)
                .param("status", status)
                .query(Long.class)
                .single();
        return new PageResult<>(content, page, size, total);
    }

    private void compareFact(
            ItemForReconciliation item,
            SettlementFactType expectedType,
            String missingType,
            UUID runId,
            Instant now) {
        UUID factReference = item.type() == SettlementReferenceType.PAYMENT
                ? item.paymentId() : item.referenceId();
        Optional<FactAmount> actual = jdbc.sql("""
                select amount,currency from settlement.financial_facts
                where fact_type=:type and reference_id=:reference
                """)
                .param("type", expectedType.name())
                .param("reference", factReference)
                .query((rs, row) -> new FactAmount(rs.getBigDecimal("amount"), rs.getString("currency")))
                .optional();
        BigDecimal expectedAmount = item.type() == SettlementReferenceType.PAYMENT
                ? item.grossAmount() : item.refundAmount();
        if (actual.isEmpty()) {
            upsertIssue(
                    missingType + ":" + item.referenceId(),
                    missingType,
                    item.referenceId(),
                    item.paymentId(),
                    item.merchantId(),
                    item.date(),
                    item.currency(),
                    null,
                    expectedAmount,
                    null,
                    runId,
                    now);
            return;
        }
        FactAmount fact = actual.orElseThrow();
        if (fact.amount().compareTo(expectedAmount) != 0 || !fact.currency().equals(item.currency())) {
            upsertIssue(
                    "FINANCIAL_FACT_MISMATCH:" + expectedType + ":" + item.referenceId(),
                    "FINANCIAL_FACT_MISMATCH",
                    item.referenceId(),
                    item.paymentId(),
                    item.merchantId(),
                    item.date(),
                    item.currency(),
                    fact.currency(),
                    expectedAmount,
                    fact.amount(),
                    runId,
                    now);
        }
    }

    private void detectUnsettledFacts(LocalDate date, UUID runId, Instant now) {
        List<UnsettledFact> facts = jdbc.sql("""
                select f.reference_id,f.payment_id,f.merchant_id,f.amount,f.currency,f.fact_type
                from settlement.financial_facts f
                left join settlement.settlement_items i on i.event_id=f.event_id
                where f.fact_type in ('PAYMENT_SUCCEEDED','REFUND_SUCCEEDED')
                  and (f.occurred_at at time zone :zone)::date=:date and i.id is null
                """)
                .param("zone", businessZone.getId())
                .param("date", date)
                .query((rs, row) -> new UnsettledFact(
                        rs.getObject("reference_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("fact_type")))
                .list();
        for (UnsettledFact fact : facts) {
            upsertIssue(
                    "UNSETTLED_FACT:" + fact.factType() + ":" + fact.referenceId(),
                    "UNSETTLED_FACT",
                    fact.referenceId(),
                    fact.paymentId(),
                    fact.merchantId(),
                    date,
                    fact.currency(),
                    null,
                    fact.amount(),
                    null,
                    runId,
                    now);
        }
    }

    private void detectBatchTotalIssues(LocalDate date, UUID runId, Instant now) {
        List<SettlementBatchView> batches = jdbc.sql("""
                select * from settlement.settlement_batches where settlement_date=:date
                """)
                .param("date", date)
                .query(JdbcSettlementStore::batch)
                .list();
        for (SettlementBatchView batch : batches) {
            TotalsRow actual = totals(batch.id());
            if (!totalsMatch(batch, actual)) {
                upsertIssue(
                        "BATCH_TOTAL_MISMATCH:" + batch.id(),
                        "BATCH_TOTAL_MISMATCH",
                        batch.id(),
                        null,
                        batch.merchantId(),
                        date,
                        batch.currency(),
                        batch.currency(),
                        batch.netAmount(),
                        actual.net(),
                        runId,
                        now);
            }
        }
    }

    private List<ItemForReconciliation> reconciliationItems(LocalDate date) {
        return jdbc.sql("""
                select i.reference_type,i.reference_id,i.payment_id,b.merchant_id,b.settlement_date,
                  i.gross_amount,i.refund_amount,i.currency
                from settlement.settlement_items i
                join settlement.settlement_batches b on b.id=i.batch_id
                where b.settlement_date=:date order by i.reference_type,i.reference_id
                """)
                .param("date", date)
                .query((rs, row) -> new ItemForReconciliation(
                        SettlementReferenceType.valueOf(rs.getString("reference_type")),
                        rs.getObject("reference_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getObject("settlement_date", LocalDate.class),
                        rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("refund_amount"),
                        rs.getString("currency")))
                .list();
    }

    private void upsertIssue(
            String issueKey,
            String issueType,
            UUID referenceId,
            UUID paymentId,
            UUID merchantId,
            LocalDate settlementDate,
            String expectedCurrency,
            String actualCurrency,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            UUID runId,
            Instant now) {
        jdbc.sql("""
                insert into settlement.reconciliation_issues(
                  id,issue_key,issue_type,reference_id,payment_id,merchant_id,settlement_date,
                  expected_currency,actual_currency,expected_amount,actual_amount,status,
                  first_detected_at,last_detected_at,resolved_at,last_seen_run_id)
                values(:id,:key,:type,:reference,:payment,cast(:merchant as uuid),:date,
                  :expectedCurrency,:actualCurrency,:expectedAmount,:actualAmount,'OPEN',:now,:now,null,cast(:run as uuid))
                on conflict(issue_key) do update set
                  expected_currency=excluded.expected_currency,actual_currency=excluded.actual_currency,
                  expected_amount=excluded.expected_amount,actual_amount=excluded.actual_amount,
                  status='OPEN',last_detected_at=excluded.last_detected_at,resolved_at=null,
                  last_seen_run_id=excluded.last_seen_run_id
                """)
                .param("id", UUID.randomUUID())
                .param("key", issueKey)
                .param("type", issueType)
                .param("reference", referenceId)
                .param("payment", paymentId)
                .param("merchant", merchantId == null ? null : merchantId.toString())
                .param("date", settlementDate)
                .param("expectedCurrency", expectedCurrency)
                .param("actualCurrency", actualCurrency)
                .param("expectedAmount", expectedAmount)
                .param("actualAmount", actualAmount)
                .param("now", utc(now))
                .param("run", runId == null ? null : runId.toString())
                .update();
    }

    private void insertAudit(
            String actor,
            String action,
            String resourceType,
            UUID resourceId,
            String decisionCode,
            SettlementStatus beforeStatus,
            SettlementStatus afterStatus,
            SettlementBatchView batch,
            String correlationId,
            Instant now) {
        jdbc.sql("""
                insert into settlement.audit_records(
                  id,actor_id,action,resource_type,resource_id,decision_code,before_status,after_status,
                  gross_amount,refund_amount,fee_amount,net_amount,correlation_id,created_at)
                values(:id,:actor,:action,:resourceType,:resource,:decision,:before,:after,
                  :gross,:refund,:fee,:net,:correlation,:now)
                """)
                .param("id", UUID.randomUUID())
                .param("actor", actor)
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resource", resourceId)
                .param("decision", decisionCode)
                .param("before", beforeStatus == null ? null : beforeStatus.name())
                .param("after", afterStatus == null ? null : afterStatus.name())
                .param("gross", batch == null ? ZERO : batch.grossAmount())
                .param("refund", batch == null ? ZERO : batch.refundAmount())
                .param("fee", batch == null ? ZERO : batch.feeAmount())
                .param("net", batch == null ? ZERO : batch.netAmount())
                .param("correlation", correlationId)
                .param("now", utc(now))
                .update();
    }

    private SettlementBatchView findBatchAny(UUID id) {
        return jdbc.sql("select * from settlement.settlement_batches where id=:id")
                .param("id", id)
                .query(JdbcSettlementStore::batch)
                .single();
    }

    private TotalsRow totals(UUID batchId) {
        return jdbc.sql("""
                select coalesce(sum(gross_amount),0) gross,coalesce(sum(refund_amount),0) refund,
                  coalesce(sum(fee_amount),0) fee,coalesce(sum(net_amount),0) net,
                  count(*) filter(where reference_type='PAYMENT') payment_count,
                  count(*) filter(where reference_type='REFUND') refund_count
                from settlement.settlement_items where batch_id=:batch
                """)
                .param("batch", batchId)
                .query((rs, row) -> new TotalsRow(
                        rs.getBigDecimal("gross").setScale(4),
                        rs.getBigDecimal("refund").setScale(4),
                        rs.getBigDecimal("fee").setScale(4),
                        rs.getBigDecimal("net").setScale(4),
                        rs.getInt("payment_count"),
                        rs.getInt("refund_count")))
                .single();
    }

    private static boolean totalsMatch(SettlementBatchView batch, TotalsRow actual) {
        return batch.grossAmount().compareTo(actual.gross()) == 0
                && batch.refundAmount().compareTo(actual.refund()) == 0
                && batch.feeAmount().compareTo(actual.fee()) == 0
                && batch.netAmount().compareTo(actual.net()) == 0
                && batch.transactionCount() == actual.paymentCount()
                && batch.refundCount() == actual.refundCount();
    }

    private static SettlementBatchView batch(ResultSet rs, int row) throws SQLException {
        return new SettlementBatchView(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("settlement_date", LocalDate.class),
                rs.getString("currency"),
                rs.getBigDecimal("gross_amount"),
                rs.getBigDecimal("refund_amount"),
                rs.getBigDecimal("fee_amount"),
                rs.getBigDecimal("net_amount"),
                rs.getInt("transaction_count"),
                rs.getInt("refund_count"),
                SettlementStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                nullableInstant(rs, "calculated_at"),
                nullableInstant(rs, "completed_at"),
                rs.getLong("version"));
    }

    private static ReconciliationIssueView issue(ResultSet rs, int row) throws SQLException {
        return new ReconciliationIssueView(
                rs.getObject("id", UUID.class),
                rs.getString("issue_key"),
                rs.getString("issue_type"),
                rs.getObject("reference_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("settlement_date", LocalDate.class),
                rs.getString("expected_currency"),
                rs.getString("actual_currency"),
                rs.getBigDecimal("expected_amount"),
                rs.getBigDecimal("actual_amount"),
                rs.getString("status"),
                instant(rs, "first_detected_at"),
                instant(rs, "last_detected_at"),
                nullableInstant(rs, "resolved_at"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record BatchIdentity(UUID id, SettlementStatus status) {
    }

    private record TotalsRow(
            BigDecimal gross,
            BigDecimal refund,
            BigDecimal fee,
            BigDecimal net,
            int paymentCount,
            int refundCount) {
    }

    private record FactAmount(BigDecimal amount, String currency) {
    }

    private record ItemForReconciliation(
            SettlementReferenceType type,
            UUID referenceId,
            UUID paymentId,
            UUID merchantId,
            LocalDate date,
            BigDecimal grossAmount,
            BigDecimal refundAmount,
            String currency) {
    }

    private record UnsettledFact(
            UUID referenceId,
            UUID paymentId,
            UUID merchantId,
            BigDecimal amount,
            String currency,
            String factType) {
    }
}
