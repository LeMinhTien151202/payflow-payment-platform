# Shared libraries và luồng cấu hình runtime của PayFlow

Tài liệu này giải thích các phần nằm **ngoài code nghiệp vụ của từng service**:

- `libs/observability-support`
- `libs/error-contract`
- `libs/event-contracts`
- Parent Maven, Maven Wrapper và dependency graph
- `.env`, `docker-compose.yml`, `application.yml`
- PostgreSQL bootstrap, Flyway, Kafka topic init, Keycloak realm import
- Docker image, health check, smoke test và CI

Đọc tài liệu này cùng [payment-api-code-walkthrough.md](payment-api-code-walkthrough.md): file kia theo request/API và Saga; file này theo dependency, contract và configuration. Bản đồ đầy đủ cho phần còn lại nằm ở [services-folder-reference.md](services-folder-reference.md) và [infrastructure-and-repository-folder-reference.md](infrastructure-and-repository-folder-reference.md).

## 1. Hiểu đúng về thư mục `libs`

Ba module trong `libs` **không phải microservice**:

- Không có `@SpringBootApplication`.
- Không mở port.
- Không có container riêng.
- Không kết nối database hoặc Kafka bằng chính nó.
- Không có vòng đời chạy độc lập.

Maven compile mỗi module thành một JAR. Service khai báo dependency sẽ nhận class của JAR đó trên classpath và gọi chúng như code Java bình thường.

```text
source trong libs
      │ Maven compile/package
      ▼
observability-support.jar / error-contract.jar / event-contracts.jar
      │ được nhúng vào service JAR cần nó
      ▼
Gateway / Payment / Risk / Account-Ledger / Notification chạy class dùng chung
```

Shared library chỉ chứa **technical convention hoặc wire contract ổn định**. Nó không được chứa:

- JPA entity/repository của service;
- Flyway migration dùng chung;
- payment/account/refund aggregate;
- state machine hoặc business policy dùng chung;
- code truy cập database của nhiều service.

Lý do: nếu đưa business logic và entity vào `libs`, các service sẽ không còn ownership độc lập và thay đổi một domain có thể phá toàn hệ thống.

## 2. Dependency graph Maven

[`pom.xml`](../../pom.xml) ở root là parent và reactor aggregator. Nó khóa:

- Java 21;
- Spring Boot `4.0.7`;
- Spring Cloud `2025.1.2`;
- Springdoc `3.0.3`;
- module build order;
- Surefire cho unit/slice test;
- Failsafe cho `*IT.java`;
- profile `no-docker` loại test mang tag `docker`.

Reactor order:

```text
payflow-parent
├── libs/observability-support
├── libs/error-contract ───────> observability-support
├── libs/event-contracts ──────> observability-support
├── libs/network-security-support
├── services/api-gateway ──────> observability-support + error-contract
├── services/payment-service ──> observability + error + event contracts
├── services/account-ledger ───> observability-support + event-contracts   (profile mvp)
├── services/account-service ──> observability-support + event-contracts   (profile full)
├── services/ledger-service ───> observability-support + event-contracts   (profile full)
├── services/merchant-service ─> observability + error + network security
├── services/reporting-service > observability-support + event-contracts   (profile full)
├── services/risk-service ─────> observability-support + event-contracts
└── services/notification ─────> observability + event contracts + network security
```

Reactor hiện có 14 module (4 libs + 10 service); Maven hiển thị thêm parent nên full build có 15 project.
`merchant-service` là service duy nhất dùng `error-contract` mà không dùng `event-contracts`: nó chỉ
phục vụ REST, chưa publish event nào. `account-ledger-service` (gộp) và cặp
`account-service`/`ledger-service` (tách) đều nằm trong reactor và đều được build ở mọi lệnh
`mvnw verify`, dù runtime chỉ chạy một trong hai theo profile Compose.

`dependencyManagement` ở parent chỉ khóa version; module service vẫn phải khai báo `<dependency>` thì mới dùng được.

Ví dụ Dockerfile chạy:

```text
./mvnw ... -pl services/payment-service -am package
```

- `-pl`: chỉ chọn Payment Service làm project đích.
- `-am` (`also-make`): build luôn các module nội bộ Payment phụ thuộc, tức ba `libs`.
- Kết quả cuối là service JAR chứa dependency cần thiết, không phải chạy bốn process.

## 3. `observability-support`

### 3.1 File trong module

| File | Chức năng |
| --- | --- |
| [`pom.xml`](../../libs/observability-support/pom.xml) | Module Java thuần, cố ý không phụ thuộc Spring để dùng được ở MVC lẫn WebFlux |
| [`CorrelationId.java`](../../libs/observability-support/src/main/java/com/payflow/observability/CorrelationId.java) | Định nghĩa header/MDC key và kiểm tra hoặc sinh correlation ID |
| [`CorrelationIdTest.java`](../../libs/observability-support/src/test/java/com/payflow/observability/CorrelationIdTest.java) | Kiểm tra null/rỗng/quá dài/ký tự nguy hiểm bị thay, ID hợp lệ được giữ |

### 3.2 `CorrelationId` làm gì?

