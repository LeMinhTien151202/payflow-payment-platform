# Infrastructure và repository folder reference

Tài liệu này mô tả các folder/file còn lại ngoài `services/` và `libs/`:

- `infrastructure/`
- root configuration
- `.github/`, `.mvn/`
- `docs/` và `.docs/`
- `.agent/`, `.agents/`, `.claude/`
- `.git/`, `.idea/` và build output

Nó tập trung vào câu hỏi: file nào được công cụ nào đọc, đọc lúc nào, tạo ra tác dụng gì và có tham gia runtime hay không.

## 1. Bản đồ repository hiện tại

```text
payflow-payment-platform/
├── services/                 # 5 Maven deployable
├── libs/                     # 3 shared JAR
├── infrastructure/           # Docker/PostgreSQL/Keycloak/smoke assets
├── docs/                     # tài liệu sản phẩm/portfolio/operations
├── .docs/                    # governance và delivery guidance
├── .agent/                   # canonical agent rules
├── .agents/                  # PayFlow skill đầy đủ
├── .claude/                  # Claude entry/router và local setting
├── .github/                  # GitHub Actions CI
├── .mvn/                     # Maven Wrapper metadata
├── .git/                     # Git internal database
├── .idea/                    # IntelliJ local metadata
├── pom.xml                   # parent/reactor build
├── docker-compose.yml        # local topology
├── .env.example / .env       # env template / local values
├── mvnw / mvnw.cmd           # Maven Wrapper launchers
└── specification/rule files
```

Không phải folder nào cũng được application đọc:

| Nhóm | Có chạy trong application? | Ai đọc? |
| --- | --- | --- |
| `services`, `libs` | Có, sau khi compile vào JAR | JVM/Spring |
| `infrastructure`, Compose, `.env` | Gián tiếp tạo runtime | Docker/Compose/PostgreSQL/Keycloak/PowerShell |
| `docs`, `.docs`, agent folders | Không | Developer, reviewer, coding agent |
| `.github` | Không ở local runtime | GitHub Actions |
| `.mvn`, root `pom.xml` | Build time | Maven Wrapper/Maven |
| `.git`, `.idea` | Không | Git/IDE |

## 2. `infrastructure/` hiện có gì?

Repository hiện chỉ có năm file infrastructure:

```text
infrastructure/
├── docker/
│   ├── HealthCheck.java
│   ├── java-service.Dockerfile
│   └── postgres/init/01-create-databases.sh
├── keycloak/realm-payflow.json
└── scripts/smoke-mvp.ps1
```

Các folder `kafka/`, `monitoring/`, `k8s/` có trong target architecture nhưng chưa có implementation trong cây hiện tại. Kafka local được cấu hình trực tiếp trong Compose.

## 3. `infrastructure/docker/java-service.Dockerfile`

[`java-service.Dockerfile`](../../infrastructure/docker/java-service.Dockerfile) là multi-stage build dùng chung cho năm Java deployable.

### 3.1 Ai gọi file này?

Mỗi service trong [`docker-compose.yml`](../../docker-compose.yml) có:

```yaml
build:
  context: .
  dockerfile: infrastructure/docker/java-service.Dockerfile
  args:
    SERVICE_MODULE: payment-service
```

Compose thay `SERVICE_MODULE` bằng một trong năm module đã biết.

### 3.2 Luồng build

```text
docker compose ... --build
  → đọc java-service.Dockerfile
  → build stage: eclipse-temurin:21-jdk
  → COPY toàn build context
  → javac HealthCheck.java
  → normalize mvnw line ending + executable bit
  → ./mvnw -pl services/${SERVICE_MODULE} -am package
  → service + dependent libs được build
  → copy executable JAR
  → runtime stage: eclipse-temurin:21-jre
  → tạo user/group 10001
  → copy app.jar + HealthCheck.class
  → USER 10001:10001
  → ENTRYPOINT java -jar /opt/payflow/app.jar
```

