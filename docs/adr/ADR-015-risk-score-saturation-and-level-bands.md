# ADR-015: Risk score v1 dùng saturation và level band cố định

- Status: ACCEPTED
- Date: 2026-07-27
- Decision owners: Repository owner (Tien le)
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-009

## Context

Spec §7.7 công bố score trong khoảng `0..100` và decision threshold `0..39`,
`40..69`, `70..100`, nhưng tổng bảy rule mẫu có thể đạt 210 điểm. Nếu cộng thẳng,
schema/event contract bị phá. Nếu tự chia theo tổng trọng số, cùng một payment có thể
đổi score chỉ vì một rule mới được thêm vào policy sau này.

Risk rule engine Phase 1B phải deterministic và test được trước khi Redis, PostgreSQL
hay Kafka được kết nối, nên normalization và level band phải là contract rõ ràng thay
vì một chi tiết nằm ẩn trong code.

## Decision drivers

- Giữ contract công khai `score` từ 0 đến 100.
- Một rule giữ nguyên số điểm spec đã gán; thêm rule mới không làm giảm điểm của rule cũ.
- Cùng input và cùng policy version luôn trả cùng matched rules, score, level và decision.
- Threshold dễ giải thích trong CV/demo và không cần floating-point hay rounding.
- Nhiều dấu hiệu rủi ro cùng lúc không được overflow hoặc tạo score ngoài schema.

## Options considered

### Option A — Saturation `min(rawScore, 100)` (chọn)

Cộng nguyên điểm của từng matched rule rồi cap tại 100.

- Ưu: giữ nguyên trọng số và threshold trong spec; số nguyên, deterministic; thêm rule
  không làm giảm score lịch sử khi chạy lại cùng bộ rule cũ.
- Nhược: các tổ hợp raw score 100, 150 và 210 cùng hiển thị 100; độ phân giải ở vùng
  rủi ro rất cao bị mất.
- Giảm thiểu: `matchedRules` là bằng chứng giải thích assessment và đủ để tái dựng raw
  sum của đúng policy version.

### Option B — Normalize theo tổng trọng số hiện tại

`score = rawScore * 100 / maxPolicyScore`.

- Ưu: giữ độ phân giải trên toàn thang.
- Nhược: thêm/bớt một rule đổi mẫu số và làm score của các tổ hợp cũ thay đổi dù input
  không đổi; cần rounding policy; các threshold trong spec mất ý nghĩa ban đầu.

### Option C — Nới score vượt 100 và dùng `score >= 70`

- Ưu: giữ mọi thông tin raw.
- Nhược: phá schema/event contract 0–100 và khiến level `CRITICAL` không có upper bound;
  mọi consumer phải thay contract chỉ để phục vụ implementation nội bộ.

## Decision

Risk policy v1 áp dụng:

```text
rawScore = SUM(points of matched rules)
score    = min(rawScore, 100)

0-19    -> LOW      + APPROVED
20-39   -> MEDIUM   + APPROVED
40-69   -> HIGH     + REVIEW_REQUIRED
70-100  -> CRITICAL + REJECTED
```

Điểm rule giữ nguyên spec §7.7. Rule được đánh giá theo thứ tự enum cố định để
`matchedRules` serialized ổn định. Score dùng integer; không có phép chia hoặc
rounding.

Chỉ normalized `score` thuộc persistence/event contract. `policyVersion` và matched
rules phải được lưu cùng assessment để kết quả cũ vẫn giải thích được sau khi policy
thay đổi. Không suy diễn từ ADR này rằng taxonomy event Risk→Payment đã được
chốt: OD-003 vẫn `OPEN`, vì vậy chưa tạo event hoặc Kafka consumer/producer.

## Consequences

### Positive

- Score luôn phù hợp constraint 0–100.
- Không có rounding và không phụ thuộc thứ tự evaluation.
- Boundary decision/level có thể kiểm tra bằng unit test thuần Java.
- Người vận hành vẫn giải thích được score 100 qua danh sách matched rules.

### Negative/trade-offs

- Mất phân giải raw score trên 100.
- Nếu sau này cần ranking chi tiết trong vùng CRITICAL, phải thêm field/version mới;
  không được âm thầm đổi nghĩa field `score` v1.
- Level band là contract mới phải version khi thay đổi.

## Contract and data impact

- API: chưa có Risk API trong lát cắt này.
- Event: OD-003 vẫn chặn event. Event tương lai chỉ được mang normalized `score` và
  level/decision theo band trên; việc expose `policyVersion` được chốt cùng OD-003.
- Database/migration: chưa có migration. Bảng tương lai phải có `CHECK score BETWEEN
  0 AND 100`, `policy_version` và `matched_rules`.
- Security/privacy: context/rule result không chứa token, secret hoặc payload thanh
  toán thô; không log toàn bộ input.
- Observability/operations: metric tương lai nên đếm decision và matched rule code,
  không dùng customer/payment ID làm metric label.

## Rollout and rollback

Đây là policy đầu tiên nên chưa có dữ liệu cần migrate. Nếu thay policy, tạo version
mới và giữ khả năng đọc assessment cũ; không viết lại score lịch sử. Rollback code
được phép vì chưa có persistence/event, nhưng ADR vẫn giữ làm lịch sử quyết định.

## Verification

- Unit test từng rule ở ngay dưới/trên boundary.
- Unit test score `39/40/69/70` và level/decision tương ứng.
- Unit test tất cả rule cùng match: raw sum lớn hơn 100 nhưng score đúng 100.
- Unit test cùng input chạy lặp lại cho kết quả bằng nhau và matched rule order ổn định.
- Khi thêm persistence: PostgreSQL constraint test cho `0..100` và unique
  `payment_id` bằng Testcontainers.
- Khi OD-003 được resolve: contract test serialized event với normalized score.
