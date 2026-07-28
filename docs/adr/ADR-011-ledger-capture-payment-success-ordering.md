# ADR-011: Ledger post, account capture và payment success ordering

- Status: ACCEPTED
- Date: 2026-07-28
- Decision owners: Repository owner (Tien le)
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-001

## Context

Sequence trong spec hiện tại đánh dấu Payment `SUCCEEDED` ngay sau
`ledger.payment-posted`, rồi Account mới capture reservation khi consume
`payment.succeeded`. Cửa sổ crash hoặc capture thất bại sau đó tạo ra một outcome đã công bố thành
công trong khi balance vẫn còn reserved. Nếu Account release reservation để tự chữa lỗi, journal đã
POSTED lại không còn khớp với balance vận hành.

Journal đã POSTED là immutable và reservation đã CAPTURED không được release. Vì không có distributed
transaction xuyên Payment, Account và Ledger, finalization phải dùng acknowledgement rõ ràng, outbox,
inbox idempotent và reconciliation thay vì giả định ba commit xảy ra cùng lúc.

## Decision drivers

- Không phát `payment.succeeded` trước khi các precondition tài chính đã commit bền vững.
- Không release reservation sau khi journal đã POSTED.
- Mọi command/event dùng `paymentId` làm Kafka key và hỗ trợ at-least-once delivery.
- Restart ở bất kỳ service nào phải tiếp tục được từ state/fact đã persist.
- Client nhìn thấy state trung thực trong cửa sổ finalization.

## Options considered

### Option A — Payment success trước capture như spec baseline

- Ưu: ít message hơn và happy path ngắn.
- Nhược: công bố outcome sai nếu capture thất bại; `payment.succeeded` vừa là business fact vừa bị dùng
  như capture command; recovery không biết nên reverse journal hay release reservation.

### Option B — Capture trước ledger

- Ưu: success chỉ cần chờ ledger sau khi tiền đã capture.
- Nhược: nếu ledger post thất bại thì balance đã bị trừ nhưng chưa có accounting fact; cần một nghiệp
  vụ hoàn tiền mới thay vì release reservation, làm failure path phức tạp hơn.

### Option C — Ledger post, explicit capture, rồi success (chọn)

- Ưu: giữ reservation trong khi tạo journal; success có hai acknowledgement bền vững; mỗi bước có thể
  retry idempotent và reconcile bằng business identifiers.
- Nhược: thêm một command/event round trip và cần persisted Saga facts trước khi triển khai consumer.

## Decision

Happy path finalization là:

```text
Account  -> Payment: account.funds-reserved
Payment  -> Ledger:  ledger.post-payment.requested
Ledger   -> Payment: ledger.payment-posted
Payment  -> Account: account.capture.requested
Account  -> Payment: account.funds-captured
Payment  -> others:  payment.succeeded
```

Tất cả message trên đi qua transactional outbox; consumer dùng durable inbox. Kafka key,
`aggregateId` và payload `paymentId` phải bằng nhau.

Quy tắc bắt buộc:

1. `ledger.payment-posted` chỉ cho phép Payment tạo `account.capture.requested`; Payment vẫn
   `PROCESSING`.
2. Account capture đúng reservation theo `reservationId`, `paymentId`, `accountId`, amount và
   currency. Duplicate capture cùng intent là no-op và trả lại cùng business outcome; terminal
   transition khác intent là inconsistency, không được đoán.
3. Payment chỉ chuyển `PROCESSING -> SUCCEEDED` sau khi đã có cả `ledger.payment-posted` và
   `account.funds-captured` khớp payment, amount và currency. Transition, Saga-state update và
   `payment.succeeded` outbox append nằm trong một local transaction.
4. `payment.succeeded` là terminal business fact cho Notification, Reporting và Settlement; nó không
   còn là command để Account capture.
5. Sau khi journal đã POSTED, timeout/capture failure không được tự động release reservation. Hệ thống
   bounded-retry capture, sau đó đánh dấu Saga cần manual review, alert và reconciliation. Cách biểu
   diễn Payment/manual-review state được chốt riêng bởi OD-006 trước khi triển khai recovery runtime.
6. Ledger journal là accounting source of truth; Account reservation/balance là operational source of
   truth. Reconciliation so khớp `paymentId`, `journalId`, `reservationId`, amount, currency và trạng
   thái; không tự sửa mismatch.

State client-visible trong toàn bộ cửa sổ từ funds reserved tới capture acknowledgement là
`PROCESSING`. Chỉ acknowledgement đến chậm không được biến thành `FAILED` hoặc `SUCCEEDED` giả.

## Consequences

### Positive

- Không còn cửa sổ công bố success trước capture.
- Command capture tách khỏi outcome success, nên intent và fact có owner rõ ràng.
- Crash/retry có thể phục hồi từ Saga facts và unique business references.

### Negative/trade-offs

- Happy path thêm một Kafka round trip.
- Cần persist ledger/capture confirmation và deadline trong Saga state; pure Java policy hiện tại chưa
  chứng minh durability đó.
- Trường hợp journal posted nhưng capture không hoàn tất cần manual operations và reconciliation,
  không thể tự động compensation an toàn.

## Contract and data impact

- API: không đổi; `GET payment` tiếp tục trả `PROCESSING` trong cửa sổ finalization.
- Event: thêm `account.funds-reserved`, `ledger.payment-posted`, `account.capture.requested`,
  `account.funds-captured` và `payment.succeeded` v1.
- Database/migration: consumer runtime tương lai phải persist Saga step/facts/deadline, inbox và outbox
  atomically; Account unique reservation theo `payment_id`, Ledger unique business reference.
- Security/privacy: payload chỉ chứa business ID và money, không chứa token, credential hoặc PII.
- Observability/operations: metric cho finalization age, capture retry, mismatch và reconciliation;
  không dùng business ID làm metric label.

## Rollout and rollback

Contract và pure policy được thêm trước adapter. Không có Kafka consumer runtime trong lát cắt này vì
OD-007 chưa resolve. Khi rollout runtime, Payment consumer phải nhận `account.funds-captured` trước khi
bất kỳ producer nào phát `payment.succeeded`; Account phải ngừng dùng `payment.succeeded` làm capture
command. Rollback sau khi message đã lưu không được đổi nghĩa event v1; phải hoàn tất/forward-fix Saga
đang dở.

## Verification

- Contract test exact JSON shape, event name/version/topic/key và money scale.
- Unit test chứng minh ledger acknowledgement chỉ tạo capture request và không đổi khỏi `PROCESSING`.
- Unit test chứng minh success cần cả ledger-posted và funds-captured khớp nhau.
- Negative test cho payment/account/reservation/amount/currency mismatch và status sai.
- Sau OD-007: Testcontainers test inbox + Saga mutation + outbox atomicity và duplicate delivery.
- E2E failure injection tại crash-before/after capture send, capture commit và success publish; assert
  không success sớm, không release sau posted journal và reconciliation phát hiện mọi mismatch.
