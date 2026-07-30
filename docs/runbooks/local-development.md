# Runbook: chạy PayFlow trên máy local

> File này giữ quy trình build không Docker và chạy application từ host/IDE. Để dựng toàn bộ
> PostgreSQL, Redis, Kafka, Keycloak và năm application container theo lát cắt Phase 1B, dùng
> [mvp-docker.md](mvp-docker.md). Phần Docker Phase 0 cũ bên dưới chỉ là lịch sử foundation;
> `mvp-docker.md` là nguồn canonical cho Compose hiện tại.

Trạng thái: viết cho Phase 0. Mọi lệnh trong file này đều ghi rõ **đã chạy thật**
hay **chưa chạy**. Kết quả đã kiểm chứng nằm ở
[`.docs/IMPLEMENTATION_STATUS.md`](../../.docs/IMPLEMENTATION_STATUS.md).

Tại thời điểm viết, hạ tầng Docker **chưa từng được start**. Phần 1 và 2 dưới đây
chạy được ngay, không cần Docker. Phần 3 trở đi là hướng dẫn cho lần đầu bật hạ
tầng và chưa được xác minh end-to-end.

---

## 0. Yêu cầu môi trường

| Thành phần | Version đã dùng để chạy build | Bắt buộc cho |
| --- | --- | --- |
| JDK | 21.0.7 (Amazon Corretto) | Tất cả |
| Maven | 3.9.16 qua `./mvnw` (không cần cài Maven) | Tất cả |
| Docker Desktop | chưa cài/chưa bật | Phần 3 trở đi |

Maven Wrapper dùng `distributionType=only-script` nên repo **không** chứa
`maven-wrapper.jar`; lần chạy đầu `./mvnw` sẽ tự tải Maven 3.9.16 về
`~/.m2/wrapper/dists/`.

Trên Windows dùng Git Bash hoặc PowerShell:

```bash
./mvnw -v
```

PowerShell thì gọi `.\mvnw.cmd -v`.

---

## 1. Build và test không cần Docker (chạy được ngay)

Đây là vòng lặp phát triển hằng ngày.

Chỉ unit test và slice test — nhanh, chạy được sau mỗi lần lưu file:

```bash
./mvnw -B clean test
```

Lần xác minh Phase 1A mới nhất được ghi trong
`.docs/IMPLEMENTATION_STATUS.md`; dùng report trong `target/surefire-reports` để xem
chi tiết từng suite, không suy số test từ tài liệu cũ.

Toàn bộ gate **trừ** những test cần Docker:

```bash
./mvnw -B -Pno-docker clean verify
```

Đã chạy gần nhất: BUILD SUCCESS trong 51.386s, 9/9 module SUCCESS, 293
unit/slice test + 13 gateway integration test pass. Các test PostgreSQL/Kafka vẫn bị
loại bởi profile `no-docker` đúng thiết kế.

Cách phân chia test:

| Plugin | Pattern | Cần gì | Trạng thái |
| --- | --- | --- | --- |
| Surefire | `*Test` | Không gì cả | Chạy được, gồm domain/application/web/outbox policy |
| Failsafe | `*IT` không có tag `docker` | WireMock stub (tự start trong process) | 13/13 pass |
| Failsafe | `*IT` có `@Tag("docker")` | Docker daemon | **Chưa chạy** — payment foundation/schema/inbox/Saga/consumer PostgreSQL cases; Kafka broker gate còn phải chạy |

`-Pno-docker` là **opt-in** có chủ đích. `./mvnw verify` không có profile là gate
thật và sẽ chạy cả test cần Docker; ai bỏ qua chúng phải tự khai trên dòng lệnh.

`.docs/TESTING_STRATEGY.md` cấm thay PostgreSQL bằng H2, nên không có đường tắt
nào để `PaymentServiceFoundationIT` pass mà không cần Docker. Chi tiết lý do:
[ADR-007](../adr/ADR-007-postgresql-money-representation.md).

---

## 2. Chạy service khi chưa có hạ tầng

