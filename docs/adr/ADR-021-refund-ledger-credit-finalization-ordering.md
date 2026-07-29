# ADR-021: Refund ledger, account credit and finalization ordering

- Status: ACCEPTED
- Date: 2026-07-29
- Decision owners: PayFlow repository owner
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-011

## Context

A refund changes three independently owned facts: Payment owns refund capacity and lifecycle,
Ledger owns immutable accounting journals, and Account owns the customer's available balance. A
distributed transaction across those boundaries is forbidden. Publishing `refund.succeeded` before
all financial work commits can therefore claim success while either the reversal journal or the
customer credit is missing.

The current payment capture journal contract is principal-only. ADR-019 already fixes fee history
inside Payment and requires a new event version before a fee-aware Ledger contract is introduced.

## Decision drivers

- A succeeded refund must have both a posted reversal journal and a committed account credit.
- A posted journal is immutable and cannot be compensated by deleting or editing it.
- Account credit must be idempotent by `refundId` and must never run twice after redelivery.
- Concurrent refunds must retain Payment's capacity guard from ADR-020.
- Fee allocation must use the immutable Payment snapshot and cumulative succeeded amount from
  ADR-019, under the Payment row lock used during finalization.
- Every step must remain recoverable with outbox, inbox and a stable business reference.

## Options considered

### Account credit before Ledger

This returns spendable money before an accounting fact exists. A permanent Ledger failure then
requires a new debit or manual recovery and is unsafe as the default order.

### Publish refund success before Account credit

This shortens the happy path but exposes a false terminal state if credit later fails.

### Ledger reversal, explicit Account credit, then success

This keeps the refund non-terminal while financial acknowledgements are incomplete and mirrors the
payment finalization discipline in ADR-011.

## Decision

Choose Ledger reversal, explicit Account credit, then Payment finalization:

```text
Payment -> Ledger:  refund.requested
Ledger  -> Payment: ledger.refund-posted
Payment -> Account: account.refund-credit.requested
Account -> Payment: account.refund-credited
Payment -> others:  refund.succeeded
```

All messages use `paymentId` as aggregate id and Kafka key. `refundId` is the idempotent business
reference for both the Ledger journal and Account credit.

Mandatory rules:

1. Ledger creates a new balanced `REFUND_REVERSAL` journal, unique by
   `(reference_type='REFUND', reference_id=refundId, journal_type='REFUND_REVERSAL')`. It never edits
   the original payment journal.
2. `ledger.refund-posted` moves Refund from `CREATED` to `PROCESSING` and causes exactly one
   `account.refund-credit.requested`. Payment status remains unchanged. Payment persists the
   immutable `journalId` before emitting that command so a restart cannot lose the causal fact.
3. Account credits the original source account. A frozen account may receive a refund; a closed
   account cannot and requires operations recovery. Duplicate credit with the same refund intent is
   a no-op; a different intent for the same `refundId` is an invariant violation.
4. Only matching `account.refund-credited` may move Payment reserved capacity to succeeded total,
   compute the cumulative fee-reversal delta, mark Refund `SUCCEEDED`, and append
   `refund.succeeded` in one Payment local transaction. The Refund row also persists the unique
   `creditId` as terminal evidence.
5. A definitive Ledger rejection before a journal is posted may mark Refund `FAILED` and release
   its reserved capacity atomically. After `ledger.refund-posted`, automatic failure and capacity
   release are forbidden: Account credit is retried with a bound and then routed to manual review
   and reconciliation.
6. Contract v1 posts principal only because payment capture v1 is principal-only. The fee reversal
   is an immutable fact in `refund.succeeded` and is consumed by Settlement. A fee-aware Ledger
   journal requires a new versioned contract; v1 is never reinterpreted.

## Consequences

The client sees `CREATED` or `PROCESSING` until both financial acknowledgements commit. The happy
path has another asynchronous round trip, but it never publishes success early. A journal-posted,
credit-pending refund is deliberately recoverable rather than automatically failed.

## Contract and data impact

- Add `ledger.refund-posted`, `ledger.refund-posting-failed`,
  `account.refund-credit.requested`, `account.refund-credited`, and `refund.succeeded` v1.
- Payment migration V7 adds durable, uniquely indexed `ledger_journal_id` and
  `account_credit_id` facts with status-consistency checks. It rejects any legacy
  `PROCESSING`/`SUCCEEDED` Refund row whose identities cannot be reconstructed safely.
- Ledger and Account persistence code uses unique refund business references and inbox/outbox local
  transactions; the prepared PostgreSQL concurrency/rollback tests remain unverified until Docker
  is started.
- No public REST shape changes.

## Verification

- Exact JSON contract tests for every new v1 payload and envelope identity.
- Pure tests for balanced reversal journals, duplicate/mismatched Account credit and Refund
  finalization matching.
- PostgreSQL tests are prepared for durable journal/credit facts, inbox deduplication and rollback
  of inbox + aggregate + outbox, but are not verified until Docker is started.
- Kafka/E2E tests later prove redelivery, crash windows, ordering and no early success.
