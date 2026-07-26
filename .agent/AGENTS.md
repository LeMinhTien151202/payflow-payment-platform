# Quy ước làm việc — PayFlow Payment Platform

## 1. Phạm vi và nguồn sự thật

- PayFlow là nền tảng thanh toán **sandbox** phục vụ học tập và portfolio; không xử lý tiền thật, dữ liệu thẻ thật, KYC thật hoặc kết nối ngân hàng thật.
- `PAYFLOW_MICROSERVICE_PROJECT_SPEC.md` là đặc tả nghiệp vụ và kỹ thuật gốc. Không âm thầm thay đổi invariant, state machine, API/event contract hoặc roadmap trong file này.
- `.docs/README.md` là điểm bắt đầu cho tài liệu triển khai. Các file trong `.docs/` diễn giải cách áp dụng spec, không thay thế spec.
- ADR đã được chấp thuận có thể tinh chỉnh một quyết định kiến trúc. Nếu ADR mâu thuẫn với spec, phải nêu rõ phạm vi thay đổi và cập nhật tài liệu liên quan trong cùng change.
- Code, migration và test hiện hữu là bằng chứng về trạng thái triển khai, không tự động trở thành yêu cầu đúng nếu trái với spec hoặc invariant tài chính.
- Khi nguồn thông tin mâu thuẫn, dừng việc suy đoán, chỉ ra xung đột và chọn phương án không làm mất tiền, trùng giao dịch, mất event hoặc phá hợp đồng.

## 2. Nền tảng đích

- Java 21 LTS và Maven Wrapper.
- Baseline trong spec là Spring Boot 4.1.x với Spring Cloud 2025.1.x; trước khi bootstrap hoặc nâng version phải kiểm tra compatibility matrix và artifact từ nguồn chính thức.
- Nếu baseline chưa tương thích, chỉ dùng profile fallback được spec cho phép sau khi giải thích và ghi quyết định; không trộn sai Spring Boot/Spring Cloud release train.
- PostgreSQL và Flyway; database/schema và credential riêng theo service.
- Kafka cho workflow/event bất đồng bộ; Redis cho rate limit, velocity counter, cache hoặc coordination, không làm source of truth cho tiền.
- Keycloak cho OAuth2/OIDC; Spring Security Resource Server ở Gateway và từng service.
- Testcontainers cho PostgreSQL, Kafka và Redis trong integration test; không thay bằng H2.

## 3. Nguyên tắc delivery

- Xây theo lát cắt dọc nhỏ và đúng thứ tự trong `.docs/DELIVERY_ROADMAP.md`.
- Không tạo toàn bộ microservice ngay từ đầu. Chỉ tách service khi phase hiện tại đã có happy path, failure path và test đạt gate.
- Foundation phải chạy trước business payment. Payment happy path phải chạy trước Kubernetes, dashboard đẹp hoặc tính năng nâng cao.
- Ưu tiên bằng chứng chạy được hơn số lượng công nghệ: test concurrency, duplicate delivery, crash recovery, trace và số đo load test.
- Không ghi số liệu throughput, latency, availability hoặc coverage vào README/CV nếu chưa đo và lưu cách tái tạo kết quả.

## 4. Kiến trúc bắt buộc

- Monorepo, nhưng mỗi service phải build/test độc lập và sở hữu schema/database của mình.
- Service không đọc bảng, gọi repository hoặc dùng JPA entity của service khác.
- Shared library chỉ chứa contract ổn định, test support, observability helper và error convention; không chứa entity, repository hoặc business logic dùng chung.
- Bên trong service tổ chức **feature/domain trước, layer sau**: `api`, `application`, `domain`, `infrastructure` khi đủ lớn.
- Luồng chuẩn: `API/consumer -> application use case -> domain invariant -> port -> infrastructure adapter`.
- Controller/consumer không gọi repository trực tiếp. Domain không phụ thuộc Spring, JPA, Kafka, Redis hoặc SDK ngoài.
- DTO API/event tách khỏi JPA entity. Không serialize entity trực tiếp.
- Dependency injection qua constructor. Transaction boundary đặt ở application service và chỉ bao phủ tài nguyên local.
- Không dùng distributed ACID transaction. Workflow xuyên service dùng Saga và compensating action.
- REST chỉ dùng khi caller cần kết quả đồng bộ ngay; workflow thanh toán chính ưu tiên command/event qua Kafka.

## 5. Invariant tài chính