Có thể build được jar, nhưng **payment-service sẽ không start** nếu không có
PostgreSQL: `application.yml` cố tình không có giá trị mặc định cho username và
password database, và `spring.jpa.hibernate.ddl-auto=validate` yêu cầu schema đã
được migrate. Đây là hành vi đúng (`.agent/AGENTS.md` §8: không fallback âm thầm
sang credential mặc định), không phải bug.

api-gateway không cần database, nhưng cần một OIDC issuer để lấy JWKS lúc validate
token. Nó khởi động được mà không có Keycloak; request có token sẽ fail khi gateway
không lấy được JWKS.

Nói ngắn: muốn chạy service thật thì làm phần 3.

---

## 3. Bật hạ tầng lần đầu (CHƯA XÁC MINH)

### 3.1 Tạo file `.env`

```bash
cp .env.example .env
```

Rồi mở `.env` và đổi mọi giá trị `change-me-local-only` thành một chuỗi local của
bạn. `.env` đã được git-ignore. Không đưa credential thật, URL production hay
secret dùng chung vào đây — sandbox này chỉ dùng dữ liệu giả.

Các biến phải điền: `POSTGRES_PASSWORD`, `PAYFLOW_PAYMENT_DB_PASSWORD`,
`KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`,
`PAYFLOW_SERVICE_CLIENT_SECRET`, `PAYFLOW_READONLY_CLIENT_SECRET`.

### 3.2 Kiểm tra compose file trước khi start

```bash
docker compose config --quiet
```

Đã chạy (với `--env-file .env.example`): exit code 0. Lệnh này chỉ chứng minh file
parse được và biến được interpolate — nó không chứng minh image pull được hay
healthcheck pass.

### 3.3 Start

```bash
docker compose up -d
```

```bash
docker compose ps
```

Cả bốn service phải báo `(healthy)`. Lần đầu sẽ chậm: Docker phải pull 4 image, và
Keycloak chờ PostgreSQL healthy rồi mới import realm.

### 3.4 Cái gì chạy ở đâu

| Service | Container | Image (pin cứng) | Port host | Ghi chú |
| --- | --- | --- | --- | --- |
| PostgreSQL | `payflow-postgres` | `postgres:17.10-alpine` | `${POSTGRES_PORT}` = 5432 | Database `payflow_bootstrap` chỉ để bootstrap; service không kết nối vào đó |
| Redis | `payflow-redis` | `redis:8.2.8-alpine` | `${REDIS_PORT}` = 6379 | Persistence tắt hẳn. Chưa service nào dùng; sẽ dùng từ Phase 1A |
| Kafka | `payflow-kafka` | `apache/kafka:4.3.1` | `${KAFKA_PORT}` = 9092 | KRaft single-node, không ZooKeeper. Auto-create topic **tắt** |
| Keycloak | `payflow-keycloak` | `quay.io/keycloak/keycloak:26.7.0` | `${KEYCLOAK_PORT}` = 8180 | `start-dev`, chỉ dùng local |

Kafka có hai advertised listener: client trong compose network dùng
`kafka:19092`, service chạy trên host dùng `localhost:9092`. Dùng sai địa chỉ sẽ
connect được lần đầu rồi fail ngay sau metadata exchange.

Version PostgreSQL phải khớp giữa `docker-compose.yml` và image trong
`PaymentServiceFoundationIT`. Nếu lệch, test đang kiểm chứng một database khác với
database bạn chạy.

### 3.5 Database được tạo như thế nào

`infrastructure/docker/postgres/init/01-create-databases.sh` chạy **một lần duy
nhất**, lúc volume `postgres-data` còn rỗng. Nó tạo cho mỗi service một role +
một database, rồi `REVOKE ALL ON DATABASE ... FROM PUBLIC`:

- `payflow_payment` — owner `payflow_payment`
- `payflow_keycloak` — owner `payflow_keycloak`

Sửa script sau khi volume đã có dữ liệu thì script **không** chạy lại. Muốn nó
chạy lại phải xoá volume (xem phần 6).

Bảng thì do Flyway tạo lúc payment-service start, không phải do script này.

### 3.6 Xác minh từng thành phần

Tất cả các lệnh dưới đây **chưa được chạy**.

