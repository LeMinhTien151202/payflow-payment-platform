# Open architecture and contract decisions

File này ghi các điểm chưa đủ rõ trong spec hoặc đang mâu thuẫn giữa các section. `OPEN` là implementation blocker cho đúng phạm vi bị ảnh hưởng; agent không được tự chọn một phương án rồi biến nó thành contract ngầm.

## Quy tắc xử lý

1. Thu thập use case, failure window, alternatives và test chứng minh.
2. Chốt bằng ADR khi có trade-off kiến trúc; cập nhật spec/contract khi thay đổi yêu cầu canonical.
3. Cập nhật mọi sơ đồ, state machine, schema, event và test fixture bị ảnh hưởng trong cùng change.
4. Chuyển mục sang `RESOLVED` chỉ khi link được ADR/spec amendment và implementation gate rõ ràng.

## Decision register

| ID | Trạng thái | Blocker | Phạm vi bị chặn |
| --- | --- | --- | --- |
| OD-001 | RESOLVED | Thứ tự ledger post, account capture và payment success | Mở khóa bằng ADR-011 |
| OD-002 | OPEN | Failure-recovery gate được remap từ spec Phase 2 | Transition sau Phase 1B |
| OD-003 | RESOLVED | Risk decision và payment rejection event taxonomy | Mở khóa bằng ADR-016 |
| OD-004 | OPEN | Fee policy/rate/rounding snapshot lịch sử | Fee, refund economics, settlement |
| OD-005 | OPEN | Atomic refundable-capacity reservation | Refund intake/concurrency |
| OD-006 | OPEN | Payment state khi Saga cần manual review | Timeout/recovery API và event |
| OD-007 | OPEN | PostgreSQL inbox insert-if-new semantics | Kafka consumer implementation |
| OD-008 | RESOLVED | Outbox claim lease và stale recovery | Đã mở khoá bằng ADR-004 + ADR-014 |
| OD-009 | RESOLVED | Risk score normalization/range | Đã mở khoá bằng ADR-015 |
| OD-010 | OPEN | Audit snapshot allowlist, retention và access | Privileged audit logging |

## OD-001 — Financial finalization boundary

Spec baseline mô tả ledger posted -> payment `SUCCEEDED` -> account capture, nhưng chưa định nghĩa crash/retry/reconciliation nếu capture thất bại sau khi đã phát success.

Quyết định phải nêu:

- precondition chính xác để Payment chuyển `SUCCEEDED`;
- command/event và acknowledgment cho capture;
- state hiển thị cho client trong cửa sổ finalization;
- recovery khi Payment hoặc Account restart;
- reconciliation source of truth và invariant E2E.

Giải quyết qua ADR-011 và cập nhật sequence/state/test trước khi implement Phase 1B finalization.

**Đã chốt** bằng [`docs/adr/ADR-011`](../docs/adr/ADR-011-ledger-capture-payment-success-ordering.md):
Ledger post trước, Payment phát explicit `account.capture.requested`, Account xác nhận
`account.funds-captured`, rồi Payment mới được chuyển `SUCCEEDED` và phát `payment.succeeded`.
Trong cửa sổ này client thấy `PROCESSING`. Sau journal POSTED không tự release reservation; bounded
retry, manual review và reconciliation xử lý capture không hoàn tất.

Implementation gate: pure contract/policy test không Docker phải xanh. Kafka consumer và durability
vẫn bị chặn bởi OD-007; recovery/manual-review runtime còn phụ thuộc OD-006 và Testcontainers evidence.

## OD-002 — Roadmap remapping

Spec đặt Saga state, compensation và DLT ở Phase 2. Bộ delivery gate muốn chứng minh failure recovery trước khi tách thêm bounded context. Không được coi việc đổi nhãn phase là đã được phê duyệt.

Chọn một trong hai:

- giữ đúng spec và đưa reliability hardening vào đầu Phase 2 trước service split; hoặc
- accept ADR-012 để nâng reliability hardening thành pre-Phase-2 gate bắt buộc.

## OD-003 — Risk and rejection events

Spec dùng cả event riêng `risk.approved`/`risk.rejected`/`risk.review-required` và event hợp nhất `risk.assessment.completed` có `decision`. `payment.rejected` cũng được nhắc nhưng chưa có schema canonical.

Quyết định phải khóa:

- một taxonomy event versioned;
- decision enum và manual-review semantics;
- producer/consumer/topic/key;
- compatibility strategy và contract test;
- payment outcome event cho risk rejection.

