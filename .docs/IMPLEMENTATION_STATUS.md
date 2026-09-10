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
| Keycloak realm và OIDC token issuance | `VERIFIED_LOCAL` | Clean Compose smoke issued a service token, 2026-08-04 | Secret không được in; Gateway và Payment xác minh token thật trong luồng MVP |
| Docker Compose infrastructure | `VERIFIED_LOCAL` | Isolated `payflow-gate` stack healthy + `smoke-mvp.ps1` pass, 2026-08-04 | Volume gốc được giữ nguyên; gate dùng volume riêng và stack gốc được khôi phục sau test |
| Payment schema và Flyway baseline | `IMPLEMENTED` | — | `PaymentServiceFoundationIT` (9 test) chưa chạy: cần Docker daemon. Không hạ xuống H2 để lấy badge |
| CI pipeline | `IMPLEMENTED` | — | `.github/workflows/ci.yml` có 3 job; **chưa chạy lần nào** vì repo chưa có commit và chưa có remote. YAML cũng chưa được lint (không có `yq`/PyYAML trong môi trường) |
| Event envelope v1 và topic contract | `VERIFIED_LOCAL` | 52/52 contract test pass; full `-Pno-docker verify` exit 0, 2026-07-28 | Thêm versioned Account reserve/outcome/capture, Ledger post-requested/posted và Payment success contracts theo ADR-011 |
| Payment intake schema (V2, DDL + constraint) | `IMPLEMENTED` | — | `PaymentIntakeSchemaIT` (23 test) và `PaymentServiceFoundationIT` chưa chạy: cần Docker daemon. Xem "Known deviations" bên dưới |
| Payment intake REST + application core | `IMPLEMENTED` | `PaymentControllerTest` 6 + `CreatePaymentHandlerTest` 24 + `RequestFingerprintTest` 13 pass, 2026-07-26 | `POST` 202, `GET`, JWT `merchant_id`, typed Problem Details và canonical replay đã có; atomicity/concurrency trên PostgreSQL thật chưa chạy |
| Payment outbox polling publisher | `IMPLEMENTED` | `PublishOutboxHandlerTest` 7 + `OutboxPropertiesTest` 1 pass, 2026-07-26 | Lease/backoff/terminal/order policy đã test không Docker; claim SQL, Kafka ack và 9 integration gate ADR-014 chưa chạy |
| Account-Ledger outbox polling publisher | `IMPLEMENTED` | `PublishOutboxHandlerTest` 7 + `OutboxPropertiesTest` 1 pass, 2026-07-29 | ADR-014 lease/backoff/terminal/order policy pass; PostgreSQL lease IT đã prepared nhưng chưa chạy; Kafka ack/crash window chưa kiểm chứng |
| Account/Reservation và Ledger domain/application core | `VERIFIED_LOCAL` | 38/38 Account/Ledger test + 52/52 event-contract test pass; full `-Pno-docker verify` exit 0, 2026-07-28 | Reserve deadline, duplicate intent, stable failure outcome và Ledger posted factory đã có; chưa có Spring Boot, database locking, inbox/outbox hoặc Kafka |
| Risk rule engine + assessment event factory | `VERIFIED_LOCAL` | 26/26 Risk test + 52/52 event-contract test pass; full `-Pno-docker verify` exit 0, 2026-07-28 | ADR-015/016; giữ payment key, correlation và causation; không dùng wall-clock khác service để suy luận thứ tự; chưa có Redis/PostgreSQL/Kafka adapter |
| Risk assessment runtime | `IMPLEMENTED` | 41/41 Risk unit test pass; targeted `-Pno-docker verify` exit 0, 2026-07-30 | Spring Boot + Redis velocity + PostgreSQL inbox/assessment/outbox + Kafka manual ack/DLT + ADR-014 publisher đã có code; `RiskWorkflowPersistenceIT` compile nhưng PostgreSQL/Redis/Kafka thật chưa chạy |
| Notification outcome runtime | `IMPLEMENTED` | 33/33 Notification unit test pass; targeted `-Pno-docker verify` exit 0, 2026-07-30 | Spring Boot + PostgreSQL inbox/notification transaction + Payment/Refund manual-ack consumers + bounded retry/DLT + lease-based email mock; `NotificationWorkflowPersistenceIT` compile nhưng hạ tầng thật chưa chạy |
| Risk→Payment Saga decision core | `VERIFIED_LOCAL` | Payment policy/factory và transactional handler unit test pass; full gate ghi bên dưới | `APPROVED/REJECTED/REVIEW_REQUIRED`, reserve/ledger/capture/success và pre-ledger compensation đã nối vào Payment consumer application flow |
| Financial finalization contract + Payment policy | `VERIFIED_LOCAL` | ADR-011; event-contracts 52/52 + Payment 138/138; full `-Pno-docker verify` exit 0, 2026-07-28 | Ledger posted → explicit capture → captured → success; pure policy chưa phải durable Saga/consumer |
| Phase 1B Saga command/outcome orchestration core | `VERIFIED_LOCAL` | Event contracts 52/52, Payment 138/138, Account/Ledger 38/38; full `-Pno-docker verify` exit 0, 2026-07-28 | Correlation/causation và aggregate key được bảo toàn; đây là pure core, không phải Kafka/PostgreSQL E2E |
| Payment consumer inbox foundation | `IMPLEMENTED` | ADR-017; 5/5 unit test pass; `PaymentInboxSchemaIT` compile nhưng chưa chạy | V3 tạo `(event_id, consumer_name)` PK; adapter dùng `ON CONFLICT DO NOTHING` + transaction `MANDATORY`; PostgreSQL/Kafka gate còn thiếu |
| Payment Saga Kafka consumer runtime | `IMPLEMENTED` | Handler/router/listener/config unit test pass; `PaymentWorkflowConsumerIT` compile | Manual ack sau local commit; inbox + Payment/Saga + causation-aware outbox; bounded retry → DLT. Chưa chạy PostgreSQL/Kafka nên chưa nâng `VERIFIED_LOCAL` |
| Happy-path Saga E2E | `VERIFIED_LOCAL` | Clean Compose smoke pass payment `6b407fbb-4b5f-49c4-9c9f-60e53ee5189e`, 2026-08-04 | Keycloak → Gateway → Payment → Risk → reserve → journal → capture → success → notification; replay chỉ có một payment row |
| Failure recovery/compensation core | `VERIFIED_LOCAL` | ADR-012/018; Payment domain/application unit tests pass; full gate ghi bên dưới | Durable Saga/deadline, optimistic scheduler, consumer compensation, bounded listener retry/DLT và manual review đã có code; PostgreSQL/Kafka integration tests mới compile/chưa chạy |
| Payment search và refund read API | `VERIFIED_LOCAL` | Payment 238 unit/slice + 62 PostgreSQL IT pass, 2026-08-04 | Merchant-scoped search/filter/page + nested refund lookup; Flyway V8/index và OpenAPI đã đồng bộ |
| Refund financial runtime | `VERIFIED_LOCAL` | `RefundCapacityPersistenceIT` 8 test pass trong full Payment verify, 2026-08-04 | Concurrent capacity/rollback/journal-credit facts đã chứng minh trên PostgreSQL; broker-level refund E2E vẫn còn trong Phase 2 gate |
| Audited manual-review operations | `VERIFIED_LOCAL` | Payment 250 unit/slice + 65 PostgreSQL IT; Gateway 11 security IT pass, 2026-08-04 | Scope `operations:write` tách khỏi merchant; state + outbox + typed append-only audit atomic; không có force-success/release |
| Phase 2 Account/Ledger deployable split | `IMPLEMENTED` | Account 53 unit test; Ledger 32 unit test; PostgreSQL IT đã compile | Database/role/schema/outbox/inbox riêng; immutable-journal và concurrent-reserve gate chờ Docker |
| Merchant service và Payment policy boundary | `IMPLEMENTED` | Merchant unit test + Payment remote-adapter test; no-Docker reactor gate | Profile full dùng authenticated internal REST, timeout/fail-closed và immutable fee/limit snapshot; Flyway/Keycloak runtime chờ Docker |
| Webhook HMAC/retry/operations | `IMPLEMENTED` | Signature/retry unit test; PostgreSQL persistence IT đã compile | Stable event id/raw body, lease/retry/DEAD, subscribed-event filter và audited manual requeue; network/PostgreSQL gate chờ Docker |
| Reporting projection/rebuild | `IMPLEMENTED` | Parser unit test; rebuild-equivalence PostgreSQL IT đã compile | Event log idempotent, generation switch, fingerprint, version rejection/DLT và audited rebuild; PostgreSQL/Kafka gate chờ Docker |
| Settlement/reconciliation/Docker/load | `VERIFIED_LOCAL` | Full clean verify 705/705; Compose + Keycloak + Kafka → Settlement runtime pass, 2026-09-09 | Settlement calculation/reconciliation/completion and idempotent outbox verified locally; k6 and deployed Prometheus/Grafana evidence remain pending; Kubernetes is intentionally deferred |

