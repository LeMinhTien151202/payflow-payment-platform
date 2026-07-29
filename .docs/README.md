# PayFlow — Documentation Index

Đây là điểm bắt đầu cho mọi công việc phân tích, thiết kế, triển khai và review PayFlow.

## Nguồn sự thật

Khi tài liệu hoặc implementation mâu thuẫn, dùng thứ tự sau:

1. Yêu cầu hiện tại đã được người dùng phê duyệt rõ ràng.
2. ADR đã accepted cho đúng phạm vi quyết định.
3. `PAYFLOW_MICROSERVICE_PROJECT_SPEC.md`.
4. Bộ tài liệu triển khai trong `.docs/`.
5. API/event contract, Flyway migration và automated test hiện hành.
6. Code runtime hiện tại.

Không âm thầm chọn một phía nếu xung đột có thể ảnh hưởng tiền, trạng thái, bảo mật hoặc compatibility. Ghi nhận xung đột và cập nhật các nguồn liên quan trong cùng thay đổi.

## Tài liệu bắt buộc

| Tài liệu | Nội dung |
| --- | --- |
| [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md) | Mục tiêu portfolio, phạm vi và tiêu chí thành công |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Topology, data ownership, Saga và hướng phụ thuộc |
| [MODULE_MAP.md](MODULE_MAP.md) | Service/module owner, dữ liệu, contract và phase |
| [DELIVERY_ROADMAP.md](DELIVERY_ROADMAP.md) | Thứ tự triển khai và quality gate từng giai đoạn |
| [TESTING_STRATEGY.md](TESTING_STRATEGY.md) | Test pyramid, invariant suite, E2E/chaos/load gate |
| [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) | Trạng thái thực tế và evidence đã kiểm chứng |
| [OPEN_DECISIONS.md](OPEN_DECISIONS.md) | Contract/architecture blocker phải chốt trước khi code |
| [adr/README.md](adr/README.md) | Danh mục quyết định kiến trúc và quy tắc ADR |
| [adr/ADR-TEMPLATE.md](adr/ADR-TEMPLATE.md) | Template cho quyết định mới |

## Cách đọc theo loại công việc

- Foundation/dependency: đọc **toàn bộ spec**, sau đó chú ý Overview, Architecture, Roadmap và các mục 3, 5, 17–20.
- Payment/refund: đọc Architecture, Module Map, Testing và các mục 7.4, 9, 10, 14 trong spec.
- Account/ledger: đọc Architecture, Testing và các mục 7.5–7.6, 14–15 trong spec.
- Kafka/outbox/Saga: đọc Architecture, Roadmap, Testing và các mục 8–9, 12–14 trong spec.
- Security/API key/webhook: đọc Architecture, Module Map và các mục 7.1–7.3, 7.8, 10–12 trong spec.
- Settlement/reporting: đọc Module Map, Testing và các mục 7.9–7.10, 14, 20–23 trong spec.
- Kubernetes/CI/observability: chỉ thực hiện sau quality gate tương ứng; đọc các mục 13, 17–20 và 22 trong spec.

Trước mọi feature, kiểm tra `OPEN_DECISIONS.md`; không implement phạm vi đang bị đánh dấu blocker.

## Tài liệu bàn giao trong `docs/`

`.docs/` là guidance cho agent; `docs/` là tài liệu sản phẩm. ADR chính thức, runbook, OpenAPI và event schema thuộc `docs/`, không thuộc `.docs/`.

| Tài liệu | Nội dung |
| --- | --- |
| [../docs/README.md](../docs/README.md) | Index tài liệu bàn giao |
| [../docs/adr/README.md](../docs/adr/README.md) | ADR đã viết (ADR-004, ADR-007, ADR-011–ADR-018 theo index) |
| [../docs/runbooks/local-development.md](../docs/runbooks/local-development.md) | Build, test và bật hạ tầng Docker |
| [../docs/runbooks/saga-manual-review.md](../docs/runbooks/saga-manual-review.md) | Triage và resolution an toàn cho Saga manual review |

## Trạng thái hiện tại

- Phase 1A payment intake/outbox core đã có code và test không Docker; PostgreSQL/Kafka gate vẫn chưa chạy.
- Theo yêu cầu của repository owner, Phase 1B đã bắt đầu sớm ở phạm vi core Account/Reservation, Ledger, Risk, Payment Saga và Notification email mock. Các module này chưa phải deployable và không đồng nghĩa Phase 1B đã mở gate.
- ADR-011/012/016/018 đã khóa risk decision, financial finalization, recovery gate và manual-review contract. Payment đã có Saga persistence/scheduler và Kafka consumer code với inbox + Payment/Saga + outbox transaction; PostgreSQL/Kafka runtime và E2E vẫn chưa được chạy.
- Build và test không cần Docker đã pass; hạ tầng Docker **chưa từng được start** và test cần Docker **chưa từng chạy**. Chi tiết và giới hạn nằm trong `IMPLEMENTATION_STATUS.md`.
- Không mô tả feature là hoàn thành cho tới khi có code, test và lệnh tái tạo kết quả.
- Spec vẫn là backlog tổng; `DELIVERY_ROADMAP.md` quyết định lát cắt được phép triển khai tiếp theo.
