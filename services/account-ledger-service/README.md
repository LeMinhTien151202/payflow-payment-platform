# account-ledger-service

Phase 1B MVP deployable for two deliberately separate bounded contexts:

```text
account/  -> account balance and reservation lifecycle
ledger/   -> immutable double-entry journals
```

The module now has a Spring Boot runtime foundation for the ADR-021 refund path. Account and Ledger
remain separate bounded contexts and schemas inside one MVP deployable. The two consumers persist
inbox + business mutation + causal outbox in one local transaction; transport redelivery and a new
event id carrying the same business intent are handled separately.

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

## Implemented refund runtime foundation

1. Spring Boot bootstrap with deny-by-default HTTP security and health probes.
2. Flyway-owned `account`, `ledger` and operational `account_ledger` schemas.
3. Account `PESSIMISTIC_WRITE`, immutable refund-credit identity, immutable balanced journals and
   unique refund business references.
4. ADR-017 PostgreSQL inbox insert-if-new plus causal outbox append in the caller transaction.
5. Typed Kafka router/listener for `refund.requested` and `account.refund-credit.requested`, manual
   acknowledgement after commit, bounded retry and DLT recovery.
6. Prepared Testcontainers cases for happy-path deduplication and injected outbox rollback.

## Remaining infrastructure work

1. Add the ADR-014 polling publisher for this service's outbox; until then rows are durable but do
   not leave the database.
2. Run Flyway/JPA and transaction tests on PostgreSQL 17, then run real Kafka redelivery and
   crash-window tests.
3. Connect the existing reserve/capture/release pure policies using the same transaction pattern.
4. Add unique reservation by `payment_id` and retain unique journal by
   `(reference_type, reference_id, journal_type)`.
5. Add seed/profile data for demo accounts and Ledger account mappings without putting it in the
   production migration path.

The refund core follows ADR-021: Ledger posts a new balanced `REFUND_REVERSAL` journal first,
Account then credits the original account idempotently by `refundId`, and Payment publishes success
only after both acknowledgements. A frozen account may receive a refund; a closed account is routed
to recovery rather than silently credited or marked failed after the journal exists.

Do not treat the refund workflow or Phase 1B as complete until those items have executable evidence.