Các constant:

| Thành phần | Giá trị/ý nghĩa |
| --- | --- |
| `HEADER` | `X-Correlation-Id`, tên dùng ở HTTP và Kafka header |
| `MDC_KEY` | `correlationId`, field trong structured log |
| `MAX_LENGTH` | 64 ký tự, tránh client bơm dữ liệu lớn vào log |

Các method:

| Method | Cách dùng |
| --- | --- |
| `generate()` | Sinh UUID mới |
| `isSafe(value)` | Chỉ chấp nhận chữ, số, `-`, `_`, `.`; chặn CR/LF và control character |
| `resolveOrGenerate(value)` | Giữ ID an toàn; nếu thiếu/hỏng thì sinh ID mới |

### 3.3 Luồng correlation ID

```text
Client X-Correlation-Id
  │
  ▼
Gateway CorrelationIdWebFilter
  ├─ gọi CorrelationId.resolveOrGenerate
  ├─ gắn request attribute
  ├─ forward HTTP header sang Payment
  ├─ gắn response header
  └─ đặt MDC khi log
  │
  ▼
Payment CorrelationIdFilter
  ├─ kiểm tra lại
  ├─ đặt request attribute + MDC
  └─ JpaOutboxAppender lấy ID từ MDC
  │
  ▼
EventEnvelope.correlationId + Kafka X-Correlation-Id header
  │
  ▼
Outbox publisher của service bind ID vào MDC khi publish/log
  │
  ▼
event kế tiếp dùng lại correlationId; causationId chỉ event ngay trước
```

Các file sử dụng chính:

- Gateway: [`CorrelationIdWebFilter`](../../services/api-gateway/src/main/java/com/payflow/gateway/web/CorrelationIdWebFilter.java).
- Payment HTTP: [`CorrelationIdFilter`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/web/CorrelationIdFilter.java).
- Payment response: [`PaymentController`](../../services/payment-service/src/main/java/com/payflow/payment/api/PaymentController.java).
- Payment outbox: [`JpaOutboxAppender`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/persistence/JpaOutboxAppender.java).
- Các `PublishOutboxHandler` bind correlation ID từ stored headers vào MDC trong lúc publish.

Module này chưa phải full tracing/OpenTelemetry SDK. Nó chuẩn hóa correlation identity để log và event có thể nối với nhau.

## 4. `error-contract`

### 4.1 Dependency và phạm vi

[`error-contract/pom.xml`](../../libs/error-contract/pom.xml) phụ thuộc:

- Spring Web để tạo `ProblemDetail`;
- `observability-support` để chỉ đưa correlation ID an toàn vào error response.

Gateway và Payment Service dùng module này. Risk/Account-Ledger/Notification hiện không có public business REST API nên không cần shared HTTP error builder trong flow chính.

### 4.2 Từng file

| File | Chức năng |
| --- | --- |
| [`ErrorCode.java`](../../libs/error-contract/src/main/java/com/payflow/error/ErrorCode.java) | Interface `code()` để platform code và service-specific code dùng chung builder |
| [`PayFlowErrorCode.java`](../../libs/error-contract/src/main/java/com/payflow/error/PayFlowErrorCode.java) | Mã lỗi chung: unauthenticated, forbidden, validation, not found, internal |
| [`FieldViolation.java`](../../libs/error-contract/src/main/java/com/payflow/error/FieldViolation.java) | Một lỗi field gồm `field` + message; cố ý không trả rejected value |
| [`ProblemDetails.java`](../../libs/error-contract/src/main/java/com/payflow/error/ProblemDetails.java) | Builder/enricher cho RFC 9457 Problem Details |
| [`ProblemDetailsTest.java`](../../libs/error-contract/src/test/java/com/payflow/error/ProblemDetailsTest.java) | Kiểm tra shape, code, timestamp, correlation ID và field errors |

`PaymentErrorCode` nằm trong Payment Service và implement `ErrorCode`. Điều này cho phép Payment có mã nghiệp vụ riêng mà không đẩy toàn bộ business vocabulary vào shared library:

```text
ErrorCode interface
├── PayFlowErrorCode          # lỗi platform dùng chung
└── PaymentErrorCode          # lỗi do Payment sở hữu
```

### 4.3 Luồng tạo error response

Luồng lỗi authentication tại Gateway:

```text
JWT/scope fail
  → Gateway SecurityConfig
  → ProblemDetailErrorWriter
  → PayFlowErrorCode
  → ProblemDetails.of(..., correlationId)
  → HTTP 401/403 application/problem+json
```

Luồng lỗi tại Payment Service:

```text
Controller/handler/domain ném exception
  → GlobalExceptionHandler
  → chọn HTTP status + PaymentErrorCode/PayFlowErrorCode
  → ProblemDetails.of/enrich
  → nếu validation: FieldViolation list
  → HTTP Problem Details
```

