# ADR-016: Một event hợp nhất cho kết quả risk assessment v1

- Status: ACCEPTED
- Date: 2026-07-28
- Decision owners: Repository owner (Tien le)
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-003

## Context

Spec §7.7 mô tả ba event `risk.approved`, `risk.rejected` và
`risk.review-required`, còn spec §8.4 lại mô tả một event
`risk.assessment.completed` có field `decision`. Nếu producer hoặc consumer tự chọn
một phía, cùng một business fact sẽ có hai taxonomy cạnh tranh và Payment Saga không
có contract duy nhất để xử lý.

ADR-015 đã khóa score, level, decision và yêu cầu giữ `policyVersion` cùng
`matchedRules`. Phase 1B cần publish kết quả deterministic đó mà không làm lộ domain
class của Risk qua shared library và không tạo topic riêng cho từng outcome.

Payment state machine cũng không có trạng thái `RISK_REVIEW_REQUIRED`. Vì vậy decision
`REVIEW_REQUIRED` cần semantics rõ ràng thay vì tự thêm một Payment status ngoài spec.

## Decision drivers

- Một assessment tạo đúng một business fact versioned.
- Consumer xử lý outcome bằng dữ liệu, không bằng topic hoặc tên event khác nhau.
- Kafka key luôn là `paymentId` để giữ ordering per payment.
- Duplicate delivery dùng cùng `eventId` và được inbox loại bỏ ở consumer.
- Score/rule history phải giải thích được bằng `policyVersion` theo ADR-015.
- Risk không được cập nhật trực tiếp Payment database.

## Options considered

### Option A — Ba event theo decision

Publish `risk.approved`, `risk.rejected` hoặc `risk.review-required`.

- Ưu: consumer có thể route theo event name, payload nhỏ.
- Nhược: ba schema cho cùng một fact; thêm decision mới cần thêm event type; producer
  phải đảm bảo chỉ phát đúng một trong ba; mâu thuẫn với schema hợp nhất ở spec §8.4.

### Option B — Một `risk.assessment.completed` có decision (chọn)

Publish một event chứa `decision`, `score`, `level`, `matchedRules` và
`policyVersion`.

- Ưu: một schema, một topic, dễ version và replay; payload giữ đủ bằng chứng giải
  thích assessment.
- Nhược: mọi consumer phải đọc `decision`; filter chỉ theo event type không đủ để
  tách approved/rejected.

### Option C — Không publish assessment, Payment gọi Risk REST

- Ưu: response đồng bộ dễ hiểu ở happy path.
- Nhược: kéo dài REST chain, coupling availability và không phù hợp Saga/event-driven
  đã chọn cho payment workflow.

## Decision

Risk Service là owner và publish đúng một event:

```text
topic:         payflow.risk.events.v1
eventType:     risk.assessment.completed
eventVersion:  1
aggregateType: PAYMENT
aggregateId:   paymentId
Kafka key:     paymentId
producer:      risk-service
consumer:      payment-service (chính), reporting-service (Phase 2)
```

Payload v1:

```json
{
  "paymentId": "uuid",
  "decision": "APPROVED",
  "score": 20,
  "level": "MEDIUM",
  "matchedRules": ["NEW_DEVICE", "IP_CHANGE"],
  "policyVersion": "risk-v1"
}
```

Semantics tại Payment Service:

| Decision | Payment behavior | Money side effect |
| --- | --- | --- |
| `APPROVED` | `RISK_CHECKING -> RESERVING_FUNDS`, tạo `account.reserve.requested` trong cùng local transaction | Chưa thay đổi balance; chỉ phát command qua outbox |
| `REJECTED` | `RISK_CHECKING -> RISK_REJECTED`, tạo `payment.failed` với `failureCode=RISK_REJECTED` | Không reserve, không journal |
| `REVIEW_REQUIRED` | Giữ `RISK_CHECKING`, không phát reserve/failure; Risk sở hữu pending risk case | Không reserve, không journal |

Manual analyst resolution thuộc Phase 2 và phải có event contract mới cùng audit an
toàn theo OD-010. Nó không được giả làm một assessment tự động mới. `RISK_CHECKING`
được dùng làm trạng thái client-visible trong lúc chờ review vì state machine hiện
tại không có trạng thái review riêng; API/read model tương lai phải kèm risk decision
để giải thích vì sao payment chưa tiến.

Event do một event khác gây ra phải giữ `correlationId` và đặt `causationId` bằng
`eventId` của `payment.created`. Kafka publish phải đi qua outbox; ADR này không cấp
quyền publish trực tiếp.

Trong Phase 1B intake transaction, Payment chụp response/idempotency body ở trạng
thái ban đầu `CREATED`, sau đó chuyển aggregate sang `RISK_CHECKING`, lưu hai history
row và append `payment.created` trước commit. Vì vậy response 202/replay vẫn đúng
contract `CREATED`, còn aggregate đã sẵn sàng nhận risk result ngay khi event được
publish; không có cửa sổ event tới trước state transition.

## Consequences

### Positive

- Loại bỏ hai taxonomy cạnh tranh trong spec.
- Một event đủ cho approved, rejected và review-required.
- `policyVersion` cùng ordered `matchedRules` làm assessment có thể giải thích và
  replay theo đúng policy.
- Không cần thêm Payment status ngoài state machine đã công bố.

### Negative/trade-offs

- `RISK_CHECKING` bao gồm cả “đang đánh giá” và “đang chờ analyst”; read model cần
  thêm risk decision để phân biệt.
- Manual review chưa có event trong v1 và chưa thể hoàn tất payment cho tới Phase 2.
- Consumer phải branch theo decision và contract test đủ cả ba giá trị.

## Contract and data impact

- API: không đổi trong lát cắt này.
- Event: thêm `risk.assessment.completed` v1 trên `payflow.risk.events.v1`;
  `policyVersion` là phần bổ sung có chủ đích so với ví dụ tối thiểu trong spec §8.4.
- Database/migration: chưa có. Persistence tương lai phải unique theo `payment_id` và
  giữ score, level, decision, matched rules, policy version.
- Security/privacy: payload không chứa IP/device raw, token, email hay secret; chỉ
  chứa mã rule giải thích kết quả.
- Observability/operations: metric theo decision/level/rule code; không dùng payment
  hoặc customer ID làm metric label. Log được phép mang payment/event/correlation ID.

## Rollout and rollback

Đây là Risk event đầu tiên nên chưa có consumer production hay dữ liệu cần migrate.
Producer contract và contract test được thêm trước Kafka adapter. Rollback code được
phép khi chưa có message được publish; sau khi v1 được dùng, breaking change phải tạo
event version/topic strategy mới, không sửa nghĩa field cũ.

## Verification

- Contract test exact JSON fields và enum wire values.
- Test score chỉ nhận `0..100`, policy version/rule code không rỗng và rule không
  duplicate.
- Factory test giữ `paymentId` làm aggregate/Kafka key, producer `risk-service`,
  correlation ID và causation ID từ `payment.created`.
- Test mapping đủ `APPROVED`, `REVIEW_REQUIRED`, `REJECTED` và mọi risk level/rule.
- Khi OD-007 được resolve: Testcontainers test chứng minh inbox + assessment + outbox
  atomic, duplicate `payment.created` không tạo assessment/event thứ hai.