`-Pno-docker` ở bước package tránh container test bên trong Docker image build. `-DskipTests` bỏ chạy test khi image build; CI/test gate phải chạy trước hoặc riêng, không dùng image build để chứng minh test.

### 3.3 Vì sao một Dockerfile dùng chung?

- Năm service đều là Java 21/Spring Boot JAR.
- Giữ cùng non-root user và runtime baseline.
- Tránh năm recipe lệch nhau.
- `SERVICE_MODULE` chỉ chọn module; business config vẫn nằm trong service/Compose.

## 4. `infrastructure/docker/HealthCheck.java`

[`HealthCheck.java`](../../infrastructure/docker/HealthCheck.java) là chương trình Java thuần không dependency.

Luồng:

```text
Docker healthcheck command
 → java -cp /opt/payflow/healthcheck HealthCheck <readiness-url>
 → HTTP GET với connect timeout 2s, request timeout 3s
 → yêu cầu status 200
 → yêu cầu body chứa "status":"UP"
 → exit 0 nếu healthy, exit 1 nếu unhealthy, exit 2 nếu sai argument
```

Nó không phải Spring bean và không chạy liên tục trong service process. Docker gọi process health probe riêng theo interval.

Keycloak dùng health probe Java source riêng inline trong Compose vì image/layout và management port khác.

## 5. PostgreSQL init folder

File [`01-create-databases.sh`](../../infrastructure/docker/postgres/init/01-create-databases.sh) được mount read-only vào:

```text
/docker-entrypoint-initdb.d
```

PostgreSQL official image chỉ chạy script trong folder này khi data directory/volume còn rỗng.

### 5.1 Luồng bootstrap

```text
postgres container lần đầu + volume rỗng
 → image tạo payflow_bootstrap
 → chạy 01-create-databases.sh
 → đọc username/password service từ environment
 → create role + create database owner
 → revoke PUBLIC
 → grant connect/temporary cho đúng owner
 → PostgreSQL healthcheck pass
```

Database được tạo:

| Database | Owner |
| --- | --- |
| `payflow_payment` | Payment Service role |
| `payflow_account_ledger` | Account-Ledger role |
| `payflow_risk` | Risk role |
| `payflow_notification` | Notification role |
| `payflow_keycloak` | Keycloak role |

Sau bootstrap, Flyway trong từng service mới tạo schema/table của service đó. Init script tạo database/role; Flyway tạo application schema.

### 5.2 Điều dễ nhầm

- `docker compose restart` không chạy lại init script.
- Sửa script sau khi volume đã tồn tại không làm database tự đổi.
- `docker compose down -v` xóa volume và dữ liệu; chỉ dùng với local disposable data khi chủ động muốn reset.
- Không dùng chung superuser `POSTGRES_USER` cho application service.

## 6. `infrastructure/keycloak/`

[`realm-payflow.json`](../../infrastructure/keycloak/realm-payflow.json) là import artifact cho Keycloak container.

### 6.1 Compose sử dụng ra sao?

```text
Compose mount infrastructure/keycloak
 → /opt/keycloak/data/import:ro
 → keycloak start-dev --import-realm
 → đọc realm-payflow.json
 → thay client secret từ environment
 → lưu realm/client/scope vào payflow_keycloak DB
 → health ready
```

File tạo:

- realm `payflow`;
- scopes `payment:read`, `payment:write`;
- confidential clients `payflow-service`, `payflow-readonly`;
- service-account/client-credentials flow;
- local hardcoded `merchant_id` mapper.

File không chứa user database của ứng dụng và không tạo browser login flow hiện tại. Gateway/Service không đọc JSON trực tiếp; chúng gọi issuer/JWK endpoint của Keycloak runtime.

## 7. `infrastructure/scripts/smoke-mvp.ps1`

[`smoke-mvp.ps1`](../../infrastructure/scripts/smoke-mvp.ps1) là E2E smoke harness cho local MVP. Nó không khởi động stack thay bạn; nó kiểm tra stack đã được start.