## Known deviations

Chỗ code lệch khỏi spec/roadmap **có chủ đích**. Mỗi dòng phải nói rõ lệch cái gì và vì sao, để lần
review sau không phải đoán đó là lệch hay là bug.

| # | Roadmap/spec nói | Thực tế | Lý do |
| --- | --- | --- | --- |
| D-01 | Phase 1A: "Merchant/customer/account seed giả" | Chỉ có **merchant** seed (3 row, `db/seed/afterMigrate__local_seed.sql`) | Bảng `customers`/`accounts` thuộc account-service, service đó chưa tồn tại. Seed chúng ở đây nghĩa là tạo bảng trong schema `payment` — đúng cái vi phạm ownership mà `MODULE_MAP.md` tồn tại để chặn. Hệ quả: `payments.customer_id` và `payments.source_account_id` **không được validate** ở Phase 1A, nhận bất kỳ UUID |
| D-02 | Spec §7.4 bảng `payments` | Thêm cột `source_account_id` | Request tạo payment (§7.4) và event `payment.created` (§8.4) đều cần nó; bảng trong spec thiếu. Đây là lỗ hổng của spec, không phải lựa chọn thiết kế |
| D-03 | Spec §8.6 bảng `outbox_events` | Thêm `topic`, `lock_owner`, `lock_until`, `last_error` | `topic` để publisher không cần một bảng routing thứ hai phải đồng bộ. Ba cột còn lại do ADR-014 yêu cầu; thiếu chúng thì row `PROCESSING` sau khi publisher chết là row không ai reclaim được |
| D-04 | Spec §15.4 index outbox `(status, next_attempt_at, created_at)` | Hai partial index theo `status` | Row `PUBLISHED` sẽ là gần như toàn bộ bảng và không xuất hiện trong query nào của publisher. Ghi trong ADR-014 |
| D-05 | Spec §7.4 vòng đời payment | DDL cho phép cả 10 status; Phase 1B intake lưu `RISK_CHECKING` nhưng response/idempotency snapshot vẫn `CREATED` | Tập status do spec cố định. Payment chuyển `CREATED -> RISK_CHECKING` trong cùng transaction append `payment.created`, loại bỏ cửa sổ risk result tới trước state transition; API 202 vẫn giữ contract ban đầu |
| D-06 | — | `merchant` là schema riêng, **không** có FK từ `payment.payments.merchant_id` | Merchant catalog sẽ tách thành service riêng. Một FK cross-schema sẽ biến việc tách đó từ thay đổi code thành một cuộc di trú dữ liệu |
| D-07 | Roadmap yêu cầu Phase 1A gate trước Phase 1B | Bắt đầu domain core Account/Ledger trước khi chạy gate PostgreSQL/Kafka | Người dùng yêu cầu tiếp tục code core trong lúc chưa chạy Docker. Phạm vi chỉ gồm invariant deterministic và unit test; không thêm persistence, consumer, event contract hay tuyên bố Phase 1B hoàn tất |
| D-08 | Roadmap yêu cầu Phase 1A gate trước Phase 1B | Bắt đầu Risk domain/event core trước integration gate | Người dùng tiếp tục yêu cầu code core không Docker. OD-009/OD-003 được resolve bằng ADR-015/016; phạm vi chỉ gồm deterministic policy, versioned contract/factory và Payment decision policy, không có consumer/persistence/Kafka |
| D-09 | Roadmap yêu cầu Phase 1A gate trước Phase 1B | Bắt đầu Notification email-mock core trước integration gate | Người dùng tiếp tục yêu cầu code core không Docker. Phạm vi chỉ gồm notification state policy, application port và in-memory email adapter; OD-007 chặn Kafka consumer/inbox, còn webhook/retry/DLT thuộc Phase 2 |
| D-10 | Spec §9.2 đánh dấu Payment `SUCCEEDED` trước khi Account capture | ADR-011 đổi thành ledger posted → explicit capture → funds captured → payment success | Thứ tự baseline có thể công bố success khi tiền chưa capture. Thứ tự mới giữ Payment `PROCESSING` đến khi cả journal và capture đã commit; sau journal POSTED không tự release reservation |
| D-11 | Roadmap Phase 1B yêu cầu Kafka/PostgreSQL E2E | Hoàn thiện trước command/outcome contract và orchestration core bằng unit/contract test không Docker | Repository owner yêu cầu viết xong code core rồi mới bật hạ tầng. Phạm vi này không tuyên bố atomic inbox/outbox, locking, duplicate delivery, crash recovery hay Phase 1B E2E đã đạt gate |
| D-12 | ADR-012 gate yêu cầu PostgreSQL/Kafka/failure E2E trước Phase 2 | Chuẩn bị Payment listener, transactional consumer, retry/DLT và Testcontainers test trước khi bật hạ tầng | Repository owner yêu cầu implementation-first. Code được compile/unit-test nhưng capability vẫn `IMPLEMENTED`; không dùng unit test để tuyên bố offset, DLT hoặc PostgreSQL atomicity đã được chứng minh |
| D-13 | Spec/Roadmap Phase 3 có Kubernetes, NetworkPolicy và HPA | Runtime Phase 3 hiện dùng Docker Compose; Kubernetes source/deploy workflow được hoãn | Repository owner yêu cầu tạm thời không dùng Kubernetes. Việc hoãn không đổi Settlement/Reconciliation, event contract, database ownership hoặc reliability invariant; không tuyên bố Kubernetes experience/runtime evidence |

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

### 2026-07-27 — Risk Phase 1B domain core

```text
Date/time (UTC): 2026-07-26T17:11:06Z
Commit SHA: N/A (working tree chưa có commit cho change này)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Redis/Kafka runtime
Capability/scenario: ADR-015 risk score saturation/level bands và bảy deterministic rule trong risk-service
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/risk-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 6.425 s; risk-service 19/19 test pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 48.596 s, 8/8 module SUCCESS.
  Surefire 196/196 pass — shared libs 47, payment-service 109, account-ledger-service 21, risk-service 19.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT bị loại đúng thiết kế.
Artifacts: services/risk-service/target/surefire-reports và report của từng module (local only)
Decision: docs/adr/ADR-015-risk-score-saturation-and-level-bands.md; OD-009 chuyển RESOLVED.
Known limitations:
  - Đây là domain core, chưa phải deployable Spring Boot service.
  - Chưa có Redis velocity counter, PostgreSQL assessment, inbox/outbox hoặc Kafka integration.
  - OD-003 vẫn OPEN nên chưa tạo risk.assessment.completed hay payment rejection event.
  - Unit test không chứng minh duplicate delivery, transaction hoặc E2E Saga; Phase 1B vẫn chưa đạt gate.
```

### 2026-07-28 — Notification Phase 1B domain core

```text
Date/time (UTC): 2026-07-28T07:22:30Z
Commit SHA: N/A (working tree chưa có commit cho change này)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Notification aggregate PENDING -> SENT/FAILED, duplicate terminal delivery no-op và in-memory email mock không tạo side effect trùng theo notification id
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/notification-service -am test
Targeted result: exit 0, BUILD SUCCESS; notification-service 14/14 test pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 01:00, 9/9 module SUCCESS.
  Surefire 210/210 pass — shared libs 47, payment-service 109, account-ledger-service 21, risk-service 19, notification-service 14.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT bị loại đúng thiết kế.
Artifacts: services/notification-service/target/surefire-reports và report của từng module (local only)
Known limitations:
  - Đây là domain/application core và in-memory adapter, chưa phải Spring Boot deployable.
  - Không có test nào chứng minh persistence, transaction, duplicate Kafka event, timeout hoặc crash recovery.
  - OD-007 vẫn chặn consumer/inbox; webhook HMAC, retry/DLT và operations retry thuộc Phase 2.
  - Keycloak được giữ nguyên nhưng không chạy trong gate không Docker này.
```

