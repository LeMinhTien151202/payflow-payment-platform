# Chạy PayFlow Phase 2 ở local

Phase 2 dùng profile Compose `full`. Profile này thay `account-ledger-service` bằng hai deployable và
hai database độc lập là `account-service`/`payflow_account` và `ledger-service`/`payflow_ledger`, và
thêm `reporting-service`/`payflow_reporting`. `merchant-service` và webhook delivery chạy ở cả hai
profile; chỉ việc tách Account/Ledger và Reporting là riêng của `full`.

Không chạy `mvp` và `full` cùng lúc: hai bên có `processed_events` ở database khác nhau nên cùng một
command reserve/capture sẽ được xử lý hai lần.

## 1. Gate không cần Docker

```powershell
.\mvnw.cmd -B -ntp -Pno-docker clean verify
docker compose --env-file .env.example --profile full config --quiet
```

Maven biên dịch integration test nhưng loại test gắn tag `docker`; nó chưa chứng minh Flyway,
PostgreSQL locking hay Kafka delivery.

## 2. Nâng cấp `.env`

Script giữ mọi giá trị cũ, tạo secret ngẫu nhiên cho các biến Phase 2 còn thiếu và lưu
`.env.phase1-backup`:

```powershell
.\infrastructure\scripts\prepare-phase2-env.ps1
```

Không commit `.env`, backup, token, API key hoặc webhook signing secret.

## 3. Khởi tạo dữ liệu

### Môi trường sạch — khuyến nghị cho lần chạy Phase 2 đầu tiên

`down -v` xóa toàn bộ dữ liệu local PostgreSQL/Kafka/Keycloak. Chỉ dùng khi dữ liệu có thể tái tạo:

```powershell
docker compose --env-file .env --profile full down -v
docker compose --env-file .env --profile full up -d --build
```

Realm mới import đủ client nội bộ; PostgreSQL init tạo role/database riêng cho từng service.

### Giữ volume PostgreSQL hiện tại

```powershell
docker compose --env-file .env --profile full up -d --force-recreate postgres
docker exec payflow-postgres bash /docker-entrypoint-initdb.d/02-provision-phase2-databases.sh
```

Keycloak không ghi đè realm đã tồn tại khi `--import-realm`. Vì Phase 2 thêm service client, bạn vẫn
phải migrate realm qua Admin API hoặc reset database Keycloak. Với sandbox portfolio, môi trường sạch
ít sai sót hơn. Không reset volume chứa dữ liệu cần giữ.

## 4. Kiểm tra trạng thái

```powershell
docker compose --env-file .env --profile full ps
docker compose --env-file .env --profile full logs --tail 200 payment-service merchant-service account-service ledger-service reporting-service notification-service
```

Các service dài hạn phải `healthy`; `kafka-init` phải `Exited (0)`. Payment chỉ nhận traffic sau khi
Merchant healthy. Intake đọc policy qua REST nội bộ, timeout 3 giây và fail closed bằng mã
`PAYMENT_MERCHANT_CATALOG_UNAVAILABLE`.

Chạy lại happy-path Saga trên topology đã tách:

```powershell
.\infrastructure\scripts\smoke-mvp.ps1 -Profile full -TimeoutSeconds 300
```

Script dùng cùng assertion của MVP nhưng query Account và Ledger ở hai database riêng, nhờ đó kiểm tra
external Saga behavior không đổi sau split.

## 5. Swagger UI

Swagger chỉ bật trong profile `local`; business endpoint vẫn yêu cầu JWT:

| Service | Swagger UI | Nội dung |
| --- | --- | --- |
| Payment | `http://localhost:8081/swagger-ui.html` | payment, search, refund, manual review |
| Merchant | `http://localhost:8087/swagger-ui.html` | profile, status, member, API key, webhook |
| Notification | `http://localhost:8085/swagger-ui.html` | retry webhook đã DEAD |
| Reporting | `http://localhost:8088/swagger-ui.html` | daily report và rebuild projection |

Account/Ledger không có public business controller: chúng nhận command Kafka và chỉ mở health probe.

## 6. Gate PostgreSQL thật sau khi bật Docker

```powershell
.\mvnw.cmd -B -ntp -pl services/account-service,services/ledger-service,services/merchant-service,services/reporting-service,services/notification-service -am verify
```

Gate chứng minh concurrent reserve không làm âm số dư, refund tạo journal mới cân bằng/bất biến,
webhook retry giữ `eventId` và raw body, audit append-only, reporting rebuild tạo read model tương đương.

## 7. Điều tra nhanh

- `401/403` từ internal Merchant: realm cũ thiếu client nội bộ hoặc secret không khớp realm đã import.
- `database does not exist`: volume PostgreSQL cũ chưa chạy helper provisioning.
- Payment trả `503`: xem log Payment/Merchant; mode `remote` không fallback về policy stale.
- Webhook `DEAD`: sửa mock endpoint rồi gọi operations retry; không tạo event id mới.
- Reporting rebuild `409`: generation mới không tương đương và chưa được activate; kiểm tra DLT/schema.