### 7.1 Các function

| Function | Chức năng |
| --- | --- |
| `Import-DotEnv` | Đọc `.env` mà không in secret |
| `Require-EnvironmentValue` | Fail sớm nếu biến bắt buộc thiếu |
| `Invoke-Compose` | Chạy Docker Compose đúng env/profile |
| `Invoke-PsqlScalar` | Query một giá trị qua PostgreSQL container |
| `Wait-HttpHealthy` | Poll readiness có deadline |
| `Assert-Equal` | Fail smoke khi invariant/result khác dự kiến |

### 7.2 Luồng smoke

```text
validate resolved Compose model
 → xác nhận 5 app container đang up
 → chờ readiness từng service
 → query account fixture phải pristine
 → gọi Keycloak token endpoint
 → không print token
 → POST payment qua Gateway
 → POST lại cùng Idempotency-Key, phải cùng paymentId
 → poll GET payment tới terminal status
 → yêu cầu SUCCEEDED
 → poll notification tới SENT
 → query Risk decision, Account balance, Reservation, Ledger journal
 → kiểm tra 1 Payment row sau replay
 → in MVP SMOKE PASSED
```

Smoke query database trực tiếp chỉ để **assert E2E test**, không phải cách service runtime giao tiếp. Runtime vẫn cấm cross-service database access.

Nếu account không pristine, script dừng để tránh test dựa trên balance đã bị lần chạy trước thay đổi.

## 8. Root `docker-compose.yml`

[`docker-compose.yml`](../../docker-compose.yml) là topology local sandbox, không phải Kubernetes/production manifest.

### 8.1 Các Compose service

| Compose service | Profile | Chức năng |
| --- | --- | --- |
| `postgres` | infra/mvp/full | Một PostgreSQL server local, nhiều database/role riêng |
| `redis` | infra/mvp/full | Ephemeral Risk velocity store |
| `kafka` | infra/mvp/full | Single-node KRaft broker |
| `kafka-init` | infra/mvp/full | One-shot topic creation job |
| `keycloak` | infra/mvp/full | OAuth2/OIDC issuer |
| `payment-service` | mvp/full | Payment/Refund/Saga |
| `account-ledger-service` | mvp/full | Account + Ledger boundaries |
| `risk-service` | mvp/full | Risk assessment |
| `notification-service` | mvp/full | Outcome notification |
| `api-gateway` | mvp/full | Public HTTP edge |

### 8.2 Profile

- `infra`: chỉ hạ tầng.
- `mvp`: hạ tầng + ứng dụng.
- `full`: hiện chưa thêm deployable nào ngoài MVP.

### 8.3 Luồng dependency

```text
postgres healthy ────────────────> keycloak
kafka healthy ──────────────────> kafka-init exit 0
postgres + kafka-init + keycloak → Payment/Account-Ledger/Notification
postgres + redis + kafka-init + keycloak → Risk
Payment + Keycloak healthy ──────> Gateway
```

`depends_on` giúp local startup order, không thay application retry/recovery.

## 9. `.env.example` và `.env`

| File | Track bởi Git? | Chức năng |
| --- | --- | --- |
| [`.env.example`](../../.env.example) | Có | Template tên biến và safe local placeholder |
| `.env` | Không | Local secret/port/config thật của developer |

Compose đọc `.env` bằng `--env-file .env`, nội suy `${VAR}`, rồi truyền subset vào từng container. Spring đọc lại các environment variable trong `application.yml`.

Không đưa `.env` vào tài liệu/commit/log. Khi cần chia sẻ cấu hình mới, thêm tên biến + placeholder vào `.env.example`.

Host PostgreSQL dùng port `5433`; container nội bộ vẫn dùng `postgres:5432`. Tương tự Kafka host `localhost:9092`, container `kafka:19092`.

## 10. Root Maven files

### 10.1 `pom.xml`

