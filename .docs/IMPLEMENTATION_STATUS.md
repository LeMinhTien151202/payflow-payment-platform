# Trạng thái triển khai PayFlow

File này là bảng bằng chứng sống. Cập nhật sau mỗi milestone; không đánh dấu theo cảm tính.

## Mức trạng thái

| Trạng thái | Ý nghĩa |
| --- | --- |
| `PLANNED` | Có trong spec/roadmap, chưa có implementation |
| `IMPLEMENTED` | Có code/config nhưng chưa có bằng chứng test đầy đủ |
| `VERIFIED_LOCAL` | Test/lệnh local đã chạy và ghi evidence |
| `VERIFIED_CI` | CI trên commit cụ thể đã pass |
| `DEMO_READY` | Chạy từ môi trường sạch theo tài liệu và có scenario/observability evidence |

## Snapshot

| Capability | Status | Evidence | Ghi chú |
| --- | --- | --- | --- |
| Governance rules và documentation skeleton | `VERIFIED_LOCAL` | `quick_validate.py` pass + structure scan, 2026-07-26 | Git đã init trên branch `master` nhưng **chưa có commit nào**, nên mọi evidence vẫn có `Commit SHA: N/A` |
| Parent Maven/platform lock | `VERIFIED_LOCAL` | `./mvnw -B -Pno-docker clean verify` → BUILD SUCCESS, 5 module, 2026-07-26 | Boot 4.0.7 + Cloud 2025.1.2 theo ADR-013; lệch nhãn spec §3.1 có chủ đích |
| Correlation ID và Problem Details contract | `VERIFIED_LOCAL` | 20 unit test + 13 gateway IT + 10 slice test pass, 2026-07-26 | RFC 9457 + `code` + `correlationId`; unsafe correlation id bị thay, không phản chiếu |
| Gateway/service authorization (deny-by-default) | `VERIFIED_LOCAL` | `ApiGatewaySecurityIT` 9 test + `PaymentErrorContractTest` 10 test pass, 2026-07-26 | `JwtDecoder` được mock; chưa có JWT thật do Keycloak phát hành |
| Keycloak realm và OIDC token issuance | `IMPLEMENTED` | — | Chưa chạy Docker. Realm import dùng `${ENV}` placeholder cho client secret; việc Keycloak 26.7 có substitute hay không **chưa được xác minh** |
| Docker Compose infrastructure | `IMPLEMENTED` | `docker compose --env-file .env.example config --quiet` → exit 0, 2026-07-26 | Chỉ validate client-side; chưa start container nào |
| Payment schema và Flyway baseline | `IMPLEMENTED` | — | `PaymentServiceFoundationIT` (9 test) chưa chạy: cần Docker daemon. Không hạ xuống H2 để lấy badge |
| CI pipeline | `IMPLEMENTED` | — | `.github/workflows/ci.yml` có 3 job; **chưa chạy lần nào** vì repo chưa có commit và chưa có remote. YAML cũng chưa được lint (không có `yq`/PyYAML trong môi trường) |
| Event envelope v1 và topic contract | `VERIFIED_LOCAL` | `./mvnw -B -ntp -pl libs/event-contracts -am test` → 27/27 pass, 2026-07-26 | Wire format được chứng minh bằng round-trip Jackson 3 thật, không chỉ mô tả bằng văn bản. Chỉ có `payment.created` v1 |
| Payment intake schema (V2, DDL + constraint) | `IMPLEMENTED` | — | `PaymentIntakeSchemaIT` (23 test) và `PaymentServiceFoundationIT` chưa chạy: cần Docker daemon. Xem "Known deviations" bên dưới |
| Payment intake REST + application core | `IMPLEMENTED` | `PaymentControllerTest` 6 + `CreatePaymentHandlerTest` 24 + `RequestFingerprintTest` 13 pass, 2026-07-26 | `POST` 202, `GET`, JWT `merchant_id`, typed Problem Details và canonical replay đã có; atomicity/concurrency trên PostgreSQL thật chưa chạy |
| Outbox polling publisher | `IMPLEMENTED` | `PublishOutboxHandlerTest` 7 + `OutboxPropertiesTest` 1 pass, 2026-07-26 | Lease/backoff/terminal/order policy đã test không Docker; claim SQL, Kafka ack và 9 integration gate ADR-014 chưa chạy |
| Account/Reservation và Ledger domain core | `VERIFIED_LOCAL` | 21/21 unit test pass; full `-Pno-docker verify` exit 0, 2026-07-26 | Core thuần Java trong `account-ledger-service`; chưa có Spring Boot bootstrap, database, locking, inbox/outbox hoặc Kafka |
| Happy-path Saga | `PLANNED` | — | Phase 1B |
| Failure recovery/compensation | `PLANNED` | — | Pre-Phase-2 decision gate (OD-002) |
| Refund/webhook/reporting | `PLANNED` | — | Phase 2 |
| Settlement/reconciliation/Kubernetes/load | `PLANNED` | — | Phase 3 |

