# Financial invariants

Load this reference for any money, payment, account, reservation, ledger, refund, fee, settlement, reconciliation, or Saga-completion change.

## Money value

- Represent money as `(amount, currency)`; amount alone is incomplete.
- Use Java `BigDecimal` and PostgreSQL `NUMERIC(19,4)` for MVP.
- Construct constants from strings or integer minor units, not binary floating-point literals.
- Compare numeric value with `compareTo`, not scale-sensitive `equals`, unless scale equality is the intended contract.
- Reject amount `<= 0` for payment, reservation, entry, and refund.
- Define scale and `RoundingMode` for fee, tax, and division in one owner policy; test boundary amounts.
- Do not mix currencies in an account, reservation, or MVP journal.

## Account and reservation

Required constraints:

```text
available_balance >= 0
reserved_balance >= 0
unique(owner_type, owner_id, account_type, currency)
unique(payment_id) on reservation
```

Reserve in one local transaction:

1. Atomically claim sufficient balance using a conditional update or lock the account row.
2. Move the same amount from available to reserved.
3. Insert one active reservation for the payment.
4. Append the outcome outbox event.
5. Commit all or none.

Never perform an unlocked read-then-write balance check. Treat zero affected rows carefully: distinguish missing, frozen, insufficient, or concurrent state without losing atomic protection.

Reservation transitions:

```text
ACTIVE -> CAPTURED
ACTIVE -> RELEASED
ACTIVE -> EXPIRED
```

Treat terminal transitions as idempotent only for the same intended operation. A late release must not undo a captured reservation; escalate inconsistent commands instead of guessing.

## Ledger

Before post:

- Require at least two positive entries.
- Require all entries to use the journal currency.
- Require debit sum to equal credit sum by currency.
- Require the reference tuple to be unique.
- Require the referenced business fact to be eligible for the journal type.

Post the journal and all entries in one transaction. Keep a posted journal immutable. Correct it with a linked reversal/new journal having its own identity and balanced entries.

Do not equate operational balance columns with the accounting ledger. They are separate models reconciled through references and domain events.

## Payment and Saga state

- Allow only explicit state transitions defined by the Payment aggregate or policy.
- Persist status history and Saga state with the aggregate change/outbox as required by the use case.
- Use optimistic version checks to prevent two events advancing the same Saga from stale state.
- Prevent duplicate or stale events from regressing a terminal state.
- Do not emit `payment.succeeded` before the chosen success preconditions are durably true.
- Define ledger-post, payment-success, and account-capture ordering and recovery in an accepted ADR before implementing that boundary.

## Refund

- Permit refund only for `SUCCEEDED` or `PARTIALLY_REFUNDED` payment.
- Require successful plus in-flight plus requested refund to stay within the original payment amount under the chosen reservation model.
- Protect the calculation and state update with payment/refundable-balance locking or an equivalent atomic database model.
- Use a separate idempotency scope per merchant/endpoint and a unique business reference.
- Make credit/reversal processing event-driven and idempotent; duplicate events cannot double-credit or double-post.
- Reach `REFUNDED` only at zero remaining amount; use `PARTIALLY_REFUNDED` only after required financial effects succeed.

## Fee, settlement, and reconciliation

- Persist the fee policy/snapshot used for a payment so later configuration changes do not rewrite history.
- Define rounding once and reconcile line totals with batch totals.
- Keep settlement identity unique by merchant, business date, and currency.
- Derive business `LocalDate` using configured timezone; store event timestamps as UTC `Instant`/`TIMESTAMPTZ`.
- Never silently auto-correct a reconciliation mismatch; record, alert, and resolve it through an auditable adjustment/reversal policy.

## Required proof

| Invariant | Minimum evidence |
| --- | --- |
| No negative balance | PostgreSQL concurrent integration test plus constraint |
| One reservation per payment | Unique constraint plus duplicate-event test |
| Balanced immutable journal | Domain unit test plus transaction/constraint integration test |
| No over-refund | Concurrent integration test plus locked/atomic state proof |
| No duplicate side effect | Idempotency/inbox unique constraint plus redelivery test |
| No lost event after commit | Outbox crash-window integration test |
| Compensation restores funds | E2E failure injection with final-state assertions |

