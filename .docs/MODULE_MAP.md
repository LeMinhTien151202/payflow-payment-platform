# Bản đồ bounded context và service

## Service ownership

| Deployable/bounded context | Trách nhiệm | Data store | Contract chính | Phase |
| --- | --- | --- | --- | --- |
| `api-gateway` | JWT validation, routing, rate limit, CORS, correlation, edge metrics | Redis cho rate-limit nếu cần | Public `/api/v1/**` | 0 |
| `payment-service` | Payment/refund lifecycle, idempotency, status history, Saga orchestration | `payflow_payment` | Payment/refund REST; payment/Saga commands/events | 0–2 |
| Merchant module -> `merchant-service` | Merchant, member, fee/limit, API key, webhook config | ban đầu payment DB namespace riêng; sau đó `payflow_merchant` | Merchant REST/event/query | 1 module, 2 service |
| `account-ledger-service` | MVP deployable chứa Account và Ledger boundary riêng | schema/table namespace riêng | Account/ledger commands/events | 1 |
| `account-service` | Balance, reservation, capture, release, refund credit | `payflow_account` | Account internal API/commands/events | 2 |
| `ledger-service` | Immutable journal, double entry, reversal, audit query | `payflow_ledger` | Ledger internal API/commands/events | 2 |
| `risk-service` | Deterministic rule evaluation, velocity, risk case | `payflow_risk` + Redis counters | Risk events/query/review API | 1–2 |
| `notification-service` | Email mock, webhook HMAC, delivery retry/DLT | `payflow_notification` | Notification commands; operations retry API | 1–2 |
| `reporting-service` | Event projections và dashboard read model | `payflow_reporting` | Reporting query API | 2 |
| `settlement-service` | Daily merchant batch, fee/net, reconciliation | `payflow_settlement` | Settlement operations API/events | 3 |
| Keycloak | Identity, roles, scopes, clients | Keycloak-owned DB | OIDC/OAuth2 | 0 |

## Domain boundary trong MVP gộp

`account-ledger-service` là một deployable nhưng không phải một model chung:

```text
account-ledger-service/
├── account/       # owns accounts + reservations
├── ledger/        # owns journals + entries
├── messaging/     # adapters route commands to owning module
└── common/        # technical helpers only
```

- Account không sửa ledger table; Ledger không sửa balance table.
- Hai module không chia sẻ entity/repository.
- Contract giữa hai module vẫn explicit, dù invocation ban đầu có thể in-process.
- Test contract được viết trước khi tách thành hai service.

## Event flow chính

| Event/command | Producer | Consumer chính | Key | Side effect |
| --- | --- | --- | --- | --- |
| `payment.created` | Payment | Risk, reporting | `paymentId` | Tạo assessment/projection |
| `risk.assessment.completed` | Risk | Payment, reporting | `paymentId` | Tiến/reject/giữ chờ review theo ADR-016 |
| `account.reserve.requested` | Payment | Account | `paymentId` | Tạo một reservation |
| `account.funds-reserved` | Account | Payment | `paymentId` | Yêu cầu post ledger |
| `account.funds-reservation-failed` | Account | Payment | `paymentId` | Fail Saga |
| `ledger.post-payment.requested` | Payment | Ledger | `paymentId` | Tạo balanced journal một lần |
| `ledger.payment-posted` | Ledger | Payment | `paymentId` | Tạo explicit capture request; Payment vẫn `PROCESSING` |
| `account.capture.requested` | Payment | Account | `paymentId` | Capture đúng reservation sau ledger POSTED |
| `account.funds-captured` | Account | Payment | `paymentId` | Xác nhận capture đã commit |
| `account.release.requested` | Payment | Account | `paymentId` | Compensation idempotent |
| `payment.succeeded/failed` | Payment | notification, reporting, settlement | `paymentId` | Outcome fact để notify/project; success không còn ra lệnh capture |
| `refund.requested` | Payment | Ledger | `paymentId` | Post immutable principal reversal journal |
| `ledger.refund-posted` | Ledger | Payment | `paymentId` | Move Refund to processing and request Account credit |
| `account.refund-credit.requested` | Payment | Account | `paymentId` | Credit original source account idempotently by refundId |
| `account.refund-credited` | Account | Payment | `paymentId` | Complete capacity/fee allocation and Refund success |
| `refund.succeeded/failed` | Payment | reporting, settlement, notification | `paymentId` | Terminal refund outcome projection |

Tên/schema cuối cùng phải được định nghĩa trong event-contract module và contract docs; bảng trên xác định ownership, không thay cho schema versioned.

## Shared libraries

Được phép:

- event envelope và schema contract ổn định;
- observability/correlation bootstrap;
- test fixtures/Testcontainers support;
- generic Problem Details/error convention.

Không được phép:

- JPA entity/repository/migration;
- domain service, state machine hoặc money policy dùng chung;
- internal DTO của một service;
- client wrapper khiến service gọi chéo database hoặc che giấu REST chain.

## Quy tắc ownership

- Chỉ owner được mutate aggregate/table.
- Consumer tạo local projection thay vì query database owner.
- Cần consistency mạnh xuyên context thì thiết kế workflow/command và compensation, không join database.
- Dữ liệu cần tại thời điểm quyết định có thể đi trong immutable event snapshot; consumer không tự suy ra bằng đọc chéo.
- Mỗi contract có một owner chịu trách nhiệm versioning và compatibility test.
