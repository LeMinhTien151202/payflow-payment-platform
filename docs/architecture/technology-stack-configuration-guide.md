# PayFlow technology stack: dùng để làm gì, cấu hình ở đâu và code chạy qua đâu

Tài liệu này giải thích các công nghệ đang có trong PayFlow theo cách thực hành. Với mỗi công nghệ, tài liệu chỉ rõ:

1. Công nghệ đó giải quyết vấn đề gì.
2. PayFlow áp dụng nó ở phạm vi nào.
3. Dependency/image được thêm ở đâu.
4. Cấu hình nằm trong file nào.
5. Luồng runtime đi qua class nào.
6. Nếu muốn thay đổi hoặc thêm mới thì sửa ở đâu.

Đây là mô tả source hiện tại. Các công nghệ mới chỉ có trong roadmap được tách riêng ở cuối.

## 1. Bảng công nghệ tổng quan

| Công nghệ/phương pháp | Trạng thái hiện tại | Được dùng cho |
| --- | --- | --- |
| Java 21 | Đang dùng | Ngôn ngữ và runtime cho toàn bộ service/libs |
| Maven 3.9.16 Wrapper | Đang dùng | Multi-module build, dependency và test lifecycle |
| Spring Boot 4.0.7 | Đang dùng | Application lifecycle, DI, configuration, starters |
| Spring Cloud 2025.1.2 | Đang dùng | Spring Cloud Gateway WebFlux |
| Spring MVC | Đang dùng | Payment và các business service |
| Spring WebFlux Gateway | Đang dùng | Reactive API Gateway |
| Spring Security OAuth2 Resource Server | Đang dùng | Xác minh JWT và scope |
| Keycloak 26.7.0 | Đang dùng | Local OAuth2/OIDC issuer, service accounts |
| PostgreSQL 17.10 | Đang dùng | Durable business/reliability state |
| Flyway | Đang dùng | Versioned database schema |
| Spring Data JPA/Hibernate | Đang dùng có chọn lọc | Payment aggregate và Account entities |
| Spring JDBC | Đang dùng có chọn lọc | Ledger, inbox/outbox, Risk, Notification SQL |
| Apache Kafka 4.3.1 | Đang dùng | Async Saga command/event workflow |
| Spring Kafka | Đang dùng | Listener, producer, retry/DLT wiring |
| Redis 8.2.8 | Đang dùng ở Risk | Velocity counter và ephemeral dedup signal |
| Jackson | Đang dùng | Event envelope/payload JSON serialization |
| Jakarta Bean Validation | Đang dùng | Payment REST boundary validation |
| Spring Scheduling | Đang dùng | Outbox polling, Saga recovery, notification delivery |
| Springdoc/Swagger UI 3.0.3 | Đang dùng local | Xem và gọi Payment REST API |
| Spring Actuator + Micrometer | Đang dùng một phần | Health/readiness và in-process metrics |
| Structured ECS logging | Đang dùng | Machine-readable log |
| Docker + Compose | Đang dùng local | Build/run reproducible MVP stack |
| JUnit/AssertJ/Mockito | Đang dùng | Unit/application tests |
| Testcontainers | Đang dùng | PostgreSQL và Redis integration tests |
| WireMock/WebTestClient/MockMvc | Đang dùng | Gateway/HTTP tests |
| GitHub Actions | Đang dùng | Fast test, full verify và Compose config gate |
| Kubernetes | Chưa có source | Chỉ roadmap, chưa có manifest |
| Prometheus/Grafana/Tempo/Loki | Chưa có stack | Metric code có, monitoring stack chưa dựng |
| OpenTelemetry | Chưa cấu hình | Target architecture, chưa có SDK/exporter |

## 2. Java 21

### 2.1 Tại sao dùng?

Java 21 là LTS, cung cấp record, pattern/language improvements và runtime ổn định phù hợp backend tài chính. PayFlow dùng:

- `record` cho immutable command/result/event payload;
- `BigDecimal` cho tiền;
- `UUID` cho aggregate/event identity;
- `Instant`/`Clock` cho UTC/deterministic time;
- Java HTTP client trong container health check.

### 2.2 Cấu hình ở đâu?

| File | Cấu hình |
| --- | --- |
| [`pom.xml`](../../pom.xml) | `<java.version>21</java.version>` và compiler release 21 |
| [`java-service.Dockerfile`](../../infrastructure/docker/java-service.Dockerfile) | Temurin 21 JDK build image và JRE runtime image |
| [`ci.yml`](../../.github/workflows/ci.yml) | `actions/setup-java` Temurin 21 |

### 2.3 Khi đổi Java version

Phải đổi đồng bộ parent POM, Docker build/runtime image, CI và ADR platform baseline. Không chỉ đổi JDK trong IDE.

## 3. Maven và monorepo multi-module

### 3.1 Vai trò

Maven quản lý:

- version nền tảng;
- dependency của từng service;
- build order giữa `libs` và `services`;
- unit/integration test lifecycle;
- executable Spring Boot JAR.