- Tiền dùng `BigDecimal` trong Java và `NUMERIC(19,4)` trong PostgreSQL; không dùng `float`/`double`.
- Currency là dữ liệu bắt buộc. Không cộng/trừ hai amount khác currency; mọi phép chia/làm tròn phải chỉ định scale và `RoundingMode` theo policy.
- `available_balance >= 0` và `reserved_balance >= 0` phải được bảo vệ cả ở domain lẫn database constraint.
- Reserve phải atomic bằng row lock hoặc conditional update; kiểm tra rồi update bằng hai thao tác không khóa là không hợp lệ.
- Mỗi payment chỉ có một reservation nghiệp vụ. Capture/release phải idempotent và chỉ chuyển từ trạng thái hợp lệ.
- Journal đã posted là bất biến: không update, không hard delete. Sai sót được sửa bằng reversal/new journal.
- Mỗi journal phải có ít nhất hai entry và `SUM(DEBIT) = SUM(CREDIT)` theo currency trước khi commit.
- Journal phải idempotent theo `(reference_type, reference_id, journal_type)`.
- Refund ở mọi trạng thái tiêu thụ capacity (`CREATED`, `PROCESSING`, `SUCCEEDED` theo contract cuối) không được làm tổng vượt payment amount; `FAILED` phải release capacity atomically.
- State transition của payment/refund/saga phải đi qua policy/state machine; không set status tùy ý từ controller hoặc listener.

## 6. Messaging và transaction phân tán

- Mọi producer ghi business data và outbox record trong cùng local database transaction.
- Không publish Kafka trực tiếp trước commit hoặc publish kiểu “commit rồi gửi” không có outbox.
- Outbox publisher phải retry có giới hạn, quan sát được và không giả định publish không trùng. Không giữ database transaction qua Kafka I/O; claim/reclaim model phải giải quyết lease owner, stale timeout và crash window theo OD-008 trước implementation.
- Mọi consumer ghi `processed_events`/inbox và business change trong cùng transaction; unique key tối thiểu là `(event_id, consumer_name)`. Dùng insert-if-new an toàn như `ON CONFLICT DO NOTHING` và chỉ xử lý business khi insert thành công; không để unique violation làm transaction aborted rồi tiếp tục.
- Chỉ acknowledge/commit Kafka offset sau khi local transaction thành công. Không swallow exception rồi commit offset.
- Kafka key của payment workflow là `aggregateId`/`paymentId`; không phụ thuộc thứ tự toàn cục.
- Event envelope phải có `eventId`, `eventType`, `eventVersion`, `aggregateType`, `aggregateId`, `correlationId`, `causationId`, `producer` và `occurredAt`.
- Event contract phải tiến hóa tương thích. Breaking change cần version/topic strategy và contract test.
- Retry chỉ áp dụng cho lỗi transient và operation idempotent. Retry hữu hạn, có backoff; lỗi poison đi DLT và có runbook/replay an toàn.
- Saga do Payment Service điều phối. Timeout cuối cùng phải compensation hoặc chuyển `MANUAL_REVIEW_REQUIRED`; không để trạng thái treo vô hạn.
- Không tuyên bố end-to-end exactly-once. Mục tiêu là at-least-once delivery cộng idempotent processing và invariant database.

## 7. API, idempotency và lỗi

- Public API dùng `/api/v1`; internal API dùng `/internal/v1` và không expose trực tiếp ra internet.
- Tạo payment/refund/cancel bắt buộc `Idempotency-Key` theo contract.
- Idempotency record phải lưu scope, request hash, trạng thái và response/resource. Cùng key/cùng canonical payload trả kết quả cũ; cùng key/khác payload trả 409.
- Canonical request hash không được phụ thuộc thứ tự key JSON hoặc field vận chuyển không ổn định.
- API bất đồng bộ trả `202 Accepted`; response/lỗi có `correlationId`.
- Lỗi theo RFC Problem Details mở rộng với stable business `code`; không trả stack trace, SQL hoặc chi tiết nội bộ.
- Validation dùng Jakarta Bean Validation ở boundary và domain validation cho invariant.
- Pagination bị giới hạn (`size <= 100`); query danh sách phải tránh unbounded result và N+1.

## 8. Security và tenant isolation

- Deny-by-default. Route public và scope/role được liệt kê tường minh.
- Gateway và service đều validate JWT; service không mù quáng tin header identity do client có thể giả mạo.
- Kiểm tra ownership/merchant scope ở application boundary. Không bao giờ chỉ dựa vào `merchantId` từ request body.
- Service-to-service dùng client credentials/token phù hợp và private network policy.
- API key chỉ hiển thị plaintext một lần, database chỉ lưu hash/prefix; hỗ trợ expiry, revoke, rotation và rate limit.
- Không commit hoặc log token, password, API key, webhook secret, client secret, private key hay credential URL.
- Webhook ký HMAC trên timestamp cộng raw body; chống replay bằng timestamp tolerance và event id.
- Audit các thao tác nhạy cảm: refund, balance adjustment, risk review, API-key lifecycle, webhook retry, settlement và role change.
- Audit snapshot phải dùng field allowlist/redaction trước khi persist; không lưu secret, token, API-key plaintext/hash hoặc webhook secret trong `before_data`/`after_data`.
- Dữ liệu local/seed phải giả. Webhook local chỉ gọi mock server.

