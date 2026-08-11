#!/bin/bash
#
# Khởi tạo một database riêng cho từng service, mỗi database được sở hữu bởi role của chính nó.
#
# ARCHITECTURE.md bắt buộc mô hình database-per-service: một service không được đọc bảng của service khác,
# và các role riêng biệt là thứ khiến cho vi phạm đó thất bại ngay ở bước kết nối thay vì ở bước code review.
#
# Entrypoint của PostgreSQL chạy các file trong thư mục này đúng 1 lần, khi data volume
# đang rỗng. Để chạy lại: docker compose down -v && docker compose up -d
#
# Đây là file .sh chứ không phải file .sql vì file .sql đơn thuần không nhận biến môi trường,
# và các mật khẩu phải đến từ môi trường thay vì từ một file đã commit.

set -euo pipefail

# CREATE ROLE không thể nhận bind parameter cho PASSWORD, nên giá trị được interpolate. Việc này chỉ
# chấp nhận được vì lệnh chạy trên container local có thể hủy bỏ với giá trị từ file .env; tuyệt đối không tái sử dụng
# pattern này ở nơi mà input không hoàn toàn nằm dưới quyền kiểm soát.
create_service_database() {
    local database="$1"
    local role="$2"
    local password="$3"

    echo "provisioning database ${database} owned by ${role}"

    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<-EOSQL
        CREATE ROLE "${role}" WITH LOGIN PASSWORD '${password}';
        CREATE DATABASE "${database}" OWNER "${role}";

        -- PUBLIC mặc định có thể kết nối tới bất kỳ database nào. Việc REVOKE ngăn role của một service trong
        -- tương lai truy cập vào database này chỉ vì nó tồn tại trên cùng một server.
        REVOKE ALL ON DATABASE "${database}" FROM PUBLIC;
        GRANT CONNECT, TEMPORARY ON DATABASE "${database}" TO "${role}";
EOSQL
}

# payment-service. Flyway tạo và sở hữu schema "payment" bên trong database này.
create_service_database \
    "payflow_payment" \
    "${PAYFLOW_PAYMENT_DB_USERNAME}" \
    "${PAYFLOW_PAYMENT_DB_PASSWORD}"

# account-ledger-service. Phiên bản MVP giữ Account và Ledger là các schema riêng biệt bên trong 1 deployable này,
# nhưng không có service nào khác nhận được role này.
create_service_database \
    "payflow_account_ledger" \
    "${PAYFLOW_ACCOUNT_LEDGER_DB_USERNAME}" \
    "${PAYFLOW_ACCOUNT_LEDGER_DB_PASSWORD}"

# Phase 2 split owners. The legacy database remains for the reproducible MVP profile only.
create_service_database \
    "payflow_account" \
    "${PAYFLOW_ACCOUNT_DB_USERNAME}" \
    "${PAYFLOW_ACCOUNT_DB_PASSWORD}"

create_service_database \
    "payflow_ledger" \
    "${PAYFLOW_LEDGER_DB_USERNAME}" \
    "${PAYFLOW_LEDGER_DB_PASSWORD}"

create_service_database \
    "payflow_merchant" \
    "${PAYFLOW_MERCHANT_DB_USERNAME}" \
    "${PAYFLOW_MERCHANT_DB_PASSWORD}"

create_service_database \
    "payflow_reporting" \
    "${PAYFLOW_REPORTING_DB_USERNAME}" \
    "${PAYFLOW_REPORTING_DB_PASSWORD}"

create_service_database \
    "payflow_settlement" \
    "${PAYFLOW_SETTLEMENT_DB_USERNAME}" \
    "${PAYFLOW_SETTLEMENT_DB_PASSWORD}"

# risk-service. Redis đóng vai trò ephemeral signal cache; PostgreSQL sở hữu durable assessments/inbox.
create_service_database \
    "payflow_risk" \
    "${PAYFLOW_RISK_DB_USERNAME}" \
    "${PAYFLOW_RISK_DB_PASSWORD}"

# notification-service sở hữu trạng thái delivery và durable consumer inbox của nó.
create_service_database \
    "payflow_notification" \
    "${PAYFLOW_NOTIFICATION_DB_USERNAME}" \
    "${PAYFLOW_NOTIFICATION_DB_PASSWORD}"

# Keycloak. Không phải service của PayFlow, nhưng nó cần bộ nhớ lưu trữ bền vững để các chỉnh sửa realm local không bị mất khi
# khởi động lại.
create_service_database \
    "payflow_keycloak" \
    "${KEYCLOAK_DB_USERNAME}" \
    "${KEYCLOAK_DB_PASSWORD}"

# Các service được thêm vào ở các phase sau mỗi service sẽ nhận một entry riêng ở đây. Không để một service mới dùng chung
# database hiện có.

echo "database provisioning complete"
