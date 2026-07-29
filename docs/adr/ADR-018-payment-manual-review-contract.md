# ADR-018: Payment có trạng thái MANUAL_REVIEW_REQUIRED

- Status: ACCEPTED
- Date: 2026-07-28
- Decision owners: PayFlow repository owner
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-006

## Context

Saga status trong spec có `MANUAL_REVIEW_REQUIRED` nhưng Payment state machine không có trạng thái
tương ứng. Giữ Payment ở `RISK_CHECKING` hoặc `PROCESSING` vô hạn khiến client không phân biệt được
đang chạy bình thường với đang chờ con người, còn chuyển `FAILED` có thể nói sai sự thật khi Ledger
hoặc Account đã commit nhưng acknowledgement bị mất.

## Decision drivers

- API phải phản ánh trung thực rằng tự động xử lý đã dừng.
- Không release reservation sau journal `POSTED` theo ADR-011.
- Operations action phải dựa trên Saga step và persisted facts, không chỉ Payment status.
- Status/event mới phải additive, versioned và có stable reason code.

## Options considered

### Option A — Giữ trạng thái cũ và chỉ nhìn Saga nội bộ

Không đổi API nhưng để `PROCESSING`/`RISK_CHECKING` vô hạn, trái với recovery contract và gây hiểu
nhầm cho client. Bị loại.

### Option B — Thêm `MANUAL_REVIEW_REQUIRED` vào Payment

Client nhìn thấy trạng thái rõ ràng; Saga vẫn giữ current step/facts để quyết định cách resume. Đây là
thay đổi additive đối với response enum nhưng client dùng closed enum phải nâng contract.

## Decision

Chọn Option B. Payment thêm non-terminal status `MANUAL_REVIEW_REQUIRED` và event
`payment.manual-review-required` v1 gồm `paymentId`, `sagaStep`, `reasonCode`.

Các nguồn vào hợp lệ:

- `RISK_CHECKING` khi Risk trả `REVIEW_REQUIRED`;
- `RESERVING_FUNDS` khi reserve outcome vẫn mơ hồ sau retry hữu hạn;
- `PROCESSING` khi Ledger/capture outcome không còn tự động kết luận an toàn;
- compensation timeout từ Saga `COMPENSATING`.

Resolution không được gọi `transitionTo` tùy ý. Application recovery policy đọc Saga step/facts và chỉ
cho phép một trong các kết quả: resume đúng step, kết thúc risk rejection, hoàn tất compensation thành
`FAILED`, hoặc tiếp tục finalization sau khi facts được operations xác minh. Mọi action phải audit.

Sau journal `POSTED`, manual review không được release reservation. Trước journal `POSTED`, release
compensation chỉ được phát khi Saga có durable reservation fact.

## Consequences

### Positive

- API không còn biểu diễn payment treo như đang xử lý bình thường.
- Alert/operations query có một state ổn định và truy vết được.
- Không cần nói sai `FAILED`/`SUCCEEDED` khi outcome đang mơ hồ.

### Negative/trade-offs

- Thêm một Payment enum value ngoài state list baseline của spec.
- OpenAPI client dùng enum đóng cần regenerate/nâng version tương thích.
- Operations resolution cần Saga facts và audit, không thể chỉ cập nhật một cột status.

## Contract and data impact

- API: `GET /api/v1/payments/{id}` có thể trả `MANUAL_REVIEW_REQUIRED`; create response vẫn chỉ
  `CREATED`.
- Event: thêm `payment.manual-review-required` v1, Kafka key/aggregateId là `paymentId`.
- Database: migration forward-only mở rộng check constraint Payment/history và tạo Saga table.
- Security: operations resolution endpoint tương lai yêu cầu `OPERATIONS`, scope riêng và audit.
- Observability: counter vào manual review và gauge age; không dùng paymentId làm metric label.

## Rollout and rollback

Migrate database và deploy reader/API trước khi producer có thể ghi status/event mới. Không rollback
bằng cách đổi status về `PROCESSING`; tắt producer mới và forward-fix các payment đã vào review.

## Verification

- Exact state-machine test và OpenAPI enum test.
- Contract JSON test cho event mới.
- Unit test risk review, pre-ledger compensation và post-ledger no-release.
- PostgreSQL migration/history constraint test.
- E2E operations resolution và audit test trước khi mở endpoint.

