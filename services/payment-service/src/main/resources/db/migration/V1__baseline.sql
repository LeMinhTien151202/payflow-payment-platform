-- PayFlow payment-service schema baseline.
--
-- Phase 0 chỉ thiết lập quyền sở hữu (ownership) và cơ chế migration. Gate Phase 0 trong
-- DELIVERY_ROADMAP.md cấm các cấu trúc thanh toán nghiệp vụ tại mốc này, nên chưa có bảng payment,
-- idempotency, outbox hay Saga nào được tạo ở đây. Các bảng đó đến trong Phase 1A cùng với
-- các constraint và index bảo vệ invariants của chúng.
--
-- Bản thân Flyway tự tạo schema (spring.flyway.create-schemas). Migration này ghi nhận ai sở hữu
-- nó, để người đọc sau này không phải suy đoán ownership từ file cấu hình.

COMMENT ON SCHEMA payment IS
    'Sở hữu duy nhất bởi payment-service. Không có service nào khác được phép đọc hoặc ghi các bảng này; '
    'dữ liệu xuyên context chỉ di chuyển qua hợp đồng REST hoặc Kafka events.';
