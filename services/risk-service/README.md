# risk-service

Phase 1B deterministic risk-policy core. The current module evaluates the seven rules from spec
§7.7 and classifies a normalized score according to
[ADR-015](../../docs/adr/ADR-015-risk-score-saturation-and-level-bands.md).
It also maps the immutable assessment to `risk.assessment.completed` v1 according to
[ADR-016](../../docs/adr/ADR-016-risk-assessment-event-taxonomy.md), preserving the
`payment.created` correlation and causation chain.

It is intentionally a pure Java Maven module, not yet a runnable Spring Boot service. The event
schema and factory are verified, but no Redis, PostgreSQL, Kafka broker, consumer, outbox or REST
endpoint is claimed by this slice.

## Run without Docker

```powershell
.\mvnw.cmd -B -ntp -Pno-docker -pl services/risk-service -am test
```

## Input semantics

- `paymentCountLastMinute` includes the candidate payment; the sixth payment matches `VELOCITY_1M`.
- `totalAmountLastHour` includes the candidate payment; an amount strictly greater than 30,000,000
  VND matches `VELOCITY_1H`.
- `failedPaymentsLastTenMinutes` counts prior failed payments; three or more matches `FAILED_BURST`.
- MVP currency is VND.

## Infrastructure work deferred

After the earlier gates are available:

1. Add Spring Boot bootstrap and deny-by-default actuator/security configuration.
2. Collect velocity inputs atomically in Redis with expiry and a documented failure policy.
3. Persist one assessment per payment with normalized score, matched rules and database constraints.
4. Add inbox + business mutation + outbox in one local transaction after OD-007 is resolved.
5. Publish the already-defined event through a Risk-owned outbox; never publish directly from the
   consumer transaction.
6. Prove Redis/PostgreSQL/Kafka behavior through Testcontainers and duplicate-event tests.

Do not describe this module as a deployable service or a completed Phase 1B workflow yet.