Response điển hình:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Idempotency key was reused with a different request",
  "code": "PAYMENT_IDEMPOTENCY_CONFLICT",
  "correlationId": "...",
  "timestamp": "...",
  "fieldErrors": []
}
```

`detail` là thông báo an toàn cho client. Stack trace, SQL, hostname nội bộ và secret không được đưa vào đây.

## 5. `event-contracts`

### 5.1 Module này là gì?

[`event-contracts/pom.xml`](../../libs/event-contracts/pom.xml) phụ thuộc compile-time duy nhất vào `observability-support`. Jackson chỉ là test dependency: contract record không bị gắn chặt với annotation của một JSON vendor.

Module chứa **wire contract** giữa producer và consumer:

- topic name;
- event name/version/aggregate type;
- envelope chung;
- Kafka header name;
- payload record và validation shape.

Nó không chứa:

- Kafka listener hay producer;
- `KafkaTemplate`;
- outbox SQL;
- business decision;
- state machine;
- repository.

### 5.2 Các file lõi

| File | Chức năng |
| --- | --- |
| [`PayFlowTopics.java`](../../libs/event-contracts/src/main/java/com/payflow/events/PayFlowTopics.java) | Tên topic cố định giữa mọi môi trường |
| [`EventType.java`](../../libs/event-contracts/src/main/java/com/payflow/events/EventType.java) | Gom `name + version + aggregateType`, validate giới hạn cột outbox |
| [`EventEnvelope.java`](../../libs/event-contracts/src/main/java/com/payflow/events/EventEnvelope.java) | Envelope chung chứa identity, correlation/causation, producer, time và typed data |
| [`EventHeaders.java`](../../libs/event-contracts/src/main/java/com/payflow/events/EventHeaders.java) | Tên Kafka header: correlation ID, event ID, event type, event version |

`EventEnvelope<T>` có các field:

| Field | Ý nghĩa |
| --- | --- |
| `eventId` | ID sinh **khi insert outbox**; republish phải dùng lại để consumer deduplicate |
| `eventType` | Tên contract, ví dụ `payment.created` |
| `eventVersion` | Version payload hiện tại |
| `aggregateType` | Hiện workflow chủ yếu là `PAYMENT` |
| `aggregateId` | Payment ID và Kafka key để giữ ordering per payment |
| `correlationId` | Nối toàn workflow về HTTP request ban đầu |
| `causationId` | Event ID ngay trước đã gây ra event này; null với event do HTTP tạo |
| `producer` | Service ghi outbox |
| `occurredAt` | Thời điểm business fact xảy ra, không phải lúc publisher gửi |
| `data` | Payload record cụ thể |

Factory:

- `EventEnvelope.of(...)`: dùng cho event đầu tiên do HTTP/request tạo, ví dụ `payment.created`.
- `EventEnvelope.causedBy(...)`: dùng khi consume event A rồi tạo event B; tự giữ correlation ID và gắn causation ID.

### 5.3 Topic đang định nghĩa

| Constant | Topic | Producer hiện tại | Consumer ở profile `mvp` | Consumer ở profile `full` |
| --- | --- | --- | --- | --- |
| `PAYMENT_EVENTS` | `payflow.payment.events.v1` | Payment (cả fact `payment.*` lẫn command `account.*.requested`/`ledger.post-payment.requested`) | Risk, Account-Ledger, Notification, Payment workflow | Risk, Account, Ledger, Notification, Reporting, Payment workflow |
| `ACCOUNT_EVENTS` | `payflow.account.events.v1` | Account-Ledger (`mvp`) / Account (`full`) | Payment Saga | Payment Saga |
| `LEDGER_EVENTS` | `payflow.ledger.events.v1` | Account-Ledger (`mvp`) / Ledger (`full`) | Payment Saga | Payment Saga |
| `RISK_EVENTS` | `payflow.risk.events.v1` | Risk | Payment Saga | Payment Saga |
| `REFUND_EVENTS` | `payflow.refund.events.v1` | Payment | Account-Ledger, Notification | Ledger, Notification, Reporting |
| `NOTIFICATION_COMMANDS` | `payflow.notification.commands.v1` | Chưa có | Chưa có | Chưa có |
| `SETTLEMENT_EVENTS` | `payflow.settlement.events.v1` | Dành cho Phase 3 | Chưa có service runtime | Chưa có service runtime |
| `DEAD_LETTER` | `payflow.dead-letter.v1` | Kafka recoverer của consumer | Operator/runbook | Operator/runbook |

Constant, topic name, event type và payload **không đổi giữa hai profile** — chỉ deployable nào
subscribe là khác. Ở `mvp` một service phát cả `account.*` lẫn `ledger.*`; ở `full`,
`account-service` phát `account.*` còn `ledger-service` phát `ledger.*`.

Hai chỗ dễ đọc nhầm ở bảng trên:

- Command gửi cho Account và Ledger (`account.reserve.requested`, `account.capture.requested`,
  `account.release.requested`, `account.refund-credit.requested`, `ledger.post-payment.requested`)
  đi trên **payment topic**, không phải trên `account.events`/`ledger.events`. Vì thế cả
  `account-service` lẫn `ledger-service` chỉ subscribe `PAYMENT_EVENTS` (và `ledger-service` thêm
  `REFUND_EVENTS` cho `refund.requested`). `account-service` không nghe refund topic — credit hoàn tiền
  đến với nó dưới dạng `account.refund-credit.requested` trên payment topic.
- `NOTIFICATION_COMMANDS` hiện được khai báo nhưng không có producer/consumer nào trong code;
  Notification nghe trực tiếp `PAYMENT_EVENTS` và `REFUND_EVENTS`.

Topic name cố ý không cấu hình bằng `.env`: topic khác nhau giữa môi trường dễ che giấu typo. Kafka local tắt auto-create, nên tên sai phải fail rõ.

### 5.4 Các nhóm event và payload

#### Payment-owned contracts

[`PaymentEvents.java`](../../libs/event-contracts/src/main/java/com/payflow/events/payment/PaymentEvents.java) định nghĩa:

| Event | Payload file | Ý nghĩa |
| --- | --- | --- |
| `payment.created` | [`PaymentCreatedData`](../../libs/event-contracts/src/main/java/com/payflow/events/payment/PaymentCreatedData.java) | Intake đã commit; bắt đầu risk |
| `payment.failed` | [`PaymentFailedData`](../../libs/event-contracts/src/main/java/com/payflow/events/payment/PaymentFailedData.java) | Outcome thất bại cuối |
| `payment.succeeded` | [`PaymentSucceededData`](../../libs/event-contracts/src/main/java/com/payflow/events/payment/PaymentSucceededData.java) | Ledger + capture đã commit |
| `payment.manual-review-required` | [`PaymentManualReviewRequiredData`](../../libs/event-contracts/src/main/java/com/payflow/events/payment/PaymentManualReviewRequiredData.java) | Workflow tự động dừng để review |
| `payment.cancelled` | [`PaymentCancelledData`](../../libs/event-contracts/src/main/java/com/payflow/events/payment/PaymentCancelledData.java) | Merchant hủy workflow trước khi Account reserve tiền |

#### Risk-owned contracts

[`RiskEvents.java`](../../libs/event-contracts/src/main/java/com/payflow/events/risk/RiskEvents.java) định nghĩa `risk.assessment.completed` với:

- [`RiskAssessmentCompletedData`](../../libs/event-contracts/src/main/java/com/payflow/events/risk/RiskAssessmentCompletedData.java): payment, decision, score, level, matched rules, policy version.
- [`RiskDecisionValue`](../../libs/event-contracts/src/main/java/com/payflow/events/risk/RiskDecisionValue.java): `APPROVED`, `REVIEW_REQUIRED`, `REJECTED`.
- [`RiskLevelValue`](../../libs/event-contracts/src/main/java/com/payflow/events/risk/RiskLevelValue.java): wire enum, không phải scoring policy.

Rule tính điểm thật vẫn nằm trong Risk Service. Contract chỉ quy định kết quả truyền đi có shape gì.

#### Account-owned contracts

[`AccountEvents.java`](../../libs/event-contracts/src/main/java/com/payflow/events/account/AccountEvents.java) và payload:

| Command/fact | Payload |
| --- | --- |
| `account.reserve.requested` | `AccountReserveRequestedData` |
| `account.funds-reserved` | `AccountFundsReservedData` |
| `account.funds-reservation-failed` | `AccountFundsReservationFailedData` |
| `account.capture.requested` | `AccountCaptureRequestedData` |
| `account.funds-captured` | `AccountFundsCapturedData` |
| `account.release.requested` | `AccountReleaseRequestedData` |
| `account.funds-released` | `AccountFundsReleasedData` |
| `account.refund-credit.requested` | `AccountRefundCreditRequestedData` |
| `account.refund-credited` | `AccountRefundCreditedData` |

[`AccountEventMoney`](../../libs/event-contracts/src/main/java/com/payflow/events/account/AccountEventMoney.java) chỉ validate wire amount/currency. Nó **không thay** Account domain Money/Reserve policy.

#### Ledger-owned contracts

[`LedgerEvents.java`](../../libs/event-contracts/src/main/java/com/payflow/events/ledger/LedgerEvents.java):

| Command/fact | Payload |
| --- | --- |
| `ledger.post-payment.requested` | `LedgerPostPaymentRequestedData` |
| `ledger.payment-posted` | `LedgerPaymentPostedData` |
| `ledger.payment-posting-failed` | `LedgerPaymentPostingFailedData` |
| `ledger.refund-posted` | `LedgerRefundPostedData` |
| `ledger.refund-posting-failed` | `LedgerRefundPostingFailedData` |

Payload nói “journal nào/amount nào đã được xử lý”; quy tắc tạo debit-credit cân bằng nằm trong Ledger domain/application factory.

#### Refund-owned contracts

[`RefundEvents.java`](../../libs/event-contracts/src/main/java/com/payflow/events/refund/RefundEvents.java):

| Event | Payload | Ý nghĩa |
| --- | --- | --- |
| `refund.requested` | [`RefundRequestedData`](../../libs/event-contracts/src/main/java/com/payflow/events/refund/RefundRequestedData.java) | Refund và capacity reservation đã commit |
| `refund.succeeded` | [`RefundSucceededData`](../../libs/event-contracts/src/main/java/com/payflow/events/refund/RefundSucceededData.java) | Journal reversal và account credit đã xác nhận |
| `refund.failed` | [`RefundFailedData`](../../libs/event-contracts/src/main/java/com/payflow/events/refund/RefundFailedData.java) | Thất bại an toàn trước financial finalization |

### 5.5 Luồng một event sử dụng shared contract

Ví dụ `payment.created`:

```text
CreatePaymentHandler
  │ dùng PaymentEvents.PAYMENT_CREATED
  │ tạo PaymentCreatedData
  ▼
