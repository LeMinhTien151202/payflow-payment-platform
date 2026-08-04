# PayFlow code walkthrough: từ đăng nhập đến REST, Kafka và database

Tài liệu này là bản đồ đọc source dành cho người mới vào dự án. Nó trả lời bốn câu hỏi:

1. Client lấy token ở đâu và hệ thống phân quyền thế nào?
2. Khi gọi API, request đi qua chính xác những file nào?
3. Sau khi API trả `202 Accepted`, Kafka và từng service tiếp tục làm gì?
4. Mỗi nhóm file có trách nhiệm gì và nên debug từ đâu?

Nội dung dưới đây mô tả **code đang tồn tại**. Contract chính thức vẫn nằm ở
[OpenAPI](../api/payment-service-v1.yaml), [event contracts](../events/refund-workflow-v1.md) và
[ADR](../adr/README.md).

## 1. Bức tranh tổng thể

| Deployable | Đầu vào | Trách nhiệm | Dữ liệu sở hữu |
| --- | --- | --- | --- |
| `api-gateway` | HTTP từ client | JWT, scope, correlation ID, route | Không có dữ liệu nghiệp vụ |
| `payment-service` | REST và Kafka | Payment/refund intake, trạng thái, Saga | Payment, refund, saga, idempotency, inbox, outbox |
| `risk-service` | Kafka | Chấm risk bằng rule và velocity | Assessment, inbox, outbox; Redis giữ velocity |
| `account-ledger-service` | Kafka | Giữ/trừ/hoàn tiền và ghi sổ kép | Account, reservation, refund credit, journal, inbox, outbox |
| `notification-service` | Kafka | Tạo và phát notification kết quả cuối | Notification, inbox, delivery state |

```text
Client
  │ HTTP + Bearer token + Idempotency-Key
  ▼
API Gateway ──HTTP──> Payment Service ──local transaction──> Payment DB + Outbox
                                                │
                                                ▼
                                              Kafka
                    ┌───────────────────────────┼───────────────────────────┐
                    ▼                           ▼                           ▼
               Risk Service          Account/Ledger Service       Notification Service
                    │                           │                           │
                    └──────── events ───────────┴──────── events ───────────┘
                                                │
                                                ▼
                                      Payment Saga cập nhật trạng thái
```

Client chỉ gọi Payment API qua Gateway. Kafka là đường giao tiếp nội bộ để tiếp tục workflow dài sau khi API nhận yêu cầu. `202 Accepted` nghĩa là yêu cầu và outbox đã được ghi bền; nó **không** có nghĩa tiền đã chuyển xong. Client dùng API GET để đọc trạng thái cuối.

## 2. Đăng nhập và phân quyền hiện tại

### 2.1 Hiện chưa có màn hình đăng nhập người dùng

Keycloak realm được import từ
[`realm-payflow.json`](../../infrastructure/keycloak/realm-payflow.json). Runtime hiện có hai confidential client dùng `client_credentials`:

| Client | Scope | Mục đích |
| --- | --- | --- |
| `payflow-service` | `payment:read`, `payment:write` | Gọi cả GET và POST |
| `payflow-readonly` | `payment:read` | Chỉ đọc payment |

Đây là service account. `standardFlowEnabled=false`, nên hiện chưa có Authorization Code/PKCE, trang login merchant hay bảng user ứng dụng. Browser login là hướng có thể bổ sung sau, không phải luồng code hiện tại.

Token được lấy trực tiếp từ Keycloak:

```text
POST http://localhost:8180/realms/payflow/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
client_id=payflow-service
client_secret=<KEYCLOAK_PAYFLOW_SERVICE_SECRET trong .env>
```

Client lấy `access_token` trong response rồi gửi:

```http
Authorization: Bearer <access_token>
```

Không ghi secret thật vào source, tài liệu hoặc log.

### 2.2 Đây không phải RBAC thuần

Authorization runtime kết hợp:

- **OAuth2 scope**: `payment:read` hoặc `payment:write` quyết định loại API được gọi.
- **Tenant/ownership claim**: `merchant_id` trong JWT quyết định dữ liệu merchant nào được truy cập.
- **Domain authorization**: trạng thái payment và các invariant quyết định thao tác có hợp lệ không.

Code Payment hiện không dùng Keycloak realm role để quyết định endpoint. Tên chính xác là “scope-based authorization + tenant isolation”, không phải RBAC thuần.

### 2.3 Quyền được kiểm tra hai lớp

