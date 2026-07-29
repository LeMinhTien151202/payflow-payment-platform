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
| Event envelope v1 và topic contract | `VERIFIED_LOCAL` | 52/52 contract test pass; full `-Pno-docker verify` exit 0, 2026-07-28 | Thêm versioned Account reserve/outcome/capture, Ledger post-requested/posted và Payment success contracts theo ADR-011 |
| Payment intake schema (V2, DDL + constraint) | `IMPLEMENTED` | — | `PaymentIntakeSchemaIT` (23 test) và `PaymentServiceFoundationIT` chưa chạy: cần Docker daemon. Xem "Known deviations" bên dưới |
| Payment intake REST + application core | `IMPLEMENTED` | `PaymentControllerTest` 6 + `CreatePaymentHandlerTest` 24 + `RequestFingerprintTest` 13 pass, 2026-07-26 | `POST` 202, `GET`, JWT `merchant_id`, typed Problem Details và canonical replay đã có; atomicity/concurrency trên PostgreSQL thật chưa chạy |
| Outbox polling publisher | `IMPLEMENTED` | `PublishOutboxHandlerTest` 7 + `OutboxPropertiesTest` 1 pass, 2026-07-26 | Lease/backoff/terminal/order policy đã test không Docker; claim SQL, Kafka ack và 9 integration gate ADR-014 chưa chạy |
| Account/Reservation và Ledger domain/application core | `VERIFIED_LOCAL` | 38/38 Account/Ledger test + 52/52 event-contract test pass; full `-Pno-docker verify` exit 0, 2026-07-28 | Reserve deadline, duplicate intent, stable failure outcome và Ledger posted factory đã có; chưa có Spring Boot, database locking, inbox/outbox hoặc Kafka |
| Risk rule engine + assessment event factory | `VERIFIED_LOCAL` | 26/26 Risk test + 52/52 event-contract test pass; full `-Pno-docker verify` exit 0, 2026-07-28 | ADR-015/016; giữ payment key, correlation và causation; không dùng wall-clock khác service để suy luận thứ tự; chưa có Redis/PostgreSQL/Kafka adapter |
| Notification record và email mock core | `VERIFIED_LOCAL` | 14/14 unit test pass; full `-Pno-docker verify` exit 0, 2026-07-28 | Core thuần Java trong `notification-service`; chưa có Spring Boot, database, Kafka inbox/outbox, webhook, retry/DLT hoặc Keycloak runtime |
| Risk→Payment Saga decision core | `VERIFIED_LOCAL` | Payment 138/138 test pass, gồm risk, reserve và finalization policy/factory; full `-Pno-docker verify` exit 0, 2026-07-28 | `APPROVED/REJECTED/REVIEW_REQUIRED` đã khóa; reserve/ledger/capture/success command chain đã có pure core, chưa có Kafka consumer hay inbox/outbox transaction |
| Financial finalization contract + Payment policy | `VERIFIED_LOCAL` | ADR-011; event-contracts 52/52 + Payment 138/138; full `-Pno-docker verify` exit 0, 2026-07-28 | Ledger posted → explicit capture → captured → success; pure policy chưa phải durable Saga/consumer |
| Phase 1B Saga command/outcome orchestration core | `VERIFIED_LOCAL` | Event contracts 52/52, Payment 138/138, Account/Ledger 38/38; full `-Pno-docker verify` exit 0, 2026-07-28 | Correlation/causation và aggregate key được bảo toàn; đây là pure core, không phải Kafka/PostgreSQL E2E |
| Payment consumer inbox foundation | `IMPLEMENTED` | ADR-017; 5/5 unit test pass; `PaymentInboxSchemaIT` compile nhưng chưa chạy | V3 tạo `(event_id, consumer_name)` PK; adapter dùng `ON CONFLICT DO NOTHING` + transaction `MANDATORY`; PostgreSQL/Kafka gate còn thiếu |
| Happy-path Saga E2E | `PLANNED` | — | Phase 1B; OD-001/007 đã resolve nhưng consumer wiring và PostgreSQL/Kafka runtime chưa được kiểm chứng |
| Failure recovery/compensation core | `VERIFIED_LOCAL` | ADR-012/018; event contracts 56/56, Payment 162/162, Account/Ledger 44/44; full `-Pno-docker verify` exit 0, 2026-07-28 | Durable Saga domain/deadline, bounded retry decision, safe pre-ledger release, manual review và V4 migration đã có; scheduler/persistence adapter/Kafka consumer chưa có, PostgreSQL migration tests chưa chạy |
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
| D-05 | Spec §7.4 vòng đời payment | DDL cho phép cả 10 status; Phase 1B intake lưu `RISK_CHECKING` nhưng response/idempotency snapshot vẫn `CREATED` | Tập status do spec cố định. Payment chuyển `CREATED -> RISK_CHECKING` trong cùng transaction append `payment.created`, loại bỏ cửa sổ risk result tới trước state transition; API 202 vẫn giữ contract ban đầu |
| D-06 | — | `merchant` là schema riêng, **không** có FK từ `payment.payments.merchant_id` | Merchant catalog sẽ tách thành service riêng. Một FK cross-schema sẽ biến việc tách đó từ thay đổi code thành một cuộc di trú dữ liệu |
| D-07 | Roadmap yêu cầu Phase 1A gate trước Phase 1B | Bắt đầu domain core Account/Ledger trước khi chạy gate PostgreSQL/Kafka | Người dùng yêu cầu tiếp tục code core trong lúc chưa chạy Docker. Phạm vi chỉ gồm invariant deterministic và unit test; không thêm persistence, consumer, event contract hay tuyên bố Phase 1B hoàn tất |
| D-08 | Roadmap yêu cầu Phase 1A gate trước Phase 1B | Bắt đầu Risk domain/event core trước integration gate | Người dùng tiếp tục yêu cầu code core không Docker. OD-009/OD-003 được resolve bằng ADR-015/016; phạm vi chỉ gồm deterministic policy, versioned contract/factory và Payment decision policy, không có consumer/persistence/Kafka |
| D-09 | Roadmap yêu cầu Phase 1A gate trước Phase 1B | Bắt đầu Notification email-mock core trước integration gate | Người dùng tiếp tục yêu cầu code core không Docker. Phạm vi chỉ gồm notification state policy, application port và in-memory email adapter; OD-007 chặn Kafka consumer/inbox, còn webhook/retry/DLT thuộc Phase 2 |
| D-10 | Spec §9.2 đánh dấu Payment `SUCCEEDED` trước khi Account capture | ADR-011 đổi thành ledger posted → explicit capture → funds captured → payment success | Thứ tự baseline có thể công bố success khi tiền chưa capture. Thứ tự mới giữ Payment `PROCESSING` đến khi cả journal và capture đã commit; sau journal POSTED không tự release reservation |
| D-11 | Roadmap Phase 1B yêu cầu Kafka/PostgreSQL E2E | Hoàn thiện trước command/outcome contract và orchestration core bằng unit/contract test không Docker | Repository owner yêu cầu viết xong code core rồi mới bật hạ tầng. Phạm vi này không tuyên bố atomic inbox/outbox, locking, duplicate delivery, crash recovery hay Phase 1B E2E đã đạt gate |

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

Quy tắc cập nhật:

- Không ghi `VERIFIED_*` nếu thiếu command và kết quả.
- Failure test phải ghi injection point và state cuối của từng aggregate.
- Load-test result phải trỏ tới script/config và môi trường.
- Nếu một regression làm gate fail, hạ trạng thái capability và ghi nguyên nhân; không giữ badge cũ.
