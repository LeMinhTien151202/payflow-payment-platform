# account-ledger-service

Phase 1B MVP deployable for two deliberately separate bounded contexts:

```text
account/  -> account balance and reservation lifecycle
ledger/   -> immutable double-entry journals
```

The module now has a Spring Boot runtime foundation for both the ADR-011 payment finalization path
and the ADR-021 refund path. Account and Ledger remain separate bounded contexts and schemas inside
one MVP deployable. Every consumer persists inbox + business mutation + causal outbox in one local
transaction; transport redelivery and a new event id carrying the same business intent are handled
separately.

This is still code-first evidence: PostgreSQL and Kafka have not been started. The V1 migration,
JPA/JDBC adapters, row locks, rollback behavior and listener acknowledgement timing are implemented
and unit-tested/compiled, but they are not `VERIFIED_LOCAL` until the prepared container tests run.

## Run the Docker-free tests

From the repository root:

```powershell
.\mvnw.cmd -B -ntp -Pno-docker -pl services/account-ledger-service -am test
```

The command runs domain, handler, router and listener unit tests. It does **not** execute the
Docker-tagged PostgreSQL tests or prove broker offset/crash behavior.

## Implemented workflow runtime foundation

1. Spring Boot bootstrap with deny-by-default HTTP security and health probes.
2. Flyway-owned `account`, `ledger` and operational `account_ledger` schemas.
3. Account `PESSIMISTIC_WRITE`, immutable refund-credit identity, immutable balanced journals and
   unique refund business references.
4. ADR-017 PostgreSQL inbox insert-if-new plus causal outbox append in the caller transaction.
5. Typed Kafka router/listener for Account reserve/capture/release, Ledger payment posting, refund
   posting and Account refund credit; manual acknowledgement happens after commit with bounded retry
   and DLT recovery.
6. Unique `payment_id` reservation, pessimistic Account locking, immutable `PAYMENT_CAPTURE`
   journals and stable duplicate-business-intent checks.
7. Prepared Testcontainers cases for payment/refund happy paths, compensation, deduplication and
   injected outbox rollback.
8. ADR-014 polling outbox publisher with PostgreSQL lease claiming, stale-lease recovery,
   conditional owner marks, bounded exponential retry and Micrometer signals.

## Remaining infrastructure work

1. Run Flyway/JPA, transaction and outbox lease tests on PostgreSQL 17, then run real Kafka
   redelivery and crash-window tests.
2. Run concurrent reserve, journal uniqueness, broker redelivery and kill/restart scenarios; code
   and Testcontainers fixtures alone are not runtime evidence.
3. Add seed/profile data for demo accounts and Ledger account mappings without putting it in the
   production migration path.

The refund core follows ADR-021: Ledger posts a new balanced `REFUND_REVERSAL` journal first,
Account then credits the original account idempotently by `refundId`, and Payment publishes success
only after both acknowledgements. A frozen account may receive a refund; a closed account is routed
to recovery rather than silently credited or marked failed after the journal exists.

Do not treat the payment/refund workflows or Phase 1B as complete until those items have executable
PostgreSQL and Kafka evidence.
