# PayFlow verification checklist

## Scope and architecture

- Change belongs to the current roadmap phase and owning service.
- No cross-service database access, shared JPA entity, shared business logic, or hidden synchronous chain was introduced.
- Controller/listener is thin; transaction/use-case orchestration is in application; deterministic invariant is in domain.
- External and infrastructure types do not leak into domain/API contracts.

## Money and state

- `BigDecimal`/`NUMERIC` and explicit currency/rounding are used.
- Balance/refund race is protected atomically and tested concurrently.
- State transition is explicit, optimistic-lock-safe, and cannot regress terminal state.
- Journal is balanced, idempotent, immutable after post, and corrected only by reversal/new journal.
- Fee/settlement uses historical policy snapshot and configured business date.

## Database

- Flyway migration is new, forward-only, and owned by one service.
- Hibernate validates rather than mutates schema.
- Constraints and indexes match idempotency, ownership, polling, and query access patterns.
- PostgreSQL-specific transaction and locking behavior is tested with PostgreSQL.
- No destructive reset, truncate, or production-like seed was introduced.

## REST and security

- Path, method, status, DTO, enum, and Problem Details code match the documented contract.
- Idempotency scope, hash, and replay behavior are correct for same and different payloads.
- 401, 403, 404, and 409 behavior is distinct and tested.
- Actor, role/scope, and merchant/customer ownership are enforced server-side.
- Internal route/network boundary and rate/size limit are appropriate.
- Response, logs, and repository contain no plaintext secret, token, API key, or real sensitive data.

## Kafka, outbox, and Saga

- Business mutation and outbox insert share a transaction.
- Consumer marker, business mutation, and outgoing outbox share a transaction.
- Offset/ack occurs only after commit; exceptions are not swallowed.
- Event has stable owner, aggregate key, full envelope, compatible version, and contract test.
- Duplicate, redelivery, stale, and allowed out-of-order behavior are tested.
- Retry is bounded and classified; DLT, replay, and manual action are idempotent and auditable.
- Saga deadline, compensation, and manual-review outcome are finite and observable.

## Observability and operations

- Correlation, trace, and causation IDs propagate through affected boundaries.
- New failure modes have structured logs, metrics, and safe context.
- Readiness/liveness remain meaningful and Actuator is not publicly overexposed.
- Alert, DLT, replay, privileged operation, or migration risk has an owner and runbook path.

## Tests and evidence

- Unit, web/security, repository/integration, contract, and E2E coverage is proportional to risk.
- Money, concurrency, and delivery guarantees are not proven with mocks alone.
- Maven Wrapper commands and results are recorded.
- Changed contracts, schema, topics, and operational behavior are documented.
- `.docs/IMPLEMENTATION_STATUS.md` is updated only when evidence exists.
- No throughput, latency, coverage, or recovery claim is invented.