### 3.2 Cấu hình

| File | Chức năng |
| --- | --- |
| [`pom.xml`](../../pom.xml) | Parent, module list, version/dependency/plugin management |
| [`mvnw`](../../mvnw), [`mvnw.cmd`](../../mvnw.cmd) | Wrapper launcher Linux/Windows |
| [`.mvn/wrapper/maven-wrapper.properties`](../../.mvn/wrapper/maven-wrapper.properties) | Maven 3.9.16 download URL |
| `services/*/pom.xml` | Starter và internal library của service |
| `libs/*/pom.xml` | Shared JAR dependency |

### 3.3 Các lệnh và ý nghĩa

```powershell
./mvnw.cmd test
```

Chạy Surefire `*Test.java`, không chạy `*IT.java`.

```powershell
./mvnw.cmd verify
```

Chạy unit + Failsafe integration test; Docker cần sẵn sàng cho Testcontainers.

```powershell
./mvnw.cmd -Pno-docker verify
```

Chạy verify nhưng loại test tag `docker`.

```powershell
./mvnw.cmd -pl services/payment-service -am test
```

Chọn Payment và build/test luôn các module PayFlow nó phụ thuộc.

### 3.4 Thêm dependency đúng chỗ

- Version dùng chung: khóa trong root `dependencyManagement` nếu cần.
- Dependency chỉ Payment dùng: thêm vào `services/payment-service/pom.xml`.
- Không thêm dependency vào parent `<dependencies>` nếu không muốn mọi module đều nhận nó.
- Shared library không được kéo Spring/JPA/Kafka dependency nếu mục đích chỉ là contract Java thuần.

## 4. Spring Boot

### 4.1 Spring Boot làm gì?

Spring Boot cung cấp:

- application startup;
- dependency injection qua constructor;
- externalized configuration;
- web server;
- datasource/JPA/JDBC/Kafka/Redis auto-configuration;
- security;
- scheduling;
- Actuator/Micrometer.

Mỗi deployable có một `*ServiceApplication` hoặc `ApiGatewayApplication` gọi `SpringApplication.run(...)`.

### 4.2 Version nằm ở đâu?

Root [`pom.xml`](../../pom.xml) kế thừa:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.0.7</version>
</parent>
```

ADR-013 khóa Boot `4.0.7` với Spring Cloud `2025.1.2`. Không nâng riêng Boot hoặc Cloud train.

### 4.3 Cấu hình property đi vào Spring thế nào?

```text
.env
 → Docker Compose environment
 → Spring Environment
 → application.yml ${VARIABLE:default}
 → auto-configuration hoặc @ConfigurationProperties
 → bean runtime
```

Credential không có default. Các tuning có safe local default.

## 5. Spring MVC và Spring WebFlux

### 5.1 Spring MVC

Dependency `spring-boot-starter-webmvc` có ở Payment, Account-Ledger, Risk và Notification.

Payment dùng MVC thật cho REST Controller:

```text
HTTP servlet request
 → CorrelationIdFilter
 → Spring Security filter chain
 → DispatcherServlet
 → PaymentController
 → application handler
```

Account-Ledger/Risk/Notification có web/Actuator/security runtime nhưng chưa có public business controller.

### 5.2 Spring WebFlux ở Gateway

Dependency:

```xml
spring-cloud-starter-gateway-server-webflux
```

Gateway dùng reactive `WebFilter` và route, không dùng MVC `DispatcherServlet`:

```text
request
 → CorrelationIdWebFilter
 → reactive SecurityWebFilterChain
 → Gateway route/filter chain
 → non-blocking downstream HTTP call