## Known deviations

Chỗ code lệch khỏi spec/roadmap **có chủ đích**. Mỗi dòng phải nói rõ lệch cái gì và vì sao, để lần
review sau không phải đoán đó là lệch hay là bug.

| # | Roadmap/spec nói | Thực tế | Lý do |
| --- | --- | --- | --- |
| D-01 | Phase 1A: "Merchant/customer/account seed giả" | Chỉ có **merchant** seed (3 row, `db/seed/afterMigrate__local_seed.sql`) | Bảng `customers`/`accounts` thuộc account-service, service đó chưa tồn tại. Seed chúng ở đây nghĩa là tạo bảng trong schema `payment` — đúng cái vi phạm ownership mà `MODULE_MAP.md` tồn tại để chặn. Hệ quả: `payments.customer_id` và `payments.source_account_id` **không được validate** ở Phase 1A, nhận bất kỳ UUID |
| D-02 | Spec §7.4 bảng `payments` | Thêm cột `source_account_id` | Request tạo payment (§7.4) và event `payment.created` (§8.4) đều cần nó; bảng trong spec thiếu. Đây là lỗ hổng của spec, không phải lựa chọn thiết kế |
| D-03 | Spec §8.6 bảng `outbox_events` | Thêm `topic`, `lock_owner`, `lock_until`, `last_error` | `topic` để publisher không cần một bảng routing thứ hai phải đồng bộ. Ba cột còn lại do ADR-014 yêu cầu; thiếu chúng thì row `PROCESSING` sau khi publisher chết là row không ai reclaim được |
| D-04 | Spec §15.4 index outbox `(status, next_attempt_at, created_at)` | Hai partial index theo `status` | Row `PUBLISHED` sẽ là gần như toàn bộ bảng và không xuất hiện trong query nào của publisher. Ghi trong ADR-014 |
| D-05 | Spec §7.4 vòng đời payment | DDL cho phép cả 10 status, code Phase 1A chỉ tạo `CREATED` | Tập status do spec cố định nên CHECK đủ 10 không tốn gì và tránh một migration mỗi phase. State machine trong domain mới là chỗ quyết định transition nào hợp lệ |
| D-06 | — | `merchant` là schema riêng, **không** có FK từ `payment.payments.merchant_id` | Merchant catalog sẽ tách thành service riêng. Một FK cross-schema sẽ biến việc tách đó từ thay đổi code thành một cuộc di trú dữ liệu |
| D-07 | Roadmap yêu cầu Phase 1A gate trước Phase 1B | Bắt đầu domain core Account/Ledger trước khi chạy gate PostgreSQL/Kafka | Người dùng yêu cầu tiếp tục code core trong lúc chưa chạy Docker. Phạm vi chỉ gồm invariant deterministic và unit test; không thêm persistence, consumer, event contract hay tuyên bố Phase 1B hoàn tất |