| Lớp | File | Việc thực hiện |
| --- | --- | --- |
| Gateway | [`SecurityConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/SecurityConfig.java) | Xác minh JWT; GET cần `SCOPE_payment:read`; POST cần `SCOPE_payment:write`; mặc định deny |
| Payment | [`SecurityConfig`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/security/SecurityConfig.java) | Xác minh JWT lại tại service; cùng scope; stateless; mặc định deny |
| Controller | [`PaymentController`](../../services/payment-service/src/main/java/com/payflow/payment/api/PaymentController.java) | Đọc `merchant_id` từ token, không tin merchant ID từ body |
| Query | [`GetPaymentHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/GetPaymentHandler.java) | Tìm theo cả `paymentId` và `merchantId`, chặn đọc chéo tenant |

Ý nghĩa lỗi:

- `401`: thiếu token hoặc token/signature/issuer/expiry không hợp lệ.
- `403`: token hợp lệ nhưng thiếu scope.
- `404`: payment không tồn tại **hoặc** thuộc merchant khác; không làm lộ ID của tenant khác.
- `409`: cùng `Idempotency-Key` nhưng payload khác, hoặc xung đột nghiệp vụ.

## 3. Cách các lớp phụ thuộc nhau

Các service dùng Hexagonal/Clean Architecture theo hướng dependency đi vào trong:

```text
api / messaging adapter
        │
        ▼
application handler ──> application port <── infrastructure adapter
        │
        ▼
domain model / policy
```

| Package | Chứa gì | Vai trò |
| --- | --- | --- |
| `api` | Controller, request/response DTO, OpenAPI | Chuyển HTTP thành command/result |
| `application.handler` | Use case và transaction boundary | Điều phối domain, port, persistence, outbox |
| `application.port` | Interface use case cần | Tách application khỏi JPA/JDBC/Kafka |
| `domain` | Aggregate, value object, state transition | Invariant thuần Java |
| `infrastructure.persistence` | JPA/JDBC adapter, entity, mapping | Implement persistence port |
| `infrastructure.messaging` | Kafka listener/router/publisher | Cổng Kafka vào và ra |
| `infrastructure.security/web/config` | JWT, filter, lỗi, bean | Framework boundary |

Muốn hiểu nghiệp vụ, hãy bắt đầu từ handler. Controller/listener là cổng vào; adapter là cổng ra; quy tắc tiền và trạng thái nằm trong domain/policy.

## 4. Luồng `POST /api/v1/payments`

```http
POST /api/v1/payments
Authorization: Bearer <token có payment:write>
Idempotency-Key: <khóa duy nhất cho một ý định>
X-Correlation-Id: <không bắt buộc>
Content-Type: application/json
```

### 4.1 Request đi qua file nào?

