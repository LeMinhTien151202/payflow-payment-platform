# PayFlow feature workflow

## Before coding

- Confirm the current phase and that prerequisite gates are met.
- Identify the aggregate/service owner; reject cross-database access.
- Write the invariant and valid state transitions in plain language.
- Identify actor, scope, merchant/customer ownership, audit need, and sensitive data.
- List request/event idempotency scopes and durable uniqueness keys.
- Define the local transaction boundary; list every network/Kafka action outside it.
- Choose concurrency control and database constraints for race-prone writes.
- Define event key, envelope/version, producer, consumers, ordering assumption, timeout, retry, DLT, replay, and compensation.
- List migrations/indexes plus expand/rollback behavior.
- List happy, validation, duplicate, concurrent, transient-failure, permanent-failure, crash/restart, and authorization tests.

## Typical service files

```text
services/<service>/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/payflow/<context>/
    │   │   ├── api/
    │   │   │   ├── <Feature>Controller.java
    │   │   │   ├── request/
    │   │   │   └── response/
    │   │   ├── application/
    │   │   │   ├── command/
    │   │   │   ├── query/
    │   │   │   └── port/
    │   │   ├── domain/
    │   │   │   ├── model/
    │   │   │   ├── policy/
    │   │   │   └── event/
    │   │   └── infrastructure/
    │   │       ├── persistence/
    │   │       ├── messaging/
    │   │       └── config/
    │   └── resources/db/migration/
    └── test/
```

Create only layers justified by the feature. Never skip API/event DTO boundaries or let a controller/listener call a repository directly.

## Change-specific checks

### REST write

- Validate `Idempotency-Key`, actor/tenant ownership, amount/currency, request size, and canonical hash.
- Persist resource, idempotency result, and outbox consistently.
- Specify repeat response while the original request is `IN_PROGRESS` and after completion/failure.

### Kafka producer

- Use outbox in the same transaction as the state change.
- Set aggregate key and complete envelope/header metadata.
- Add serialization/contract test and publisher failure metric.

### Kafka consumer

- Insert durable processed-event marker in the business transaction.
- Make the business operation idempotent independently where practical.
- Classify retry/DLT behavior and verify offset commit timing.

### Balance or refund write

- Use lock/conditional atomic update, not read-then-write without protection.
- Add database constraints/unique keys and a real concurrent integration test.
- Verify balance columns or remaining refundable amount after success and failure.

### Ledger write

- Build all entries, group by currency, validate debit equals credit, then post atomically.
- Enforce an idempotent business reference.
- Do not add update/delete flow for posted data; use reversal.

### External webhook or email

- Keep the network call outside the financial transaction.
- Add timeout, bulkhead, bounded retry, terminal state, and audit/manual retry.
- Store only safe response excerpts and redact headers/secrets.

## Definition of Done

- The owner, transaction, consistency, and failure model are explicit.
- Financial/state invariants have unit plus database/concurrency coverage as applicable.
- REST/event contract, schema migration, and indexes are documented and tested.
- Duplicate request/event cannot repeat the business side effect.
- Retry, timeout, DLT, compensation, and manual-recovery paths are finite and observable.
- Security, ownership, and audit cases are tested.
- Logs, metrics, and traces explain success and failure without secrets.
- Relevant Maven, Testcontainers, contract, and E2E commands pass.
- Documentation and implementation status reflect evidence, not intent.