## Evidence record template

```text
Date/time (UTC):
Commit SHA:
Environment:
Capability/scenario:
Command:
Result:
Artifacts/log/dashboard link:
Known limitations:
```

## Evidence log

### 2026-07-26 — Governance and skill scaffold

```text
Date/time (UTC): 2026-07-26T02:33:17Z
Commit SHA: N/A (workspace is not an initialized Git repository)
Environment: Windows PowerShell; Codex bundled Python 3.12; PyYAML 6.0.3 in C:\tmp\payflow-skill-validator-deps
Capability/scenario: PayFlow rules, architecture docs, and project skill
Setup command: & 'C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m pip install PyYAML --target 'C:\tmp\payflow-skill-validator-deps'
Command: $env:PYTHONPATH='C:\tmp\payflow-skill-validator-deps'; & 'C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' 'C:\Users\Admin\.codex\skills\.system\skill-creator\scripts\quick_validate.py' '.agents\skills\payflow-backend'
Result: Skill is valid!
Command: powershell -NoProfile -ExecutionPolicy Bypass -File .agents\skills\payflow-backend\scripts\validate-governance.ps1
Result: Governance validation passed: 18 required paths and 16 Markdown files checked.
Artifacts: AGENTS.md, .agent/, .agents/skills/payflow-backend/, .docs/
Known limitations: No Java/Maven/infrastructure implementation exists yet. Governance validation is local and not wired into CI because Phase 0 has not started. The temporary PyYAML target was removed after validation and must be recreated to rerun the external validator.
```

### 2026-07-26 — Phase 0 foundation, Docker-free gate

```text
Date/time (UTC): 2026-07-26T08:50:04Z
Commit SHA: N/A (`.git/` exists but is an empty directory; `git rev-parse HEAD` reports "not a git repository")
Environment: Windows 10 Pro 10.0.19045; Git Bash; Maven Wrapper -> Apache Maven 3.9.16; Java 21.0.7 (Amazon Corretto); no Docker daemon, no local PostgreSQL, no Keycloak
Capability/scenario: Parent platform lock, correlation-id propagation, RFC 9457 Problem Details contract, gateway and payment-service deny-by-default authorization
Command: ./mvnw -B -Pno-docker clean verify
Result: BUILD SUCCESS in 48.580 s. 5/5 modules SUCCESS (PayFlow Parent, Observability Support, Error Contract, API Gateway, Payment Service).
  Surefire 30/30 pass — CorrelationIdTest 15, ProblemDetailsTest 5, PaymentErrorContractTest 10.
  Failsafe 13/13 pass — ApiGatewaySecurityIT 9, GatewayCorrelationIdIT 4.
  payment-service Failsafe: "Tests run: 0" — PaymentServiceFoundationIT excluded by the `no-docker` profile as designed.
Command: docker compose --env-file .env.example config --quiet
Result: exit code 0. Compose file resolves with postgres:17.10-alpine, redis:8.2.8-alpine, apache/kafka:4.3.1, quay.io/keycloak/keycloak:26.7.0.
Artifacts/log/dashboard link: target/surefire-reports/ and target/failsafe-reports/ per module (local only, not published)
Known limitations:
  - PaymentServiceFoundationIT (8 tests) has never been executed. It is the only test that needs a Docker daemon, so Flyway migration, the `payment` schema, schema-ownership comment, database-backed readiness, and actuator exposure over real HTTP are all UNVERIFIED. This is why "Payment schema và Flyway baseline" is `IMPLEMENTED`, not `VERIFIED_LOCAL`.
  - No container has ever been started from docker-compose.yml. `config --quiet` only proves the file parses and interpolates; it proves nothing about images pulling, healthchecks passing, the postgres init script running, or the Keycloak realm importing.
  - Every JWT in the passing tests comes from a mocked JwtDecoder. Real token issuance, signature validation against Keycloak JWKS, and the `payflow-readonly` client's 403 path are UNVERIFIED end to end.
  - Whether Keycloak 26.7 substitutes ${ENV} placeholders during realm import is unverified. If it does not, the client secret becomes the literal string and must be reset in the admin console.
  - No CI has run. Nothing here is `VERIFIED_CI`.
  - `-Pno-docker` is the opt-in Docker-free gate. Plain `./mvnw verify` is the real gate and has not passed yet.
```

