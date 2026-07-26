# Tổng quan dự án PayFlow

## Mục tiêu

PayFlow là nền tảng thanh toán giả lập dùng để chứng minh năng lực Java backend, hệ thống phân tán và vận hành production-like. Dự án không tối ưu cho số lượng service; dự án tối ưu cho bằng chứng rằng một workflow tài chính nhỏ vẫn đúng khi request bị retry, event bị giao trùng hoặc service chết giữa chừng.

## Giá trị portfolio cần chứng minh

1. API idempotent không tạo payment/refund trùng.
2. Số dư không âm dưới tải đồng thời.
3. Ledger double-entry bất biến và luôn cân bằng.
4. Database commit không làm mất event nhờ Transactional Outbox.
5. Event at-least-once không tạo business side effect trùng nhờ inbox/processed event.
6. Saga compensation khôi phục reservation khi bước ledger thất bại.
7. Một giao dịch được truy vết xuyên Gateway, Kafka và các service.
8. Docker Compose, CI và test tự động tái tạo được demo.
9. Chỉ số throughput/latency/recovery được đo, không bịa cho CV.

## Ranh giới hệ thống

### Trong phạm vi

- Merchant/customer/operations identity qua Keycloak.
- Payment, balance reservation/capture/release, double-entry ledger.
- Rule-based risk assessment.
- Refund toàn phần/một phần.
- Notification và webhook mock có HMAC/retry.
- Settlement, reconciliation và reporting read model ở phase sau.
- Metrics, logs, traces, CI/CD, Docker và Kubernetes theo roadmap.

### Ngoài phạm vi

- Tiền, thẻ, KYC hoặc tài khoản ngân hàng thật.
- PCI DSS production certification.
- Card network/bank integration.
- Machine-learning fraud model, crypto hoặc multi-region active-active trong bản portfolio cốt lõi.
- Frontend đẹp trước khi backend workflow và failure recovery được chứng minh.

## MVP được phép

MVP gồm `api-gateway`, `payment-service`, `account-ledger-service`, `risk-service` và `notification-service`. Account và Ledger được phép tạm gộp để hoàn thành vertical slice; ranh giới domain vẫn phải rõ để tách ở phase 2 mà không đổi contract nghiệp vụ.

Luồng demo tối thiểu:

```text
create payment
 -> risk decision
 -> reserve funds
 -> post balanced journal
 -> finalize capture/payment outcome according to accepted ADR-011
 -> persist notification/webhook delivery
```

Failure path bắt buộc trước khi mở rộng:

```text
reserve succeeded
 -> ledger fails after bounded retry
 -> Saga requests release
 -> reservation released exactly once
 -> Saga/payment outcome follows resolved OD-006 contract
 -> trace and metrics explain what happened
```

## Stack đích

- Java 21, Maven, Spring Boot/Spring Cloud version đã được xác minh và khóa trong parent POM.
- Spring MVC cho business services; Spring Cloud Gateway WebFlux ở edge.
- PostgreSQL + Flyway, Kafka, Redis, Keycloak.
- Micrometer/Prometheus, OpenTelemetry, Grafana, Tempo và Loki.
- JUnit 5, AssertJ, Mockito, Testcontainers, WireMock, REST Assured và k6.
- Docker Compose trước; Kubernetes chỉ sau production-like gate.

## Tiêu chí thành công

Một milestone chỉ có giá trị portfolio khi có:

- scenario chạy được từ môi trường sạch;
- automated test cho invariant và failure path;
- log/metric/trace giúp giải thích workflow;
- tài liệu contract và lệnh demo;
- kết quả đo có script, cấu hình và ngày chạy đi kèm.