### 2026-07-28 — Risk event contract và Payment Saga decision core

```text
Date/time (UTC): 2026-07-28T08:55:07Z
Commit SHA: N/A (working tree chưa có commit cho change này)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Redis/Kafka/Keycloak runtime
Capability/scenario: ADR-016 risk taxonomy; risk.assessment.completed/payment.failed v1; Risk event factory; Payment CREATED -> RISK_CHECKING intake và APPROVED/REJECTED/REVIEW_REQUIRED decision policy
Targeted command 1: .\mvnw.cmd -B -ntp -Pno-docker -pl services/risk-service -am test
Targeted result 1: exit 0, BUILD SUCCESS; event-contracts 35/35 và risk-service 25/25 trước regression aggregate-key.
Targeted command 2: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test
Targeted result 2: exit 0, BUILD SUCCESS; event-contracts 39/39 và payment-service 118/118.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 53.636 s, 9/9 module SUCCESS.
  Surefire 238/238 pass — observability 15, error-contract 5, event-contracts 39, payment-service 118, account-ledger-service 21, risk-service 26, notification-service 14.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT bị loại đúng thiết kế.
Artifacts: target/surefire-reports và target/failsafe-reports từng module (local only)
Decision/contracts: docs/adr/ADR-016-risk-assessment-event-taxonomy.md; docs/events/risk-assessment-completed-v1.md; docs/events/payment-failed-v1.md
Known limitations:
  - Contract/factory/policy đã verified; không có Kafka producer/consumer hay Risk database transaction.
  - OD-007 vẫn chặn consumer inbox insert-if-new và atomic business change + outbox.
  - OD-001 vẫn chặn ledger/capture/payment-success finalization; không có payment.succeeded contract trong change này.
  - PostgreSQL persistence của intake RISK_CHECKING và hai history row chưa được chứng minh vì Docker-tagged IT chưa chạy.
  - Keycloak được giữ nguyên nhưng không chạy trong gate không Docker này.
```

### 2026-07-28 — ADR-011 financial finalization core

```text
Date/time (UTC): 2026-07-28T09:30:07Z
Commit SHA: N/A (change chưa commit)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Ledger-posted → explicit account capture → funds-captured → payment-succeeded ordering; versioned contracts và pure Payment finalization policy
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 28.629 s; event-contracts 48/48 và payment-service 126/126 trước hai exact-field regression test cuối.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 58.684 s, 9/9 module SUCCESS.
  Surefire 257/257 pass — observability 15, error-contract 5, event-contracts 50, payment-service 126, account-ledger-service 21, risk-service 26, notification-service 14.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT bị loại đúng thiết kế.
Governance: validate-governance.ps1 pass; git diff --check exit 0.
Decision/contracts: docs/adr/ADR-011-ledger-capture-payment-success-ordering.md và năm contract docs trong docs/events/.
Known limitations:
  - Pure policy nhận các financial fact làm input; chưa persist Saga facts/deadline và chưa chứng minh restart recovery.
  - OD-007 vẫn chặn Kafka consumer/inbox atomic transaction; chưa gửi/nhận message thật.
  - Capture locking/idempotency và journal unique business reference chưa có PostgreSQL Testcontainers evidence.
  - OD-006 còn chặn manual-review state/runtime khi capture không hoàn tất sau journal POSTED.
  - Không có E2E Saga, compensation hay reconciliation runtime; không được coi Phase 1B đã hoàn tất.
  - Keycloak được giữ nguyên nhưng không chạy trong gate không Docker này.
```

### 2026-07-28 — Phase 1B Saga command/outcome orchestration core

```text
Date/time (UTC): 2026-07-28T09:59:12Z
Commit SHA: N/A (change chưa commit)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Payment phát reserve/ledger/capture command theo state; Account reserve có deadline, duplicate-intent và stable failure; Ledger chỉ phát posted sau journal local đã POSTED; mọi event giữ payment key, correlation và causation
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service,services/account-ledger-service,services/risk-service -am test
Targeted result: exit 0, BUILD SUCCESS; event-contracts 52/52, payment-service 138/138, account-ledger-service 37/37 và risk-service 26/26 trước regression ownership-expiry cuối.
Regression command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/account-ledger-service -am test
Regression result: exit 0, BUILD SUCCESS; event-contracts 52/52 và account-ledger-service 38/38. Lần chạy ngay trước đó chỉ fail một assertion message mới; production invariant đã ném đúng exception và assertion được sửa.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 51.328 s, 9/9 module SUCCESS.
  Surefire 288/288 pass — observability 15, error-contract 5, event-contracts 52, payment-service 138, account-ledger-service 38, risk-service 26, notification-service 14.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT bị loại đúng theo profile.
Contracts: docs/events/account-reserve-requested-v1.md; account-funds-reservation-failed-v1.md; ledger-post-payment-requested-v1.md; các contract finalization theo ADR-011.
Known limitations:
  - Chưa có Spring/Kafka consumer, durable inbox, local business + outbox transaction hoặc Kafka acknowledgement; OD-007 vẫn là blocker.
  - Atomic reserve/duplicate reservation và journal uniqueness chưa được chứng minh bằng PostgreSQL/Testcontainers.
  - Fee-aware journal posting chưa được suy đoán vì OD-004 vẫn OPEN; factory hiện chỉ kiểm chứng fact journal đã POSTED và balanced theo domain.
  - Không so timestamp giữa service để quyết định thứ tự; `causationId` là quan hệ logic, giảm lỗi do clock skew.
  - Happy-path E2E, timeout/compensation, manual review và restart recovery chưa chạy; Phase 1B chưa đạt integration gate.
  - Keycloak được giữ nguyên nhưng không chạy trong gate không Docker này.
```

### 2026-07-28 — ADR-017 payment consumer inbox foundation

```text
Date/time (UTC): 2026-07-28T10:07:43Z
Commit SHA: N/A (change chưa commit)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Durable inbox identity, PostgreSQL insert-if-new adapter, V3 migration và Docker-tagged duplicate/concurrency/rollback tests
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test
Targeted result: exit 0, BUILD SUCCESS; payment-service 143/143 và upstream contract/library test pass. `PaymentInboxSchemaIT` được testCompile nhưng bị loại khỏi execution bởi profile không Docker.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 51.386 s lúc 2026-07-28T10:10:09Z, 9/9 module SUCCESS.
  Surefire 293/293 pass — payment-service tăng lên 143; các module còn lại giữ nguyên.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT, gồm 5 inbox case, bị loại đúng theo profile.
Governance: validate-governance.ps1 pass; git diff --check exit 0.
Decision/schema: docs/adr/ADR-017-postgresql-inbox-insert-if-new.md; V3__consumer_inbox.sql.
Known limitations:
  - Trạng thái là IMPLEMENTED, không phải VERIFIED_LOCAL cho PostgreSQL semantics: 5 Testcontainers case chưa chạy vì Docker daemon chưa được bật.
  - Chưa có Kafka listener/application consumer cụ thể, nên chưa chứng minh inbox + payment mutation + outbox commit/rollback cùng nhau hoặc offset-after-commit.
  - Mỗi service deployable sau này phải sở hữu bảng inbox trong schema riêng; không dùng chung bảng payment.
  - Keycloak không thay đổi và không chạy trong gate này.
```

### 2026-07-28 — Pre-Phase-2 Saga failure-recovery core

```text
Date/time (UTC): 2026-07-28T13:23:59Z
Commit SHA: N/A (change chưa commit)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Durable Payment Saga model, deadline/bounded-retry policy, pre-ledger Account release compensation, manual-review state/event, V4 schema constraints và operations runbook
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service,services/account-ledger-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 24.584 s; shared libraries/contracts 76/76, payment-service 162/162, account-ledger-service 44/44.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 54.594 s, 9/9 module SUCCESS.
  Surefire 322/322 pass — observability 15, error-contract 5, event-contracts 56, payment-service 162, account-ledger-service 44, risk-service 26, notification-service 14.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment schema/inbox tests bị loại đúng theo profile.
Decisions/schema/contracts: ADR-012, ADR-018; V4__payment_saga_recovery.sql; account release, Ledger failure và Payment manual-review event docs.
Known limitations:
  - `payment_sagas` migration và 7 schema case mới chỉ compile; chưa được chạy trên PostgreSQL vì Docker chưa bật.
  - Chưa có Saga persistence adapter, due-row scheduler, Kafka listener hoặc atomic inbox + Saga/Payment + outbox transaction.
  - Chưa có retry/DLT/offset-after-commit và kill/restart E2E evidence, nên pre-Phase-2 gate chưa xanh và chưa được tách service/bắt đầu refund.
  - Keycloak giữ nguyên nhưng không chạy trong gate này.
```