PostgreSQL — liệt kê database đã tạo:

```bash
docker compose exec postgres psql -U payflow -d payflow_bootstrap -c "\l"
```

Redis:

```bash
docker compose exec redis redis-cli ping
```

Kafka — liệt kê topic (Phase 0 chưa có topic nào, danh sách rỗng là đúng):

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list
```

Keycloak — OIDC discovery document:

```bash
curl http://localhost:8180/realms/payflow/.well-known/openid-configuration
```

Admin console: <http://localhost:8180> — đăng nhập bằng `KEYCLOAK_ADMIN` /
`KEYCLOAK_ADMIN_PASSWORD` trong `.env`.

---

## 4. Chạy gate đầy đủ khi đã có Docker

```bash
./mvnw -B clean verify
```

**Chưa chạy.** Lệnh này thêm các payment Testcontainers test so với
`-Pno-docker`. Nó không dùng PostgreSQL trong compose: Testcontainers tự start một
container `postgres:17.10-alpine` riêng, chạy Flyway trên đó rồi xoá đi. Nghĩa là
`docker compose up` **không** phải điều kiện tiên quyết — chỉ cần Docker daemon
đang chạy.

Các test này kiểm chứng Flyway V1–V4, schema/constraint/index payment intake, readiness và actuator,
đồng thời kiểm chứng inbox duplicate/concurrency/rollback, Saga persistence và transactional Payment
workflow consumer trên PostgreSQL thật. Kafka listener/retry/DLT đã có code nhưng offset commit và
redelivery trên broker thật vẫn cần Kafka integration gate riêng khi Docker được bật.

Nếu Docker chưa bật, các test này fail vì không tìm được daemon — đó là fail đúng,
không phải flaky. Dùng `-Pno-docker` nếu chủ ý bỏ qua.

---

## 5. Chạy service với hạ tầng đang lên (CHƯA XÁC MINH)

Sau `docker compose up -d`, cần hai việc: nạp biến môi trường, rồi chạy jar.

`docker compose` tự đọc `.env`, nhưng một process Java thì không. Trong Git Bash:

```bash
set -a && . ./.env && set +a
```

Biến bắt buộc phải có, nếu thiếu thì payment-service fail lúc start:
`PAYFLOW_PAYMENT_DB_USERNAME`, `PAYFLOW_PAYMENT_DB_PASSWORD`. Các biến còn lại
(`PAYFLOW_PAYMENT_DB_URL`, `PAYFLOW_OIDC_ISSUER_URI`, `PAYFLOW_PAYMENT_PORT`,
`PAYFLOW_GATEWAY_PORT`, `PAYFLOW_PAYMENT_SERVICE_URI`,
`PAYFLOW_KAFKA_BOOTSTRAP_SERVERS`) đều có default trỏ về localhost, khớp với port
trong `.env.example`.

Nếu chỉ muốn kiểm tra REST + PostgreSQL trong lúc Kafka chưa bật, đặt
`PAYFLOW_OUTBOX_ENABLED=false` và `PAYFLOW_WORKFLOW_CONSUMER_ENABLED=false`. Payment vẫn ghi row `PENDING` cùng transaction;
khi bật publisher lại, các row đó mới được drain. Không dùng cờ này để tuyên bố
Phase 1A hoàn tất vì publisher/Kafka vẫn chưa được kiểm chứng.

Khi bật Kafka để chạy workflow, bỏ hai cờ trên hoặc đặt chúng thành `true`. Consumer dùng group
`payment-saga-orchestrator-v1`, manual acknowledgement, retry theo
`PAYFLOW_WORKFLOW_CONSUMER_RETRY_BACKOFF`/`PAYFLOW_WORKFLOW_CONSUMER_MAX_RETRIES` và đưa record thất
bại cuối cùng vào `payflow.dead-letter.v1`. Xem [payment-workflow-dlt.md](payment-workflow-dlt.md)
trước khi replay.

Build jar (`spring-boot:repackage` đã chạy trong `verify`, nên jar đã executable):

```bash
./mvnw -B -Pno-docker clean verify
```

Rồi mở hai terminal, mỗi terminal nạp `.env` như trên trước khi chạy.

payment-service:

```bash
java -jar services/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar
```

api-gateway:

```bash
java -jar services/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar
```

Không dùng `./mvnw -pl services/payment-service spring-boot:run` một mình: reactor
lúc đó chỉ có một module nên `com.payflow:observability-support` và
`com.payflow:error-contract` không resolve được. Muốn dùng `spring-boot:run` thì
phải `./mvnw -B install -DskipTests` một lần trước đó.

Kiểm tra sức khoẻ (không cần token):

```bash
curl -i http://localhost:8081/actuator/health
```

```bash
curl -i http://localhost:8080/actuator/health
```

Lấy access token bằng client credentials — thay `<secret>` bằng
`PAYFLOW_SERVICE_CLIENT_SECRET` trong `.env` của bạn:

```bash
curl -s -X POST http://localhost:8180/realms/payflow/protocol/openid-connect/token -d grant_type=client_credentials -d client_id=payflow-service -d client_secret=<secret>
```

Tạo payment qua gateway với token đó:

```bash
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer <access_token>" \
  -H "Idempotency-Key: d290f1ee-6c54-4b01-90e6-d701748f0851" \
  -H "X-Correlation-Id: local-create-1" \
  -H "Content-Type: application/json" \
  -d '{"merchantReference":"ORDER-LOCAL-0001","customerId":"3beff442-7f10-4504-aab4-12d985cf3e95","sourceAccountId":"039bedb6-b2d6-47df-aa25-2035e39136a3","amount":500000,"currency":"VND","description":"Local sandbox payment","metadata":{"orderId":"ORDER-LOCAL-0001"}}'