| Bước | File/class | Bên trong làm gì |
| --- | --- | --- |
| 1 | [`CorrelationIdWebFilter`](../../services/api-gateway/src/main/java/com/payflow/gateway/web/CorrelationIdWebFilter.java) | Nhận hoặc sinh `X-Correlation-Id`, gắn request/response và log context |
| 2 | Gateway [`SecurityConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/SecurityConfig.java) | Xác minh JWT bằng Keycloak và yêu cầu `payment:write` |
| 3 | [`GatewayRoutesConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/GatewayRoutesConfig.java) | Match `/api/v1/payments/**`, forward sang Payment Service |
| 4 | [`CorrelationIdFilter`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/web/CorrelationIdFilter.java) | Khôi phục correlation ID tại service và đưa vào MDC/response |
| 5 | Payment [`SecurityConfig`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/security/SecurityConfig.java) | Xác minh JWT và scope lần hai |
| 6 | [`CreatePaymentRequest`](../../services/payment-service/src/main/java/com/payflow/payment/api/request/CreatePaymentRequest.java) | Jakarta Validation kiểm tra request shape, amount, currency, trường bắt buộc |
| 7 | [`PaymentController`](../../services/payment-service/src/main/java/com/payflow/payment/api/PaymentController.java) | Kiểm tra idempotency key, lấy `merchant_id`, map DTO thành command |
| 8 | [`CreatePaymentCommand`](../../services/payment-service/src/main/java/com/payflow/payment/application/command/CreatePaymentCommand.java) | Input use case không còn phụ thuộc HTTP |
| 9 | [`CreatePaymentHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/CreatePaymentHandler.java) | Điều phối idempotency, merchant, aggregate, saga, database và outbox |
| 10 | [`Payment`](../../services/payment-service/src/main/java/com/payflow/payment/domain/model/Payment.java) | Kiểm tra invariant và thực hiện transition hợp lệ |
| 11 | JPA adapters | Lưu payment/history/saga/idempotency/outbox |
| 12 | [`ApiResponse`](../../services/payment-service/src/main/java/com/payflow/payment/api/response/ApiResponse.java) | Bọc result, timestamp, correlation ID; trả `202` |

### 4.2 Bên trong `CreatePaymentHandler`

1. Tạo scope `merchantId + create-payment` bằng
   [`IdempotencyScope`](../../services/payment-service/src/main/java/com/payflow/payment/application/idempotency/IdempotencyScope.java).
2. Chuẩn hóa và hash payload bằng
   [`RequestFingerprint`](../../services/payment-service/src/main/java/com/payflow/payment/application/idempotency/RequestFingerprint.java).
3. Đọc idempotency record cũ ngoài write transaction:
   - cùng key, cùng fingerprint: replay kết quả cũ;
   - cùng key, khác fingerprint: ném conflict;
   - chưa có: tiếp tục.
4. Mở local transaction bằng `TransactionTemplate`.
5. Đọc merchant qua
   [`MerchantCatalog`](../../services/payment-service/src/main/java/com/payflow/payment/application/port/MerchantCatalog.java); adapter thật là
   [`JpaMerchantCatalog`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/persistence/JpaMerchantCatalog.java).
6. Tạo `PaymentIntake`, `Money`, merchant/fee snapshot rồi gọi `Payment.create(...)`. Domain kiểm tra merchant, currency, amount và fee.
7. Tạo acceptance snapshot, sau đó gọi `payment.submitForRisk(...)`. Do đó phản hồi intake có thể ghi `CREATED`, còn aggregate lưu ở bước `RISK_CHECKING`.
8. Lưu idempotent response với replay window 24 giờ.
9. Lưu payment và status history qua
   [`PaymentRepository`](../../services/payment-service/src/main/java/com/payflow/payment/application/port/PaymentRepository.java) /
   [`JpaPaymentRepository`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/persistence/JpaPaymentRepository.java).
10. Tạo [`PaymentSaga`](../../services/payment-service/src/main/java/com/payflow/payment/domain/model/PaymentSaga.java) và lưu saga.
11. Tạo `payment.created`, append vào outbox qua
    [`JpaOutboxAppender`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/persistence/JpaOutboxAppender.java).
12. Commit một lần: payment, history, saga, idempotency và outbox cùng thành công hoặc cùng rollback.

Không có `KafkaTemplate.send(...)` trực tiếp trong transaction API. Database commit trước; outbox publisher gửi Kafka sau.

Nếu hai request cùng key chạy đồng thời, unique constraint chọn một request thắng. Request thua rollback rồi đọc kết quả của request thắng **ngoài failed transaction** để replay, nên không tạo hai payment cho cùng một ý định.

[`GlobalExceptionHandler`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/web/GlobalExceptionHandler.java) map validation/application/domain/security exception sang RFC Problem Details có error code ổn định. Lỗi bất ngờ chỉ log chi tiết ở server; client không nhận stack trace hoặc SQL.

## 5. Outbox gửi Kafka sau `202`

1. Handler append JSON [`EventEnvelope`](../../libs/event-contracts/src/main/java/com/payflow/events/EventEnvelope.java) vào outbox trong transaction nghiệp vụ.
2. [`OutboxPollingJob`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/OutboxPollingJob.java) chạy định kỳ.
3. [`PublishOutboxHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/PublishOutboxHandler.java) claim một batch bằng lease ngắn.
4. [`JdbcOutboxLeaseStore`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/JdbcOutboxLeaseStore.java) dùng PostgreSQL để tránh hai publisher giữ cùng row và cho worker khác reclaim khi lease hết hạn.
5. [`KafkaOutboxTransport`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/KafkaOutboxTransport.java) gửi JSON đã lưu, dùng aggregate/payment ID làm key và chờ broker ack có timeout.
6. Publisher mark `PUBLISHED`, lên lịch retry/backoff hoặc mark `FAILED` khi hết retry.

Kafka có thể đã nhận nhưng app timeout trước khi mark published, tạo event trùng. Vì vậy cam kết là **at-least-once + idempotent consumer**, không phải exactly-once end-to-end.

Contract dùng chung nằm ở:

- [`PayFlowTopics`](../../libs/event-contracts/src/main/java/com/payflow/events/PayFlowTopics.java)
- [`EventType`](../../libs/event-contracts/src/main/java/com/payflow/events/EventType.java)
- [`EventHeaders`](../../libs/event-contracts/src/main/java/com/payflow/events/EventHeaders.java)
- [`EventEnvelope`](../../libs/event-contracts/src/main/java/com/payflow/events/EventEnvelope.java)
- DTO trong [`libs/event-contracts`](../../libs/event-contracts/src/main/java/com/payflow/events/)