### 2026-07-26 — Phase 1A core, Docker-free gate

```text
Date/time (UTC): 2026-07-26T14:43:07Z
Commit SHA: N/A (repository chưa có commit)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7 (Amazon Corretto); không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Payment create/get REST contract, JWT merchant ownership, canonical idempotency use case, typed payment errors, outbox lease/retry policy và Kafka adapter compile
Command: .\mvnw.cmd -B -ntp -Pno-docker clean verify
Result: BUILD SUCCESS trong 53.422 s, 6/6 module SUCCESS.
  Surefire 156/156 pass — shared libs 47, payment-service 109.
  Failsafe 13/13 pass — api-gateway 13; payment-service Docker-tagged IT bị loại bởi profile đúng thiết kế.
Final config retest: `.\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test` — BUILD SUCCESS trong 20.839 s; 156/156 test pass (47 shared + 109 payment-service).
Artifacts: target/surefire-reports và target/failsafe-reports từng module (local only)
Contract: docs/api/payment-service-v1.yaml; POST /api/v1/payments trả 202; GET merchant-scoped; business/idempotency errors có stable code.
Known limitations:
  - Không test nào trong lần này chứng minh transaction/constraint/JDBC claim SQL trên PostgreSQL thật.
  - Chưa publish event tới Kafka thật; 9 integration gate trong ADR-014 vẫn chưa chạy.
  - Keycloak mapper merchant_id đã có config nhưng realm import/token thật chưa được xác minh.
  - Plain .\mvnw.cmd trước thay đổi lỗi trên PowerShell khi ~/.m2 không phải symlink; wrapper đã được sửa và lệnh trên là lần xác minh sau sửa.
  - Không có CI/commit SHA; capability chưa phải VERIFIED_CI hoặc DEMO_READY.
```

### 2026-07-26 — Account/Ledger Phase 1B domain core

```text
Date/time (UTC): 2026-07-26T16:30:10Z
Commit SHA: N/A (repository chưa có commit)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka runtime
Capability/scenario: Account balance/reservation state invariants và immutable balanced double-entry Journal core trong account-ledger-service
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/account-ledger-service -am test
Targeted result: BUILD SUCCESS; 20/20 test pass trước bổ sung regression frozen-release.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 47.617 s, 7/7 module SUCCESS.
  Surefire 177/177 pass — shared libs 47, payment-service 109, account-ledger-service 21.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT bị loại đúng thiết kế.
Artifacts: services/account-ledger-service/target/surefire-reports và report của từng module (local only)
Known limitations:
  - Đây là domain core, chưa phải deployable Spring Boot service và chưa có API/event contract.
  - Unit test không chứng minh atomic reserve, row locking, unique reservation hoặc journal transaction trên PostgreSQL.
  - Chưa implement inbox/outbox/Kafka consumer vì OD-007 và các contract Saga liên quan vẫn OPEN.
  - Happy-path Saga vẫn PLANNED; Phase 1A và Phase 1B chưa đạt integration/E2E gate.
```

Quy tắc cập nhật:

- Không ghi `VERIFIED_*` nếu thiếu command và kết quả.
- Failure test phải ghi injection point và state cuối của từng aggregate.
- Load-test result phải trỏ tới script/config và môi trường.
- Nếu một regression làm gate fail, hạ trạng thái capability và ghi nguyên nhân; không giữ badge cũ.
