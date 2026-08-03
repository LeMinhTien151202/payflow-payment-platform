# Sổ tay Hướng dẫn Lệnh Chạy & Khắc phục Sự cố PayFlow

Tài liệu này tổng hợp toàn bộ các lệnh cần thiết phục vụ quá trình **phát triển, kiểm thử, khởi động hệ thống sandbox MVP và khắc phục sự cố** trên môi trường Windows (PowerShell / Command Prompt).

---

## 1. 🧪 Phát triển & Kiểm thử Local (Không cần Docker)

Các lệnh dưới đây phục vụ vòng lặp lập trình hàng ngày (Fast-feedback loop), chạy trực tiếp trên máy host mà không cần khởi động hạ tầng Docker.

### 1.1. Kiểm tra phiên bản Maven Wrapper
```powershell
.\mvnw.cmd -v
```
*Xác nhận Maven Wrapper (phiên bản 3.9.16) hoạt động bình thường.*

### 1.2. Chạy Unit Test nhanh (Fast Unit Tests)
```powershell
.\mvnw.cmd -B clean test
```
*Chạy toàn bộ unit test cho domain invariants, application use cases, và policy logic (thời gian chạy chỉ vài giây).*

### 1.3. Build và Verify không cần Docker (Opt-in Gate)
```powershell
.\mvnw.cmd -B -ntp -Pno-docker clean verify
```
*Biên dịch toàn bộ 9 module monorepo, đóng gói JAR và chạy 293+ unit/slice test kèm 13 Gateway integration test mà không yêu cầu Docker daemon.*

### 1.4. Dọn dẹp build output
```powershell
.\mvnw.cmd clean
```
*Xóa sạch toàn bộ thư mục `target/` của các module để phục vụ build lại từ đầu.*

---

## 2. 🐳 Khởi động Hệ thống Sandbox MVP bằng Docker Compose

Lát cắt MVP bao gồm Gateway, Payment Service, Risk Service, Account Ledger Service, Notification Service cùng các hạ tầng PostgreSQL, Redis, Kafka và Keycloak.

### 2.1. Khởi tạo file cấu hình `.env`
```powershell
Copy-Item .env.example .env
```
*Tạo file môi trường local. Tiến hành đổi các mật khẩu `change-me-local-only` trong file `.env` nếu cần.*

### 2.2. Kiểm tra tính hợp lệ của file Compose
```powershell
docker compose --env-file .env --profile mvp config --quiet
```
*Kiểm tra cú pháp và biến môi trường trong docker-compose (Exit code `0` là hợp lệ).*

### 2.3. Khởi động toàn bộ Hệ thống MVP (Hạ tầng + 5 Microservices)
```powershell
docker compose --env-file .env --profile mvp up -d --build
```
*Tự động build Docker Image cho 5 Java microservices và khởi động 10 containers (bao gồm job `kafka-init`).*

### 2.4. Khởi động riêng Hạ tầng (Infra Only)
```powershell
docker compose --env-file .env --profile infra up -d
```
*Chỉ khởi động PostgreSQL, Redis, Kafka và Keycloak. Phù hợp khi bạn muốn chạy/debug ứng dụng Java trực tiếp từ IDE.*

### 2.5. Kiểm tra trạng thái các Containers
```powershell
docker compose --env-file .env --profile mvp ps -a
```
*Đảm bảo các container hiển thị trạng thái `healthy` (riêng `payflow-kafka-init` ở trạng thái `Exited (0)`).*

---

## 3. 🚦 Smoke Test & Kiểm tra E2E Workflow

### 3.1. Chạy MVP Smoke Test tự động
```powershell
.\infrastructure\scripts\smoke-mvp.ps1
```
*Kịch bản kiểm thử E2E: Tải cấu hình -> Kiểm tra Health endpoints -> Lấy OAuth2 Token từ Keycloak -> POST Payment 500.000 VND -> Đánh giá Risk -> Reserve Account Balance -> Post Ledger Journal -> Capture Reserve -> Xác nhận Payment `SUCCEEDED` và Notification `SENT`.*

### 3.2. Chạy Full Integration Gate với Testcontainers
```powershell
.\mvnw.cmd -B -ntp clean verify
```
*Yêu cầu Docker Desktop đang chạy. Sẽ khởi chạy container PostgreSQL/Kafka ngắn hạn để verify Flyway migration, concurrency lock, và transactional outbox/inbox logic.*

---

## 4. 🔍 Chẩn đoán & Xem Log Hệ thống

### 4.1. Xem log thời gian thực (Follow Logs) của các Microservices
```powershell
docker compose --env-file .env --profile mvp logs -f payment-service risk-service account-ledger-service notification-service
```

### 4.2. Xem 200 dòng log gần nhất của tất cả container
```powershell
docker compose --env-file .env --profile mvp logs --tail 200
```

### 4.3. Liệt kê các Kafka Topics đã khởi tạo
```powershell
docker compose --env-file .env --profile mvp exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list
```

### 4.4. Truy vấn trạng thái Transactional Outbox trực tiếp từ PostgreSQL
```powershell
docker compose --env-file .env --profile mvp exec postgres psql -U payflow -d payflow_payment -c "select id, aggregate_id, event_type, status, attempt_count, last_error from payment.outbox_events order by created_at desc limit 10;"
```

---

## 5. 🛠️ Dọn dẹp & Khắc phục Sự cố

### 5.1. Dừng hệ thống (Giữ lại Dữ liệu)
```powershell
docker compose --env-file .env --profile mvp down
```
*Dừng các container. Dữ liệu PostgreSQL, Redis và Kafka vẫn được lưu giữ trên Docker Volume.*

### 5.2. Reset hoàn toàn Sandbox (Xóa Dữ liệu)
```powershell
docker compose --env-file .env --profile mvp down -v
```
> [!CAUTION]
> Lệnh này sẽ **xóa sạch không thể khôi phục** toàn bộ Docker Volume (PostgreSQL DB, Redis cache, Kafka logs, Keycloak realm state). Chỉ sử dụng khi bạn muốn tạo lại môi trường hoàn toàn mới từ đầu.

### 5.3. Bảng tra cứu xử lý lỗi thường gặp

| Hiện tượng | Nguyên nhân | Thao tác khắc phục |
| :--- | :--- | :--- |
| **`Premature end of Content-Length...`** | Mạng bị rớt/timeout khi Maven tải thư viện JAR trong Docker build. | Run lại lệnh: `docker compose --env-file .env --profile mvp up -d --build` |
| **`Could not find a valid Docker environment`** | Docker Desktop chưa bật hoặc chưa sẵn sàng. | Khởi động Docker Desktop hoặc dùng cờ `-Pno-docker` để test local. |
| **`invalid_client` khi lấy OAuth2 Token** | Keycloak realm import chưa substitute biến secret từ `.env`. | Truy cập Keycloak Admin Console (`http://localhost:8180`) -> Clients -> `payflow-service` -> Regenerate Secret và cập nhật lại file `.env`. |
| **Port đã bị chiếm (5433, 9092, 8180, 8080)** | Có ứng dụng khác trên máy đang chiếm giữ port. | Mở file `.env` và thay đổi các hằng số port tương ứng (`POSTGRES_PORT`, `KAFKA_PORT`, ...). |