## 6. Happy path payment qua Kafka

| Bước | Event | Consumer → handler | Transaction tạo ra gì? |
| --- | --- | --- | --- |
| 1 | `payment.created` | [`RiskPaymentKafkaListener`](../../services/risk-service/src/main/java/com/payflow/risk/infrastructure/messaging/RiskPaymentKafkaListener.java) → [`RiskPaymentEventRouter`](../../services/risk-service/src/main/java/com/payflow/risk/infrastructure/messaging/RiskPaymentEventRouter.java) → [`HandlePaymentCreatedHandler`](../../services/risk-service/src/main/java/com/payflow/risk/application/handler/HandlePaymentCreatedHandler.java) | Inbox + risk assessment + outbox `risk.assessment.completed` |
| 2 | `risk.assessment.completed` | [`PaymentWorkflowKafkaListener`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/PaymentWorkflowKafkaListener.java) → [`PaymentWorkflowEventRouter`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/PaymentWorkflowEventRouter.java) → [`HandlePaymentWorkflowEventHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/HandlePaymentWorkflowEventHandler.java) | Payment/Saga update + `account.reserve.requested` nếu approved |
| 3 | `account.reserve.requested` | [`AccountLedgerWorkflowKafkaListener`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/infrastructure/messaging/AccountLedgerWorkflowKafkaListener.java) → router → `HandleReserveFundsRequestedHandler` | Inbox + lock account + reservation + `account.funds-reserved` |
| 4 | `account.funds-reserved` | Payment listener/router/handler | Payment `PROCESSING`, lưu reservation fact + `ledger.post-payment.requested` |
| 5 | `ledger.post-payment.requested` | Account/Ledger listener/router → `HandlePostPaymentRequestedHandler` | Inbox + journal kép cân bằng + `ledger.payment-posted` |
| 6 | `ledger.payment-posted` | Payment listener/router/handler | Lưu journal fact + `account.capture.requested` |
| 7 | `account.capture.requested` | Account/Ledger listener/router → `HandleCaptureFundsRequestedHandler` | Inbox + giảm reserved + reservation `CAPTURED` + `account.funds-captured` |
| 8 | `account.funds-captured` | Payment listener/router/handler | Saga hoàn tất, Payment `SUCCEEDED` + `payment.succeeded` |
| 9 | `payment.succeeded` | [`NotificationOutcomeKafkaListener`](../../services/notification-service/src/main/java/com/payflow/notification/infrastructure/messaging/NotificationOutcomeKafkaListener.java) → router → `CreateOutcomeNotificationHandler` | Inbox + notification duy nhất theo business key |

Listener dùng manual acknowledgement. Chỉ `ack` sau khi handler transaction commit; exception được ném lại cho Kafka retry/DLT.

### 6.1 Risk bên trong

[`HandlePaymentCreatedHandler`](../../services/risk-service/src/main/java/com/payflow/risk/application/handler/HandlePaymentCreatedHandler.java):

1. Router kiểm tra envelope, event type và Kafka key.
2. [`RedisRiskSignalProvider`](../../services/risk-service/src/main/java/com/payflow/risk/infrastructure/redis/RedisRiskSignalProvider.java) dùng Lua cập nhật velocity atomically và deduplicate payment ID.
3. Trong SQL transaction, ghi inbox trước; event trùng không chạy nghiệp vụ lần hai.
4. [`RiskRuleEngine`](../../services/risk-service/src/main/java/com/payflow/risk/domain/policy/RiskRuleEngine.java) chạy rule, giới hạn score 0–100, trả approved/review/rejected.
5. [`JdbcRiskAssessmentStore`](../../services/risk-service/src/main/java/com/payflow/risk/infrastructure/persistence/JdbcRiskAssessmentStore.java) lưu assessment.
6. Append `risk.assessment.completed` vào Risk outbox.

Signal enrichment chưa có trong event v1 được đặt neutral rõ ràng; code không giả vờ đã tích hợp fraud provider bên ngoài.

### 6.2 Payment Saga consumer bên trong

[`PaymentWorkflowEventRouter`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/PaymentWorkflowEventRouter.java) deserialize và route theo `eventType`. Nghiệp vụ nằm ở
[`HandlePaymentWorkflowEventHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/HandlePaymentWorkflowEventHandler.java):

