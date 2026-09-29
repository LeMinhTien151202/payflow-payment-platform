# Bản đồ module PayFlow Core

## Deployable

| Module | Ownership | Database |
| --- | --- | --- |
| `api-gateway` | edge routing/security/rate limit | Redis chỉ cho rate limit |
| `payment-service` | payment, refund, idempotency, Saga, merchant policy cục bộ | `payflow_payment` |
| `risk-service` | assessment, rules, risk case | `payflow_risk` + Redis velocity |
| `account-ledger-service` | account/reservation và journal/entry theo hai module nội bộ | `payflow_account_ledger` |
| `notification-service` | notification, webhook delivery/retry | `payflow_notification` |
| `settlement-service` | settlement batch/item, reconciliation issue | `payflow_settlement` |
| Keycloak | identity, role, client, scope | `keycloak` |

## Shared libraries

| Library | Cho phép chứa |
| --- | --- |
| `event-contracts` | topic, event name/version và immutable payload |
| `error-contract` | Problem Details và stable error code |
| `observability-support` | correlation convention |
| `network-security-support` | outbound URL validation chống SSRF |
| `test-support` | fixture/test utility thuần kỹ thuật |

Không đưa entity, repository, migration hoặc domain policy vào shared library.

## Account-Ledger boundary

```text
account-ledger-service/
├── account/       # account, reservation, refund credit
├── ledger/        # journal, entry, double-entry invariant
├── application/   # inbox/outbox ports và handler chung
└── infrastructure # Kafka routing, persistence, security
```

Hai module không dùng chung entity/repository. Việc gộp giảm một JVM, một image và một database
credential; contract event vẫn explicit.

## Event ownership

| Contract | Producer | Consumer chính |
| --- | --- | --- |
| `payment.created` | Payment | Risk |
| `risk.assessment.completed` | Risk | Payment |
| `account.*.requested` | Payment | Account module |
| `account.funds-*` | Account module | Payment |
| `ledger.*.requested` | Payment | Ledger module |
| `ledger.*-posted/failed` | Ledger module | Payment |
| `payment.succeeded/failed` | Payment | Notification, Settlement |
| `refund.requested` | Payment | Ledger module |
| `refund.succeeded/failed` | Payment | Notification, Settlement |