JpaOutboxAppender
  │ EventEnvelope.of(eventId, type, paymentId, correlationId, ...)
  │ ObjectMapper serialize envelope thành JSON
  │ lấy EventHeaders constant tạo stored headers
  ▼
payment.outbox_events
  │
  ▼
PublishOutboxHandler → KafkaOutboxTransport
  │ topic = PayFlowTopics.PAYMENT_EVENTS
  │ key = aggregateId/paymentId
  │ value = JSON đã lưu
  │ headers = X-Event-Id/Type/Version/Correlation-Id
  ▼
Kafka
  │
  ▼
RiskPaymentKafkaListener
  │ nhận String payload
  ▼
RiskPaymentEventRouter
  │ kiểm tra event type/key
  │ ObjectMapper deserialize EventEnvelope<PaymentCreatedData>
  ▼
HandlePaymentCreatedHandler
  │ kiểm tra contract lần nữa
  │ ghi inbox + assessment
  │ tạo EventEnvelope.causedBy(... RiskAssessmentCompletedData ...)
  └─ append Risk outbox
```

Contract JAR không tự publish và không tự consume. Producer/consumer adapter của từng service mới làm I/O; shared record giúp hai phía dùng cùng tên và shape.

### 5.6 Versioning

- Thêm field tương thích phải cân nhắc consumer cũ và JSON fixture test.
- Breaking payload change cần tăng `eventVersion` hoặc topic version tùy mức độ.
- Đổi `eventType`, record component name, enum wire value hoặc topic name là contract change, không phải refactor nội bộ.
- Producer service vẫn là owner của event dù class nằm trong shared module.
- Contract test trong `src/test` serialize record để kiểm tra JSON thực tế, không chỉ compile Java.

## 6. `network-security-support`

Module Java thuần này chứa [`OutboundHttpUrlPolicy`](../../libs/network-security-support/src/main/java/com/payflow/security/net/OutboundHttpUrlPolicy.java).
Nó resolve DNS và chỉ cho phép HTTPS tới địa chỉ public; chặn user-info, fragment, loopback, link-local,
private và reserved address. Merchant gọi policy trước khi lưu webhook URL; Notification gọi lại ngay
trước HTTP POST để giảm cửa sổ DNS rebinding. Escape hatch local được điều khiển bằng
`PAYFLOW_WEBHOOK_ALLOW_UNSAFE_LOCAL_TARGETS=false` và mặc định luôn an toàn.

## 7. Luồng cấu hình từ `.env` vào application

### 7.1 Ba tầng cấu hình

```text
.env
  │ Docker Compose nội suy ${VARIABLE}
  ▼
