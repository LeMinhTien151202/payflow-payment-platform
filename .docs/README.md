# PayFlow Core — agent documentation index

## Nguồn sự thật

Khi có mâu thuẫn, ưu tiên:

1. Yêu cầu hiện tại đã được người dùng phê duyệt.
2. Accepted ADR cho đúng phạm vi.
3. Contract/migration/test và code runtime hiện tại.
4. `.docs` và spec tổng.

Không tự chọn một phía nếu xung đột ảnh hưởng money invariant, trạng thái, security hoặc compatibility.

## Tài liệu hiện hành

| File | Nội dung |
| --- | --- |
| [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md) | Phạm vi portfolio Core |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Topology, Saga, ownership và security |
| [MODULE_MAP.md](MODULE_MAP.md) | Sáu deployable, library và event ownership |
| [TESTING_STRATEGY.md](TESTING_STRATEGY.md) | Test pyramid và invariant gate |
| [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) | Evidence theo ngày; các entry cũ là lịch sử |
| [OPEN_DECISIONS.md](OPEN_DECISIONS.md) | Quyết định còn mở |
| [../docs/architecture/payflow-core-guide.md](../docs/architecture/payflow-core-guide.md) | Tài liệu học chính |
| [../docs/runbooks/core-local.md](../docs/runbooks/core-local.md) | Chạy Core local |

`DELIVERY_ROADMAP.md` và `PAYFLOW_MICROSERVICE_PROJECT_SPEC.md` giữ lịch sử/backlog của topology
đầy đủ. Chúng không được dùng để tự động thêm lại service đã loại khỏi nhánh `portfolio-core`.

## Runtime hiện tại

- Profile duy nhất: `core`.
- Deployable: API Gateway, Payment, Risk, Account-Ledger, Notification, Settlement.
- Đã bỏ: Merchant, Reporting, Account tách và Ledger tách.
- Không có Kubernetes trong runtime hiện tại.
- Merchant policy là dữ liệu cục bộ của Payment.
- Reporting riêng được thay bằng payment query và settlement/reconciliation.

Trước khi gọi một capability là verified, phải ghi command và kết quả tái tạo vào
`IMPLEMENTATION_STATUS.md`.