[`pom.xml`](../../pom.xml) làm ba vai trò:

1. Parent: Java/Spring/plugin/test convention.
2. Aggregator: danh sách module `libs` và `services`.
3. Dependency management: version chung cho Spring Cloud, Springdoc, internal JAR.

Nó không tạo service runtime riêng vì packaging là `pom`.

### 10.2 Maven Wrapper

| File | Chức năng |
| --- | --- |
| [`mvnw`](../../mvnw) | Launcher Linux/macOS/container/CI |
| [`mvnw.cmd`](../../mvnw.cmd) | Launcher Windows |
| [`.mvn/wrapper/maven-wrapper.properties`](../../.mvn/wrapper/maven-wrapper.properties) | Khóa Maven `3.9.16` download URL |

Wrapper giúp dev và CI dùng cùng Maven version. Nó không khóa JDK; JDK 21 được khóa ở POM/CI/Docker image.

## 11. GitHub Actions

[` .github/workflows/ci.yml`](../../.github/workflows/ci.yml) được GitHub đọc khi push/PR/manual dispatch.

| Job | Luồng |
| --- | --- |
| `fast-tests` | checkout → JDK 21 → `mvnw clean test` → upload Surefire report |
| `verify` | chờ fast test → xác nhận Docker → `mvnw clean verify` → upload Surefire/Failsafe |
| `compose-config` | render `.env.example` cho infra/mvp → xác nhận đúng 10 Compose services |

`verify` không dùng `-Pno-docker`, vì mục tiêu là chạy Testcontainers thật trên CI runner.

CI không deploy production, không push image và không chạy Kubernetes trong workflow hiện tại.

## 12. Git/Docker/editor metadata

### 12.1 `.gitignore`

[` .gitignore`](../../.gitignore) loại khỏi Git:

- `.env` và secret/key material;
- `target`, JAR/class/build output;
- IDE/OS metadata;
- logs/local data;
- ZIP bootstrap/archive.

`.env.example` được allow lại để template vẫn tracked.

### 12.2 `.dockerignore`

[` .dockerignore`](../../.dockerignore) loại khỏi Docker build context:

- Git/IDE/agent/governance folders;
- `target`, logs và env file;
- docs/spec không cần cho compile runtime.

Điều này giảm context và tránh đưa local secret vào build. Dockerfile vẫn copy source/POM/Wrapper cần để build.

### 12.3 `.gitattributes`

[` .gitattributes`](../../.gitattributes) chuẩn hóa line ending:

- phần lớn text dùng LF;
- `.cmd`, `.bat`, `.ps1` dùng CRLF;
- `.sh` và `mvnw` giữ LF;
- binary không bị text conversion.

Điều này đặc biệt quan trọng vì Windows checkout build Linux container.

### 12.4 `.git/` và `.idea/`

- `.git/`: commit/index/object/config nội bộ của Git; không sửa bằng tay.
- `.idea/`: IntelliJ local metadata, bị git-ignore; không phải source of truth.
- `target/`: output Maven của từng module, có thể xóa/rebuild; không review như source.

## 13. `docs/`: tài liệu bàn giao

[`docs/README.md`](../README.md) là index cho người đọc repository.

### 13.1 `docs/api`

[`payment-service-v1.yaml`](../api/payment-service-v1.yaml) là OpenAPI contract. Swagger runtime sinh docs từ annotation/config, nhưng file YAML là contract reviewable trong Git.

### 13.2 `docs/events`

Mỗi file mô tả một Kafka contract hoặc workflow:

- event name/version/topic/key;
- producer/consumer;
- envelope/payload;
- precondition/meaning;
- compatibility/failure semantics.

Java source of typed schema nằm trong `libs/event-contracts`; Markdown giúp con người review và vận hành.

### 13.3 `docs/adr`

ADR là quyết định kiến trúc đã accepted. Danh mục hiện tại:

