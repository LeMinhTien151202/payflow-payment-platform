# Settlement and reconciliation runbook

## Scope and ownership

Owner: `payments-platform`. This runbook covers `PayFlowSettlementReconciliationIssues`,
`PayFlowSettlementLateEvents`, and `PayFlowSettlementOutboxStalled`. PayFlow is a sandbox: never
copy production credentials or real financial/PII data into the investigation.

## Triage

1. Capture the alert time, cluster, settlement date, merchant id, batch id, correlation id and event
   id. Do not capture JWTs, database URLs containing credentials, or event payloads containing PII.
2. Check readiness, consumer lag and `payflow_outbox_pending_age_seconds`. An outbox alert is a
   delivery incident; a reconciliation alert is a data discrepancy and must not be auto-corrected.
3. Query `GET /api/v1/operations/reconciliation/issues?date=YYYY-MM-DD&status=OPEN` with a token that
   has `reconciliation:read`. Keep the returned issue ids as audit evidence.
4. Correlate structured logs by `correlationId`, `paymentId` and `eventId`. Never update settlement
   tables manually.

## Recovery

- `LATE_SETTLEMENT_EVENT`: leave the completed batch immutable; investigate producer delay and create
  the next compensating settlement decision through a reviewed change.
- `MISSING_*`: restore the affected consumer/outbox path, then invoke
  `POST /api/v1/operations/reconciliation/run?date=...` with `reconciliation:run`.
- `BATCH_TOTAL_MISMATCH`: do not complete the batch. Preserve status `FAILED`, export evidence and
  investigate the offending immutable item/fact.
- stalled outbox: restore Kafka connectivity or broker capacity. The publisher reclaims expired
  leases and preserves the same event id; do not reinsert rows by hand.

## Verification and closure

Run reconciliation again. Close only when the new run has zero open issues or every remaining issue
has an explicit reviewed disposition. Record timestamps, commands, issue ids and links to metrics.
Do not claim recovery performance or RTO without a measured report.
