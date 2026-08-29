# `services/` folder reference: từng service, package, file và luồng gọi

Tài liệu này mô tả toàn bộ thư mục `services/` theo code đang tồn tại. Mục tiêu là giúp trả lời:

- Service nào được chạy thành process/container?
- Request hoặc Kafka record đi vào file nào trước?
- `api`, `application`, `domain`, `infrastructure` khác nhau thế nào?
- Các port, adapter, entity, policy, listener, scheduler và test được dùng ở đâu?

Luồng API/Saga cụ thể nằm ở [payment-api-code-walkthrough.md](payment-api-code-walkthrough.md). Shared JAR và cấu hình nằm ở [shared-libraries-and-runtime-configuration-guide.md](shared-libraries-and-runtime-configuration-guide.md).

## 1. Cấu trúc chung của một service

Mỗi thư mục trực tiếp dưới `services/` là một Maven module và một deployable độc lập. Tất cả đều được
build, nhưng không phải tất cả cùng chạy — Docker profile quyết định:

| Module | Chạy ở profile | Port host mặc định |
| --- | --- | --- |
| `api-gateway` | `mvp`, `full` | 8084 |
| `payment-service` | `mvp`, `full` | 8081 |
| `merchant-service` | `mvp`, `full` | 8087 |
| `risk-service` | `mvp`, `full` | 8083 |
| `notification-service` | `mvp`, `full` | 8085 |
| `account-ledger-service` | chỉ `mvp` | 8082 |
| `account-service` | chỉ `full` | 8082 |
| `ledger-service` | chỉ `full` | 8086 |
| `reporting-service` | chỉ `full` | 8088 |

`account-ledger-service` và cặp `account-service`/`ledger-service` là hai cách đóng gói cùng một
nghiệp vụ; sửa logic account/ledger thì phải sửa ở cả hai bên.

Module nào cũng có cùng bố cục:

```text
services/<service>/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/          # code runtime
    │   └── resources/     # application.yml, Flyway migration/seed
    └── test/
        └── java/          # unit, slice, integration test
```

`pom.xml` của service khai báo Spring starter, PayFlow shared library, database/Kafka/Redis và test dependency. Root Maven reactor quyết định build order.

Khi service khởi động:

```text
*ServiceApplication.main
  → SpringApplication.run
  → đọc application.yml + environment
  → tạo config/security/persistence/messaging bean
  → Flyway migrate + Hibernate validate nếu có database
  → Kafka listener/scheduled job được đăng ký
  → Actuator readiness báo UP
```

Hướng phụ thuộc trong code:

```text
HTTP Controller / Kafka Listener
              ↓
Application Handler
       ↓              ↓
Domain model/policy   Application Port
                            ↑
                  Infrastructure Adapter
```

Domain không gọi Spring, JPA, Kafka hay Redis. Controller/listener không được gọi repository trực tiếp.

## 2. `api-gateway`

### 2.1 Vai trò

Gateway là edge service WebFlux. Nó không sở hữu database và không chạy workflow thanh toán. Nó làm bốn việc:

1. Nhận HTTP từ client.
2. Chuẩn hóa correlation ID.
3. Xác minh JWT/scope.
4. Route Payment API sang Payment Service.

### 2.2 Toàn bộ file runtime

Base path: `services/api-gateway/src/main/java/com/payflow/gateway`.