```

Không đưa JPA hoặc blocking database call vào Gateway reactive chain.

## 6. Spring Cloud Gateway

### 6.1 Dùng để làm gì?

Gateway là một public entry point để:

- kiểm tra token/scope ở edge;
- gắn correlation ID;
- giới hạn header;
- route API;
- chuẩn hóa 401/403.

Nó không thay authorization trong Payment Service.

### 6.2 Thêm dependency ở đâu?

[`services/api-gateway/pom.xml`](../../services/api-gateway/pom.xml):

```xml
<artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
```

Spring Cloud version được import trong root POM qua BOM.

### 6.3 Cấu hình ở đâu?

| File | Nội dung |
| --- | --- |
| [`api-gateway/application.yml`](../../services/api-gateway/src/main/resources/application.yml) | Gateway port và Payment downstream URI |
| [`.env.example`](../../.env.example) | `PAYFLOW_GATEWAY_PORT`, `PAYFLOW_PAYMENT_SERVICE_URI` |
| [`docker-compose.yml`](../../docker-compose.yml) | Container URI `http://payment-service:8081` |
| [`GatewayDownstreamProperties`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/GatewayDownstreamProperties.java) | Bind typed URI |
| [`GatewayRoutesConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/GatewayRoutesConfig.java) | Route predicate và target URI |

### 6.4 Thêm route mới

1. Service đích phải tồn tại và healthy.
2. Thêm URI property vào `.env.example`/Compose/application config nếu cần.
3. Thêm typed property.
4. Thêm route trong `GatewayRoutesConfig`.
5. Thêm authorization rule deny-by-default.
6. Thêm WireMock/WebTestClient route/security test.

Không route `/internal/v1` ra public gateway.

## 7. Keycloak và OAuth2/OIDC

### 7.1 Keycloak là gì trong PayFlow?

Keycloak là Authorization Server/Identity Provider local. Nó:

- xác thực confidential client;
- phát signed JWT access token;
- đưa scope và `merchant_id` vào token;
- public JWK để Gateway/service xác minh signature.

Keycloak không xử lý business authorization như “payment này thuộc merchant nào”; application vẫn kiểm tra ownership.

### 7.2 Image và runtime

[`docker-compose.yml`](../../docker-compose.yml):

- image `quay.io/keycloak/keycloak:26.7.0`;
- command `start-dev --import-realm --health-enabled=true`;
- PostgreSQL database `payflow_keycloak`;
- public host port `8180` mặc định;
- realm folder mount read-only.

`start-dev` chỉ dành cho local sandbox, không phải production.

### 7.3 Realm cấu hình ở đâu?

[`realm-payflow.json`](../../infrastructure/keycloak/realm-payflow.json) định nghĩa:

- realm `payflow`;
- access token lifespan 900 giây;
- `payment:read`, `payment:write` client scopes;
- `payflow-service` có read + write;
- `payflow-readonly` chỉ read;
- `standardFlowEnabled=false`;
- `serviceAccountsEnabled=true`;
- local `merchant_id` hardcoded claim mapper.

Secret được lấy từ environment:

```text
PAYFLOW_SERVICE_CLIENT_SECRET
PAYFLOW_READONLY_CLIENT_SECRET
```

Không commit secret vào realm JSON.

### 7.4 Service kết nối Keycloak ra sao?

Không dùng Keycloak Java SDK. Mỗi service dùng chuẩn Spring OAuth2 Resource Server:

```xml
spring-boot-starter-security
spring-boot-starter-oauth2-resource-server
```

Trong `application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${PAYFLOW_OIDC_ISSUER_URI:...}
          jwk-set-uri: ${PAYFLOW_OIDC_JWK_SET_URI:...}
```

Compose giữ issuer public là `http://localhost:8180/realms/payflow`, nhưng container tải key qua `http://keycloak:8080/.../certs`. Điều này cho phép strict `iss` validation và internal network access cùng lúc.

### 7.5 Luồng token

```text
Client
 → POST Keycloak token endpoint với client_credentials
 → Keycloak kiểm client secret
 → JWT có scope + merchant_id + sub
 → Client gửi Authorization: Bearer JWT
 → Gateway Resource Server tải/cache JWK, verify signature/iss/exp
 → Gateway kiểm scope
 → Payment Service verify lại
 → PaymentController lấy merchant_id/sub
 → repository/handler kiểm ownership
```

### 7.6 Java authorization config

- Gateway: [`SecurityConfig`](../../services/api-gateway/src/main/java/com/payflow/gateway/config/SecurityConfig.java).
- Payment: [`SecurityConfig`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/security/SecurityConfig.java).
- Internal services có deny-by-default HTTP SecurityConfig.

Hiện tại đây là scope-based authorization + tenant claim, không phải RBAC thuần.

### 7.7 Nếu thêm browser login sau này

Cần thêm public client/Authorization Code + PKCE, redirect URI, user/merchant mapping, role/claim policy và frontend session/token handling. Không chỉ bật `standardFlowEnabled` rồi coi như xong.

## 8. PostgreSQL

### 8.1 Dùng để làm gì?

PostgreSQL là durable source of truth cho:

- Payment/Refund/Saga/idempotency;
- Account balance/reservation/refund credit;
- immutable Ledger journal/entry;
- Risk assessment;
- Notification/delivery state;
- outbox và processed-event inbox.

Redis/Kafka không thay PostgreSQL làm money truth.

### 8.2 Image và port

Compose dùng `postgres:17.10-alpine`.

- Host: `localhost:5433` theo `.env.example`.
- Container network: `postgres:5432`.

### 8.3 Database-per-service cấu hình

[`01-create-databases.sh`](../../infrastructure/docker/postgres/init/01-create-databases.sh) tạo role/database riêng khi volume rỗng.

`.env.example` có URL/username/password riêng:

```text
PAYFLOW_PAYMENT_DB_*
PAYFLOW_ACCOUNT_LEDGER_DB_*
PAYFLOW_RISK_DB_*
PAYFLOW_NOTIFICATION_DB_*
```

Compose ghi đè URL host thành container URL. `application.yml` bind vào `spring.datasource`.

### 8.4 Tiền trong PostgreSQL

- Java: `BigDecimal`.
- SQL: `NUMERIC(19,4)`.
- Currency bắt buộc.
- Database constraint bảo vệ non-negative/unique/status facts.

Không dùng `float/double` cho tiền.

### 8.5 Locking và concurrency

- Account reserve/capture/release: pessimistic row lock qua JPA store.
- Concurrent refund: Payment row lock trước khi tính capacity.
- Saga/Payment update: optimistic version/conditional update.
- Outbox claim: SQL lease và `SKIP LOCKED`/conditional owner semantics.
- Inbox: `INSERT ... ON CONFLICT DO NOTHING`.

Các behavior PostgreSQL-specific phải test bằng PostgreSQL/Testcontainers, không dùng H2.

## 9. Flyway

### 9.1 Vì sao dùng?

Flyway là source of truth cho schema. Hibernate không tự tạo/sửa bảng.

Mỗi DB service thêm:

```xml
spring-boot-starter-flyway
flyway-database-postgresql
```

### 9.2 Cấu hình

Trong service `application.yml`:

```yaml
spring:
  flyway:
    enabled: true
    schemas: ...
    default-schema: ...
    create-schemas: true
    locations: classpath:db/migration
```

Payment và Account-Ledger profile `local` thêm `classpath:db/seed`.

### 9.3 Startup flow

```text
DataSource được tạo
 → Flyway đọc flyway_schema_history
 → chạy V1, V2... chưa apply theo thứ tự
 → local afterMigrate seed callback nếu profile local
 → Hibernate ddl-auto=validate
 → service ready
```

### 9.4 Thêm migration

Thêm file mới trong owner service:

```text
src/main/resources/db/migration/V<next>__description.sql
```

Không sửa migration đã apply. Với table có dữ liệu, dùng expand → backfill → switch → contract.

## 10. JPA/Hibernate và JDBC

### 10.1 Tại sao dùng cả hai?

PayFlow không chọn “JPA cho mọi thứ” hoặc “JDBC cho mọi thứ”.

| Công nghệ | Dùng khi | Ví dụ |
| --- | --- | --- |
| JPA/Hibernate | Aggregate/entity mapping và repository lifecycle thuận tiện | Payment, Refund, Saga, Account, Reservation |
| JDBC | Cần SQL rõ, insert-if-new, lease claim, immutable multi-row journal | Inbox/outbox, Ledger, Risk, Notification |

### 10.2 JPA cấu hình

Dependency `spring-boot-starter-data-jpa` ở Payment và Account-Ledger.

```yaml
spring.jpa.hibernate.ddl-auto: validate
spring.jpa.open-in-view: false
spring.jpa.properties.hibernate.jdbc.time_zone: UTC
```

`open-in-view=false` buộc load/map dữ liệu trong transaction, tránh lazy query rò ra Controller.

### 10.3 JDBC cấu hình

Risk/Notification dùng `spring-boot-starter-jdbc`; Account-Ledger/Payment cũng dùng JDBC qua starter liên quan cho reliability SQL.

Adapter dùng `JdbcTemplate`/SQL để:

- `ON CONFLICT DO NOTHING` inbox;
- claim/reclaim outbox lease;
- insert immutable journal/entries;
- query Risk/Notification state.

### 10.4 Transaction

Application handlers thường dùng `TransactionTemplate` để transaction boundary hiện rõ:

```text
transactions.executeWithoutResult(...)
```

Persistence method cần transaction bên ngoài dùng:

```text
@Transactional(propagation = MANDATORY)
```

Điều này ngăn vô tình gọi store ngoài use-case transaction.

## 11. Apache Kafka và Spring Kafka

### 11.1 Kafka giải quyết vấn đề gì?

Payment workflow dài và có nhiều service. Nếu REST request chờ Risk → Account → Ledger → Notification thì:

- request giữ connection quá lâu;
- một service chậm kéo sập toàn chuỗi;
- retry khó phân biệt bước nào đã commit;
- không dễ fan-out outcome.

Kafka cho phép các bước bất đồng bộ và replay/retry có kiểm soát. Payment trả `202` sau intake transaction rồi workflow tiếp tục.

### 11.2 Broker local

Compose dùng `apache/kafka:4.3.1` ở KRaft single-node, không ZooKeeper.

Listener:

- `HOST://localhost:9092` cho app trên host.
- `INTERNAL://kafka:19092` cho container.
- controller listener `9093` nội bộ broker.

Auto topic creation bị tắt để typo fail rõ.

### 11.3 Topic được thêm ở đâu?

1. Tên chuẩn: [`PayFlowTopics`](../../libs/event-contracts/src/main/java/com/payflow/events/PayFlowTopics.java).
2. Local creation: `kafka-init` section trong [`docker-compose.yml`](../../docker-compose.yml).
3. Producer-owned `NewTopic` bean: `KafkaConsumerConfig`/messaging config của service owner.
4. Human contract: `docs/events`.

Topic active:

```text
payflow.payment.events.v1
payflow.account.events.v1
payflow.ledger.events.v1
payflow.risk.events.v1
payflow.refund.events.v1
payflow.dead-letter.v1
```

Mỗi topic local có 3 partition, replication factor 1.

### 11.4 Dependency

Các business service thêm:

```xml
<artifactId>spring-boot-starter-kafka</artifactId>
```

Gateway không dùng Kafka dependency.

### 11.5 Producer config

Trong `application.yml`:

```yaml
spring:
  kafka:
    bootstrap-servers: ${PAYFLOW_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: StringSerializer
      value-serializer: StringSerializer
      acks: all
      properties:
        enable.idempotence: true
        delivery.timeout.ms: ${PAYFLOW_KAFKA_DELIVERY_TIMEOUT_MS:60000}
```

Value là JSON string đã lưu trong outbox. Producer idempotence giảm duplicate ở broker retry, nhưng không giải quyết crash PostgreSQL→Kafka; inbox vẫn bắt buộc.

### 11.6 Consumer config

```yaml
consumer:
  enable-auto-commit: false
  auto-offset-reset: earliest
  key-deserializer: StringDeserializer
  value-deserializer: StringDeserializer
listener:
  ack-mode: manual_immediate
```

Listener nhận raw key/value/headers, router deserialize typed event rồi handler xử lý transaction. Chỉ sau commit listener mới acknowledge.

### 11.7 Java config và code

| Service | Listener/router | Java config |
| --- | --- | --- |
| Payment | `PaymentWorkflowKafkaListener/Router` | `PaymentWorkflowConsumerConfig` |
| Risk | `RiskPaymentKafkaListener/Router` | `KafkaConsumerConfig` |
| Account-Ledger | `AccountLedgerWorkflowKafkaListener/Router` | `KafkaConsumerConfig` |
| Notification | `NotificationOutcomeKafkaListener/Router` | `KafkaConsumerConfig` |

Java config tạo listener container/recoverer/retry/DLT và topic owner bean. `application.yml` cung cấp connection/serializer/ack defaults.

### 11.8 Kafka key

Key là `aggregateId/paymentId`. Mọi event của một payment đi vào cùng partition để giữ order per payment. Hệ thống không yêu cầu global order giữa mọi payment.

### 11.9 Transactional Outbox

Không gọi Kafka ngay trong API transaction:

```text
BEGIN PostgreSQL
  update business state
  insert outbox JSON + headers
COMMIT

scheduled OutboxPollingJob
  → claim row bằng lease ngắn
  → commit claim
  → KafkaOutboxTransport.send ngoài DB transaction
  → conditional mark PUBLISHED/retry/FAILED
```

Cấu hình ở `.env.example`/service `application.yml` dưới `payflow.outbox`:

- enabled;
- poll interval;
- batch size;
- lease;
- max attempts;
- max backoff;
- delivery timeout.

### 11.10 Consumer Inbox

```text
Kafka record
 → BEGIN DB
 → insert processed_events(eventId, consumerName) ON CONFLICT DO NOTHING
 → nếu duplicate: no-op
 → nếu mới: mutate business + append outgoing outbox
 → COMMIT
 → ack Kafka
```

Đây là at-least-once + idempotent processing, không phải exactly-once end-to-end.

### 11.11 Thêm event/topic/consumer

1. Xác định producer owner/consumer/key.
2. Thêm typed contract + test trong `libs/event-contracts`.
3. Thêm docs event.
4. Producer append outbox trong business transaction.
5. Nếu topic mới, thêm `PayFlowTopics`, Compose `kafka-init`, owner `NewTopic` bean.
6. Consumer thêm config/group/listener/router/handler/inbox.
7. Thêm duplicate/retry/DLT/contract test.

## 12. Redis

### 12.1 Redis dùng ở đâu?

Chỉ Risk Service dùng Redis trong runtime hiện tại, cho velocity signal:

- đếm số payment của customer trong time window;
- deduplicate cùng payment ID trong counter;
- TTL retention.

Redis không giữ balance, payment state, journal hoặc assessment bền vững.

### 12.2 Dependency và image

- Risk POM: `spring-boot-starter-data-redis`.
- Compose image: `redis:8.2.8-alpine`.
- Persistence local bị tắt: `--save "" --appendonly no`.

### 12.3 Cấu hình

`.env.example`:

```text
PAYFLOW_REDIS_HOST
PAYFLOW_REDIS_PORT
PAYFLOW_REDIS_TIMEOUT
PAYFLOW_RISK_VELOCITY_RETENTION
```

[`risk-service/application.yml`](../../services/risk-service/src/main/resources/application.yml) bind vào `spring.data.redis` và `payflow.risk-velocity.retention`.

[`RiskVelocityProperties`](../../services/risk-service/src/main/java/com/payflow/risk/infrastructure/config/RiskVelocityProperties.java) bind retention.

### 12.4 Runtime flow

```text
Risk payment.created handler
 → RedisRiskSignalProvider
 → Lua script atomically check payment dedup + update counters + TTL
 → RiskSignalSnapshot
 → RiskRuleEngine
 → assessment lưu PostgreSQL
```

Lua được dùng để nhiều Redis operation thành atomic. Redis unavailable là infrastructure failure/retry, không được giả kết quả tiền.

## 13. Jackson JSON

### 13.1 Dùng cho gì?

- REST request/response JSON qua Spring MVC.
- `EventEnvelope<T>` và payload JSON trong outbox/Kafka.
- JSON headers/payload persistence.

`event-contracts` không gắn vendor annotation vào record; Jackson được dùng trong test để chứng minh wire shape.

### 13.2 Cấu hình/code

Spring Boot auto-configure `ObjectMapper`. Các `RuntimeConfig`/messaging adapter inject mapper để serialize/deserialize.

Producer:

```text
typed EventEnvelope → ObjectMapper.writeValueAsString → outbox JSON
```

Consumer:

```text
Kafka String JSON → router ObjectMapper.readValue(TypeReference<EventEnvelope<Data>>) → typed handler
```

Đổi record component name là thay wire contract, không chỉ refactor Java.

## 14. Jakarta Bean Validation

Payment POM thêm `spring-boot-starter-validation`.

`CreatePaymentRequest`/`CreateRefundRequest` dùng annotation để kiểm:

- null/blank;
- format/length;
- positive amount;
- request boundary constraints.

`PaymentController` dùng `@Valid`. Validation exception được `GlobalExceptionHandler` map thành field errors.

Bean Validation không thay domain invariant. Ví dụ “merchant có active không”, “refund có vượt capacity không” chỉ domain/application mới biết.

## 15. Spring Scheduling

### 15.1 Các scheduled job

| Job | Cấu hình | Việc làm |
| --- | --- | --- |
| Payment/Risk/Account outbox polling | `payflow.outbox.poll-interval` | Claim/publish pending outbox |
| Payment Saga recovery | `payflow.saga-recovery.poll-interval` | Recovery Saga quá hạn |
| Notification delivery | `payflow.notification-delivery.poll-interval` | Claim và gửi notification |

`@EnableScheduling` nằm trong Outbox/Runtime config. `@Scheduled(fixedDelayString=...)` nằm trên job/handler.

### 15.2 Vì sao fixed delay?

Lượt sau bắt đầu sau khi lượt trước kết thúc, giảm overlap trong cùng instance. Multi-instance safety vẫn đến từ database lease/optimistic locking, không chỉ scheduler.

## 16. Springdoc và Swagger UI

### 16.1 Dependency

Root khóa `springdoc-openapi.version=3.0.3`; Payment POM thêm:

```xml
springdoc-openapi-starter-webmvc-ui
```

### 16.2 Cấu hình

[`PaymentOpenApiConfig`](../../services/payment-service/src/main/java/com/payflow/payment/api/PaymentOpenApiConfig.java) cung cấp OpenAPI metadata/security scheme.

[`payment-service/application.yml`](../../services/payment-service/src/main/resources/application.yml):

- mặc định API docs/Swagger tắt;
- profile `local` bật `/v3/api-docs` và `/swagger-ui.html`;
- chỉ match `/api/v1/payments/**`.

Compose chạy Payment với `SPRING_PROFILES_ACTIVE=local`, nên Swagger có ở Payment port `8081`, không phải Gateway port.

### 16.3 Swagger làm được gì và không làm gì?

- Làm: xem/gọi REST API, schema, header, response/error.
- Không làm: hiển thị Kafka listener như REST endpoint, inspect outbox/inbox hoặc điều khiển Saga.

Kafka contract nằm trong `docs/events` và `libs/event-contracts`.

## 17. Spring Actuator và Micrometer

### 17.1 Actuator

Mỗi service thêm `spring-boot-starter-actuator`.

`application.yml` chỉ expose `health`, bật liveness/readiness probe, `show-details=never`.

Docker `HealthCheck.java` gọi:

```text
/actuator/health/readiness
```

Payment readiness bao gồm database health.

### 17.2 Micrometer

Actuator cung cấp `MeterRegistry`. Code hiện ghi counter/gauge cho:

- outbox published/retried/failed/pending/oldest age;
- payment workflow consumer outcome;
- risk consumer outcome;
- account-ledger consumer outcome;
- Saga recovery action;
- notification consumer/delivery outcome.

Hiện chưa có Prometheus registry/export endpoint và chưa có Grafana dashboard. Metric tồn tại trong application registry nhưng monitoring stack production-like chưa hoàn thành.

## 18. Logging và correlation

`application.yml` cấu hình:

```yaml
logging:
  structured:
    format:
      console: ecs
```

Log JSON/ECS dễ query hơn text tự do.

Shared [`CorrelationId`](../../libs/observability-support/src/main/java/com/payflow/observability/CorrelationId.java) chuẩn hóa `X-Correlation-Id` và MDC key.

Luồng:

```text
Gateway filter → HTTP header/MDC
 → Payment filter/MDC
 → outbox envelope/header
 → publisher MDC
 → event correlationId/causationId
```

Hiện chưa có OpenTelemetry trace exporter/Tempo/Loki stack; correlation ID là cơ chế truy vết đang hoạt động rõ nhất.

## 19. Docker và Docker Compose

### 19.1 Docker dùng để làm gì?

- đóng gói năm Java service bằng cùng recipe;
- chạy version cố định PostgreSQL/Kafka/Redis/Keycloak;
- tạo network/service DNS;
- health/dependency orchestration local;
- giữ volume PostgreSQL/Kafka.

### 19.2 File cấu hình

| File | Vai trò |
| --- | --- |
| [`docker-compose.yml`](../../docker-compose.yml) | Topology, images, env, ports, volume, health, profile |
| [`.env.example`](../../.env.example) | Config template |
| `.env` | Local values/secrets |
| [`java-service.Dockerfile`](../../infrastructure/docker/java-service.Dockerfile) | Build/run Java image non-root |
| [`.dockerignore`](../../.dockerignore) | Loại secret/output/docs khỏi context |
| [`HealthCheck.java`](../../infrastructure/docker/HealthCheck.java) | Readiness probe |

### 19.3 Profile

- `infra`: PostgreSQL + Redis + Kafka + topic init + Keycloak.
- `mvp`: infra + năm PayFlow deployable.
- `full`: hiện giống MVP.

### 19.4 Khi thêm service mới

1. Thêm Maven module và executable app.
2. Thêm DB/role init nếu service sở hữu DB.
3. Thêm env template và service application config.
4. Thêm Compose build args/env/ports/dependencies/health.
5. Thêm smoke/CI/config validation.
6. Không dùng chung credential/database của service cũ.

## 20. Testing technologies

### 20.1 JUnit 5, AssertJ, Mockito

`spring-boot-starter-test` được parent cung cấp cho mọi module.

- JUnit 5: test lifecycle/assertions framework.
- AssertJ: fluent assertions.
- Mockito: mock port/boundary khi không cần chứng minh database/lock.

Domain test chạy không Spring. Handler unit test mock ports nhưng không được dùng mock để tuyên bố SQL lock/constraint đúng.

### 20.2 MockMvc

Payment dùng MockMvc cho Controller/security/error slice:

```text
Mock HTTP request → MVC filters/controller/advice → assert status/header/JSON
```

Dependency test nằm trong Payment POM: webmvc/security/data-jpa test starters.

### 20.3 WebTestClient và WireMock

Gateway dùng:

- WebTestClient gọi gateway reactive server;
- WireMock giả Payment downstream;
- test xác minh route/header/security mà không cần start Payment thật.

Dependencies nằm trong Gateway POM.

### 20.4 Testcontainers

DB services thêm Spring Boot Testcontainers, PostgreSQL module và JUnit Jupiter integration.

Runtime test hiện có:

- Payment: PostgreSQL 17.10 container.
- Account-Ledger: PostgreSQL 17.10 container.
- Risk: PostgreSQL 17.10 + Redis 8.2.8 generic container.
- Notification: PostgreSQL 17.10 container.

Kafka broker delivery E2E hiện được chứng minh chủ yếu qua Docker Compose smoke; repository chưa có KafkaContainer test dependency riêng.

### 20.5 Surefire/Failsafe

- Surefire bỏ `*IT.java`, chạy test nhanh.
- Failsafe chỉ chạy `*IT.java` trong `integration-test/verify`.
- `@Tag("docker")` được profile `no-docker` loại khi chủ động chọn.

## 21. GitHub Actions CI

[`ci.yml`](../../.github/workflows/ci.yml) có:

| Job | Công nghệ | Mục đích |
| --- | --- | --- |
| `fast-tests` | GitHub runner + Temurin 21 + Maven | Compile/unit/slice nhanh |
| `verify` | Docker daemon + Maven/Testcontainers | Full integration gate |
| `compose-config` | Docker Compose | Render `.env.example`, kiểm topology 10 service |

CI hiện chưa build/push registry image, chưa deploy Kubernetes và chưa chạy load/chaos pipeline.

## 22. Các thiết kế hệ thống đi cùng công nghệ

### 22.1 Hexagonal/Clean Architecture

```text
API/Kafka adapter → application handler → domain
                                  ↓
                                port
                                  ↑
                         JPA/JDBC/Kafka adapter
```

Spring/Kafka/PostgreSQL là infrastructure detail ở bên ngoài domain.

### 22.2 DDD aggregate và state machine

`Payment`, `Refund`, `PaymentSaga`, `Account`, `Reservation`, `Journal`, `Notification` bảo vệ invariant. Không set status tùy ý từ listener/controller.

### 22.3 Saga orchestration

Payment Service lưu Saga và quyết định command tiếp theo. Kafka vận chuyển message; Saga policy quyết định nghiệp vụ. Kafka không tự hiểu “reserve/capture/compensation”.

### 22.4 Outbox/Inbox

PostgreSQL giải quyết atomicity local; Kafka vận chuyển at-least-once; inbox chống duplicate. Ba phần phối hợp, không có công nghệ đơn lẻ giải quyết distributed transaction.

### 22.5 Idempotency HTTP

`Idempotency-Key` + canonical request hash + stored response trong PostgreSQL xử lý client retry. Đây không phải tính năng Kafka.

## 23. Cấu hình nằm ở đâu: bảng tra nhanh

| Muốn cấu hình | File cần xem đầu tiên | Java class dùng |
| --- | --- | --- |
| Port service | `.env`/`.env.example` + Compose + service `application.yml` | Embedded server auto-config |
| DB URL/credential | `.env`, Compose, service `application.yml` | DataSource auto-config |
| Schema/table | Owner `db/migration/*.sql` | Flyway + JPA/JDBC adapter |
| Kafka broker | `.env`, Compose, `spring.kafka.bootstrap-servers` | Kafka auto-config |
| Topic | `PayFlowTopics`, Compose `kafka-init`, owner Kafka config | Listener/Outbox handler |
| Consumer retry/DLT | service Java Kafka config + consumer properties | Listener container/recoverer |
| Outbox polling | `.env` + `payflow.outbox` | `OutboxProperties`, `OutboxPollingJob` |
| Saga timeout | `.env` + `payflow.saga-recovery` | Recovery properties/job/policy |
| Redis | `.env`, Compose, Risk `application.yml` | `RedisRiskSignalProvider` |
| JWT issuer/JWK | `.env`, Compose, service `application.yml` | SecurityConfig/Resource Server |
| Scope/client/claim | `realm-payflow.json` | Keycloak token + SecurityConfig/Controller |
| Swagger | Payment POM/config/application local profile | Springdoc |
| Health | service `application.yml` + Compose healthcheck | Actuator + HealthCheck.java |
| Notification retry | `.env` + Notification application config | Delivery properties/policy/handler |
| Logging format | service `application.yml` | Spring Boot logging + MDC filters |
| Webhook outbound policy | `.env` + Merchant/Notification `application.yml` | `OutboundHttpUrlPolicy`, `HttpWebhookTransport` |

### Webhook outbound HTTP và chống SSRF

Merchant không coi URL do người dùng nhập là endpoint an toàn. Khi cấu hình, `MerchantApplicationService`
gọi shared `OutboundHttpUrlPolicy`; ngay trước mỗi HTTP POST, `HttpWebhookTransport` resolve và kiểm tra
lại URL. Mặc định chỉ HTTPS và mọi địa chỉ DNS resolve được đều phải là public. User-info, fragment,
localhost, private/link-local/reserved IP bị từ chối. HTTP client không tự đi theo redirect; response
3xx được coi là lỗi dứt điểm để redirect không trở thành đường vòng tới mạng nội bộ.

Biến `PAYFLOW_WEBHOOK_ALLOW_UNSAFE_LOCAL_TARGETS` được nối qua `.env.example`, Compose và
`application.yml` của Merchant/Notification. Giữ `false` trong mọi môi trường thông thường; chỉ bật
ngắn hạn khi cố ý demo receiver trên mạng local đáng tin cậy. Đây là defense-in-depth: validate lúc lưu
và validate lại lúc gửi để giảm DNS rebinding/stale configuration.

## 24. Công nghệ chưa được áp dụng hoàn chỉnh

### Chưa có Kubernetes

Không có `infrastructure/k8s`, Deployment, Service, ConfigMap, Secret, HPA hoặc Helm chart. Docker Compose không phải Kubernetes.

### Chưa có monitoring stack

Micrometer counters/gauges và Actuator health đã có, nhưng chưa có:

- Prometheus registry/scrape config;
- Grafana datasource/dashboard;
- Tempo collector;
- Loki/Promtail;
- alert rule.

### Chưa có OpenTelemetry distributed tracing

Chưa có SDK/agent/exporter/collector config. `correlationId` và event causation hiện cung cấp manual traceability, không đồng nghĩa full distributed trace.

### Chưa có real email provider

Notification dùng `InMemoryEmailDeliveryAdapter`. `EmailDeliveryPort` và timeout/retry contract đã sẵn sàng cho adapter thật, nhưng chưa có SMTP/API SDK.

### Chưa có frontend/browser authentication

Keycloak hiện dùng client credentials. Chưa có Authorization Code + PKCE và user-facing login application.

## 25. Thứ tự học stack đề xuất

1. Java/Spring Boot dependency injection và configuration.
2. MVC request → Controller → handler → domain → repository.
3. PostgreSQL/Flyway/JPA/JDBC và local transaction.
4. Keycloak JWT/scope/tenant ownership.
5. Kafka topic/key/listener/manual ack.
6. Transactional Outbox + Inbox.
7. Saga state/compensation/recovery.
8. Redis risk velocity.
9. Docker Compose startup/network/health.
10. Testcontainers, CI và observability.

Tài liệu liên quan:

- [API code walkthrough](payment-api-code-walkthrough.md)
- [Shared libraries/runtime configuration](shared-libraries-and-runtime-configuration-guide.md)
- [Services folder reference](services-folder-reference.md)
- [Infrastructure/repository reference](infrastructure-and-repository-folder-reference.md)
- [MVP Docker runbook](../runbooks/mvp-docker.md)
