# ADR-012: Failure recovery là gate bắt buộc trước khi tách service Phase 2

- Status: ACCEPTED
- Date: 2026-07-28
- Decision owners: PayFlow repository owner
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-002

## Context

Spec đặt Saga timeout, compensation và DLT trong Phase 2, trong khi roadmap chỉ cho tách
`account-ledger-service` sau khi happy path, idempotency và compensation đã được chứng minh. Nếu tách
trước, dự án thêm network hop và failure mode nhưng chưa có cơ chế khôi phục reservation bị giữ.

## Decision drivers

- Không để tiền reserved vô hạn khi Ledger không hoàn tất.
- Không công bố payment success khi journal/capture chưa đủ fact theo ADR-011.
- Restart Payment orchestrator phải tiếp tục từ Saga state bền vững.
- Portfolio phải có failure evidence trước khi tăng số microservice.

## Options considered

### Option A — Giữ recovery ở đầu Phase 2

Giữ nhãn spec nhưng cho phép bắt đầu service split trước khi gate recovery xanh. Phương án này tạo
đúng loại distributed failure mà hệ thống chưa xử lý, nên bị loại.

### Option B — Nâng recovery thành pre-Phase-2 gate

Hoàn thiện persisted Saga deadline, bounded retry, compensation, manual review và DLT trước service
split. Tốn thêm công việc ở MVP nhưng làm topology Phase 2 dựa trên semantics đã được chứng minh.

## Decision

Chọn Option B. Pre-Phase-2 gate là bắt buộc. Không tách Account/Ledger, không làm refund và không thêm
service mới cho tới khi có tối thiểu:

1. Saga state/deadline bền vững và optimistic concurrency;
2. retry hữu hạn cho command idempotent;
3. release compensation đúng một lần khi Ledger chưa POSTED;
4. manual review thay cho phỏng đoán khi outcome không còn tự động an toàn;
5. inbox/outbox atomicity, DLT hữu hạn và test restart/failure injection.

Code thuần và migration có thể được chuẩn bị khi chưa có Docker, nhưng gate chỉ xanh sau PostgreSQL,
Kafka và E2E evidence. ADR này đổi thứ tự delivery, không tuyên bố recovery đã hoàn thành.

## Consequences

### Positive

- Service split không thay đổi failure semantics đã kiểm chứng.
- Compensation trở thành khả năng nền tảng thay vì phần vá sau refund.
- Có câu chuyện portfolio rõ ràng về crash/restart và at-least-once delivery.

### Negative/trade-offs

- Phase 2 bắt đầu muộn hơn.
- Cần schema Saga, scheduler, operations runbook và nhiều failure test trước feature mới.

## Contract and data impact

- API/event: manual-review contract được khóa riêng trong ADR-018.
- Database: Payment sở hữu `payment_sagas`, deadline, retry và durable financial fact IDs.
- Security: operations resolution cần role/scope riêng và audit; chưa mở endpoint trong ADR này.
- Observability: metric timeout, retry, compensation, manual review và DLT là bắt buộc.

## Rollout and rollback

Triển khai schema/reader trước scheduler và listener. Có thể tắt scheduler/listener để rollback code;
không xóa Saga rows hoặc đổi nghĩa event đã phát. Saga đang dở phải forward-fix hoặc xử lý operations.

## Verification

- Unit test state/deadline/retry và safe-compensation decision.
- PostgreSQL test optimistic concurrency, scheduler claim và atomic inbox/Saga/outbox.
- Kafka test retry/DLT/offset-after-commit.
- E2E kill/restart và Ledger outage chứng minh reservation được release đúng một lần.

