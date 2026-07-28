# PayFlow — tài liệu sản phẩm

Đây là tài liệu bàn giao cho người đọc repository. Hướng dẫn dành cho agent trong
giai đoạn xây dựng nằm ở [`.docs/`](../.docs/README.md) và
[`.agent/AGENTS.md`](../.agent/AGENTS.md); hai thư mục không thay thế nhau.

| Thư mục | Nội dung | Trạng thái |
| --- | --- | --- |
| [adr/](adr/README.md) | ADR chính thức | ADR-004, ADR-007, ADR-013–ADR-016 `ACCEPTED` |
| [runbooks/](runbooks/local-development.md) | Vận hành và chạy local | Có runbook local-development |
| [api/](api/payment-service-v1.yaml) | OpenAPI contract | Payment create/get v1 |
| [events/](events/risk-assessment-completed-v1.md) | Kafka event contract | Risk assessment completed và payment failed v1 |
| `architecture/` | Sơ đồ và mô tả kiến trúc bàn giao | Chưa có |
| `diagrams/` | Sequence/state diagram | Chưa có |
| `postman/` | Collection để thử API | Chưa có — Phase 1A |

Bắt đầu từ đâu:

- Muốn chạy dự án: [runbooks/local-development.md](runbooks/local-development.md).
- Muốn hiểu vì sao stack là như vậy:
  [adr/ADR-013](adr/ADR-013-platform-version-baseline.md) và
  [adr/ADR-007](adr/ADR-007-postgresql-money-representation.md).
- Muốn biết cái gì đã thật sự được kiểm chứng:
  [`.docs/IMPLEMENTATION_STATUS.md`](../.docs/IMPLEMENTATION_STATUS.md). Không có
  capability nào được coi là hoàn thành nếu thiếu command và kết quả tái tạo được.
