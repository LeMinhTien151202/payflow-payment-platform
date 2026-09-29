# Chạy PayFlow Core trên máy local

## Yêu cầu

- Java 21
- Docker Desktop
- PowerShell 7 hoặc Windows PowerShell
- Cổng Gateway trong `.env` (mặc định 8084) còn trống
- PostgreSQL Compose publish ở 5433, không chiếm 5432

## Lần chạy đầu

```powershell
Copy-Item .env.example .env
./payflow.ps1 rebuild
./payflow.ps1 smoke
```

`rebuild` build sáu ứng dụng của profile `core`, đợi healthcheck và provision scope Keycloak.
`smoke` lấy service token, tạo payment, kiểm idempotency và chờ kết quả terminal.

## Các lần sau

```powershell
./payflow.ps1 start
./payflow.ps1 status
./payflow.ps1 token
./payflow.ps1 token -Client operations
./payflow.ps1 logs payment-service -Follow
./payflow.ps1 stop
```

- Console: <http://localhost:8084/console.html>
- Swagger: <http://localhost:8084/swagger-ui.html>
- Keycloak: <http://localhost:8180>

`stop` giữ volume/database. Chỉ xóa volume khi thật sự muốn mất toàn bộ dữ liệu local.

## Chỉ kiểm code, không chạy Docker

```powershell
./mvnw.cmd -B -ntp -Pno-docker verify
docker compose --env-file .env --profile core config --quiet
```

## Webhook local

Webhook bị tắt mặc định. Muốn thử, chạy một receiver ở host rồi sửa:

```dotenv
PAYFLOW_WEBHOOK_ENABLED=true
PAYFLOW_WEBHOOK_ENDPOINT_URI=http://host.docker.internal:8090/payflow-webhook
PAYFLOW_WEBHOOK_SIGNING_SECRET=replace-with-local-secret
PAYFLOW_WEBHOOK_ALLOW_UNSAFE_LOCAL_TARGETS=true
```

Notification ký body bằng HMAC và retry độc lập. Webhook lỗi không làm payment thất bại.
`ALLOW_UNSAFE_LOCAL_TARGETS=true` chỉ dành cho receiver local; không dùng với endpoint production.