```

Kỳ vọng `202 Accepted`. Gửi lại đúng key và đúng JSON theo nghĩa canonical phải trả
cùng `paymentId`; đổi amount nhưng giữ key phải trả `409` với code
`IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST`.

Token local phải có claim `merchant_id=11111111-1111-4111-8111-111111111111`.
Realm import đã khai báo mapper hardcoded tới merchant seed giả này nhưng chưa được
xác minh với Keycloak thật. Nếu claim vắng mặt, payment-service trả 403 theo chủ đích;
không được thêm `merchantId` vào request body để lách ownership.

Đọc payment vừa tạo:

```bash
curl -i -H "Authorization: Bearer <access_token>" -H "X-Correlation-Id: local-get-1" http://localhost:8080/api/v1/payments/<paymentId>
```

Kiểm tra outbox bằng PostgreSQL:

```bash
docker exec payflow-postgres psql -U payflow_payment -d payflow_payment -c "select id, aggregate_id, event_type, status, attempt_count, last_error from payment.outbox_events order by created_at desc limit 10;"
```

Khi Kafka và publisher hoạt động, row phải đi `PENDING -> PROCESSING -> PUBLISHED`.
Nếu Kafka tắt, request tạo payment vẫn trả 202 và row retry hữu hạn rồi có thể thành
`FAILED`; không sửa payload của row để retry. Quy trình requeue thủ công nằm trong
[ADR-014](../adr/ADR-014-outbox-claim-lease-and-recovery.md).

Mọi response lỗi phải là `application/problem+json` và có `code` +
`correlationId`. Header `X-Correlation-Id` phải được echo lại đúng giá trị bạn gửi.

### Điều đã biết là chưa xác minh về Keycloak

`infrastructure/keycloak/realm-payflow.json` khai báo client secret dưới dạng
`${PAYFLOW_SERVICE_CLIENT_SECRET}` và `${PAYFLOW_READONLY_CLIENT_SECRET}` để không
commit secret nào vào repo. **Việc Keycloak 26.7 có thật sự substitute các
placeholder này lúc import realm hay không thì chưa được kiểm chứng** — chưa lần
nào chạy được container.

Nếu nó không substitute, secret của client sẽ đúng bằng chuỗi
`${PAYFLOW_SERVICE_CLIENT_SECRET}` theo nghĩa văn tự. Dấu hiệu: lệnh lấy token ở
trên trả `invalid_client`. Cách khắc phục: vào admin console → Clients →
`payflow-service` → Credentials → regenerate secret, rồi cập nhật `.env`. Đây là
failure mode lành tính: không leak secret, chỉ là phải set tay một lần.

---

## 6. Reset và dọn dẹp

Dừng container nhưng giữ dữ liệu:

```bash
docker compose down
```

Xoá luôn cả dữ liệu — database, realm, Kafka log đều mất:

```bash
docker compose down -v
```

Chỉ chạy `down -v` khi bạn thật sự muốn mất dữ liệu local. Nó cũng là cách duy
nhất để `01-create-databases.sh` chạy lại. Mọi thứ trong sandbox này là disposable
theo thiết kế: schema dựng lại từ Flyway, realm dựng lại từ file import.

Xoá build output:

```bash
./mvnw -B clean
```

---

## 7. Sự cố thường gặp

| Hiện tượng | Nguyên nhân | Xử lý |
| --- | --- | --- |
| `Could not find a valid Docker environment` | Docker daemon chưa chạy | Bật Docker Desktop, hoặc dùng `-Pno-docker` |
| payment-service fail lúc start, lỗi datasource | Chưa có `.env`, hoặc biến chưa được export vào process | Đã cố tình không có default cho credential; xem phần 2 |
| Flyway `Validate failed` | Đã sửa một migration script đã apply | `docker compose down -v` rồi start lại. Không sửa migration đã apply trên môi trường có dữ liệu |
| Hibernate fail lúc start với lỗi schema | `ddl-auto=validate` phát hiện entity lệch schema | Sửa migration cho khớp entity. Không đổi sang `ddl-auto=update` |
| `invalid_client` khi lấy token | Có thể là vấn đề `${ENV}` substitution ở phần 5 | Regenerate secret trong admin console |
| Port đã bị chiếm | 5432/6379/9092/8180/8080/8081 đang có process khác | Đổi giá trị port trong `.env` |
| Keycloak không bao giờ healthy | Nó chờ PostgreSQL healthy trước | `docker compose logs keycloak`; kiểm tra `payflow_keycloak` đã được tạo |

---

## 8. CI (CHƯA CHẠY LẦN NÀO)

[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) có ba job:

| Job | Lệnh | Vai trò |
| --- | --- | --- |
| `fast-tests` | `./mvnw -B -ntp clean test` | Compile + Surefire. Không cần Docker |
| `verify` | `./mvnw -B -ntp clean verify` | Gate thật, gồm cả test Testcontainers. Chạy sau `fast-tests` |
| `compose-config` | `docker compose --env-file .env.example config --quiet` | Chứng minh `.env.example` đủ biến để render compose file |

`verify` **không** dùng `-Pno-docker`: runner `ubuntu-latest` có Docker daemon, nên
bỏ qua test container ở CI sẽ làm gate mất ý nghĩa. Đó cũng là job duy nhất có thể
đưa một capability lên trạng thái `VERIFIED_CI`.

Workflow này chưa từng execute — repo chưa có commit và chưa có remote. Trước khi
push lần đầu, cần set exec bit cho Maven Wrapper, vì Git trên Windows không ghi
nhận nó:

```bash
git update-index --chmod=+x mvnw
```

Workflow vẫn có một step `chmod +x ./mvnw` để phòng trường hợp trên bị bỏ sót, nên
việc quên lệnh này không làm CI đỏ — nó chỉ tạo ra diff mode rác về sau.

---

## 9. Đang thiếu gì ở Phase 0

- Repo đã `git init` (branch `master`) nhưng **chưa có commit nào**, nên mọi
  evidence entry vẫn ghi `Commit SHA: N/A`. Cần commit đầu tiên để evidence truy
  vết được.
- CI đã có file workflow nhưng **chưa chạy lần nào**; không capability nào ở trạng
  thái `VERIFIED_CI`.
- 5 mục `OPEN` trong [`.docs/OPEN_DECISIONS.md`](../../.docs/OPEN_DECISIONS.md)
  vẫn chặn scope tương ứng của chúng.
- `.docs/DELIVERY_ROADMAP.md` quyết định lát cắt được phép làm tiếp; không nhảy
  sang phase sau.