1. `ProcessedEventStore.recordIfNew(...)` ghi inbox đầu transaction.
2. `eventId` đã có thì trả `DUPLICATE`, không áp side effect lần hai.
3. Load Payment và Saga cùng version.
4. Policy kiểm tra event facts và state transition.
5. Cập nhật aggregate/saga bằng optimistic locking.
6. Append event tiếp theo, giữ `correlationId`, đặt `causationId` là event đang xử lý.
7. Commit rồi listener mới ack offset.

Policy quan trọng:

- [`ApplyRiskAssessmentPolicy`](../../services/payment-service/src/main/java/com/payflow/payment/application/saga/ApplyRiskAssessmentPolicy.java)
- [`PaymentFundsReservationPolicy`](../../services/payment-service/src/main/java/com/payflow/payment/application/saga/PaymentFundsReservationPolicy.java)
- [`PaymentFinalizationPolicy`](../../services/payment-service/src/main/java/com/payflow/payment/application/saga/PaymentFinalizationPolicy.java)
- [`PaymentSagaEventFactory`](../../services/payment-service/src/main/java/com/payflow/payment/application/saga/PaymentSagaEventFactory.java)

### 6.3 Account và Ledger bên trong

Hai boundary cùng deployable nhưng trách nhiệm tách biệt:

- Account quản lý `available`/`reserved`, reservation, refund credit.
- Ledger quản lý journal/entry bất biến; tổng debit phải bằng tổng credit.

| Handler | Bên trong làm gì |
| --- | --- |
| [`HandleReserveFundsRequestedHandler`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/account/application/handler/HandleReserveFundsRequestedHandler.java) | Inbox → row lock account → kiểm tra số dư → available sang reserved → reservation → outbox |
| [`HandlePostPaymentRequestedHandler`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/ledger/application/handler/HandlePostPaymentRequestedHandler.java) | Inbox → journal duplicate check → journal kép cân bằng → JDBC save → outbox |
| [`HandleCaptureFundsRequestedHandler`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/account/application/handler/HandleCaptureFundsRequestedHandler.java) | Inbox → lock account/reservation → giảm reserved → reservation `CAPTURED` → outbox |
| [`HandleReleaseFundsRequestedHandler`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/account/application/handler/HandleReleaseFundsRequestedHandler.java) | Compensation: trả reserved về available nếu chưa có financial finalization |

Invariant nằm ở
[`ReserveFundsPolicy`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/account/application/reservation/ReserveFundsPolicy.java),
[`CaptureFundsPolicy`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/account/application/reservation/CaptureFundsPolicy.java),
[`ReleaseFundsPolicy`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/account/application/reservation/ReleaseFundsPolicy.java) và
[`PaymentJournalFactory`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/ledger/application/payment/PaymentJournalFactory.java).

## 7. Nhánh lỗi và compensation

| Tình huống | Kết quả |
| --- | --- |
| Risk `REJECTED` | Payment bị từ chối, Saga kết thúc; không reserve tiền |
| Risk `REVIEW` | Payment `MANUAL_REVIEW_REQUIRED`; workflow tự động dừng, đây không phải lỗi hạ tầng |
| Không đủ số dư | Account phát reservation failed; Payment `FAILED` |
| Ledger fail trước khi có journal | Payment yêu cầu release; Account trả reserved về available |
| Event trùng | Inbox nhận diện `eventId`; không áp side effect lần hai |
| Consumer chết sau commit, trước ack | Kafka giao lại; inbox biến lần giao lại thành no-op |
| Publisher chết sau claim | Lease hết hạn; worker khác reclaim |
| Saga quá hạn | [`SagaRecoveryJob`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/recovery/SagaRecoveryJob.java) retry/compensate hoặc đưa manual review theo facts |

Không có distributed ACID transaction giữa các database. Saga và compensation xử lý eventual consistency.

## 8. Luồng `GET /api/v1/payments/{paymentId}`