### 2026-07-29 — Durable Saga persistence and optimistic recovery scheduler

```text
Date/time (UTC): 2026-07-29T07:48:12Z
Commit SHA: N/A (change chưa commit)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Payment creation persists initial Saga atomically; JPA Saga mapping/store; optimistic Payment/Saga workflow updates; bounded due scheduler; retry command mapping cho Risk/Reserve/Ledger/Capture/Release; recovery metrics/config
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 23.424 s; payment-service 176/176 và upstream libraries/contracts 76/76 pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 53.092 s, 9/9 module SUCCESS.
  Surefire 336/336 pass — observability 15, error-contract 5, event-contracts 56, payment-service 176, account-ledger-service 44, risk-service 26, notification-service 14.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT bị loại đúng theo profile.
Prepared Docker evidence: PaymentSagaPersistenceIT có 3 case cho initial Saga persistence, stale-version rejection và ordered/bounded due scan; PaymentServiceFoundationIT đã cập nhật migration/table expectation qua V4.
Known limitations:
  - Ba persistence/concurrency case mới chỉ compile, chưa chạy trên PostgreSQL thật vì Docker daemon chưa bật; JPA mapping và optimistic SQL semantics vẫn là IMPLEMENTED, chưa VERIFIED_LOCAL.
  - Scheduler chỉ tạo local outbox work; Kafka listeners, inbox + business + outbox consumer transaction và offset-after-commit chưa được nối/chạy.
  - Chưa có Account/Ledger persistence runtime, Kafka failure injection hoặc kill/restart E2E, nên ADR-012 gate chưa xanh.
  - OD-004/OD-005 vẫn chặn fee/refund Phase 2; Keycloak giữ nguyên và chưa chạy trong gate này.
```

### 2026-07-29 — Payment Saga transactional Kafka consumer code

```text
Date/time (UTC): 2026-07-29T08:33:14Z
Commit SHA: N/A (working tree change chưa commit; HEAD ed2d6f3)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Risk/Account/Ledger router; manual-ack listener; inbox + Payment/Saga optimistic update + causation-aware outbox transaction; bounded retry → DLT; risk reject/review, reserve success/failure, Ledger post/failure, capture success và release compensation
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 30.023 s; shared/event contracts 76/76 và payment-service 193/193 pass trước hai PaymentSaga regression test cuối.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 52.469 s, 9/9 module SUCCESS.
  Surefire 355/355 pass — observability 15, error-contract 5, event-contracts 56, payment-service 195, account-ledger-service 44, risk-service 26, notification-service 14.
  Failsafe 13/13 pass — api-gateway 13; Docker-tagged payment IT bị loại đúng theo profile.
Prepared Docker evidence: PaymentWorkflowConsumerIT có 2 case cho atomic commit/duplicate và rollback inbox/workflow/outbox trên PostgreSQL; test compile nhưng chưa chạy.
Known limitations:
  - Không có broker thật trong gate này; manual acknowledgement, redelivery, DLT publish/offset commit và partition ordering vẫn là IMPLEMENTED, chưa VERIFIED_LOCAL.
  - PostgreSQL atomicity/JPA optimistic semantics của consumer chưa được chạy; `PaymentWorkflowConsumerIT` chỉ mới compile.
  - Account/Ledger/Risk/Notification vẫn là pure core, nên chưa thể chạy happy-path/compensation E2E xuyên deployable.
  - Không thay đổi refund/fee/settlement; OD-004/OD-005 vẫn chặn Phase 2 và Keycloak vẫn chưa chạy.
```

### 2026-07-29 — Fee snapshot và refundable-capacity foundation

```text
Date/time (UTC): 2026-07-29T08:59:33Z
Commit SHA: N/A (working tree change chưa commit; HEAD ed2d6f3)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: ADR-019 immutable fee snapshot + cumulative fee reversal; ADR-020 succeeded/in-flight refund capacity; JPA SELECT FOR UPDATE adapter; V5 fee/refund schema
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 24.351 s; payment-service 202/202 và upstream shared/event tests 76/76 pass. RefundCapacityPersistenceIT compile nhưng bị loại khỏi no-docker execution.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 52.916 s, 9/9 module SUCCESS.
  Surefire 362/362 pass; Failsafe 13/13 pass; Docker-tagged payment IT bị loại đúng theo profile.
Governance: 18 required paths và 16 Markdown files pass; git diff --check exit 0.
Decisions/schema: ADR-019, ADR-020; OD-004/OD-005 RESOLVED; V5__fee_snapshot_and_refund_capacity.sql.
Prepared Docker evidence: RefundCapacityPersistenceIT có 3 case cho database capacity constraint, fee/reversal constraint và hai transaction cạnh tranh qua SELECT FOR UPDATE.
Known limitations:
  - V5 Flyway migration, Hibernate mapping và PostgreSQL row-lock/constraint semantics chỉ mới compile; chưa chạy vì Docker/PostgreSQL chưa bật, nên không ghi VERIFIED_LOCAL cho persistence.
  - Đây là fee/refund domain + persistence foundation, chưa phải refund REST vertical slice: chưa có Refund aggregate/repository, idempotent handler, outbox event hay Account/Ledger refund consumer.
  - Ledger event v1 vẫn gross-only; fee-aware posting phải dùng event version mới theo ADR-019, chưa được suy diễn vào v1.
  - ADR-012 pre-Phase-2 runtime gate vẫn chưa xanh vì chưa có PostgreSQL/Kafka/kill-restart evidence. Foundation này không được hiểu là Phase 2 đã hoàn thành.
  - Keycloak giữ nguyên và không chạy trong gate no-docker.
```

### 2026-07-29 — Idempotent refund intake vertical slice

```text
Date/time (UTC): 2026-07-29T11:40:24Z
Commit SHA: N/A (working tree change chưa commit; HEAD cd0e7b5)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: POST refund 202; JWT merchant/actor ownership; Refund CREATED aggregate; Payment row-lock capacity reservation; endpoint-scoped idempotency; refund + capacity + response + refund.requested outbox local transaction
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 25.080 s; event-contracts 58/58 và payment-service 215/215 pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 01:02, 9/9 module SUCCESS.
  Surefire 377/377 pass; Failsafe 13/13 pass; Docker-tagged payment IT bị loại đúng theo profile.
Governance: 18 required paths và 16 Markdown files pass; git diff --check exit 0.
Contract/schema: POST /api/v1/payments/{paymentId}/refunds; refund.requested v1; V6__refund_intake_contract.sql; error codes PAYMENT_REFUND_NOT_ALLOWED và PAYMENT_REFUND_CAPACITY_EXCEEDED.
Prepared Docker evidence: RefundCapacityPersistenceIT có 6 case, gồm lock concurrency, fee/capacity constraint, accepted+replayed intake, different-payload conflict và injected outbox failure rollback toàn bộ local facts.
Known limitations:
  - V5/V6 Flyway, Hibernate Refund mapping, SELECT FOR UPDATE và transaction rollback mới compile; chưa chạy trên PostgreSQL vì Docker chưa bật, nên persistence chưa VERIFIED_LOCAL.
  - refund.requested chỉ nằm trong outbox; Kafka publisher/broker không chạy trong gate này.
  - Account credit, Ledger refund/reversal, refund outcome consumer và Payment capacity success/failure completion chưa được nối; refund sẽ ở CREATED cho tới lát cắt workflow kế tiếp.
  - ADR-012 pre-Phase-2 runtime gate vẫn chưa xanh. Code-first refund intake không đồng nghĩa Phase 2 hoặc E2E refund đã hoàn thành.
  - Keycloak contract được giữ và web tests dùng JWT fixture; Keycloak runtime chưa chạy.
```

### 2026-07-29 — Refund financial workflow pure core

