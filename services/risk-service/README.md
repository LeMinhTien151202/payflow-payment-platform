# risk-service

Phase 1B deterministic risk-policy core. The current module evaluates the seven rules from spec
§7.7 and classifies a normalized score according to
[ADR-015](../../docs/adr/ADR-015-risk-score-saturation-and-level-bands.md).

It is intentionally a pure Java Maven module, not yet a runnable Spring Boot service. No Redis,
PostgreSQL, Kafka, REST endpoint or event contract is claimed by this slice.

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

1. Resolve OD-003 before defining the Risk→Payment event taxonomy.
2. Add Spring Boot bootstrap and deny-by-default actuator/security configuration.
3. Collect velocity inputs atomically in Redis with expiry and a documented failure policy.
4. Persist one assessment per payment with normalized score, matched rules and database constraints.
5. Add inbox + business mutation + outbox in one local transaction after OD-007 is resolved.
6. Prove Redis/PostgreSQL/Kafka behavior through Testcontainers and duplicate-event tests.

Do not describe this module as a deployable service or a completed Phase 1B workflow yet.