1. Gateway correlation → JWT/scope `payment:read` → route.
2. Payment correlation → JWT/scope check lần hai.
3. [`PaymentController`](../../services/payment-service/src/main/java/com/payflow/payment/api/PaymentController.java) lấy `merchant_id` từ JWT.
4. [`GetPaymentHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/GetPaymentHandler.java) mở read-only transaction.
5. Repository query bằng **cả** `paymentId` và `merchantId`.
6. Handler map sang [`PaymentDetail`](../../services/payment-service/src/main/java/com/payflow/payment/application/PaymentDetail.java).
7. Controller trả `200` trong `ApiResponse`.

GET không phát Kafka, không tạo outbox, không đổi payment. Client poll cho đến `SUCCEEDED`, `FAILED` hoặc `MANUAL_REVIEW_REQUIRED`.

## 9. Luồng refund

### 9.1 API intake

1. `POST /api/v1/payments/{paymentId}/refunds` cần `payment:write` và `Idempotency-Key`.
2. `PaymentController` lấy `merchant_id` và JWT `sub`; `sub` là actor ID audit.
3. [`CreateRefundRequest`](../../services/payment-service/src/main/java/com/payflow/payment/api/request/CreateRefundRequest.java) validate body.
4. [`CreateRefundHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/CreateRefundHandler.java) tạo scoped fingerprint và xử lý replay/conflict.
5. Trong transaction, `RefundPaymentStore.findForRefund(paymentId, merchantId)` lock Payment row.
6. Kiểm tra idempotency lần nữa sau lock để đóng race window.
7. `Payment.reserveRefund(...)` chỉ cho payment thành công/đã refund một phần, amount dương, không vượt refundable capacity.
8. Tạo Refund `CREATED`, giữ trước refund capacity, lưu payment/refund/idempotency.
9. Append `refund.requested`, commit và trả `202`.

### 9.2 Financial workflow

