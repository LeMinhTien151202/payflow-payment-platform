# Refund financial workflow v1

The ordering and failure boundary are fixed by
[ADR-021](../adr/ADR-021-refund-ledger-credit-finalization-ordering.md). Every message uses
`aggregateType=PAYMENT`, `aggregateId=paymentId`, and Kafka key `paymentId`.

| Event | Producer | Topic | Main consumer | Exact payload |
| --- | --- | --- | --- | --- |
| `ledger.refund-posted` v1 | Ledger | `payflow.ledger.events.v1` | Payment | `refundId`, `paymentId`, `journalId`, `accountId`, `amount`, `currency` |
| `ledger.refund-posting-failed` v1 | Ledger | `payflow.ledger.events.v1` | Payment | `refundId`, `paymentId`, `amount`, `currency`, `failureCode` |
| `account.refund-credit.requested` v1 | Payment | `payflow.payment.events.v1` | Account | `refundId`, `paymentId`, `accountId`, `journalId`, `amount`, `currency` |
| `account.refund-credited` v1 | Account | `payflow.account.events.v1` | Payment | `refundId`, `paymentId`, `accountId`, `journalId`, `creditId`, `amount`, `currency` |
| `refund.succeeded` v1 | Payment | `payflow.refund.events.v1` | Reporting, Settlement, Notification | `refundId`, `paymentId`, `merchantId`, `journalId`, `creditId`, `amount`, `feeReversalAmount`, `currency`, `completedAt` |
| `refund.failed` v1 | Payment | `payflow.refund.events.v1` | Reporting, Notification | `refundId`, `paymentId`, `merchantId`, `amount`, `currency`, `failureCode`, `failedAt` |

All principal amounts are positive `NUMERIC(19,4)`-compatible values and currency is a three-letter
uppercase code. `feeReversalAmount` may be zero but not negative. Failure codes contain 1–100
characters.

`ledger.refund-posted` confirms a new immutable balanced `REFUND_REVERSAL` journal. It does not by
itself make the refund successful. `refund.succeeded` is emitted only after a matching Account
credit commits and contains the fee-reversal fact calculated from Payment's immutable fee snapshot.

A definitive `ledger.refund-posting-failed` before journal creation may release refund capacity.
After `ledger.refund-posted`, Account credit failure is retried and reconciled; it must not produce
`refund.failed` or release capacity automatically.

Runtime producers/consumers are not considered verified until PostgreSQL/Kafka inbox, outbox,
redelivery and crash-window tests pass.
