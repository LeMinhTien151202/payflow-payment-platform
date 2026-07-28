# account-ledger-service

Phase 1B MVP deployable for two deliberately separate bounded contexts:

```text
account/  -> account balance and reservation lifecycle
ledger/   -> immutable double-entry journals
```

The current slice is **domain/application core only**. It is a normal Maven module but not yet a
runnable Spring Boot application. Reserve command/result contracts, deadline enforcement and
duplicate-intent policy now exist; persistence/listeners remain gated by OD-007 and by the missing
PostgreSQL/Kafka evidence.

## Run the Docker-free tests

From the repository root:

```powershell
.\mvnw.cmd -B -ntp -Pno-docker -pl services/account-ledger-service -am test
```

The tests prove deterministic domain behavior only. They do **not** prove PostgreSQL locking,
transaction atomicity, unique reservation/reference constraints, Kafka deduplication, or crash
recovery.

## Infrastructure work to connect later

After the Phase 1A gate and relevant ADRs are resolved, the next vertical slice must add together:

1. A Spring Boot bootstrap with deny-by-default security and health probes.
2. Account and Ledger Flyway migrations in separate schema namespaces and credentials.
3. Atomic reserve using a conditional update or row lock; never unlocked read-then-write.
4. Unique reservation by `payment_id` and unique journal by
   `(reference_type, reference_id, journal_type)`.
5. Application handlers and ports with local transaction boundaries; pure reserve policy is already
   available and must be invoked inside that boundary.
6. Inbox/processed-event insert-if-new semantics after OD-007 is resolved.
7. Outbox rows committed with each balance/journal mutation.
8. PostgreSQL concurrency and transaction tests through Testcontainers; Kafka integration tests for
   duplicate delivery and acknowledgement timing.

Do not treat this module as deployable or Phase 1B-complete until those items have executable
evidence.