```text
Date/time (UTC): 2026-07-29T12:04:42Z
Commit SHA: N/A (working tree change chưa commit; HEAD c7c923c)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: ADR-021 ordering; immutable principal REFUND_REVERSAL journal; idempotent Account refund credit; Payment matching/finalization; pre-journal failure capacity release; versioned terminal refund contracts
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl libs/event-contracts,services/account-ledger-service,services/payment-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 35.445 s; event-contracts 63/63, payment-service 223/223 và account-ledger-service 54/54 pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 55.205 s; 9/9 module SUCCESS; Surefire 400/400 và Failsafe 13/13 pass.
Governance: validate-governance.ps1 pass 18 required paths/16 Markdown files; git diff --check exit 0.
Skill validator: đã chạy nhưng môi trường Python thiếu dependency yaml (ModuleNotFoundError); không ghi pass cho check này. PayFlow skill không bị sửa.
Contract/schema: thêm ledger.refund-posted, ledger.refund-posting-failed, account.refund-credit.requested, account.refund-credited, refund.succeeded và refund.failed v1; không đổi REST hoặc Flyway schema trong lát cắt pure-core này.
Known limitations:
  - account-ledger-service vẫn là Maven core module, chưa có Spring Boot bootstrap, database adapter, inbox/outbox hoặc Kafka listener cho refund.
  - Payment RefundFinalizationPolicy mới là pure policy; chưa được nối vào Payment workflow consumer/persistence transaction.
  - PostgreSQL uniqueness/locking/rollback và Kafka redelivery/order/crash windows chưa chạy, nên refund E2E chưa VERIFIED_LOCAL.
  - Sau ledger.refund-posted, bounded retry/manual-review runtime cho Account credit chưa được dựng; ADR-021 cấm tự fail hoặc release capacity trong cửa sổ này.
  - Ledger v1 chỉ reverse principal, đồng nhất với payment capture v1. Fee-aware ledger cần contract version mới; refund.succeeded hiện mang fee-reversal fact cho Settlement tương lai.
```

### 2026-07-29 — Payment refund outcome transactional runtime

```text
Date/time (UTC): 2026-07-29T12:45:58Z
Commit SHA: N/A (working tree change chưa commit; HEAD 29e30e6)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Payment refund outcome router/consumer; inbox + Payment/Refund row locks + aggregate state + causal outbox transaction; durable Ledger journal/Account credit IDs; V7 status/identity constraints; pre-journal failure boundary
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 24.645 s; event-contracts 63/63 và payment-service 231/231 pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 01:00; 9/9 module SUCCESS; Surefire 408/408 và Failsafe 13/13 pass.
Governance: validate-governance.ps1 pass 18 required paths/16 Markdown files; git diff --check exit 0.
Contract/schema: không thêm event mới ngoài ADR-021 pure-core checkpoint; V7__refund_financial_workflow_facts.sql thêm ledger_journal_id/account_credit_id, unique partial indexes và status consistency checks.
Prepared Docker evidence: RefundCapacityPersistenceIT có thêm happy path journal → credit → success + duplicate delivery, và injected refund.succeeded outbox failure để chứng minh rollback inbox + Refund credit fact + Payment capacity. Các case chỉ compile, chưa chạy.
Known limitations:
  - V7 Flyway, PESSIMISTIC_WRITE và PostgreSQL atomic rollback chưa VERIFIED_LOCAL vì Docker/PostgreSQL chưa bật.
  - Kafka listener dùng router chung và đã compile/unit-test, nhưng broker redelivery, offset-after-commit, DLT, ordering và crash window chưa chạy.
  - Account-Ledger refund runtime vẫn là pure core: chưa có Spring Boot bootstrap, database ownership, inbox/outbox hoặc Kafka consumer/producer, nên chưa có refund E2E.
  - Sau journal posted, bounded retry/manual-review cho Account credit vẫn là backlog; theo ADR-021 không được tự fail hoặc release capacity trong cửa sổ này.
  - Keycloak contract giữ nguyên; runtime chưa chạy trong gate no-docker.
```

### 2026-07-29 — Account-Ledger refund runtime foundation

```text
Date/time (UTC): 2026-07-29T13:07:03Z
Commit SHA: N/A (working tree change chưa commit; HEAD 29e30e6)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: runnable Account-Ledger Spring Boot module; separate account/ledger/operational schemas; refund reversal and Account credit consumers; inbox + business mutation + causal outbox local transactions; transport/business duplicate distinction; Account row lock; bounded Kafka retry/DLT; producer-owned topic declarations
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/account-ledger-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 18.149 s; event-contracts 63/63 và account-ledger-service 66/66 pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 01:01; 9/9 module SUCCESS; Surefire 420/420 và Failsafe 13/13 pass.
Schema/runtime: V1__account_ledger_refund_runtime.sql; Account JPA adapter; Ledger/inbox/outbox JDBC adapters; typed listeners cho refund.requested và account.refund-credit.requested; Payment khai báo refund topic, Account-Ledger khai báo account/ledger/DLT topics.
Prepared Docker evidence: RefundWorkflowPersistenceIT có happy path journal cân bằng → credit → hai outbox facts với redelivery, và injected account.refund-credited outbox failure để chứng minh rollback inbox + balance + credit.
Known limitations:
  - PostgreSQL V1/Flyway/JPA validation, PESSIMISTIC_WRITE, unique constraints và rollback IT chỉ compile; chưa VERIFIED_LOCAL vì Docker chưa bật.
  - Kafka broker chưa chạy, nên manual ack, redelivery, DLT send, partition ordering và crash window chưa VERIFIED_LOCAL.
  - account-ledger-service chưa có ADR-014 polling outbox publisher; output rows đã durable nhưng chưa thể tự phát ra Kafka.
  - Reserve/capture/release runtime cho payment happy path vẫn là pure core và chưa dùng persistence/listener mới.
  - Chưa có local seed profile cho Account/Ledger account mapping; production migration cố ý không chứa demo data.
  - Keycloak contract giữ nguyên; runtime chưa chạy trong gate no-docker.
```

### 2026-07-29 — Account-Ledger ADR-014 outbox publisher

```text
Date/time (UTC): 2026-07-29T15:02:25Z
Commit SHA: N/A (working tree change chưa commit; HEAD efb1129)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: Account-Ledger polling publisher; short PostgreSQL lease claims; stale lease reclaim; stable event id; conditional owner marks; bounded exponential retry/terminal FAILED; same-aggregate ordering guard; synchronous Kafka acknowledgement boundary; Micrometer counters/gauge
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/account-ledger-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 18.916 s; event-contracts 63/63 và account-ledger-service 74/74 pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 01:00; 9/9 module SUCCESS; Surefire 428/428 và Failsafe 13/13 pass.
Governance: validate-governance.ps1 pass 18 required paths/16 Markdown files; git diff --check exit 0.
Schema/config: V1 index được hoàn thiện trước lần chạy đầu thành pending-due, expired-lease và aggregate-order indexes; payflow.outbox.* fail-fast defaults; Kafka delivery timeout luôn nhỏ hơn lease.
Prepared Docker evidence: OutboxLeaseStorePersistenceIT có pending claim + conditional owner mark và expired-vs-active lease recovery; test compile nhưng bị loại đúng theo profile no-docker.
Operations: docs/runbooks/outbox-recovery.md mô tả read-only triage, single-row conditional requeue và cấm sửa payload/business identity.
Known limitations:
  - PostgreSQL SKIP LOCKED, clock_timestamp, partial indexes và transaction boundary mới compile; chưa VERIFIED_LOCAL vì Docker/PostgreSQL chưa bật.
  - Kafka acknowledgement, ambiguous timeout duplicate, broker redelivery và kill/restart crash window chưa chạy.
  - Không tuyên bố exactly-once; consumer inbox/idempotency vẫn là bắt buộc.
  - V1 migration chưa được apply ở bất kỳ database nào theo xác nhận của repository owner; do đó index được hoàn thiện ngay trong initial migration, không sửa lịch sử database đã chạy.
  - Keycloak giữ nguyên và không tham gia gate no-docker.
```

### 2026-07-29 — Account-Ledger payment finalization transactional runtime