docker-compose.yml environment của từng container
  │ Spring Boot đọc environment variable
  ▼
services/*/application.yml ${VARIABLE:default}
  │ bind vào Spring/Kafka/JPA hoặc @ConfigurationProperties
  ▼
Bean runtime: datasource, listener, publisher, scheduler, security
```

Ví dụ:

```text
.env:
  PAYFLOW_OUTBOX_BATCH_SIZE=100

docker-compose.yml:
  PAYFLOW_OUTBOX_BATCH_SIZE: ${PAYFLOW_OUTBOX_BATCH_SIZE}

payment/application.yml:
  payflow.outbox.batch-size: ${PAYFLOW_OUTBOX_BATCH_SIZE:100}

OutboxProperties:
  batchSize = 100

OutboxPollingJob/PublishOutboxHandler:
  claim tối đa 100 row mỗi lượt
```

`:${default}` trong `application.yml` là fallback khi chạy service trên host. Username/password không có fallback để thiếu credential phải fail thay vì dùng secret cứng.

### 7.2 `.env.example` và `.env`

| File | Vai trò |
| --- | --- |
| [`.env.example`](../../.env.example) | Template được commit; chỉ placeholder/local defaults |
| `.env` | Giá trị local thật, git-ignore; Compose đọc khi có `--env-file .env` |

Nhóm biến:

- PostgreSQL port + credential riêng từng database.
- Keycloak admin, realm issuer/JWK và client secret.
- Kafka bootstrap/delivery timeout, Redis host/port/timeout.
- Port của Gateway và service.
- Outbox polling/lease/retry.
- Kafka consumer enable/retry.
- Saga recovery timeout/retry.
- Notification delivery lease/retry.

`POSTGRES_PORT=5433` chỉ là cổng từ **host** vào container. Bên trong Compose, service vẫn gọi `postgres:5432`.

## 8. `application.yml` của từng service

### 8.1 Cấu hình chung

| Section | Tác dụng |
| --- | --- |
| `spring.application.name` | Tên service cho log/metrics/context |
| `spring.datasource` | Database URL + credential của đúng owner |
| `spring.jpa.hibernate.ddl-auto=validate` | Hibernate chỉ kiểm tra mapping, không tạo/sửa schema |
| `spring.flyway` | Migration location và schema owner |
| `spring.security.oauth2.resourceserver.jwt` | Issuer và JWK để validate JWT |
| `spring.kafka.producer` | String serializer, `acks=all`, idempotent producer, timeout |
| `spring.kafka.consumer` | Manual offset, String JSON input |
| `spring.kafka.listener.ack-mode=manual_immediate` | Code ack sau transaction thành công |
| `management` | Chỉ expose health/readiness, không lộ actuator detail |
| `logging.structured.console=ecs` | JSON/ECS structured logs |

### 8.2 Payment

[`payment-service/application.yml`](../../services/payment-service/src/main/resources/application.yml):

- schema `payment` + `merchant`;
- JPA validate, UTC, Flyway;
- JWT, Kafka producer/consumer;
- outbox, Saga recovery, workflow consumer;
- Problem Details;
- Swagger tắt mặc định, chỉ bật trong profile `local`;
- profile `local` thêm `db/seed` vào Flyway locations.

### 8.3 Account-Ledger (profile `mvp`)

[`account-ledger/application.yml`](../../services/account-ledger-service/src/main/resources/application.yml):

- database riêng, schema logic `account` + `ledger` + operational/default schema;
- bật/tắt payment consumer và refund consumer riêng;
- outbox publisher;
- profile `local` nạp fixture account/ledger giả.

### 8.4 Account và Ledger tách rời (profile `full`)

Hai file gần như đối xứng với nhau và với bản gộp — khác nhau ở database, schema, cờ consumer và port:

| Khóa | [`account-service`](../../services/account-service/src/main/resources/application.yml) | [`ledger-service`](../../services/ledger-service/src/main/resources/application.yml) |
| --- | --- | --- |
| `spring.datasource.url` | `PAYFLOW_ACCOUNT_DB_URL` → `payflow_account` | `PAYFLOW_LEDGER_DB_URL` → `payflow_ledger` |
| `spring.flyway.schemas` | `account_runtime,account` | `ledger_runtime,ledger` |
| `spring.flyway.default-schema` | `account_runtime` | `ledger_runtime` |
| Cờ consumer | `payflow.account-consumer.enabled` (`PAYFLOW_ACCOUNT_CONSUMER_ENABLED`) | `payflow.ledger-consumer.enabled` (`PAYFLOW_LEDGER_CONSUMER_ENABLED`) |
| `server.port` mặc định khi chạy trên host | `8082` | `8084` |

Cả hai giữ nguyên `payflow.outbox.*` (poll interval, batch, lease, max attempts, max backoff,
delivery timeout) đọc chung các biến `PAYFLOW_OUTBOX_*` như bản gộp, cùng `ddl-auto=validate`,
`ack-mode=manual_immediate`, `create-schemas: true` và profile `local` nạp thêm `classpath:db/seed`.
Cả hai chỉ expose health, không có business controller.

### 8.5 Merchant

[`merchant-service/application.yml`](../../services/merchant-service/src/main/resources/application.yml)
là service duy nhất **không có block `spring.kafka`**: nó thuần REST.

- database `payflow_merchant`, schema `merchant` vừa là `schemas` vừa là `default-schema`;
- `payflow.merchant.encryption-key-base64` (`PAYFLOW_MERCHANT_ENCRYPTION_KEY_BASE64`) — **không có
  default**, thiếu key là service không khởi động được; đây là key dùng cho `SecretCipher`;
- `springdoc.paths-to-match: /api/v1/merchants/**`, Swagger chỉ bật ở profile `local`;
- profile `local` nạp thêm `classpath:db/seed`.

### 8.6 Reporting (profile `full`)

[`reporting-service/application.yml`](../../services/reporting-service/src/main/resources/application.yml):

- database `payflow_reporting`, schema `reporting`; **không khai báo `spring.jpa`** vì read model
  ghi bằng JDBC thuần (`JdbcProjectionStore`);
- `payflow.reporting-consumer.enabled` (`PAYFLOW_REPORTING_CONSUMER_ENABLED`);
- Kafka producer khai báo `acks: all` nhưng **không có `enable.idempotence`/`delivery.timeout.ms`**
  như các service outbox, vì Reporting không publish event nào — nó chỉ consume;
- `springdoc.paths-to-match: /api/v1/reports/**,/api/v1/operations/reporting/**`.

Lưu ý port: `server.port` trong `application.yml` chỉ là default khi chạy trực tiếp trên host.
Trong Compose, `docker-compose.yml` set lại biến port cho container (ledger `8086`, merchant `8087`,
reporting `8088`) nên số cổng bạn thấy khi `docker compose ps` khác với default ở file YAML.

### 8.7 Risk

[`risk-service/application.yml`](../../services/risk-service/src/main/resources/application.yml):

- PostgreSQL giữ assessment/inbox/outbox;
- Redis giữ velocity signal với retention;
- consumer `payment.created` và Risk outbox;
- Redis không làm money source of truth.

### 8.8 Notification

[`notification-service/application.yml`](../../services/notification-service/src/main/resources/application.yml):

- PostgreSQL cho notification/inbox/delivery;
- Kafka outcome consumer;
- delivery worker config: poll interval, batch, lease, provider timeout, attempts;
- adapter hiện là in-memory, nhưng config giữ contract cho adapter thật.

### 8.9 Gateway

[`api-gateway/application.yml`](../../services/api-gateway/src/main/resources/application.yml):

- không có datasource/Kafka;
- JWT issuer/JWK;
- downstream Payment URI;
- giới hạn header 16 KB;
- health và structured log.

## 9. Luồng khởi động Docker Compose

[`docker-compose.yml`](../../docker-compose.yml) có ba profile:

- `infra`: PostgreSQL, Redis, Kafka, Kafka init, Keycloak.
- `mvp`: infra + năm deployable PayFlow.
- `full`: hiện tương đương MVP; không có service phase sau giả.

Startup dependency:

```text
PostgreSQL
  ├─ init script tạo 5 database/role
  └─ healthy
       └─ Keycloak start + import realm + healthy

Kafka
  └─ healthy
       └─ kafka-init tạo 6 topic hiện cần + exit 0

Redis healthy

PostgreSQL + Kafka init + Keycloak
  ├─ Payment
  ├─ Account-Ledger
  ├─ Notification
  └─ Risk còn chờ Redis

Payment healthy + Keycloak healthy
  └─ API Gateway start
```

Compose `depends_on` chỉ giải quyết startup local; nó không thay retry/recovery trong code.

## 10. PostgreSQL bootstrap và Flyway

### 10.1 Bootstrap database

[`01-create-databases.sh`](../../infrastructure/docker/postgres/init/01-create-databases.sh) chỉ chạy khi PostgreSQL volume còn rỗng. Nó tạo:

- `payflow_payment` + role `payflow_payment`;
- `payflow_account_ledger` + role riêng;
- `payflow_risk` + role riêng;
- `payflow_notification` + role riêng;
- `payflow_keycloak` + role riêng.

Script revoke quyền `PUBLIC` và cấp owner/connect đúng role. Đây là hàng rào hạ tầng để service không đọc nhầm database khác.

Script này không chạy lại mỗi lần `docker compose up`. Xóa volume làm mất dữ liệu và chỉ nên làm với local disposable environment khi chủ động yêu cầu.

### 10.2 Flyway trong service

```text
Service start
  → DataSource dùng credential riêng
  → Flyway đọc classpath:db/migration
  → kiểm tra flyway_schema_history
  → chạy migration version chưa có theo thứ tự
  → profile local chạy afterMigrate seed callback
  → Hibernate ddl-auto=validate
  → application ready
```

Flyway sở hữu schema. Không sửa migration đã apply; thay đổi schema sau này tạo migration forward-only mới.

## 11. Kafka bootstrap và runtime config

Kafka chạy KRaft một node, không ZooKeeper. Có hai advertised listener:

- `localhost:${KAFKA_PORT}` cho app chạy trên host;
- `kafka:19092` cho container trong Compose.

`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`. Container `kafka-init` tạo topic deterministically với 3 partition, replication factor 1. Các `NewTopic` bean trong producer service vẫn thể hiện ownership và kiểm tra cùng cấu trúc.

Một node/replication factor 1 chỉ dành cho sandbox local, không phải HA production.

## 12. Keycloak realm import

[`realm-payflow.json`](../../infrastructure/keycloak/realm-payflow.json) định nghĩa:

- realm `payflow`;
- scope `payment:read`, `payment:write`;
- client `payflow-service`, `payflow-readonly`;
- service account flow;
- hardcoded local `merchant_id` claim;
- token lifespan.

Secret không nằm trong JSON. `${PAYFLOW_SERVICE_CLIENT_SECRET}` và `${PAYFLOW_READONLY_CLIENT_SECRET}` được Keycloak thay từ environment khi import.

Keycloak lưu realm trong `payflow_keycloak`, nên restart container không làm mất cấu hình. Realm import không phải Java shared library và không chạy trong Gateway; Gateway/Service chỉ tải JWK và validate token Keycloak phát.

## 13. Docker build và health check

[`java-service.Dockerfile`](../../infrastructure/docker/java-service.Dockerfile) là recipe dùng chung cho năm deployable:

1. Nhận `SERVICE_MODULE` từ Compose.
2. Copy monorepo.
3. Compile [`HealthCheck.java`](../../infrastructure/docker/HealthCheck.java).
4. Maven package service đích và các `libs` cần thiết bằng `-pl ... -am`.
5. Copy một executable Spring Boot JAR sang JRE image.
6. Chạy bằng UID/GID `10001`, không chạy root.
7. `java -jar /opt/payflow/app.jar`.

`HealthCheck` dùng Java HTTP client gọi `/actuator/health/readiness`, yêu cầu status 200 và body chứa `"status":"UP"`. Dùng Java thuần vì runtime image không cần cài curl/wget.

## 14. Các file root và công cụ khác

| File/thư mục | Runtime hay development? | Chức năng |
| --- | --- | --- |
| [`mvnw.cmd`](../../mvnw.cmd), [`mvnw`](../../mvnw) | Build | Chạy đúng Maven Wrapper trên Windows/Linux |
| [`.mvn/wrapper`](../../.mvn/wrapper/) | Build | Khóa Maven `3.9.16` và download URL |
| [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) | CI | Fast test, full Testcontainers verify, Compose config validation |
| [`.gitignore`](../../.gitignore) | Git | Không track build output, IDE/local secret như `.env` |
| [`.dockerignore`](../../.dockerignore) | Docker build | Giảm build context và tránh copy file không cần |
| [`.gitattributes`](../../.gitattributes) | Git checkout | Chuẩn hóa line ending/attribute xuyên Windows/Linux |
| [`AGENTS.md`](../../AGENTS.md), [`.agent`](../../.agent/), [`.agents`](../../.agents/) | Agent/development | Quy tắc làm dự án và skill; không được service load lúc runtime |
| [`.docs`](../../.docs/) | Development governance | Roadmap/status/module map; không phải API contract runtime |
| [`docs`](../) | Product/operations docs | OpenAPI, event docs, ADR, runbook |
| `PAYFLOW_MICROSERVICE_PROJECT_SPEC.md` | Product specification | Nguồn yêu cầu; application không parse file này |
| `payflowPayment.zip` | Archive | Không tham gia Maven reactor, Docker Compose hay runtime |

## 15. CI chạy các file này như thế nào

[`ci.yml`](../../.github/workflows/ci.yml):

1. `fast-tests`: checkout → JDK 21 → `mvnw clean test` → Surefire reports.
2. `verify`: chỉ chạy sau fast test → xác nhận Docker → `mvnw clean verify` → unit + `*IT`/Testcontainers.
3. `compose-config`: render `.env.example` với profile infra/mvp, không start container; kiểm tra MVP có đúng 10 Compose service.

Maven test convention:

- `*Test.java`: Surefire, nhanh, không external container.
- `*IT.java`: Failsafe; có thể boot context hoặc Testcontainers.
- `-Pno-docker verify`: bỏ nhóm `@Tag("docker")`, phù hợp gate local khi Docker tắt nhưng không thay full verify.

## 16. Ba luồng kết hợp dễ hình dung nhất

### 16.1 HTTP thành event có thể truy vết

```text
.env issuer/JWK
 → Gateway application.yml
 → Gateway SecurityConfig validate JWT
 → CorrelationId shared helper chuẩn hóa ID
 → Payment Controller/Handler
 → Event contract tạo PaymentCreatedData + EventEnvelope
 → Outbox JSON + headers
 → Kafka
```

### 16.2 Event trùng nhưng không trừ tiền hai lần

```text
event-contracts giữ nguyên eventId khi republish
 → Kafka có thể giao lại
 → router deserialize cùng EventEnvelope
 → handler insert processed_events(eventId, consumerName)
 → duplicate insert không tạo row
 → handler trả duplicate/no-op
 → listener ack
```

Shared contract cung cấp identity; inbox database của từng service mới thực thi idempotency.

### 16.3 Lỗi vẫn nối được về request

```text
CorrelationId shared helper
 → Gateway/Payment filter đặt MDC
 → exception xảy ra
 → error-contract ProblemDetails lấy stable code + correlationId
 → client nhận correlationId
 → operator grep cùng ID trong structured log và Kafka/outbox metadata
```

## 17. Khi thêm hoặc sửa một phần dùng chung

### Thêm event mới

1. Xác định producer owner và consumer.
2. Thêm `EventType` vào đúng family class.
3. Thêm payload record chỉ chứa wire validation.
4. Thêm serialization/contract test.
5. Producer tạo event factory + outbox.
6. Consumer thêm router + handler + inbox transaction.
7. Cập nhật `docs/events` và compatibility/version decision.

Không đưa handler/domain policy vào `event-contracts`.

### Thêm error code

- Lỗi platform dùng chung mới cân nhắc `PayFlowErrorCode`.
- Lỗi nghiệp vụ đặt enum trong service và implement `ErrorCode`.
- Map exception tại web boundary; không để domain phụ thuộc Spring `ProblemDetail`.

### Thêm observability helper

Chỉ thêm convention kỹ thuật thật sự dùng chung. Filter MVC/WebFlux vẫn ở service tương ứng vì lifecycle framework khác nhau.

## 18. Phần hiện chưa tồn tại

- Kiến trúc mục tiêu từng nhắc `libs/test-support`, nhưng repository hiện chưa có module này.
- Chưa có shared tracing/OpenTelemetry auto-configuration module.
- Chưa có `infrastructure/monitoring` hoặc `infrastructure/k8s` runtime hoàn chỉnh trong cây file hiện tại.
- `NOTIFICATION_COMMANDS` và `SETTLEMENT_EVENTS` là contract placeholder cho phase sau, không chứng minh service tương ứng đã chạy.
- Shared library không biến at-least-once thành exactly-once; database outbox/inbox và domain invariant vẫn là hàng rào chính.