| ADR | Quyết định |
| --- | --- |
| 004 | Transactional outbox polling publisher |
| 007 | PostgreSQL money representation |
| 011 | Ledger → capture → payment success ordering |
| 012 | Recovery trước khi tách service |
| 013 | Platform version baseline |
| 014 | Outbox claim/lease/recovery |
| 015 | Risk score saturation/level bands |
| 016 | Risk event taxonomy |
| 017 | PostgreSQL inbox insert-if-new |
| 018 | Payment manual-review contract |
| 019 | Immutable fee snapshot |
| 020 | Pessimistic refund capacity reservation |
| 021 | Refund ledger → credit → success ordering |

ADR giải thích **vì sao**; code/migration/test chứng minh **đã làm thế nào**.

### 13.4 `docs/runbooks`

| Runbook | Khi dùng |
| --- | --- |
| `commands-guide` | Tra lệnh build/test/Compose |
| `local-development` | Chạy app từ host/IDE |
| `mvp-docker` | Dựng và smoke toàn MVP |
| `outbox-recovery` | Outbox pending/failed/stale lease |
| `payment-workflow-dlt` | Poison event/retry/DLT triage |
| `saga-manual-review` | Saga dừng cần operator quyết định |
| `notification-delivery-failure` | Notification retry/failed triage |

Runbook không được application execute. Operator đọc và chạy command có kiểm soát.

### 13.5 `docs/architecture`

Chứa tài liệu giải thích code/nghiệp vụ:

- business processing reference;
- code flow guide;
- REST/Kafka guide;
- API walkthrough;
- shared libraries/runtime configuration;
- service folder reference này;
- infrastructure/repository reference này.

## 14. `.docs/`: governance trong giai đoạn xây dựng

`.docs/` khác `docs/`:

| File | Chức năng |
| --- | --- |
| `README.md` | Source precedence và routing tài liệu |
| `PROJECT_OVERVIEW.md` | Mục tiêu portfolio/phạm vi |
| `ARCHITECTURE.md` | Target topology, ownership, Saga, dependency rules |
| `MODULE_MAP.md` | Module/service owner, dữ liệu và contract |
| `DELIVERY_ROADMAP.md` | Phase/gate và thứ tự lát cắt |
| `OPEN_DECISIONS.md` | Blocker chưa chốt; không được code vượt qua |
| `IMPLEMENTATION_STATUS.md` | Evidence thực tế và limitation |
| `TESTING_STRATEGY.md` | Test pyramid/invariant/E2E/failure matrix |
| `adr/README.md`, `ADR-TEMPLATE.md` | Quy trình đề xuất ADR mới |

Spring/Maven/Docker không đọc `.docs`. Đây là governance cho developer/agent. Không dùng nó thay OpenAPI/event contract/runbook chính thức.

## 15. Agent-related folders

### 15.1 `.agent/`

[` .agent/AGENTS.md`](../../.agent/AGENTS.md) là canonical repository rule set: tiền, ownership, Kafka, transaction, security, migration, test và workflow.

### 15.2 `.agents/`

```text
.agents/skills/payflow-backend/
├── SKILL.md
├── agents/openai.yaml
├── references/
│   ├── feature-workflow.md
│   ├── financial-invariants.md
│   └── verification.md
└── scripts/validate-governance.ps1
```

| File | Chức năng |
| --- | --- |
| `SKILL.md` | Workflow bắt buộc cho PayFlow work |
| `openai.yaml` | Tên/description/default prompt hiển thị của skill |
| `feature-workflow` | Checklist trước/trong/sau feature |
| `financial-invariants` | Checklist khi chạm amount/balance/ledger/refund |
| `verification` | Lệnh/evidence/review checklist |
| `validate-governance.ps1` | Kiểm required paths, TODO/conflict marker/code fence/local link |

Governance validator chỉ kiểm agent/governance Markdown và required path; nó không thay Maven test, link checker của toàn `docs` hay runtime smoke.

### 15.3 `.claude/` và `CLAUDE.md`