```text
Date/time (UTC): 2026-07-29T18:47:12Z
Commit SHA: N/A (working tree change chưa commit; HEAD 2d3988f)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21.0.7; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: account.reserve.requested → funds-reserved/failure; ledger.post-payment.requested → immutable balanced PAYMENT_CAPTURE journal; account.capture.requested → funds-captured; pre-ledger account.release.requested → funds-released; inbox + business + causal outbox local transaction cho mỗi bước
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/account-ledger-service -am test
Targeted result: exit 0, BUILD SUCCESS trong 13.921 s; event-contracts 63/63 và account-ledger-service 83/83 pass.
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 57.607 s; 9/9 module SUCCESS; Surefire 442/442 và Failsafe 13/13 pass.
Governance: validate-governance.ps1 pass 18 required paths/16 Markdown files; git diff --check exit 0. Skill quick validator đã được gọi nhưng bundled Python thiếu dependency yaml (ModuleNotFoundError); PayFlow skill không thay đổi trong lát cắt này.
Schema/runtime: Account balance_reservations unique payment_id + terminal consistency; Ledger payment_postings unique payment/journal; Account pessimistic row locks; typed Account-Ledger workflow router/listener; Payment topic consumer toggle; transport/business duplicate distinction.
Prepared Docker evidence: PaymentWorkflowPersistenceIT có reserve → balanced journal → capture với redelivery; pre-ledger release trả balance; injected account.funds-reserved outbox failure chứng minh rollback inbox + balance + reservation. Ba case chỉ compile và bị loại đúng theo profile no-docker.
Failure boundary: thiếu Ledger account mapping phát ledger.payment-posting-failed trước khi tạo journal; lỗi kỹ thuật rollback để Kafka retry/DLT. Sau journal POSTED, capture lỗi không phát release và được để Payment Saga bounded-retry/manual-review xử lý theo ADR-011/018.
Known limitations:
  - V1 Flyway, PESSIMISTIC_WRITE, unique constraints, rollback và concurrent reserve chưa VERIFIED_LOCAL vì Docker/PostgreSQL chưa bật.
  - Kafka manual ack, broker redelivery, DLT, partition ordering và kill/restart crash window chưa chạy.
  - V1 migration được bổ sung trước lần apply đầu tiên dựa trên xác nhận repository owner rằng chưa có database nào chạy; không suy diễn quy tắc này cho migration đã apply.
  - Chưa có local seed profile cho customer Account và Ledger account mapping; production migration cố ý không chứa demo data.
  - Keycloak giữ nguyên và không tham gia gate no-docker.
```

### 2026-07-30 — Risk assessment transactional runtime

```text
Date/time (UTC): 2026-07-29T19:12:07Z
Commit SHA: N/A (working tree change chưa commit; HEAD abe72cd)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21; không Docker/PostgreSQL/Redis/Kafka/Keycloak runtime
Capability/scenario: payment.created v1 -> Redis amount/velocity signals -> deterministic policy -> one immutable assessment; processed event + assessment + risk.assessment.completed outbox local transaction; manual ack; bounded retry/DLT; ADR-014 polling publisher
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/risk-service -am verify
Targeted result: exit 0, BUILD SUCCESS trong 15.837 s; observability 15/15, event-contracts 63/63, risk-service 41/41 unit test; Docker-tagged Failsafe tests compile và bị loại đúng theo profile
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 01:03; 9/9 module SUCCESS; Surefire 457/457 và Failsafe 13/13 pass
Governance: validate-governance.ps1 pass 18 required paths/16 Markdown files; git diff --check exit 0
Schema/runtime: V1__risk_assessment_runtime.sql; Redis Lua dùng paymentId dedup, event-time bounded windows và decimal minor-unit string addition; JDBC inbox/assessment/outbox; typed router/listener; producer-owned Risk/DLT declarations
Prepared Docker evidence: RiskWorkflowPersistenceIT khởi động PostgreSQL 17 + Redis 8; kiểm tra sixth-payment velocity, transport duplicate, unique assessment/outbox và injected outbox failure rollback inbox + assessment
Known limitations:
  - Flyway/constraint/rollback và Redis Lua behavior mới compile, chưa VERIFIED_LOCAL vì Docker chưa bật.
  - Kafka manual ack, broker redelivery, DLT publication, partition ordering và crash window chưa chạy.
  - payment.created v1 không chứa trusted device/IP/failed-burst/merchant-risk enrichment; runtime dùng neutral values, không suy đoán. Amount và customer velocity đã active.
  - Keycloak contract giữ nguyên; Risk hiện không có business REST endpoint.
```

### 2026-07-30 — Notification outcome transactional runtime

```text
Date/time (UTC): 2026-07-29T19:56:34Z
Commit SHA: N/A (working tree change chưa commit; HEAD abe72cd)
Environment: Windows 10; Maven Wrapper 3.9.16; Java 21; không Docker/PostgreSQL/Kafka/Keycloak runtime
Capability/scenario: payment.succeeded/payment.failed/refund.succeeded/refund.failed -> typed notification intent -> processed event + notification local transaction -> manual ack; short lease claim -> email mock ngoài transaction -> conditional SENT/FAILED; bounded Kafka retry/DLT
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/notification-service -am clean verify
Targeted result: exit 0, BUILD SUCCESS trong 22.519 s; observability 15/15, event-contracts 63/63, notification-service 33/33 unit test; Docker-tagged Failsafe tests compile và bị loại đúng theo profile
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 01:11; 9/9 module SUCCESS; Surefire 476/476 và Failsafe 13/13 pass
Governance: validate-governance.ps1 pass 18 required paths/16 Markdown files; git diff --check exit 0
Schema/runtime: V1__notification_runtime.sql; JDBC inbox + notification atomic transaction; unique business outcome/channel; typed Payment/Refund router; manual ack; shared DLT; lease ownership và finite crash reclaim; deny-by-default HTTP security
Prepared Docker evidence: NotificationWorkflowPersistenceIT khởi động PostgreSQL 17; kiểm tra transport/business duplicate, conflicting outcome rollback inbox, injected notification insert failure rollback inbox và chỉ lease owner được hoàn tất delivery
Known limitations:
  - Flyway/constraint/rollback/claim SQL mới compile, chưa VERIFIED_LOCAL vì Docker/PostgreSQL chưa bật.
  - Kafka broker redelivery, DLT publication, partition ordering và crash window chưa chạy.
  - Email adapter vẫn là in-memory mock; provider timeout là contract cấu hình cho adapter thật, chưa có network provider.
  - payment.failed v1 không có customerId; runtime lưu routing identity PAYMENT/paymentId thay vì suy đoán người nhận.
  - Webhook HMAC, scheduled provider retry và audited manual retry thuộc Phase 2; OD-010 vẫn chặn operation mutation.
```

### 2026-07-30 — Local MVP Compose wiring và smoke harness

```text
Date/time (UTC): 2026-07-29T20:24:06Z
Commit SHA: N/A (working tree change chưa commit)
Environment: Windows; Maven Wrapper 3.9.16; Java 21.0.7; Docker CLI/Compose có sẵn nhưng Docker daemon chưa bật
Capability/scenario: code-first local runtime cho Phase 1B; database/credential per service; local-only Account/Ledger fixtures; non-root multi-stage Java image; Compose profiles infra/mvp/full; deterministic Kafka topic init; public issuer + internal JWKS; bounded PowerShell smoke cho payment happy path và idempotent replay
Static validation: docker compose --env-file .env.example --profile infra config --quiet -> exit 0; profile mvp -> exit 0; mvp config render đúng 10 service; smoke-mvp.ps1 PowerShell parser -> 0 errors; git diff --check -> exit 0
Final command: .\mvnw.cmd -B -ntp -Pno-docker verify
Final result: exit 0, BUILD SUCCESS trong 01:10; 9/9 module SUCCESS; Surefire 471/471 và Failsafe 13/13 pass
Targeted seed-test compile: .\mvnw.cmd -B -ntp -Pno-docker -pl services/account-ledger-service -am test -> exit 0, BUILD SUCCESS trong 18.921 s; LocalSeedPersistenceIT compile và được Docker tag loại đúng khỏi no-Docker gate
Governance: validate-governance.ps1 pass 18 required paths/16 Markdown files
Security/data: image chạy UID/GID 10001; không commit secret; seed chỉ ở profile local, dữ liệu giả cố định và ON CONFLICT DO NOTHING; application không truy cập chéo database
Runbook: docs/runbooks/mvp-docker.md; smoke: infrastructure/scripts/smoke-mvp.ps1
Known limitations:
  - Chưa build/pull image và chưa start container vì Docker daemon vẫn tắt theo chủ đích của repository owner.
  - Chưa chạy Flyway seed trên PostgreSQL thật, Keycloak token exchange, Kafka broker delivery hoặc Compose smoke; do đó chưa VERIFIED_LOCAL/E2E.
  - LocalSeedPersistenceIT đã chuẩn bị để kiểm tra fixture/mapping và non-replenishing callback trên PostgreSQL 17 khi Docker được bật.
  - Email vẫn là in-memory mock; full profile hiện là alias của MVP; observability/Kubernetes/Reporting/Settlement chưa nằm trong lát cắt này.
  - Lần clean verify đầu bị execution wrapper timeout ở 120 giây sau khi đã sinh report tới module cuối; rerun verify với timeout 300 giây hoàn tất exit 0. Không bỏ hoặc hạ test.
```

