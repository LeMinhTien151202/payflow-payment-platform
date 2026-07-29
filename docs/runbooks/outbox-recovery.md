# Outbox publisher recovery

Use this runbook when `payflow.outbox.failed.terminal` increases, the oldest pending age exceeds
60 seconds, or an operator finds a durable business event that is not reaching Kafka. The protocol
applies to both `payment.outbox_events` and `account_ledger.outbox_events`.

## Safety boundary

- Treat a Kafka send timeout as ambiguous: the broker may already have accepted the event.
- Consumers must deduplicate the stable `eventId`; PayFlow does not claim exactly-once delivery.
- Never edit `payload`, `headers`, `event_type`, `aggregate_id`, `topic` or `created_at`.
- Never bulk-requeue rows. Resolve and approve one exact event id at a time.
- Take no write action until the Kafka/topic/configuration root cause is fixed.

## 1. Inspect without mutation

Connect to the database owned by the affected service and select the appropriate schema:

```sql
SELECT status, count(*)
  FROM account_ledger.outbox_events
 GROUP BY status
 ORDER BY status;

SELECT id, aggregate_type, aggregate_id, event_type, topic, status,
       attempt_count, next_attempt_at, lock_owner, lock_until,
       last_error, created_at, published_at
  FROM account_ledger.outbox_events
 WHERE id = :event_id;
```

For Payment, replace `account_ledger.outbox_events` with `payment.outbox_events`. Do not copy payload
or headers into tickets or chat; they may contain business data.

Interpretation:

- `PENDING`: wait for `next_attempt_at`; investigate if pending age exceeds the alert threshold.
- `PROCESSING` with a future `lock_until`: an owner still has the lease; do not interfere.
- `PROCESSING` with an expired lease: the publisher reclaims it automatically.
- `FAILED`: bounded attempts are exhausted and a human decision is required.
- `PUBLISHED`: no requeue is needed even if a downstream consumer has a separate problem.

## 2. Confirm the root cause is fixed

Verify broker reachability, topic existence/authorization, serializer compatibility and producer
delivery timeout. Check adjacent events for the same `aggregate_id`; an earlier failure deliberately
blocks later events for that aggregate to preserve order.

Record the service, event id, aggregate id, failure summary, corrective action and approver. Do not
record the event payload.

## 3. Requeue one terminal row

Run the following in a transaction after replacing the schema and binding one reviewed UUID:

```sql
BEGIN;

UPDATE account_ledger.outbox_events
   SET status = 'PENDING',
       attempt_count = 0,
       next_attempt_at = clock_timestamp(),
       lock_owner = NULL,
       lock_until = NULL,
       last_error = NULL
 WHERE id = :event_id
   AND status = 'FAILED';

-- The affected-row count must be exactly 1. Otherwise ROLLBACK and investigate.
COMMIT;
```

The conditional `status = 'FAILED'` prevents an operator from stealing a live claim. A different
business payload must be represented by a new domain event and new event id, never by modifying the
existing row.

## 4. Verify recovery

Observe the row until it becomes `PUBLISHED`, then check that terminal-failure counters stop rising
and pending age returns below the threshold. Validate the downstream consumer inbox/business state
by event id and business reference; a duplicate delivery is an expected crash-window outcome.

Escalate instead of repeating requeue if the row returns to `FAILED`. Repeated manual retries hide a
deterministic contract or data defect and can block later events for the same aggregate.

See [ADR-014](../adr/ADR-014-outbox-claim-lease-and-recovery.md) for the claim/lease protocol and
crash semantics.
