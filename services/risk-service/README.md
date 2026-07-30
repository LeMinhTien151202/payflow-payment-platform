# risk-service

Runnable Phase 1B Risk boundary for `payment.created -> risk.assessment.completed`.

The domain remains deterministic and framework-free. Runtime adapters add:

- Redis customer velocity windows with payment-id deduplication;
- one immutable PostgreSQL assessment per payment;
- inbox + assessment + Risk-owned outbox in one local transaction;
- manual-ack Kafka consumption with bounded retry and DLT recovery;
- ADR-014 lease-based outbox publishing with stable event IDs;
- deny-by-default HTTP security; only health probes are public.

## Run without Docker

```powershell
.\mvnw.cmd -B -ntp -Pno-docker -pl services/risk-service -am verify
```

This runs domain/application/messaging tests and compiles the Docker-tagged PostgreSQL + Redis
integration suite without starting containers.

## Runtime configuration for later

Required when the infrastructure is started:

- `PAYFLOW_RISK_DB_USERNAME`
- `PAYFLOW_RISK_DB_PASSWORD`

Optional overrides include `PAYFLOW_RISK_DB_URL`, `PAYFLOW_REDIS_HOST`, `PAYFLOW_REDIS_PORT`,
`PAYFLOW_KAFKA_BOOTSTRAP_SERVERS`, and `PAYFLOW_OIDC_ISSUER_URI`. The service exposes no business
REST controller because its current input and output are Kafka contracts.

## Transaction and failure boundary

Redis collection finishes before the PostgreSQL transaction starts. A Redis error fails the Kafka
attempt and therefore follows bounded retry/DLT; Risk never silently approves with an unknown
velocity value. Once signals are available, `processed_events`, `risk_assessments`, and
`outbox_events` commit or roll back together. Kafka is contacted later by the polling publisher.

`paymentCountLastMinute` and `totalAmountLastHour` include the candidate payment. Redis uses the
event occurrence time, not consumer wall-clock time, and money totals are added as decimal minor-unit
strings rather than Lua floating point.

## Contract v1 limitation

`payment.created` v1 carries no trusted device, IP-country, failed-payment-burst, or merchant
blacklist signal. The runtime therefore supplies neutral values for those four rules and persists
that complete input snapshot. Amount and Redis velocity rules are active. Activating the remaining
rules requires a versioned enrichment contract; the service does not infer or invent those facts.

## Verification still pending

`RiskWorkflowPersistenceIT` is prepared for real PostgreSQL + Redis. It proves sixth-payment
velocity, duplicate delivery, one assessment/outbox per payment, and rollback after an injected
outbox failure. It remains unverified until Docker is enabled. Real Kafka acknowledgement,
redelivery, partition ordering, DLT publication, and crash-window behavior also remain pending.
