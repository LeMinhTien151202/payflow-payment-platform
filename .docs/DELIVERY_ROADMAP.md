# Lộ trình delivery có quality gate

## Nguyên tắc

- Chỉ mở phase kế tiếp khi gate của phase hiện tại có bằng chứng tự động hoặc demo tái tạo được.
- Không triển khai một phần “cho có” ở nhiều service. Hoàn thành một vertical slice gồm code, migration, test, observability và tài liệu.
- Mỗi milestone ghi rõ environment, lệnh chạy, expected result và known limitation.
- Phase 1A/1B là cách chia nhỏ spec Phase 1. Reliability hardening ở mục kế tiếp thuộc spec Phase 2 và chỉ được remap thành pre-Phase-2 gate nếu OD-002/ADR-012 được chấp thuận.

## Phase 0 — Foundation

### Deliverable

- Parent Maven POM và Maven Wrapper, Java 21 toolchain.
- Version compatibility được xác minh; dependency do BOM quản lý.
- Docker Compose cho PostgreSQL, Kafka, Redis và Keycloak.
- Skeleton `api-gateway` và `payment-service`.
- Flyway baseline, health probe, structured logging/correlation và Problem Details.
- Keycloak realm import không chứa production secret.
- CI compile/unit/integration skeleton; ADR nền tảng.

### Gate

- `mvnw.cmd clean verify` chạy từ checkout sạch.
- Infrastructure start bằng một lệnh và healthcheck pass.
- Gateway chấp nhận token hợp lệ, phân biệt 401/403 và từ chối token sai.
- Service readiness phản ánh dependency cần thiết.
- Correlation ID xuất hiện trong response và structured log.
- Chưa có business payment giả vờ hoàn tất.

## Phase 1A — Payment intake

### Deliverable

- Merchant/customer/account seed giả.
- `POST /api/v1/payments` trả 202.
- Payment state/history và idempotency record.
- Payment + outbox insert trong một transaction.
- Outbox polling publisher và event envelope v1.

### Gate

- Same key + same canonical payload trả cùng payment.
- Same key + different payload trả 409.
- Concurrent same key chỉ tạo một payment/outbox business event.
- Mô phỏng crash/restart không làm mất event; duplicate publish không tạo payment thứ hai.

## Phase 1B — Happy-path Saga

### Deliverable

- Risk rule deterministic.
- Account reserve/capture và concurrency guard.
- Ledger balanced journal.
- Notification record/mock delivery.
- E2E successful payment.

### Gate

- Balance không âm trong concurrent test.
- Duplicate commands/events không tạo reservation/journal/notification trùng.
- Journal debit bằng credit và duplicate reference bị no-op/conflict đúng contract.
- E2E từ 1.000.000 VND thanh toán 500.000 VND cho kết quả đã định nghĩa.
- Trace nối được Gateway -> Kafka -> Risk -> Account -> Ledger -> Payment.
- ADR-011 phải được accepted trước khi implement/đánh giá finalization boundary giữa ledger, capture và `SUCCEEDED`.

## Pre-Phase-2 decision gate — Failure recovery

Spec đặt Saga state, compensation và DLT trong Phase 2. OD-002 phải được giải quyết trước: hoặc giữ hạng mục này ở đầu Phase 2, hoặc accept ADR-012 để nâng thành gate bắt buộc trước khi tách service. Danh sách dưới đây không tự nó cấp quyền remap roadmap.

### Deliverable

- Timeout, bounded retry, backoff và Saga deadline.
- Persisted Saga state và recovery scheduler.
- Compensation release khi ledger không hoàn tất.
- DLT, alert metric và manual-review state tối thiểu.
- Failure E2E/chaos test.

### Gate

- Kill/timeout Ledger sau reserve làm balance trở về đúng giá trị một lần.
- Restart orchestrator tiếp tục Saga từ persisted state.
- Poison event không bị retry vô hạn hoặc block partition vĩnh viễn.
- Không có payment `SUCCEEDED` nếu journal chưa được posted theo contract.

## Phase 2 — Bounded-context split và refund

### Deliverable

- Contract test cho Account/Ledger rồi mới tách deployable/database.
- Nếu OD-002 chọn giữ roadmap gốc, hoàn tất failure recovery ở đầu phase này trước service split.
- Merchant service/module hoàn chỉnh.
- Partial/full refund, webhook HMAC/retry và reporting projection.
- Event/schema versioning, replay tooling và operations endpoint có audit.

### Gate

- Split không đổi external behavior của Saga.
- Concurrent refunds không vượt original amount.
- Refund tạo journal mới/reversal, không sửa journal cũ.
- Webhook duplicate/retry giữ cùng event id, signature xác minh được.
- Reporting rebuild tạo kết quả tương đương projection hiện tại.

## Phase 3 — Production-like

### Deliverable

- Settlement và reconciliation.
- Full observability dashboards/alerts/runbooks.
- Docker images non-root và Docker Compose full-profile deployment.
- CI build/test, Compose smoke test và dependency/security scan.
- k6 load test và container failure scenarios có report tái tạo được.

Kubernetes/Kustomize, NetworkPolicy và HPA tạm hoãn theo quyết định của repository owner. Đây là
hạng mục tùy chọn sau khi Docker Compose runtime gate ổn định, không phải dependency của nghiệp vụ
Settlement/Reconciliation.

### Gate

- Settlement gross/refund/fee/net đúng bằng fixture và reconciliation không lệch.
- Full-profile Compose start/restart/smoke test được document và thực thi.
- Alert quan trọng có owner/runbook.
- Performance report ghi hardware/config/dataset/date; không dùng số liệu giả.

## Phase 4 — Tùy chọn

Chỉ chọn hạng mục có câu hỏi học tập rõ ràng: Debezium CDC outbox, Schema Registry + Avro/Protobuf, OpenSearch, gRPC, feature flag, canary hoặc Spring Batch. Mỗi lựa chọn cần ADR, benchmark/failure model và không được làm suy yếu invariant đã đạt.

## Trạng thái ban đầu

Repository hiện ở trước Phase 0. Bước code đầu tiên hợp lệ là chuẩn hóa platform/dependency và foundation; chưa được nhảy tới business workflow hoặc Kubernetes.
