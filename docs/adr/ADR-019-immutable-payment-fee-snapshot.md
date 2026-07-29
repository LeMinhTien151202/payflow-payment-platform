# ADR-019: Đóng băng fee snapshot tại thời điểm chấp nhận payment

- Status: ACCEPTED
- Date: 2026-07-29
- Decision owners: PayFlow repository owner
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-004

## Context

Fee policy của merchant có thể thay đổi nhưng payment, ledger, refund và settlement phải tiếp tục giải
thích được đúng số tiền đã áp dụng tại thời điểm giao dịch được chấp nhận. Nếu downstream đọc
`merchant.fee_rate` hiện tại hoặc tự tính lại, cùng một payment có thể cho kết quả khác nhau theo thời
gian. Refund từng phần còn tạo sai số nếu mỗi refund tự làm tròn độc lập.

## Decision drivers

- Lịch sử tài chính không thay đổi khi cấu hình merchant thay đổi.
- Mọi số tiền dùng `BigDecimal`, scale 4 và một rounding policy có tên, theo ADR-007.
- Ledger và Settlement chỉ dùng fact bất biến, không gọi ngược Merchant để tính lại.
- Tổng fee được reverse sau full refund phải bằng chính xác fee gốc.
- Contract phải version được mà không thay nghĩa event v1 đã công bố.

## Options considered

### Option A — Downstream đọc fee hiện tại của merchant

Ít cột hơn nhưng làm thay đổi lịch sử, tạo coupling đồng bộ và không thể tái hiện settlement cũ. Bị loại.

### Option B — Chỉ lưu applied rate rồi tính lại khi cần

Giữ được rate lịch sử nhưng vẫn cho phép khác rounding hoặc khác implementation giữa Payment, Ledger và
Settlement. Bị loại.

### Option C — Payment đóng băng policy và kết quả tính

Lưu cả policy version, applied rate, rounding mode và fee amount. Tốn thêm dữ liệu nhưng biến fee thành
một financial fact kiểm toán được.

## Decision

Chọn Option C. Merchant module sở hữu cấu hình fee mutable. Payment Service đọc một
`FeePolicySnapshot` cùng merchant snapshot và tính fee đúng một lần khi chấp nhận payment:

```text
fee = grossAmount × appliedRate
feeAmount = fee.setScale(4, HALF_UP)
```

Rate có scale tối đa 6, không âm và không lớn hơn `1.000000`. Payment lưu bất biến:
`fee_policy_version`, `applied_fee_rate`, `fee_amount`, `fee_currency`, `fee_rounding_mode`.
MVP dùng basis `GROSS_PAYMENT_AMOUNT`, currency fee phải bằng currency payment và rounding mode duy nhất
là `HALF_UP`.

Refund fee dùng phép tính lũy kế, không làm tròn từng tỷ lệ độc lập:

```text
targetCumulativeReversal = originalFee × cumulativeSucceededRefund / originalAmount
targetCumulativeReversal = round(scale=4, HALF_UP)
refundFeeReversal = targetCumulativeReversal - previousCumulativeReversal
```

Khi cumulative refund bằng original amount, target được gán chính xác bằng original fee. Do đó tổng các
`refundFeeReversal` luôn bằng fee gốc sau full refund, kể cả có nhiều partial refund.

Ledger/Settlement nhận hoặc đọc snapshot này; không được đọc fee config hiện tại. Event ledger fee-aware
sẽ được phát hành bằng version mới, không đổi nghĩa payload v1.

## Consequences

### Positive

- Payment cũ không đổi khi merchant đổi rate.
- Ledger, refund và settlement dùng cùng một fact và rounding rule.
- Full refund không để lại phần fee lẻ do rounding drift.

### Negative/trade-offs

- Dữ liệu rate/version/amount bị lặp có chủ ý để bảo toàn lịch sử.
- Thay đổi fee đã áp dụng phải là adjustment/reversal mới, không UPDATE snapshot.
- Contract fee-aware cần event version mới và compatibility window.

## Contract and data impact

- API: create response chưa công bố fee; GET/payment detail có thể bổ sung fee theo additive contract sau.
- Event: event ledger fee-aware dùng version mới; v1 không được diễn giải ngầm là có fee.
- Database/migration: thêm fee config vào merchant và snapshot bất biến vào payment; payment cũ trong
  sandbox được backfill policy `LEGACY_NO_FEE_V1`, rate/fee bằng zero vì chưa từng có fee posting.
- Security/privacy: fee không phải secret nhưng mọi mutation cấu hình sau này cần audit.
- Observability/operations: metric tổng fee chỉ lấy stored fact, không gắn paymentId làm label.

## Rollout and rollback

Migration theo expand/backfill/constrain, rồi deploy reader/writer. Không rollback bằng cách xóa snapshot
đã được dùng; tắt writer mới và forward-fix. Ledger fee-aware chỉ bật sau khi consumer hiểu event version
mới.

## Verification

- Unit test zero/non-zero rate, boundary rate, scale và HALF_UP.
- Property/example test nhiều partial refund, chứng minh không âm, không reverse quá fee và full refund
  bằng chính xác fee gốc.
- PostgreSQL migration test cho backfill, constraint currency/rate/fee.
- Contract test exact JSON cho event fee-aware trước khi kết nối Kafka.