| Bước | Handler | Kết quả |
| --- | --- | --- |
| 1 | [`HandleRefundRequestedHandler`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/ledger/application/handler/HandleRefundRequestedHandler.java) | Inbox + reversal journal + `ledger.refund-posted` |
| 2 | [`HandleRefundWorkflowEventHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/HandleRefundWorkflowEventHandler.java) | Lock Payment rồi Refund, Refund `PROCESSING` + `account.refund-credit.requested` |
| 3 | [`HandleRefundCreditRequestedHandler`](../../services/account-ledger-service/src/main/java/com/payflow/accountledger/account/application/handler/HandleRefundCreditRequestedHandler.java) | Lock account, credit idempotently theo refund ID + `account.refund-credited` |
| 4 | Payment refund handler | Complete refund, cập nhật capacity, Payment `PARTIALLY_REFUNDED`/`REFUNDED` + `refund.succeeded` |
| 5 | Notification | Tạo notification kết quả |

Ledger fail trước khi có journal thì refund fail và capacity được trả. Nếu journal đã post nhưng account credit chưa chắc chắn, code không tự fail/release vì có thể làm sai tiền; recovery/manual review phải dựa trên financial facts.

## 10. Notification delivery

[`CreateOutcomeNotificationHandler`](../../services/notification-service/src/main/java/com/payflow/notification/application/notification/CreateOutcomeNotificationHandler.java) ghi inbox và notification trong một transaction. Sau đó:

1. [`DeliverPendingNotificationsHandler`](../../services/notification-service/src/main/java/com/payflow/notification/application/delivery/DeliverPendingNotificationsHandler.java) claim batch bằng lease ngắn.
2. Gọi email adapter ngoài database transaction dài.
3. Store conditionally mark `SENT`, retry hoặc `FAILED`.
4. Adapter hiện tại là [`InMemoryEmailDeliveryAdapter`](../../services/notification-service/src/main/java/com/payflow/notification/infrastructure/delivery/InMemoryEmailDeliveryAdapter.java), chưa phải email provider thật.

Notification fail không rollback payment thành công.

## 11. File map theo service

### API Gateway

| File | Chức năng |
| --- | --- |
| [`ApiGatewayApplication`](../../services/api-gateway/src/main/java/com/payflow/gateway/ApiGatewayApplication.java) | Spring Boot entry point |
| [`application.yml`](../../services/api-gateway/src/main/resources/application.yml) | Port, issuer/JWK, downstream URI, actuator |
| [`GatewayDownstreamProperties`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/GatewayDownstreamProperties.java) | Bind downstream URI thành typed properties |
| [`GatewayRoutesConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/GatewayRoutesConfig.java) | Route `/api/v1/payments/**` sang Payment |
| [`SecurityConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/SecurityConfig.java) | JWT và deny-by-default scope rules |
| [`CorrelationIdWebFilter`](../../services/api-gateway/src/main/java/com/payflow/gateway/web/CorrelationIdWebFilter.java) | Correlation ID cho reactive gateway |
| [`ProblemDetailErrorWriter`](../../services/api-gateway/src/main/java/com/payflow/gateway/web/ProblemDetailErrorWriter.java) | Chuẩn hóa auth error tại gateway |

### Payment Service

| Nhóm file | Chức năng |
| --- | --- |
| `api/PaymentController` | Ba REST entry point: create, get, refund |
| `api/request/*`, `api/response/*` | HTTP validation và response envelope |
| `api/PaymentOpenApiConfig` | Metadata Swagger/OpenAPI local |
| `application/command/*` | Input use case độc lập HTTP |
| `application/handler/Create*` | REST write transaction boundary |
| `application/handler/GetPaymentHandler` | Read-only tenant-safe query |
| `application/handler/Handle*WorkflowEventHandler` | Kafka consume/Saga transaction boundary |
| `application/handler/PublishOutboxHandler` | Claim/publish/mark với retry |
| `application/handler/RecoverOverdueSagasHandler` | Xử lý Saga quá hạn |
| `application/port/*` | Interface persistence/catalog/inbox/outbox/transport |
| `application/idempotency/*` | Scope, fingerprint, stored response |
| `application/saga/*`, `application/refund/*` | Workflow policy và event factory |
| `domain/model/Payment` | Aggregate, state, fee snapshot, refund capacity |
| `domain/model/PaymentSaga` | Saga facts/step/status/version |
| `domain/model/Refund` | Refund state machine |
| `domain/model/Money` | Value object tiền; không dùng `double` |
| `infrastructure/persistence/*` | JPA/JDBC entity, mapping, adapter |
| `infrastructure/messaging/*Listener/*Router` | Kafka entry, validation, deserialize, dispatch |
| `infrastructure/messaging/*Outbox*` | Schedule, lease, Kafka transport |
| `infrastructure/security`, `web`, `recovery` | JWT, correlation/error, Saga recovery |
| [`db/migration`](../../services/payment-service/src/main/resources/db/migration/) | Flyway V1–V7 cho payment/refund/saga/inbox/outbox |
| [`db/seed`](../../services/payment-service/src/main/resources/db/seed/) | Local seed, không phải production truth |

### Risk Service

| Nhóm | Chức năng |
| --- | --- |
| `RiskPaymentKafkaListener/EventRouter` | Nhận và route `payment.created` |
| `HandlePaymentCreatedHandler` | Inbox + risk + assessment + outbox |
| `RiskRuleEngine` | Rule deterministic và decision |
| `RedisRiskSignalProvider` | Velocity/dedup nhanh; không giữ money truth |
| `JdbcRiskAssessmentStore` | Assessment PostgreSQL |
| `JdbcProcessedEventStore` | Inbox chống event trùng |
| Outbox classes | Phát kết quả risk tin cậy |
| [`V1 migration`](../../services/risk-service/src/main/resources/db/migration/V1__risk_assessment_runtime.sql) | Risk/inbox/outbox schema |

### Account/Ledger Service

| Nhóm | Chức năng |
| --- | --- |
| `AccountLedgerWorkflowKafkaListener/EventRouter` | Route command sang account hoặc ledger |
| `account/application/handler/*` | Reserve, capture, release, refund credit |
| `account/.../*Policy` | Invariant số dư/reservation/compensation |
| `JpaAccountReservationStore` | Row locking và account/reservation persistence |
| `ledger/application/handler/*` | Post payment/refund journal |
| `PaymentJournalFactory`, `RefundJournalFactory` | Tạo debit/credit cân bằng |
| `Jdbc*JournalStore` | Ghi immutable journal bằng JDBC |
| Operational adapters | Inbox/outbox reliability state |
| [`V1 migration`](../../services/account-ledger-service/src/main/resources/db/migration/V1__account_ledger_refund_runtime.sql) | Account/ledger/operational schema |

### Notification Service

| Nhóm | Chức năng |
| --- | --- |
| `NotificationOutcomeKafkaListener/EventRouter` | Chỉ nhận outcome cuối payment/refund |
| `CreateOutcomeNotificationHandler` | Inbox + notification transaction |
| `OutcomeNotificationFactory` | Event thành notification intent/content |
| `DeliverPendingNotificationsHandler` | Claim và delivery batch |
| `NotificationDeliveryPolicy` | Retry/backoff/terminal failure |
| `JdbcNotificationStore/DeliveryStore` | Business row và delivery lease/state |
| `InMemoryEmailDeliveryAdapter` | Mock adapter local hiện tại |
| [`V1 migration`](../../services/notification-service/src/main/resources/db/migration/V1__notification_runtime.sql) | Notification/inbox/delivery schema |

## 12. Database ownership và transaction boundary

Mỗi service chỉ đọc/ghi database của mình:

| Owner | Dữ liệu |
| --- | --- |
| Payment | Payment, refund, history, saga, idempotency, inbox, outbox, local merchant catalog |
| Risk | Assessment, inbox, outbox |
| Account/Ledger | Account/reservation/refund credit; journal/entry/mapping; inbox/outbox |
| Notification | Notification, inbox, delivery state |

Hai ranh giới phải nhớ:

- **REST write transaction**: business data + idempotency + outbox commit cùng nhau.
- **Kafka consume transaction**: inbox + state change + outgoing outbox commit cùng nhau; sau commit mới ack.

Không service nào join/query database của service khác. REST không giữ transaction mở để chờ Kafka.

## 13. Cấu hình runtime nằm ở đâu

| File | Chứa gì |
| --- | --- |
| [`docker-compose.yml`](../../docker-compose.yml) | Container, network, volume, healthcheck, dependency, port |
| [`.env.example`](../../.env.example) | Tên biến; copy thành `.env`, không commit secret |
| [`realm-payflow.json`](../../infrastructure/keycloak/realm-payflow.json) | Realm, client, scope, local `merchant_id` claim |
| `services/*/src/main/resources/application.yml` | Datasource, Kafka, Redis, OAuth, outbox/recovery, actuator |
| [`smoke-mvp.ps1`](../../infrastructure/scripts/smoke-mvp.ps1) | Lấy token, tạo payment, test idempotency, theo dõi happy path |

Gateway không route `/internal/v1/**`; workflow nội bộ hiện trao đổi bằng Kafka. Swagger UI mô tả/test REST, nên không hiển thị Kafka consumer như API endpoint. Kafka contract nằm trong `docs/events` và `libs/event-contracts`.

## 14. Cách debug một payment

Giữ ba ID:

- `paymentId`: aggregate nghiệp vụ.
- `correlationId`: nối log xuyên workflow.
- `eventId`: nhận diện message/duplicate.

Kiểm tra theo thứ tự:

1. Gateway log: JWT/scope và route có qua không?
2. Payment GET/log: payment ở status/step nào?
3. Payment outbox: `PENDING`, `IN_FLIGHT`, `PUBLISHED` hay `FAILED`?
4. Risk inbox/assessment/outbox: đã consume và phát kết quả chưa?
5. Payment inbox/saga: event risk/account/ledger đã áp dụng chưa?
6. Account/Ledger inbox, reservation, journal: dừng trước hay sau financial fact nào?
7. Consumer group/DLT: message đang retry hay vào DLT?
8. Notification chỉ kiểm tra sau outcome cuối.

Không replay mù event tài chính. Xác nhận inbox, outbox, reservation, journal và saga facts trước; xem
[outbox recovery](../runbooks/outbox-recovery.md), [workflow DLT](../runbooks/payment-workflow-dlt.md) và
[Saga manual review](../runbooks/saga-manual-review.md).

## 15. Thứ tự đọc source đề xuất

1. `PaymentController` để biết API public.
2. `CreatePaymentHandler` để thấy intake transaction.
3. `Payment` và `PaymentSaga` để hiểu invariant/state machine.
4. `JpaOutboxAppender` → `OutboxPollingJob` → `PublishOutboxHandler` → `KafkaOutboxTransport`.
5. Risk listener → router → handler → `RiskRuleEngine`.
6. Payment workflow listener → router → workflow handler.
7. Account/Ledger listener → router → các handler reserve/post/capture/release.
8. Notification listener → router → create/delivery handler.
9. `CreateRefundHandler` → refund workflow handler.
10. Flyway migration và integration test để đối chiếu constraint/concurrency.

## 16. Những gì hiện chưa có

- Chưa có user-service hay bảng người dùng ứng dụng.
- Chưa có browser login/Authorization Code + PKCE cho merchant/operator.
- Chưa dùng realm roles làm RBAC cho Payment API.
- Public REST hiện là create/get payment và create refund; Kafka không thay thế query/admin API tương lai.
- Notification email đang in-memory, chưa nối provider thật.
- Kafka không exactly-once end-to-end; an toàn đến từ outbox, inbox, idempotency, locking và invariant.

Tài liệu liên quan:

- [Business processing reference](business-processing-reference.md): nghiệp vụ và phương án thay thế.
- [Code flow guide](code-flow-guide.md): package và implementation theo lát cắt.
- [Shared libraries và runtime configuration](shared-libraries-and-runtime-configuration-guide.md): `libs`, Maven, `.env`, Compose, Flyway, Kafka và Keycloak.
- [REST và Kafka flow guide](rest-kafka-flow-guide.md): giải thích nhập môn REST/Kafka.
- [MVP Docker runbook](../runbooks/mvp-docker.md): cách chạy toàn bộ stack.
