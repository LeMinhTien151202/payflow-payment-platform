# notification-service

Phase 1B notification-record and email-mock core. This slice creates a deterministic notification
aggregate, delivers an email-shaped message through an application port, and includes an in-memory
mock adapter for local tests.

It is intentionally a pure Java Maven module, not yet a runnable Spring Boot service. It does not
claim PostgreSQL persistence, Kafka consumption, inbox/outbox atomicity, webhook delivery, HMAC,
retry/DLT, operations retry, or Keycloak integration.

## Run without Docker

```powershell
.\mvnw.cmd -B -ntp -Pno-docker -pl services/notification-service -am test
```

## Current state policy

```text
PENDING -> SENT
PENDING -> FAILED
```

- A duplicate delivery request after a terminal outcome is a no-op.
- An attempt cannot complete before the notification was created.
- Payload maps are copied at the boundary so callers cannot mutate stored content accidentally.
- `failureCode` is a stable technical classification, not a raw exception or secret-bearing message.

`FAILED` is terminal only for this Phase 1B email-mock slice. Phase 2 retry semantics must define
scheduled retry and `DEAD` transitions before extending this policy.

## Infrastructure work deferred

1. Resolve OD-007 before adding the event consumer and processed-event transaction.
2. Add a Spring Boot bootstrap with deny-by-default security and private health/operations routes.
3. Add Notification-owned Flyway migrations, uniqueness keys, inbox and outbox records.
4. Persist notification creation plus inbox marker atomically; perform outbound delivery outside the
   payment transaction.
5. In Phase 2, add webhook HMAC, timeout, bulkhead, bounded retry, DLT and audited manual retry.
6. Prove duplicate event handling and database/Kafka behavior with Testcontainers.

Do not describe this module as a deployable service or a completed Phase 1B workflow yet.