[`CLAUDE.md`](../../CLAUDE.md) chỉ route Claude Code về canonical `.agent/AGENTS.md`, `.docs` và PayFlow skill.

`.claude/skills/payflow-backend/SKILL.md` là entry/router tương ứng. `.claude/settings.local.json` là local user setting; không phải runtime PayFlow và không nên biến thành project contract.

## 16. Specification và archive ở root

| File | Vai trò |
| --- | --- |
| [`PAYFLOW_MICROSERVICE_PROJECT_SPEC.md`](../../PAYFLOW_MICROSERVICE_PROJECT_SPEC.md) | Product/technical source specification; không được app parse |
| [`AGENTS.md`](../../AGENTS.md) | Router yêu cầu đọc canonical agent rules/skill |
| [`CLAUDE.md`](../../CLAUDE.md) | Claude-specific router, không thêm rule riêng |
| `payflowPayment.zip` | Archive cũ của developer, bị ignore; không nằm trong Maven reactor/Compose/runtime |

Spec là nguồn yêu cầu, không phải executable configuration. Thay spec không tự thay code/migration/contract.

## 17. Luồng hoàn chỉnh từ command tới runtime

### 17.1 `docker compose up --build`

```text
PowerShell command
 → Docker Compose đọc docker-compose.yml
 → đọc .env và nội suy biến
 → Dockerfile build service + libs
 → PostgreSQL init script nếu volume rỗng
 → kafka-init tạo topic
 → Keycloak import realm
 → container truyền env vào Spring Boot
 → application.yml bind property
 → Flyway migrate
 → Spring tạo Security/Kafka/Outbox/Saga bean
 → HealthCheck gọi readiness
 → Gateway start sau Payment healthy
```

### 17.2 `mvnw verify`

```text
mvnw.cmd/mvnw
 → đọc wrapper.properties, dùng Maven 3.9.16
 → đọc parent pom và module list
 → build libs trước service phụ thuộc
 → compile main/test
 → Surefire chạy *Test
 → package service JAR
 → Failsafe chạy *IT
 → verify lifecycle kết thúc
```

### 17.3 Developer thay đổi code đúng quy trình

```text
AGENTS + .docs + skill
 → xác định owner/phase/invariant
 → đọc docs contract/ADR
 → sửa đúng services/libs/infrastructure owner
 → update test/migration/docs nếu contract thay đổi
 → targeted test → verify → governance/diff check
 → cập nhật implementation status chỉ khi có evidence
```

## 18. Folder nào nên sửa trong từng tình huống?

| Nhu cầu | Folder owner |
| --- | --- |
| Thêm Payment API | `services/payment-service/api` + application/domain/adapter + `docs/api` |
| Thêm Kafka event | Producer/consumer service + `libs/event-contracts` + `docs/events` |
| Thêm database column | Owner service `resources/db/migration` + entity/adapter/test |
| Đổi local container wiring | `docker-compose.yml`, `.env.example`, `infrastructure` và runbook |
| Thêm Keycloak client/scope local | `infrastructure/keycloak`, security test/runbook |
| Thêm CI gate | `.github/workflows`, Maven config và evidence docs |
| Chốt architecture decision | `docs/adr`, related `.docs`/contract/code |
| Đổi agent workflow | `.agent`/`.agents`, governance validation |
| IDE setting cá nhân | `.idea`/`.claude/settings.local`, không coi là project runtime |

## 19. Những folder chưa có

Không thấy implementation hiện tại cho:

- `infrastructure/k8s`;
- `infrastructure/monitoring`;
- `infrastructure/kafka` riêng;
- `docs/diagrams` có artifact;
- `docs/postman` collection;
- `libs/test-support`;
- frontend;
- reporting/settlement deployable.

Đây là khác biệt giữa target tree trong architecture/spec và source tree hiện tại. Chỉ mô tả một capability là đã có khi file/code/test/runtime evidence thực sự tồn tại.
