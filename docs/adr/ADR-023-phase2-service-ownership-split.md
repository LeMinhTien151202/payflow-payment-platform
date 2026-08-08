# ADR-023: Phase 2 tách Account, Ledger và Merchant ownership

- Status: ACCEPTED
- Date: 2026-08-07

## Context

MVP giữ Account/Ledger trong một deployable và merchant catalog trong Payment để giảm chi phí vận hành.
Giữ cách đọc database này ở Phase 2 sẽ làm service mới chỉ mang tính trang trí, không tạo ownership thật.

## Decision

- Account, Ledger và Merchant có deployable, role và database riêng.
- Profile `mvp` còn `account-ledger-service`; profile `full` chạy hai service đã tách.
- Kafka contract v1, aggregate key và Payment Saga state machine không đổi khi tách deployable.
- Payment lấy status/currency/limit/fee qua internal Merchant REST bằng client least-privileged, timeout
  hữu hạn; snapshot policy ngay khi accept.
- Merchant 404 là chưa đăng ký. Network/5xx/auth failure trả 503; không fallback sang bảng stale.
- Ledger chặn UPDATE/DELETE journal, entry và posting; sửa sai bằng reversal journal mới.

## Consequences

Phase 2 có thêm network hop và service credential nhưng thực thi database-per-service. Realm/volume cũ
cần migration hoặc reset có chủ đích trước khi chạy profile `full`.

## Verification gate

- contract/unit gate không Docker;
- Testcontainers cho Account locking và immutable/balanced Ledger;
- full Compose smoke giữ external Payment Saga behavior;
- Payment–Merchant adapter test bearer token và policy snapshot.