## 9. Database và migration

- Flyway là nguồn sự thật của schema; Hibernate dùng `ddl-auto=validate`.
- Không sửa migration đã chạy. Tạo migration forward-only mới và ghi rollout/rollback plan khi có rủi ro.
- Mỗi service có migration riêng và chỉ dùng credential sở hữu schema của chính nó.
- Thay đổi tương thích theo expand -> backfill -> switch -> contract; không thêm ngay cột `NOT NULL` vào bảng có dữ liệu nếu chưa có kế hoạch backfill.
- Constraint và unique index phải bảo vệ invariant quan trọng, không chỉ dựa vào check trong Java.
- Trước khi thêm query, kiểm tra index theo access pattern; đặc biệt idempotency, outbox, processed event, payment search, reservation, journal và webhook retry.
- Không reset/drop/truncate dữ liệu ngoài môi trường disposable nếu người dùng chưa yêu cầu rõ.

## 10. Observability

- Gateway tạo hoặc chuyển tiếp `X-Correlation-Id`; trace context phải truyền qua HTTP và Kafka.
- Structured log tối thiểu có service, traceId, spanId, correlationId, event/business action và stable error code.
- Không log payload chứa secret hoặc PII thật.
- Mỗi service có liveness/readiness; không expose toàn bộ Actuator công khai.
- Thêm metric cho business invariant và reliability, không chỉ JVM/HTTP: payment outcome, saga duration/timeout, outbox age, consumer lag, duplicate event, insufficient funds, unbalanced attempt, webhook retry/DLT.
- Mọi retry, compensation, DLT và manual intervention phải truy vết được bằng correlation/payment/event ID.

## 11. Kiểm thử và Definition of Done

- Unit test domain invariant không cần Spring; web/security test cho contract; integration test với PostgreSQL/Kafka/Redis thật qua Testcontainers.
- Bug về tiền, duplicate, race condition hoặc state transition phải có regression test tái hiện lỗi.
- Test bắt buộc tương ứng thay đổi: idempotency cùng/khác payload, duplicate event, concurrent reserve/refund, balanced journal, outbox crash window, retry/DLT và compensation.
- Không mock repository/database trong test nhằm chứng minh locking, unique constraint, transaction hoặc SQL behavior.
- Trước bàn giao chạy targeted test trong lúc làm, sau đó Maven verify phù hợp. Với thay đổi contract/schema phải chạy thêm contract/migration test.
- Chỉ coi hoàn tất khi code, migration, API/event docs, test, observability và lệnh kiểm chứng được cập nhật đồng bộ.

## 12. Quy trình làm việc của agent

1. Xác định phase và service sở hữu thay đổi từ `.docs/MODULE_MAP.md` và `.docs/DELIVERY_ROADMAP.md`; kiểm tra blocker trong `.docs/OPEN_DECISIONS.md`.
2. Đọc code, migration, test và contract hiện có trước khi thiết kế.
3. Trước khi code, nêu requirement/invariant, service ảnh hưởng, file dự kiến, migration, API/event, failure/concurrency case và test case.
4. Thay đổi nhỏ nhất đủ hoàn chỉnh; không lén mở rộng sang service hoặc phase khác.
5. Viết test cùng change, chạy kiểm chứng và review diff.
6. Báo cáo file đã đổi, tác động contract/schema, lệnh và kết quả test, cùng rủi ro còn lại.
7. Với yêu cầu chỉ đọc/review/plan, không sửa file.

## 13. Thứ tự đọc tài liệu

1. `AGENTS.md` và file này.
2. `.docs/README.md`.
3. `.docs/PROJECT_OVERVIEW.md`, `.docs/ARCHITECTURE.md`, `.docs/MODULE_MAP.md`.
4. `.docs/DELIVERY_ROADMAP.md`, `.docs/OPEN_DECISIONS.md` và `.docs/TESTING_STRATEGY.md`.
5. Phần liên quan trong `PAYFLOW_MICROSERVICE_PROJECT_SPEC.md`; đọc toàn bộ trước thay đổi foundation, architecture hoặc workflow xuyên service.
6. ADR và code/migration/test liên quan.