| File | Chức năng |
| --- | --- |
| [`ApiGatewayApplication`](../../services/api-gateway/src/main/java/com/payflow/gateway/ApiGatewayApplication.java) | `main()` và Spring Boot entry point |
| [`GatewayDownstreamProperties`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/GatewayDownstreamProperties.java) | Bind `payflow.gateway.downstream.payment-service` thành typed config |
| [`GatewayRoutesConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/GatewayRoutesConfig.java) | Tạo route `/api/v1/payments/**` → Payment Service URI |
| [`SecurityConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/SecurityConfig.java) | JWT resource server, GET/read, POST/write, deny-by-default |
| [`CorrelationIdWebFilter`](../../services/api-gateway/src/main/java/com/payflow/gateway/web/CorrelationIdWebFilter.java) | Nhận/sinh ID, forward request header, response header và MDC log |
| [`ProblemDetailErrorWriter`](../../services/api-gateway/src/main/java/com/payflow/gateway/web/ProblemDetailErrorWriter.java) | Render 401/403 thành shared Problem Details |

Resource:

- [`pom.xml`](../../services/api-gateway/pom.xml): WebFlux Gateway, OAuth2 Resource Server, actuator và shared libs.
- [`application.yml`](../../services/api-gateway/src/main/resources/application.yml): port, issuer/JWK, downstream URI, health và structured log.

### 2.3 Luồng gọi

```text
HTTP request
 → CorrelationIdWebFilter
 → SecurityWebFilterChain trong SecurityConfig
 → Route trong GatewayRoutesConfig
 → HTTP client nội bộ của Spring Cloud Gateway
 → payment-service
```

Gateway không deserialize `CreatePaymentRequest`, không gọi Payment repository và không giữ transaction.

### 2.4 Test

| File | Chứng minh |
| --- | --- |
| `ApiGatewaySecurityIT` | Route public/deny-by-default, token/scope và error contract |
| `GatewayCorrelationIdIT` | Correlation ID được sinh/giữ/forward an toàn |
| `GatewayTestSupport` | Fixture và helper dùng chung cho gateway integration test |

## 3. `payment-service`

### 3.1 Vai trò

Payment Service là service trung tâm của MVP:

- sở hữu public Payment/Refund REST API;
- sở hữu Payment và Refund aggregate;
- sở hữu idempotency HTTP;
- điều phối Payment/Refund Saga;
- consume kết quả Risk/Account/Ledger;
- phát command/event tiếp theo qua outbox;
- recovery Saga quá hạn.

Module có 134 file runtime và 41 file test vì nó vừa là REST owner vừa là orchestrator.

### 3.2 Entry point và API

Base package: `com.payflow.payment`.

| File | Chức năng |
| --- | --- |
| [`PaymentServiceApplication`](../../services/payment-service/src/main/java/com/payflow/payment/PaymentServiceApplication.java) | Spring Boot entry point |
| [`PaymentController`](../../services/payment-service/src/main/java/com/payflow/payment/api/PaymentController.java) | Tạo/đọc/tìm payment và tạo/đọc refund; lấy `merchant_id/sub` từ JWT |
| [`PaymentOpenApiConfig`](../../services/payment-service/src/main/java/com/payflow/payment/api/PaymentOpenApiConfig.java) | Tiêu đề/security scheme/tag cho Swagger local |
| [`PaymentErrorCode`](../../services/payment-service/src/main/java/com/payflow/payment/api/PaymentErrorCode.java) | Stable business error code của Payment API |
| `api/exception/IdempotencyKeyRequiredException` | Báo thiếu/sai `Idempotency-Key` tại HTTP boundary |
| `api/request/CreatePaymentRequest` | HTTP DTO và Jakarta Validation cho payment intake |
| `api/request/CreateRefundRequest` | HTTP DTO và validation cho refund intake |
| `api/response/ApiResponse` | Envelope `data + meta` |
| `api/response/ResponseMeta` | `correlationId + timestamp` |

API package không chứa JPA entity và không publish Kafka trực tiếp.

### 3.3 Application input/output

| File/nhóm | Chức năng |
| --- | --- |
| `CreatePaymentCommand` | Input use case đã tách khỏi HTTP/JWT type |
| `CreateRefundCommand` | Input refund use case |
| `PaymentAcceptance` | Snapshot trả cho create payment |
| `PaymentDetail` | Read model trả cho GET |
| `PaymentSearchQuery`, `PaymentSearchResult` | Filter/phân trang giới hạn và page trả về cho payment search |
| `RefundAcceptance` | Snapshot trả cho refund intake |
| `RefundDetail` | Trạng thái refund cùng journal/credit fact đã commit |
| `CreatePaymentResult` | Phân biệt created/replayed result |
| `CreateRefundResult` | Phân biệt accepted/replayed refund result |

Controller map request → command; handler trả application result; controller mới map result → HTTP response.

### 3.4 Application handlers

Base path: `application/handler`.

| Handler | Transaction/use case |
| --- | --- |
| [`CreatePaymentHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/CreatePaymentHandler.java) | Idempotency + merchant + Payment + Saga + `payment.created` outbox |
| [`GetPaymentHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/GetPaymentHandler.java) | Read-only query theo payment ID + merchant ID |
| [`SearchPaymentsHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/SearchPaymentsHandler.java) | Read-only search theo merchant/status/[from,to), phân trang tối đa 100 |
| [`CreateRefundHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/CreateRefundHandler.java) | Lock Payment, giữ refund capacity, tạo Refund + outbox |
| [`GetRefundHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/GetRefundHandler.java) | Read-only lookup theo refund + parent payment + merchant |
| [`HandlePaymentWorkflowEventHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/HandlePaymentWorkflowEventHandler.java) | Inbox + Payment/Saga transition + outgoing event |
| [`HandleRefundWorkflowEventHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/HandleRefundWorkflowEventHandler.java) | Inbox + Payment/Refund financial facts + outgoing event |
| [`PublishOutboxHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/PublishOutboxHandler.java) | Claim lease, publish ngoài DB transaction, conditional mark/retry |
| [`RecoverOverdueSagasHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/RecoverOverdueSagasHandler.java) | Đọc Saga quá hạn, chọn retry/compensation/manual review |

Đây là nơi đặt local transaction boundary. Mỗi handler chỉ dùng port, domain và type contract; chi tiết SQL/Kafka nằm ngoài.

### 3.5 Idempotency, inbox và outbox application types

| Folder/file | Chức năng |
| --- | --- |
| `idempotency/IdempotencyScope` | Scope key theo merchant + operation để key không đụng giữa use case |
| `idempotency/RequestFingerprint` | Canonical hash payload |
| `idempotency/IdempotentResponse` | Response payment được lưu để replay |
| `idempotency/RefundIdempotentResponse` | Response refund được lưu để replay |
| `inbox/IncomingEventIdentity` | Event ID, consumer, type, aggregate, processed time |
| `inbox/EventProcessingResult` | `PROCESSED` hoặc `DUPLICATE` cho listener quyết định ack/log |
| `outbox/ClaimedOutboxEvent` | Row đã claim cùng payload/header/lease info |
| `outbox/OutboxBatchResult` | Số published/retried/failed của một poll |
| `outbox/OutboxPublishPolicy` | Backoff và terminal failure decision |

HTTP idempotency xử lý request retry. Inbox idempotency xử lý Kafka redelivery. Hai cơ chế khác nhau và đều cần thiết.

### 3.6 Application ports

Base path: `application/port`.

| Port | Adapter/ý nghĩa |
| --- | --- |
| `IdGenerator` | UUID generator được cấu hình ở infrastructure |
| `MerchantCatalog` | Đọc merchant snapshot; `JpaMerchantCatalog` implement |
| `PaymentRepository` | Lưu/query Payment và history; `JpaPaymentRepository` implement |
| `RefundRepository` | Lưu Refund; `JpaRefundRepository` implement |
| `RefundPaymentStore` | Lock/load Payment cho refund concurrency |
| `IdempotencyStore` | Payment request replay store |
| `RefundIdempotencyStore` | Refund replay store |
| `PaymentSagaStore` | Tạo/tìm Saga và recovery query |
| `PaymentWorkflowStore` | Atomic load/update Payment + Saga theo workflow/version |
| `ProcessedEventStore` | Inbox insert-if-new |
| `OutboxAppender` | Append typed event vào local outbox |
| `OutboxLeaseStore` | Claim/mark/retry outbox row |
| `OutboxTransport` | Publish message tới transport; Kafka adapter implement |

Port là interface do application sở hữu. Infrastructure phụ thuộc vào port để implement, không đảo ngược.

### 3.7 Saga và refund policies

Base path: `application/saga`.

| File | Chức năng |
| --- | --- |
| `ApplyRiskAssessmentPolicy` | Risk result → approved/rejected/review action |
| `PaymentFundsReservationPolicy` | Validate funds reserved/failure và payment facts |
| `PaymentFinalizationPolicy` | Ledger/capture facts → finalization hoặc compensation/manual review |
| `PaymentSagaRecoveryPolicy` | Saga step/deadline/retry count → recovery action |
| `PaymentSagaEventFactory` | Tạo typed outgoing Saga event giữ causation/correlation |
| `SagaRecoverySettings` | Immutable recovery configuration |
| `SagaRecoveryAction` | Action được policy chọn |
| `SagaRecoveryBatchResult` | Kết quả một vòng recovery |
| `VersionedPayment`, `VersionedPaymentSaga` | Aggregate + optimistic version cho conditional update |

Base path `application/refund`:

| File | Chức năng |
| --- | --- |
| `RefundFinalizationPolicy` | Kiểm tra ledger refund/credit facts trước success/failure |
| `RefundWorkflowEventFactory` | Tạo credit requested, refund succeeded/failed event |

### 3.8 Domain model

Base path: `domain/model`.

| File | Trách nhiệm |
| --- | --- |
| [`Payment`](../../services/payment-service/src/main/java/com/payflow/payment/domain/model/Payment.java) | Aggregate root, state transition, fee snapshot, refundable capacity |
| [`Refund`](../../services/payment-service/src/main/java/com/payflow/payment/domain/model/Refund.java) | Refund state machine và financial facts |
| [`PaymentSaga`](../../services/payment-service/src/main/java/com/payflow/payment/domain/model/PaymentSaga.java) | Saga step/status/facts/deadline/retry invariant |
| `Money` | BigDecimal + currency + scale invariant |
| `PaymentIntake` | Input domain đã chuẩn hóa |
| `MerchantSnapshot` | Immutable merchant data dùng lúc tạo payment |
| `MerchantStatus` | Merchant lifecycle value |
| `FeePolicySnapshot` | Chính sách fee được chụp lúc intake |
| `PaymentFeeSnapshot` | Fee amount/rate/fixed component gắn Payment |
| `PaymentStatus`, `RefundStatus` | State enum |
| `PaymentSagaStatus`, `PaymentSagaStep` | Saga lifecycle enum |
| `PaymentStatusChange` | Một transition/history fact |
| `PaymentRiskDecision`, `PaymentRiskAction` | Kết quả normalized từ Risk dùng trong Payment |

`domain/exception` chứa exception theo invariant: currency/limit/merchant, illegal transition, refund capacity/not allowed, unexpected state và Saga invariant. Chúng không biết HTTP status; web layer mới map chúng.

### 3.9 Persistence infrastructure

Base path: `infrastructure/persistence`.

| Nhóm | File và chức năng |
| --- | --- |
| Entities | `PaymentEntity`, `RefundEntity`, `PaymentSagaEntity`, `PaymentStatusHistoryEntity`, `MerchantEntity`, `IdempotencyRecordEntity`, `OutboxEventEntity` ánh xạ table |
| Enums | `IdempotencyStatus`, `OutboxStatus` là persistence state, tách khỏi domain state |
| Aggregate adapters | `JpaPaymentRepository`, `JpaRefundRepository`, `JpaPaymentSagaStore`, `JpaMerchantCatalog` map entity ↔ domain |
| Reliability adapters | `JpaIdempotencyStore`, `JpaRefundIdempotencyStore`, `JpaOutboxAppender` implement application ports |
| Constraint utility | `ConstraintViolations` nhận diện constraint/race cụ thể để map thành application conflict an toàn |

JPA entity không được trả ra Controller và không được đặt trong `libs`.

### 3.10 Messaging infrastructure

Base path: `infrastructure/messaging`.

| File | Chức năng |
| --- | --- |
| `PaymentWorkflowKafkaListener` | Nhận Risk/Account/Ledger record, manual ack sau router thành công |
| `PaymentWorkflowEventRouter` | Chọn payload type theo event, kiểm tra key, gọi payment/refund handler |
| `PaymentWorkflowConsumerConfig` | Topic/consumer factory/retry/DLT wiring |
| `PaymentWorkflowConsumerProperties` | Bind enable/retry config |
| `JdbcProcessedEventStore` | Inbox `INSERT ... ON CONFLICT DO NOTHING` |
| `OutboxPollingJob` | Scheduled trigger, không chứa publish policy |
| `OutboxProperties` | Poll/batch/lease/attempt/backoff/timeout config |
| `OutboxMessagingConfig` | Kafka/outbox beans và validation config |
| `OutboxPublisherOwner` | Identity riêng cho publisher instance/lease owner |
| `JdbcOutboxLeaseStore` | PostgreSQL claim/reclaim/conditional mark |
| `KafkaOutboxTransport` | Kafka send với stored payload/header/key |

### 3.11 Recovery, security và web

| Folder/file | Chức năng |
| --- | --- |
| `infrastructure/recovery/SagaRecoveryProperties` | Bind poll interval, step timeout, retry, batch |
| `SagaRecoveryConfig` | Enable/wire scheduled recovery |
| `SagaRecoveryJob` | Trigger handler theo lịch |
| `infrastructure/config/ClockConfig` | Injectable `Clock` cho deterministic time |
| `IdGeneratorConfig` | Injectable UUID generator |
| `infrastructure/security/SecurityConfig` | JWT, scope, stateless, deny-by-default, Swagger local rules |
| `infrastructure/web/CorrelationIdFilter` | Servlet correlation boundary |
| `infrastructure/web/GlobalExceptionHandler` | Exception → stable Problem Details |

### 3.12 Resources và database

| File | Ý nghĩa |
| --- | --- |
| [`application.yml`](../../services/payment-service/src/main/resources/application.yml) | Datasource/JPA/Flyway/JWT/Kafka/outbox/Saga/Swagger/health |
| `V1__baseline.sql` | Baseline payment/history/idempotency/outbox |
| `V2__payment_intake.sql` | Intake/merchant support |
| `V3__consumer_inbox.sql` | `processed_events` |
| `V4__payment_saga_recovery.sql` | Persisted Saga/recovery fields |
| `V5__fee_snapshot_and_refund_capacity.sql` | Immutable fee + refund capacity |
| `V6__refund_intake_contract.sql` | Refund intake/idempotency |
| `V7__refund_financial_workflow_facts.sql` | Durable journal/credit/refund workflow facts |
| `db/seed/afterMigrate__local_seed.sql` | Fake merchant/local fixture, chỉ profile local |

### 3.13 Test organization

| Test group | Ví dụ | Chứng minh |
| --- | --- | --- |
| API/security | `PaymentControllerTest`, `PaymentErrorContractTest` | Validation, scopes, response/error shape |
| Domain | `PaymentTest`, `PaymentSagaTest`, `RefundTest`, `MoneyTest` | Transition và invariant không cần Spring |
| Application | `CreatePaymentHandlerTest`, workflow/recovery policy tests | Use case, duplicate, failure branch, event output |
| Messaging | Router/listener/properties/publisher tests | Dispatch, ack/rethrow, retry config |
| PostgreSQL IT | `PaymentIntakeSchemaIT`, `PaymentSagaPersistenceIT`, `RefundCapacityPersistenceIT` | Migration, locking, unique constraint, rollback |
| Kafka/component IT | `PaymentWorkflowConsumerIT` | Consumer transaction/redelivery behavior |
| Test fixtures | `PaymentTokens`, `CreatePaymentCommands`, `AbstractPostgresIT` | Token/command/container setup, không phải production code |

## 4. `account-ledger-service`

### 4.1 Vì sao hai domain trong một deployable?

MVP giảm vận hành bằng cách chạy Account và Ledger trong một Spring Boot process, nhưng code tách hai package root:

```text
com.payflow.accountledger
├── account/       # balance, reservation, refund credit
├── ledger/        # immutable double-entry journal
├── application/   # reliability concern dùng trong deployable
└── infrastructure/# Kafka, persistence, security, runtime config
```

Không được hiểu việc cùng process là Account và Ledger đã thành một domain.

### 4.2 Entry và shared deployable layer

| File/nhóm | Chức năng |
| --- | --- |
| `AccountLedgerServiceApplication` | Spring Boot entry |
| `application/exception/*` | Contract/data mismatch của payment/refund command |
| `application/inbox/*` | Incoming identity và processed/duplicate result |
| `application/outbox/*` | Claimed event, batch result, publish policy |
| `application/port/ProcessedEventStore` | Inbox port |
| `application/port/OutboxAppender/LeaseStore/Transport` | Outbox ports |
| `application/handler/PublishOutboxHandler` | Shared publisher use case |

### 4.3 Account boundary

Domain files:

| File | Chức năng |
| --- | --- |
| `Account` | available/reserved balance và status invariant |
| `Reservation` | Một payment reservation, capture/release transition |
| `RefundCredit` | Idempotent refund credit fact |
| `Money` | Amount/currency arithmetic |
| `AccountStatus`, `ReservationStatus` | Lifecycle enums |
| `InsufficientFundsException`, `AccountInvariantViolationException` | Domain failures |

Application policies/results:

| Folder | Chức năng |
| --- | --- |
| `reservation/ReserveFundsPolicy` | Kiểm tra account/status/currency/số dư và giữ tiền |
| `reservation/CaptureFundsPolicy` | Capture đúng reservation một lần |
| `reservation/ReleaseFundsPolicy` | Compensation đúng trạng thái một lần |
| `reservation/AccountReservationEventFactory` | funds-reserved/failure event |
| `reservation/AccountReleaseEventFactory` | funds-released event |
| `reservation/*Result` | Typed outcome cho handler |
| `refund/RefundCreditPolicy` | Credit refund idempotent |
| `refund/AccountRefundCreditedEventFactory` | account.refund-credited event |

Handlers:

- `HandleReserveFundsRequestedHandler`
- `HandleCaptureFundsRequestedHandler`
- `HandleReleaseFundsRequestedHandler`
- `HandleRefundCreditRequestedHandler`

Mỗi handler: inbox → lock/load account → policy → persist → outbox trong một local transaction.

Ports:

- `AccountReservationStore`: lock/load/save account và reservation.
- `AccountRefundStore`: lock/load/save account và refund credit.

### 4.4 Ledger boundary

Domain files:

| File | Chức năng |
| --- | --- |
| `Journal` | Aggregate journal, posted/immutable và balanced invariant |
| `LedgerEntry` | Một debit hoặc credit entry |
| `EntryDirection` | `DEBIT`/`CREDIT` |
| `JournalStatus` | Journal lifecycle |
| `JournalInvariantViolationException`, `UnbalancedJournalException` | Domain failure |

Application files:

| File/nhóm | Chức năng |
| --- | --- |
| `PaymentJournalFactory` | Tạo balanced capture journal từ command |
| `RefundJournalFactory` | Tạo reversal/refund journal |
| `LedgerPaymentPostedEventFactory` | Tạo success fact sau journal commit |
| `LedgerRefundPostedEventFactory` | Refund journal success fact |
| `LedgerRefundPostingFailedEventFactory` | Safe failure fact trước journal finalization |
| `HandlePostPaymentRequestedHandler` | Inbox + mapping + journal + outbox transaction |
| `HandleRefundRequestedHandler` | Inbox + reversal journal + outbox transaction |

Ports/records:

- `LedgerAccountDirectory`, `LedgerAccountPair`: tìm debit/credit account mapping.
- `PaymentJournalStore`, `PaymentJournalRecord`: persist/read payment journal.
- `RefundJournalStore`, `RefundJournalRecord`: persist/read refund journal.

### 4.5 Infrastructure

| Folder/file | Chức năng |
| --- | --- |
| `messaging/AccountLedgerWorkflowKafkaListener` | Consume Payment/Refund topics và manual ack |
| `messaging/AccountLedgerWorkflowEventRouter` | Event type → đúng Account/Ledger handler |
| `messaging/KafkaConsumerConfig` | Retry/DLT/topic ownership wiring |
| `messaging/Outbox*`, `JdbcOutboxLeaseStore`, `KafkaOutboxTransport` | Outbox pipeline |
| `persistence/AccountEntity`, `ReservationEntity`, `RefundCreditEntity` | Account JPA mapping |
| `JpaAccountReservationStore`, `JpaAccountRefundStore` | Row lock + domain mapping |
| `JdbcPaymentJournalStore`, `JdbcRefundJournalStore` | Immutable journal SQL |
| `JdbcLedgerAccountDirectory` | Ledger account mapping query |
| `JdbcProcessedEventStore`, `JdbcOutboxAppender` | Inbox/outbox reliability SQL |
| `config/RuntimeConfig` | Clock/ObjectMapper/transaction/application bean wiring |
| `security/SecurityConfig` | Deny-by-default HTTP resource server |

### 4.6 Resources và test

- [`application.yml`](../../services/account-ledger-service/src/main/resources/application.yml): DB/JPA/Flyway/JWT/Kafka/consumer/outbox/local seed.
- [`V1 migration`](../../services/account-ledger-service/src/main/resources/db/migration/V1__account_ledger_refund_runtime.sql): account, reservation, ledger, refund, inbox/outbox schema.
- [`local seed`](../../services/account-ledger-service/src/main/resources/db/seed/afterMigrate__local_seed.sql): fake account và ledger mapping cho smoke.

Test chia thành Account domain/policy, Ledger balance/journal, listener/router/outbox và PostgreSQL workflow IT. `PaymentWorkflowPersistenceIT` chứng minh reserve → journal → capture/compensation; `RefundWorkflowPersistenceIT` chứng minh reversal → credit.

## 5. `risk-service`

### 5.1 Vai trò và entry

[`RiskServiceApplication`](../../services/risk-service/src/main/java/com/payflow/risk/RiskServiceApplication.java) khởi động service. Risk không có public business controller; đầu vào chính là `payment.created` Kafka event.

### 5.2 Application

| File/nhóm | Chức năng |
| --- | --- |
| `HandlePaymentCreatedHandler` | Signal collection + inbox + rule + assessment + outbox transaction |
| `PublishOutboxHandler` | Risk outbox publisher use case |
| `RiskAssessmentEventFactory` | Domain assessment → `risk.assessment.completed` envelope |
| `inbox/*`, `outbox/*` | Reliability types giống pattern các service khác |
| `RiskSignalProvider`, `RiskSignalSnapshot` | Port và immutable input từ Redis/enrichment |
| `RiskAssessmentStore`, `RiskAssessmentRecord` | Durable assessment port/data |
| `ProcessedEventStore`, outbox ports | Inbox/outbox abstractions |

### 5.3 Domain

| File | Chức năng |
| --- | --- |
| `RiskEvaluationContext` | Normalized inputs cho rule engine |
| `RiskAssessment` | Kết quả score/decision/level/matched rules |
| `RiskDecision`, `RiskLevel`, `RiskRuleCode` | Domain enums |
| `RiskScorePolicy` | Tính/saturate score |
| `RiskClassification` | Map score/rules thành level/decision |
| `RiskRuleEngine` | Chạy bộ rule deterministic |
| `RiskInvariantViolationException` | Reject context/assessment không hợp lệ |

Wire enum trong `event-contracts` và domain enum ở Risk tách nhau để contract không trở thành business engine.

### 5.4 Infrastructure

| File/nhóm | Chức năng |
| --- | --- |
| `RiskPaymentKafkaListener` | Consume Payment topic, ack sau handler |
| `RiskPaymentEventRouter` | Chỉ nhận `payment.created`, deserialize typed envelope |
| `KafkaConsumerConfig` | Risk topic/DLT/retry |
| `RedisRiskSignalProvider` | Lua velocity counter và payment dedup trong Redis |
| `JdbcRiskAssessmentStore` | Assessment persistence |
| `JdbcProcessedEventStore`, `JdbcOutboxAppender` | Inbox/outbox SQL |
| `Outbox*` messaging classes | Poll/lease/publish/retry |
| `RiskVelocityProperties` | Redis retention config |
| `RuntimeConfig` | Clock/ObjectMapper/transaction wiring |
| `SecurityConfig` | Deny-by-default HTTP security |

Resources:

- [`application.yml`](../../services/risk-service/src/main/resources/application.yml): PostgreSQL, Redis, Kafka, JWT, outbox.
- [`V1 migration`](../../services/risk-service/src/main/resources/db/migration/V1__risk_assessment_runtime.sql): assessment/inbox/outbox.

Test gồm rule/score/context unit tests, event factory/handler, router/listener/publisher và `RiskWorkflowPersistenceIT` dùng PostgreSQL + Redis container.

## 6. `notification-service`

### 6.1 Vai trò và entry

[`NotificationServiceApplication`](../../services/notification-service/src/main/java/com/payflow/notification/NotificationServiceApplication.java) khởi động service. Đầu vào chính là payment/refund terminal event; delivery chạy bằng scheduled worker.

### 6.2 Notification intake

| File | Chức năng |
| --- | --- |
| `NotificationOutcomeKafkaListener` | Consume Payment/Refund outcome, manual ack |
| `NotificationOutcomeEventRouter` | Chỉ route succeeded/failed event hỗ trợ |
| `OutcomeNotificationFactory` | Event → normalized notification intent |
| `OutcomeNotificationIntent` | Recipient reference/channel/template/business key input |
| `CreateOutcomeNotificationHandler` | Inbox + unique notification local transaction |
| `NotificationStore`, `NotificationRecord` | Port và persistence data |
| `ProcessedEventStore`, `IncomingEventIdentity`, `EventProcessingResult` | Inbox idempotency |

### 6.3 Delivery worker

| File | Chức năng |
| --- | --- |
| `DeliverPendingNotificationsHandler` | Claim batch và gọi per-item delivery |
| `DeliverEmailNotificationHandler` | Tạo message, gọi port, quyết định complete/retry/fail |
| `NotificationDeliveryPolicy` | Bounded retry/backoff/terminal decision |
| `ClaimedNotification`, `NotificationClaimBatch` | Lease result |
| `EmailMessage`, `EmailDeliveryResult`, `DeliveryDisposition` | Typed adapter request/result |
| `EmailDeliveryPort` | Interface provider email |
| `NotificationDeliveryStore` | Claim/conditional mark port |
| `NotificationDeliveryProperties` | Poll/batch/lease/timeout/attempt config |

### 6.4 Domain và infrastructure

| File/nhóm | Chức năng |
| --- | --- |
| `Notification` | Aggregate status/attempt/transition invariant |
| `NotificationChannel`, `NotificationStatus` | Domain enum |
| `NotificationInvariantViolationException` | Invalid transition/input |
| `InMemoryEmailDeliveryAdapter` | Local mock implement `EmailDeliveryPort` |
| `JdbcNotificationStore` | Insert unique business notification |
| `JdbcNotificationDeliveryStore` | Claim lease và conditional mark |
| `JdbcProcessedEventStore` | Inbox insert-if-new |
| `KafkaConsumerConfig` | Topic/retry/DLT wiring |
| `RuntimeConfig` | Clock/ObjectMapper/transaction/scheduler bean wiring |
| `SecurityConfig` | Deny-by-default HTTP |

### 6.5 Webhook delivery Phase 2

| File/nhóm | Chức năng |
| --- | --- |
| `WebhookIntentFactory`, `WebhookDeliveryStore` | Tạo durable delivery intent từ outcome trong cùng local transaction với inbox/notification |
| `WebhookSignature` | Ký HMAC SHA-256 trên `timestamp.rawBody`; retry giữ nguyên event id và raw body |
| `WebhookDeliveryJob`, `HttpWebhookTransport` | Claim lease, gọi endpoint ngoài transaction, timeout ngắn và cập nhật retry/`DEAD` có điều kiện |
| `JdbcWebhookDeliveryStore` | Persistence intent, attempt, lease và append-only operations audit |
| `WebhookOperationsController` | Requeue bản ghi `DEAD` bằng scope riêng; không tạo event business mới |
| `ClientCredentialsTokenProvider` | Lấy service token để đọc webhook config/subscription từ Merchant internal API |

Resources:

- [`application.yml`](../../services/notification-service/src/main/resources/application.yml): DB/Kafka/JWT/consumer/delivery.
- [`V1 migration`](../../services/notification-service/src/main/resources/db/migration/V1__notification_runtime.sql): notification/inbox/delivery state.
- [`V2 migration`](../../services/notification-service/src/main/resources/db/migration/V2__webhook_delivery.sql): webhook intent/attempt/audit state.

Test gồm Notification aggregate, factory/handler, delivery policy/adapter, listener/router, HMAC/retry và
hai PostgreSQL IT cho transaction/duplicate/lease ownership/webhook persistence.

## 7. Các deployable mới của Phase 2

### 7.1 `account-service` và `ledger-service`

- [`account-service`](../../services/account-service/) sở hữu account, balance reservation, inbox/outbox và
  database `payflow_account`. Kafka command được route tới reserve/capture/release handler; row lock và
  constraint bảo vệ `available/reserved`.
- [`ledger-service`](../../services/ledger-service/) sở hữu journal kép, payment/refund posting, inbox/outbox
  và database `payflow_ledger`. Trigger V2 từ chối update/delete journal đã post; refund luôn tạo journal
  reversal mới.
- `account-ledger-service` chỉ còn cho profile `mvp`; profile `full` chạy hai service tách mà giữ nguyên event
  contract/external Saga behavior.

### 7.2 `merchant-service`

[`merchant-service`](../../services/merchant-service/) sở hữu profile/status, member, versioned fee/limit
policy, API-key hash, webhook secret mã hóa/subscription và audit. Public API dùng merchant scope; internal
policy/webhook lookup dùng service credential riêng. Payment lấy immutable policy snapshot trước khi mở
local database transaction và fail closed nếu Merchant không sẵn sàng.

### 7.3 `reporting-service`

[`reporting-service`](../../services/reporting-service/) consume versioned Payment/Risk/Refund facts, ghi
`event_log` idempotent và project daily merchant read model. Rebuild tạo generation mới, replay event log,
so fingerprint với generation active rồi mới atomic switch; mismatch giữ generation cũ. Query lấy
`merchant_id` từ JWT, còn rebuild dùng operations scope và append-only audit.

## 8. Cùng một pattern, khác nghiệp vụ

Các service lặp lại một số tên file có chủ đích:

| Pattern | Có ở đâu | Vì sao không đặt hết vào `libs`? |
| --- | --- | --- |
| `ProcessedEventStore` | Payment, Risk, Account-Ledger, Account, Ledger, Notification | Mỗi service sở hữu schema/transaction riêng (Reporting thay bằng `event_log` unique `event_id`) |
| `OutboxAppender/LeaseStore/Transport` | Producer services | Port giống ý tưởng nhưng data/query/transaction owner khác |
| `PublishOutboxHandler` | Producer services | Có thể tiến hóa metric/retry/config độc lập; tránh shared business runtime |
| `KafkaListener/EventRouter` | Consumer services | Mỗi service có group, topic, supported event và handler riêng |
| `SecurityConfig` | Mỗi deployable | Defense in depth; endpoint/scope khác nhau |
| `RuntimeConfig` | Business services | Bean wiring phụ thuộc bounded context |

Đây không phải code duplication vô nghĩa. Reliability protocol giống nhau, nhưng copy có kiểm soát giữ service độc lập. Chỉ contract/header/convention ổn định mới nằm trong `libs`.

## 9. Luồng xuyên toàn bộ `services/`

```text
Gateway
  CorrelationIdWebFilter → SecurityConfig → GatewayRoutesConfig
        │ HTTP
        ▼
Payment
  CorrelationIdFilter → SecurityConfig → PaymentController
  → CreatePaymentHandler → Payment domain → JPA/outbox adapters
        │ Kafka payment.created
        ▼
Risk
  Listener → Router → HandlePaymentCreatedHandler
  → Redis signal → RiskRuleEngine → JDBC assessment/outbox
        │ Kafka risk.assessment.completed
        ▼
Payment Saga
  Listener → Router → WorkflowHandler → Saga policy → outbox command
        │
        ▼
Account-Ledger  (profile mvp — profile full: account-service + ledger-service, mỗi bên một listener,
                 một database, cùng event contract)
  Listener → Router
    ├─ Account handler → Account policy → JPA account/outbox
    └─ Ledger handler → Journal factory → JDBC journal/outbox
        │ outcome events
        ▼
Payment Saga → final Payment event
        │
        ├──────────────► Reporting (chỉ profile full)
        │                  Listener → event_log (idempotent) → payment_projection
        ▼
Notification
  Listener → Router → Create handler → JDBC notification
  → scheduled delivery handler → EmailDeliveryPort → in-memory adapter
  → webhook delivery worker → merchant webhook endpoint (HMAC)
```

## 10. Cách tìm file khi debug

| Hiện tượng | Bắt đầu đọc |
| --- | --- |
| API 401/403 | Gateway `SecurityConfig`/`ProblemDetailErrorWriter`, sau đó Payment `SecurityConfig` |
| Request không tới Payment | `CorrelationIdWebFilter` → `GatewayRoutesConfig` → downstream URI |
| API 400/409/500 | `PaymentController` → handler → `GlobalExceptionHandler` |
| Payment đứng `RISK_CHECKING` | Payment outbox → Risk listener/router/handler → Risk outbox |
| `MANUAL_REVIEW_REQUIRED` | Risk decision hoặc Payment Saga recovery/finalization policy |
| Số dư không đúng | Account handler → policy → `JpaAccountReservationStore` → constraint |
| Journal không tạo | Ledger handler → account directory → journal factory → JDBC store |
| Event bị xử lý hai lần | Listener ack → router → `ProcessedEventStore` → unique key |
| Outbox không drain | polling job → properties → publish handler → lease store → Kafka transport |
| Notification không gửi | create handler/store → delivery store lease → delivery handler → email adapter |
| Report sai số (`full`) | `reporting.event_log` (đã nhận event chưa) → `JdbcProjectionStore.project(...)` → `payment_projection` join `active_generation` |
| Payment trả 503 khi tạo | `HttpMerchantCatalog` → merchant-service `/internal/v1/merchants/{id}/payment-policy` → `MerchantCatalogUnavailableException` |

## 11. Phần không có trong `services/` hiện tại

- Chưa có `user-service` hoặc `settlement-service` deployable (`payflow.settlement.events.v1` mới chỉ có
  tên trong `PayFlowTopics`).
- Chưa có public Account/Ledger/Risk business controller. Notification chỉ có endpoint vận hành
  `/api/v1/operations/webhooks/{deliveryId}/retry`, không có API nghiệp vụ.
- Chưa có browser-login application service; Keycloak hiện cấp service token.
- Profile `mvp` vẫn dùng Account-Ledger ghép; profile `full` đã tách Account và Ledger thành container/database riêng.
- Email adapter chưa gọi provider thật.

Không suy ra service đã tồn tại chỉ vì spec/roadmap hoặc `PayFlowTopics` có tên dành cho phase sau.