### 2026-08-04 — Runtime gate và Phase 2 payment/refund query slice

```text
Date/time (UTC): 2026-08-04T14:46:29Z
Commit SHA: 9b9cb3e (working tree changes not committed)
Environment: Windows; Java 21.0.7; Maven Wrapper 3.9.16; Docker Desktop 28.0.1; PostgreSQL 17.10 Testcontainers; Compose Kafka/Redis/Keycloak
Capability/scenario: clean MVP runtime gate; merchant-scoped payment search; merchant/payment-scoped refund lookup
Runtime command: COMPOSE_PROJECT_NAME=payflow-gate; .\infrastructure\scripts\smoke-mvp.ps1 -TimeoutSeconds 300
Runtime result: MVP SMOKE PASSED; payment SUCCEEDED, risk APPROVED, balance/reservation/journal/notification correct, idempotent replay kept one payment row
Test command: .\mvnw.cmd -B -ntp -pl services/payment-service -am verify
Test result: BUILD SUCCESS; payment-service Surefire 238/238 and Failsafe 62/62 pass; Flyway applied V1..V8 on PostgreSQL 17.10
Contract/schema: GET /api/v1/payments filters status/[from,to)/page/size; GET /api/v1/payments/{paymentId}/refunds/{refundId}; V8 merchant/status/created/id index; docs/api/payment-service-v1.yaml updated
Known limitations:
  - Phase 2 is not complete: Account/Ledger deployable split, merchant service, webhook HMAC/retry, reporting rebuild/replay and audited operations remain.
  - OD-010 is still OPEN, so no privileged audit mutation/operations endpoint was implemented.
  - Phase 3 remains gated on completion of Phase 2; settlement/reconciliation/Kubernetes/load evidence is still absent.
```

### 2026-08-04 — Audited manual-review operations endpoint

```text
Date/time (UTC): 2026-08-04T16:10:31Z
Commit SHA: 9b9cb3e (working tree changes not committed)
Environment: Windows; Java 21.0.7; Maven Wrapper 3.9.16; Docker Desktop 28.0.1; PostgreSQL 17.10 Testcontainers
Capability/scenario: operations-only manual-review resolution; explicit risk approval/rejection or exact-step retry; Payment/Saga + command outbox + typed append-only audit local transaction; Gateway and service deny-by-default scope separation
Targeted test command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/payment-service -am test '-Dtest=ResolveManualReviewHandlerTest,OperationsControllerTest,PaymentErrorContractTest,PaymentSagaTest,PaymentTest' '-Dsurefire.failIfNoSpecifiedTests=false'
Targeted result: BUILD SUCCESS; 53/53 selected tests pass.
PostgreSQL command: .\mvnw.cmd -B -ntp -pl services/payment-service -am verify '-Dit.test=ManualReviewResolutionPersistenceIT' '-Dfailsafe.failIfNoSpecifiedTests=false'
PostgreSQL result: BUILD SUCCESS; 2/2 IT pass. Injected audit INSERT failure rolled back Payment, Saga, history and outbox; successful approval committed all four artifacts.
Full Payment command: .\mvnw.cmd -B -ntp -pl services/payment-service -am verify
Full Payment result: BUILD SUCCESS; Payment Surefire 250/250 and Failsafe 65/65 pass; Flyway V1..V9 applied on PostgreSQL 17.10.
Gateway command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/api-gateway -am verify '-Dit.test=ApiGatewaySecurityIT' '-Dfailsafe.failIfNoSpecifiedTests=false'
Gateway result: BUILD SUCCESS; 11/11 security/routing IT pass, including operations-only allow and merchant-token deny.
Repository command: .\mvnw.cmd -B -ntp -Pno-docker verify
Repository result: BUILD SUCCESS in 01:12; all 9/9 modules SUCCESS.
Schema/security: Flyway V9 typed audit allowlist + UPDATE/DELETE rejection trigger; Keycloak local operations:write client; Gateway and Payment Service enforce the same separate scope.
Known limitations:
  - Existing persisted Keycloak realm is not auto-reimported; local environment must recreate/migrate that realm before requesting a payflow-operations token.
  - Queue/list endpoint for discovering manual-review work remains backlog; endpoint resolves a known paymentId.
  - Webhook HMAC/retry, reporting projection/rebuild and Account/Ledger deployable split remain Phase 2 work; Phase 2 is not yet complete.
```

### 2026-08-07 — Phase 2 code-complete, Docker-free gate

```text
Date/time (UTC): 2026-08-07T06:24:02Z
Commit SHA: 9b9cb3e (working tree changes not committed)
Environment: Windows; Java 21; Maven Wrapper 3.9.16; Docker runtime intentionally deferred
Capability/scenario: deployable Account/Ledger split; Merchant ownership and authenticated Payment policy lookup; durable signed webhook delivery; idempotent reporting projection and generation-based rebuild; Gateway/Keycloak/Compose wiring
Command: .\mvnw.cmd -B -ntp -Pno-docker clean verify
Result: BUILD SUCCESS in 02:10; all 13/13 reactor modules SUCCESS; 602 tests, 0 failures, 0 errors, 0 skipped across Surefire/Failsafe reports.
Static infrastructure checks: Compose profiles mvp/full resolve; prepare-phase2-env.ps1 and smoke-mvp.ps1 parse; realm-payflow.json parses; governance validation passes 18 required paths/16 Markdown files; git diff --check exits 0.
Code corrections found by the clean gate: Reporting and Webhook ProblemDetail handlers now adapt numeric application status to Spring 7 HttpStatusCode without changing the external status contract.
Artifacts: services/account-service, services/ledger-service, services/merchant-service, services/reporting-service, notification webhook runtime, docs/runbooks/phase2-local.md, ADR-023, ADR-024.
Known limitations:
  - Docker-tagged Testcontainers tests are compiled but intentionally excluded; PostgreSQL locking/triggers, Flyway against fresh databases, network webhook delivery and reporting rebuild persistence remain IMPLEMENTED rather than VERIFIED_LOCAL.
  - Kafka broker delivery/redelivery/DLT, real Keycloak client credentials and the full-profile Saga smoke have not run in this gate.
  - Existing local .env and persisted Keycloak realm require the documented Phase 2 preparation/migration when Docker is enabled.
```

### 2026-08-08 — Phase 3 code-first, Docker-free gate

```text
Date/time (UTC): 2026-08-08T09:11:24Z
Commit SHA: 860b3fa (working tree changes not committed)
Environment: Windows; Java 21.0.7; Maven Wrapper 3.9.16; Docker/runtime intentionally not invoked
Capability/scenario: payment.succeeded v2 fee snapshot; daily settlement/reconciliation service; merchant/operations API and scopes; transactional inbox/outbox; alerts/dashboard/k6/security-scan source artifacts
Command: .\mvnw.cmd -B -ntp -Pno-docker clean verify
Result: BUILD SUCCESS in 02:16; all 14/14 reactor modules SUCCESS; 615 tests, 0 failures, 0 errors, 0 skipped across generated Surefire/Failsafe XML reports.
Static checks at the time: realm-payflow.json and Grafana dashboard parse; git diff --check exits 0. Earlier Kustomize evidence is historical only; Kubernetes was subsequently removed from active scope by D-13.
Delivery source: Docker Compose `full` profile builds settlement-service with the shared non-root Dockerfile and wires PostgreSQL, Kafka, Keycloak and Gateway configuration.
Post-change targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/api-gateway,services/reporting-service,services/notification-service,services/settlement-service -am verify
Post-change targeted result: BUILD SUCCESS in 00:53; Gateway settlement scope/route IT 13/13, reporting parser 4/4 and notification unit 37/37 pass. Final settlement-only test rerun BUILD SUCCESS in 00:15 with 10/10 settlement tests after outbox validation was tightened.
Final repository regression: `.\mvnw.cmd -B -ntp -Pno-docker verify` -> BUILD SUCCESS in 01:44; 14/14 reactor modules and 622/622 generated Surefire/Failsafe test cases pass with no failure, error or skip.
Known limitations:
  - SettlementPersistenceIT is compiled and tagged docker but not executed, so PostgreSQL constraints, triggers, locking, Flyway and transaction rollback are not yet runtime evidence.
  - No Kafka broker delivery/redelivery, Keycloak token, Compose full-profile smoke, Prometheus alert or k6 threshold was executed in this historical code-first gate.
  - Kubernetes is intentionally outside the current runtime scope; no deployment or operational evidence is claimed.
```

