# PayFlow — tài liệu sản phẩm

Đây là tài liệu bàn giao cho người đọc repository. Hướng dẫn dành cho agent trong
giai đoạn xây dựng nằm ở [`.docs/`](../.docs/README.md) và
[`.agent/AGENTS.md`](../.agent/AGENTS.md); hai thư mục không thay thế nhau.

| Thư mục | Nội dung | Trạng thái |
| --- | --- | --- |
| [adr/](adr/README.md) | ADR chính thức | ADR-004, ADR-007, ADR-011–ADR-021 (các ID có file) `ACCEPTED` |
| [runbooks/](runbooks/mvp-docker.md) | Vận hành và chạy local | Docker MVP, local development, Saga manual review, outbox recovery, Payment workflow DLT và Notification delivery failure |
| [api/](api/payment-service-v1.yaml) | OpenAPI contract | Payment create/get và refund intake v1 |
| [events/](events/refund-workflow-v1.md) | Kafka event contract | Payment Saga, refund intake và refund financial workflow v1 |
| [architecture/](architecture/business-processing-reference.md) | Nghiệp vụ, cấu hình, source layout và call flow | Có business reference và code-flow guide |
| `diagrams/` | Sequence/state diagram | Chưa có |
| `postman/` | Collection để thử API | Chưa có — Phase 1A |

Bắt đầu từ đâu:

- Mới vào dự án và thấy luồng rối, muốn hiểu nghiệp vụ thật + luồng code + dữ liệu truyền giữa các service
  theo đúng code hiện tại:
  [architecture/current-business-code-flow-guide.md](architecture/current-business-code-flow-guide.md).
- Muốn chạy toàn bộ MVP bằng Docker: [runbooks/mvp-docker.md](runbooks/mvp-docker.md).
- Muốn hiểu toàn bộ nghiệp vụ đang chạy, cấu hình và vì sao chọn từng phương pháp:
  [architecture/business-processing-reference.md](architecture/business-processing-reference.md).
- Muốn lần source từ Controller/Kafka đến domain, transaction, adapter và database:
  [architecture/code-flow-guide.md](architecture/code-flow-guide.md).
- Muốn xem request đi qua chính xác file nào, Keycloak/phân quyền ra sao và từng handler xử lý gì:
  [architecture/payment-api-code-walkthrough.md](architecture/payment-api-code-walkthrough.md).
- Muốn hiểu `libs`, event contract, correlation/error convention và luồng `.env` → Compose → `application.yml`:
  [architecture/shared-libraries-and-runtime-configuration-guide.md](architecture/shared-libraries-and-runtime-configuration-guide.md).
- Muốn tra toàn bộ package/file trong năm service và biết request/event đi qua chúng thế nào:
  [architecture/services-folder-reference.md](architecture/services-folder-reference.md).
- Muốn hiểu `infrastructure`, root config, CI, `docs/.docs` và agent/governance folders:
  [architecture/infrastructure-and-repository-folder-reference.md](architecture/infrastructure-and-repository-folder-reference.md).
- Muốn học từng công nghệ đang dùng và biết dependency/config/code của Kafka, Keycloak, PostgreSQL, Redis, Swagger, Docker, test:
  [architecture/technology-stack-configuration-guide.md](architecture/technology-stack-configuration-guide.md).
- Mới làm quen event-driven, muốn hiểu REST dùng ở đâu và Kafka xử lý bước nào:
  [architecture/rest-kafka-flow-guide.md](architecture/rest-kafka-flow-guide.md).
- Muốn chạy app từ IDE/host: [runbooks/local-development.md](runbooks/local-development.md).
- Muốn xử lý Saga bị dừng: [runbooks/saga-manual-review.md](runbooks/saga-manual-review.md).
- Muốn triage/replay poison event: [runbooks/payment-workflow-dlt.md](runbooks/payment-workflow-dlt.md).
- Muốn kiểm tra/requeue outbox bị lỗi: [runbooks/outbox-recovery.md](runbooks/outbox-recovery.md).
- Muốn triage Notification delivery lỗi: [runbooks/notification-delivery-failure.md](runbooks/notification-delivery-failure.md).
- Muốn hiểu vì sao stack là như vậy:
  [adr/ADR-013](adr/ADR-013-platform-version-baseline.md) và
  [adr/ADR-007](adr/ADR-007-postgresql-money-representation.md).
- Muốn biết cái gì đã thật sự được kiểm chứng:
  [`.docs/IMPLEMENTATION_STATUS.md`](../.docs/IMPLEMENTATION_STATUS.md). Không có
  capability nào được coi là hoàn thành nếu thiếu command và kết quả tái tạo được.
