# notification-service

Phase 1B deployable Spring Boot runtime for durable outcome notifications and local email-mock
delivery. It consumes terminal Payment and Refund facts, inserts the inbox marker and notification
record in one PostgreSQL transaction, then acknowledges Kafka only after commit.

## Run without Docker

```powershell
.\mvnw.cmd -B -ntp -Pno-docker -pl services/notification-service -am test
```

## Runtime flow

```text
payment/refund outcome -> Kafka router -> processed_events + notifications (one SQL transaction)
notifications PENDING -> short lease claim -> email port outside transaction -> SENT / FAILED
```

- `payment.succeeded` routes to `CUSTOMER/customerId`.
- `payment.failed` v1 has no customer identity, so it truthfully records `PAYMENT/paymentId` instead
  of inventing an address. A later contract may add a resolvable recipient reference.
- Refund outcomes route to `MERCHANT/merchantId`.
- Business uniqueness is `(PAYMENT_OUTCOME|REFUND_OUTCOME, id, EMAIL)`. A contradictory terminal
  outcome is rejected and rolls its inbox marker back.
- Delivery leases are committed before the provider call. Only the lease owner can complete a row;
  expired leases are reclaimed up to the configured finite maximum.
- `notificationId` is the provider idempotency key; every real provider adapter must preserve it
  across an ambiguous timeout or lease reclaim.
- Provider errors become bounded stable codes; raw exception text is never persisted.

The adapter is deliberately an in-memory email mock and is idempotent by notification id within one
JVM. `FAILED` remains terminal in Phase 1B. Webhook HMAC, scheduled provider retry and audited manual
retry are Phase 2 work and are not claimed here.

## Configuration

Required at runtime: `PAYFLOW_NOTIFICATION_DB_USERNAME` and
`PAYFLOW_NOTIFICATION_DB_PASSWORD`. PostgreSQL URL, Kafka bootstrap servers and Keycloak issuer have
local defaults in `application.yml`. Health is public; every other HTTP route is denied.

Disable external loops for focused startup with:

```text
PAYFLOW_NOTIFICATION_CONSUMER_ENABLED=false
PAYFLOW_NOTIFICATION_DELIVERY_ENABLED=false
```

## Verification status

The no-Docker suite validates the core, factory, transaction orchestration, Kafka routing/manual ack,
delivery policy and configuration. `NotificationWorkflowPersistenceIT` is prepared for PostgreSQL
and proves inbox rollback, transport/business deduplication and lease ownership when Docker is later
enabled. Kafka broker delivery, DLT and crash-window behavior remain unverified until that gate runs.

See `docs/runbooks/notification-delivery-failure.md` for read-only failure triage.