### 2026-09-09 — Phase 3 Docker Compose runtime gate, không Kubernetes

```text
Date/time (UTC): 2026-09-09T13:11:00Z
Commit SHA: 94cce61 (Phase 3 working tree changes not committed at gate time)
Environment: Windows; Java 21.0.7; Maven Wrapper 3.9.16; Docker Desktop 28.0.1; PostgreSQL 17.10 Testcontainers và Compose; Kafka 4.3.1; Keycloak 26.7.0
Capability/scenario: Phase 3 Settlement/Reconciliation integrated on main; Docker Compose is the active runtime; Kubernetes artifacts excluded; existing PostgreSQL and Keycloak volumes upgraded idempotently without reset.
Repository command: .\mvnw.cmd -B -ntp clean verify
Repository result: BUILD SUCCESS in 04:31; all 14/14 reactor modules SUCCESS; 705 tests, 0 failures, 0 errors, 0 skipped across Surefire/Failsafe XML reports.
PostgreSQL result: SettlementPersistenceIT passed against PostgreSQL 17.10 after removing final from the two @Transactional JDBC outbox adapters so Spring transaction proxies can be created.
Compose result: full profile built successfully; PostgreSQL, Kafka, Redis, Keycloak, Gateway, Payment, Account, Ledger, Merchant, Risk, Notification, Reporting and Settlement became healthy; kafka-init exited 0.
Keycloak result: provision-phase3-keycloak.ps1 added missing settlement/reconciliation scopes to the persisted realm and a second run returned only KEEP, proving the migration is idempotent; newly issued service and operations tokens contained the expected scopes.
Runtime payment: payment 5ac23b7a-fcea-4bfd-b5bc-a8a5db8f0556, amount 100000 VND, reached SUCCEEDED and idempotent replay returned the same payment id.
Kafka/settlement facts: PAYMENT_SUCCEEDED, LEDGER_PAYMENT_POSTED and ACCOUNT_FUNDS_CAPTURED were persisted for the payment.
Settlement result: batch e0c320e6-4064-4665-a2ce-8608989435ea calculated READY with gross 100000, fee 2000 and net 98000; reconciliation checked 1 item with 0 open issues; complete + replay both returned COMPLETED and exactly one settlement.completed outbox row reached PUBLISHED.
Known limitations:
  - The existing full smoke harness stopped safely because its fixture balance was already 500000 rather than pristine 1000000; the bounded 100000 VND runtime scenario above replaced that destructive reset and passed.
  - k6 thresholds and a deployed Prometheus/Grafana stack were not run, so no throughput, latency or alert-firing claim is made.
  - COMPLETED is an audited settlement accounting transition and event; external bank payout execution is not implemented or claimed.
  - Kubernetes remains intentionally outside the active runtime scope.
```
### 2026-09-09 — Gateway-only application edge, centralized Swagger và root command

```text
Date/time (UTC): 2026-09-09T14:48:30Z
Commit SHA: 62c96c4 (working tree changes not committed at gate time)
Environment: Windows; Java 21.0.7; Maven Wrapper 3.9.16; Docker Desktop 28.0.1; full Compose profile
Capability/scenario: một public application edge qua API Gateway; 5 OpenAPI documents trong một Swagger UI; root payflow.ps1 cho rebuild/start/stop/status/logs/token/smoke; service ports chỉ còn Docker-internal.
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/api-gateway -am verify '-Dit.test=ApiGatewaySecurityIT' '-Dfailsafe.failIfNoSpecifiedTests=false'
Targeted result: BUILD SUCCESS; ApiGatewaySecurityIT 14/14 pass, gồm public Swagger/static OpenAPI và JWT/scope deny-by-default cho business routes.
Static checks: 5/5 OpenAPI YAML parse bằng SnakeYAML 2.6; PowerShell scripts parse; Maven XML parse; full Compose model resolves; git diff --check passes.
Docker build: lần đầu gặp Maven Central trả thiếu zstd-jni; shared locked BuildKit /root/.m2 cache được thêm, retry build toàn bộ 9 Java images thành công và Gateway-only rebuild xác nhận copy 5 OpenAPI specs.
Runtime network: Gateway là application container duy nhất publish host port (127.0.0.1:8084); Payment/Account/Ledger/Merchant/Reporting/Settlement/Risk/Notification chỉ expose trong Compose network. PostgreSQL/Kafka/Redis/Keycloak development ports vẫn bind localhost.
Swagger runtime: /swagger-ui.html redirect hợp lệ; /v3/api-docs/swagger-config trả đúng 5 definitions; cả 5 /openapi/*.yaml trả 200 và không chứa client secret/bearer token.
Token command: payflow.ps1 token -Client service lấy token client_credentials, không in/persist token, copy clipboard; token có đúng payment/merchant/reporting/settlement scopes.
Smoke command: .\payflow.ps1 smoke -TimeoutSeconds 300
Smoke result: FULL SMOKE PASSED for payment 07d4b1cf-f917-40a5-b8c6-824a4a16fc21 amount 1000 VND; SUCCEEDED/APPROVED/CAPTURED, balanced two-line ledger, SENT notification, one row after idempotent replay; fixture balance 400000 -> 399000.
Known limitations:
  - Profile split/rút gọn ở mục 2 không được thực hiện theo quyết định của repository owner; root command dùng full profile duy nhất.
  - START_HERE và đợt đồng bộ hóa toàn bộ runbook cũ (mục 5) được chủ động hoãn; không tạo user-guide mới trong lát cắt này.
  - Swagger API specifications là public metadata; gọi business API vẫn yêu cầu JWT/scope. Swagger không persist bearer token.
```
Quy tắc cập nhật:

- Không ghi `VERIFIED_*` nếu thiếu command và kết quả.
- Failure test phải ghi injection point và state cuối của từng aggregate.
- Load-test result phải trỏ tới script/config và môi trường.
- Nếu một regression làm gate fail, hạ trạng thái capability và ghi nguyên nhân; không giữ badge cũ.

### 2026-09-10 — PayFlow Operations Console tại API Gateway

```text
Date/time (Asia/Bangkok): 2026-09-10T10:10:03+07:00
Base commit SHA: b4f727f (working tree changes not committed at gate time)
Capability/scenario: responsive same-origin Operations Console served by API Gateway; 24 OpenAPI-aligned payment, refund, merchant, reporting, settlement, reconciliation and recovery operations; memory-only JWT slots; exact decimal JSON serialization; correlation and idempotency headers; structured response/activity states.
Security boundary: only /console.html and /console/** are public static assets. Every business route remains JWT/scope protected and deny-by-default; no client secret or access token is embedded or persisted by the Console.
Targeted command: .\mvnw.cmd -B -ntp -Pno-docker -pl services/api-gateway -am verify '-Dit.test=ApiGatewaySecurityIT' '-Dfailsafe.failIfNoSpecifiedTests=false'
Targeted result: BUILD SUCCESS in 45.151 s; ApiGatewaySecurityIT 15/15 pass, including public Console assets and unchanged 401/403 routing behavior.
Static checks: node --check console/app.js passes; secret/storage/em-dash/gradient scan has no match; git diff --check exits 0.
Visual QA: Chrome headless screenshots at 1440x1100 and responsive 560x900 passed using a temporary localhost static server that was stopped immediately after capture.
Docker image: gateway-only image payflow/api-gateway:dev built successfully from final source. Docker Desktop stopped before the final container recreate, so live Compose activation of this exact image remains a one-command local step.
Known limitation: Console accepts an already-issued short-lived JWT; it intentionally does not place a Keycloak client secret in browser code or implement a browser login flow.
```