**Đã chốt** bằng [`docs/adr/ADR-016`](../docs/adr/ADR-016-risk-assessment-event-taxonomy.md):
Risk publish một `risk.assessment.completed` v1 trên `payflow.risk.events.v1`, key
theo `paymentId`, payload gồm decision/score/level/matchedRules/policyVersion. Payment
dùng `payment.failed` với `failureCode=RISK_REJECTED` cho outcome bị từ chối;
`REVIEW_REQUIRED` giữ payment ở `RISK_CHECKING`, không reserve tiền và chờ workflow
manual review Phase 2.

Implementation gate: contract/factory test không Docker phải xanh; consumer/inbox và
outbox atomicity chỉ được triển khai sau OD-007 và phải có Testcontainers evidence.

## OD-004 — Historical fee snapshot

Merchant có `fee_rate` mutable, trong khi payment/event chưa lưu policy version, applied rate, rounding hoặc calculated fee. Settlement không được tính lại giao dịch cũ bằng cấu hình hiện tại.

Contract phải bổ sung snapshot tối thiểu cần thiết, owner tính fee, thời điểm tính, rounding policy và cách refund phân bổ/reverse fee.

## OD-005 — Refundable capacity

`total_refunded_amount` chưa định nghĩa có bao gồm refund `CREATED`/`PROCESSING` hay chỉ `SUCCEEDED`. Hai request đồng thời có thể cùng vượt qua validation trước khi side effect hoàn tất.

Quyết định phải định nghĩa một atomic capacity reservation cho mọi trạng thái tiêu thụ hạn mức, release khi failure, database lock/constraint và concurrent integration test.

## OD-006 — Manual review state

Saga có `MANUAL_REVIEW_REQUIRED` nhưng Payment state machine không có trạng thái tương ứng. Không tự thêm Payment status hoặc để `PROCESSING` vô hạn.

Quyết định phải nêu Payment status/API response/event, allowed operations, SLA/alert, operations action và transition sau review/compensation.

## OD-007 — Inbox conflict semantics

PostgreSQL unique violation làm transaction hiện tại aborted nếu chỉ `INSERT` rồi catch như thao tác bình thường. Consumer phải dùng insert-if-new an toàn, ví dụ `INSERT ... ON CONFLICT DO NOTHING` và chỉ chạy business logic khi affected row bằng 1, hoặc một cơ chế savepoint tương đương đã test.

Spec §8.5 phải được làm rõ trước khi tạo consumer template.

## OD-008 — Outbox claim and recovery — RESOLVED 2026-07-26

`PROCESSING` hiện chưa có owner/lease/timeout và stale-claim recovery. Không được giữ transaction database mở qua Kafka I/O hoặc để row mắc kẹt sau crash.

Quyết định phải nêu:

- claim transaction ngắn và fields như `lock_owner`/`lock_until` nếu dùng lease;
- publish ngoài transaction và conditional mark;
- reclaim stale claim;
- crash-before-send và crash-after-send semantics;
- retry/backoff/terminal state, metric và operations recovery.

**Đã chốt** bằng [`docs/adr/ADR-004`](../docs/adr/ADR-004-transactional-outbox-polling-publisher.md) (chọn polling publisher, hoãn Debezium sang Giai đoạn 4) và [`docs/adr/ADR-014`](../docs/adr/ADR-014-outbox-claim-lease-and-recovery.md) (lease claim, ba cột `lock_owner`/`lock_until`/`last_error`, giao thức claim → publish → conditional mark, bảng 5 cửa sổ crash, backoff `min(2^attempt,300)s`, `FAILED` terminal, 5 metric).

Implementation gate: 9 test bắt buộc trong ADR-014 §Verification phải xanh trước khi outbox publisher được coi là xong. Tại thời điểm resolve, **chưa test nào chạy** — ADR mở khoá quyền implement, không phải chứng nhận đã implement.

## OD-009 — Risk score range — RESOLVED 2026-07-27

Tổng điểm rule mẫu có thể vượt 100 trong khi schema/threshold công bố 0–100. Chọn saturation tại 100, normalization khác, hoặc nới range và định nghĩa `score >= 70` rõ ràng. Khóa bằng test nhiều rule đồng thời.

**Đã chốt** bằng [`docs/adr/ADR-015`](../docs/adr/ADR-015-risk-score-saturation-and-level-bands.md):
cộng nguyên điểm rule rồi dùng `min(rawScore, 100)`; level band cố định LOW 0–19,
MEDIUM 20–39, HIGH 40–69, CRITICAL 70–100. OD-003 vẫn độc lập và tiếp tục chặn
event Risk→Payment.

## OD-010 — Audit data safety

`before_data`/`after_data` dạng JSONB không được trở thành đường vòng lưu API key, hash, token, webhook secret hoặc PII. Quyết định phải có field allowlist/redaction trước persistence, append-only protection, access control, retention và test chống secret leakage.
