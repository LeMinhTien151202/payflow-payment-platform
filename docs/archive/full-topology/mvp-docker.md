# Runbook: chạy PayFlow MVP bằng Docker Compose

Đây là đường chạy canonical cho lát cắt Phase 1B:

```text
Gateway -> Payment -> Risk -> Account reserve -> Ledger post
                    <- event acknowledgements <- Account capture
Payment -> Notification
```

Mục tiêu là một payment 500.000 VND từ tài khoản giả có 1.000.000 VND đi tới `SUCCEEDED`,
balance còn 500.000 VND, reservation `CAPTURED`, journal cân bằng, notification mock `SENT`, và
gửi lại cùng idempotency key không tạo payment thứ hai.

> Trạng thái bằng chứng: cấu hình có thể được validate khi Docker daemon chưa bật. Chỉ sau khi phần
> “Smoke test” chạy thành công mới được coi lát cắt là `VERIFIED_LOCAL`.

## 1. Thành phần và profile

| Profile | Thành phần | Mục đích |
| --- | --- | --- |
| `infra` | PostgreSQL, Redis, Kafka, `kafka-init`, Keycloak | Hạ tầng để chạy app từ IDE/host |
| `mvp` | Toàn bộ `infra` + Gateway, Payment, Merchant, Account-Ledger (gộp), Risk, Notification | Lát cắt E2E của runbook này |
| `full` | Toàn bộ `infra` + Gateway, Payment, Merchant, **Account** + **Ledger** tách riêng, Reporting, Risk, Notification | Topology Phase 2 — xem [`phase2-local.md`](phase2-local.md) |

Hai profile là **hai cách đóng gói cùng một nghiệp vụ**, không chạy đồng thời: `account-ledger-service`
và cặp `account-service`/`ledger-service` có `processed_events` ở database khác nhau, nên nếu bật cả
hai thì cùng một command reserve sẽ được thực hiện hai lần. Runbook này mô tả `mvp`.

`kafka-init` là job một lần và phải kết thúc với exit code `0`; nó không phải process cần luôn
healthy. Kafka tắt auto-create topic để lỗi chính tả topic không tạo một luồng dữ liệu im lặng.

Mỗi owner có database và credential riêng:

| Owner | Database | Schema chính |
| --- | --- | --- |
| payment-service | `payflow_payment` | `payment`, `merchant` (bản snapshot dùng khi `payflow.merchant-client.mode=local`) |
| merchant-service | `payflow_merchant` | `merchant` (nguồn sự thật của merchant) |
| account-ledger-service | `payflow_account_ledger` | `account`, `ledger`, `account_ledger` |
| risk-service | `payflow_risk` | `risk` |
| notification-service | `payflow_notification` | `notification` |
| Keycloak | `payflow_keycloak` | do Keycloak quản lý |

Ở profile `full` còn có `payflow_account`, `payflow_ledger` và `payflow_reporting` thay cho
`payflow_account_ledger`.

Smoke script đọc nhiều database bằng PostgreSQL superuser với vai trò test vận hành bên ngoài; code
ứng dụng không truy cập chéo database.

## 2. Điều kiện trước khi chạy

- Docker Desktop đang chạy với Linux containers và Docker Compose v2.
- Khuyến nghị dành khoảng 8 GB RAM cho Docker Desktop; Keycloak được giới hạn 1 GB.
- Các port mặc định chưa bị chiếm: `5433` (PostgreSQL), `6379` (Redis), `9092` (Kafka), `8180`
  (Keycloak), `8081` (Payment), `8082` (Account-Ledger ở `mvp` / Account ở `full`), `8083` (Risk),
  `8084` (Gateway), `8085` (Notification), `8087` (Merchant). Profile `full` dùng thêm `8086`
  (Ledger) và `8088` (Reporting). Có thể đổi port phía host trong `.env`.
- PowerShell 5.1+ hoặc PowerShell 7 để chạy smoke script.
- JDK 21 chỉ cần khi chạy Maven trên host; build image tự dùng JDK 21.

```powershell
docker version
docker compose version
```

Nếu `docker version` chỉ hiện Client hoặc báo không tìm thấy named pipe, Docker Desktop chưa sẵn sàng.

## 3. Tạo `.env`

```powershell
Copy-Item .env.example .env
notepad .env
```

Đổi mọi `change-me-local-only` thành chuỗi mạnh nhưng chỉ dùng cho sandbox. Ít nhất gồm:

- `POSTGRES_PASSWORD` và bốn `PAYFLOW_*_DB_PASSWORD`;
- `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`;
- `PAYFLOW_SERVICE_CLIENT_SECRET`, `PAYFLOW_READONLY_CLIENT_SECRET`.

Không commit `.env`; Git đã ignore file đó. Không dùng credential thật hay dữ liệu cá nhân.

OIDC có hai URL khác vai trò:

- `PAYFLOW_OIDC_ISSUER_URI=http://localhost:8180/realms/payflow` là issuer public trong claim `iss`;
- application container tải signing key qua
  `http://keycloak:8080/realms/payflow/protocol/openid-connect/certs` trên mạng nội bộ.

Spring vẫn kiểm tra `iss` khi `jwk-set-uri` được cấu hình trực tiếp. Keycloak dùng full frontend URL
qua `KC_HOSTNAME`, nên token lấy từ host khớp issuer mà container kiểm tra. Tham khảo
[Spring Security Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
[Keycloak hostname v2](https://www.keycloak.org/server/hostname) và
[Keycloak realm import](https://www.keycloak.org/server/importExport).

## 4. Validate không cần khởi động

```powershell
docker compose --env-file .env --profile infra config --quiet
docker compose --env-file .env --profile mvp config --quiet
docker compose --env-file .env --profile mvp config --services
```

Lệnh cuối phải có 10 tên: bốn infra process, `kafka-init` và năm ứng dụng.

## 5. Khởi động

Cách ngắn nhất:

```powershell
docker compose --env-file .env --profile mvp up -d --build
docker compose --env-file .env --profile mvp ps -a
```

Lần đầu sẽ lâu vì pull image và Maven tải dependency trong build stage. Kỳ vọng:

- PostgreSQL, Redis, Kafka, Keycloak và năm ứng dụng là `healthy`;
- `payflow-kafka-init` là `Exited (0)`;
- không application nào restart lặp.

Muốn quan sát hạ tầng trước khi build app:

```powershell
docker compose --env-file .env --profile infra up -d
docker compose --env-file .env --profile infra ps -a
docker compose --env-file .env --profile mvp up -d --build
```

| Thành phần | URL/port |
| --- | --- |
| Gateway API | <http://localhost:8080> |
| Payment health | <http://localhost:8081/actuator/health/readiness> |
| Account-Ledger health | <http://localhost:8082/actuator/health/readiness> |
| Risk health | <http://localhost:8083/actuator/health/readiness> |
| Notification health | <http://localhost:8085/actuator/health/readiness> |
| Keycloak | <http://localhost:8180> |
| PostgreSQL / Kafka / Redis | `localhost:5433` / `localhost:9092` / `localhost:6379` |

## 6. Smoke test xuyên dịch vụ

Trên volume mới:

```powershell
.\infrastructure\scripts\smoke-mvp.ps1
```

Máy chậm có thể tăng timeout:

```powershell
.\infrastructure\scripts\smoke-mvp.ps1 -TimeoutSeconds 300
```

Script sẽ:

1. đọc `.env` nhưng không in secret;
2. validate Compose và chờ năm health endpoint `UP`;
3. xác nhận account seed còn `1.000.000|0`;
4. lấy client-credentials token từ Keycloak;
5. POST payment 500.000 VND qua Gateway;
6. replay đúng request/key và xác nhận cùng `paymentId`;
7. poll tới `SUCCEEDED` với timeout hữu hạn;
8. kiểm tra Risk `APPROVED`, balance `500.000|0`, reservation `CAPTURED`, journal `2|0`,
   notification `SENT` và đúng một payment row.

Output cuối:

```text
MVP SMOKE PASSED: <paymentId>
Payment -> Risk -> Account reserve -> Ledger post -> Account capture -> Payment success -> Notification completed.
```

Smoke happy path cố ý chỉ chạy một lần trên fixture sạch. Lần sau dừng ở precheck vì balance đã
500.000 VND; script không tự nạp tiền và không tự xóa dữ liệu.

## 7. Dữ liệu local cố định

| Ý nghĩa | UUID | Ban đầu |
| --- | --- | --- |
| Merchant active | `11111111-1111-4111-8111-111111111111` | claim của `payflow-service` |
| Happy customer | `3beff442-7f10-4504-aab4-12d985cf3e95` | Ledger mapping |
| Happy source account | `039bedb6-b2d6-47df-aa25-2035e39136a3` | 1.000.000 VND |
| Low-balance customer | `44444444-4444-4444-8444-444444444444` | Ledger mapping |
| Low-balance source account | `55555555-5555-4555-8555-555555555555` | 100.000 VND |

Seed chỉ load bởi Spring profile `local` và dùng `ON CONFLICT DO NOTHING`; restart không ghi đè
balance hiện tại.

## 8. Log và chẩn đoán

```powershell
docker compose --env-file .env --profile mvp logs --tail 200
docker compose --env-file .env --profile mvp logs -f payment-service risk-service account-ledger-service notification-service
```

Liệt kê Kafka topic:

```powershell
docker compose --env-file .env --profile mvp exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list
```

Kiểm tra Payment outbox, thay `<paymentId>`:

```powershell
docker compose --env-file .env --profile mvp exec postgres psql -U payflow -d payflow_payment -c "select event_type,status,attempt_count,last_error from payment.outbox_events where aggregate_id='<paymentId>' order by created_at;"
```

Không sửa payload/identity trực tiếp để “chữa” outbox. Xem [outbox-recovery.md](outbox-recovery.md).

## 9. Dừng và reset

Dừng nhưng giữ dữ liệu:

```powershell
docker compose --env-file .env --profile mvp down
```

Khởi động lại với dữ liệu cũ:

```powershell
docker compose --env-file .env --profile mvp up -d
```

Reset hoàn toàn sandbox:

```powershell
docker compose --env-file .env --profile mvp down -v
```

> `down -v` xóa không thể khôi phục PostgreSQL, Kafka log và Keycloak local. Chỉ dùng khi chủ ý về
> fixture sạch. Script tạo database chỉ chạy khi PostgreSQL volume trống; volume cũ từ phiên bản
> Compose trước có thể cần reset để có đủ database/role mới.

## 10. Test Java

Không cần Docker:

```powershell
.\mvnw.cmd -B -ntp -Pno-docker clean verify
```

Khi Docker daemon đã bật, gate Testcontainers đầy đủ:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Testcontainers dùng container riêng, không dùng database Compose. Sau Maven gate vẫn cần smoke
Compose để chứng minh wiring xuyên service.

## 11. Lỗi thường gặp

| Triệu chứng | Nguyên nhân | Xử lý |
| --- | --- | --- |
| `invalid_client` | `.env` còn placeholder hoặc realm cũ giữ secret cũ | Đổi secret; cập nhật client hoặc reset volume disposable |
| JWT 401 dù lấy token được | issuer/JWKS không đồng bộ | Giữ issuer host `localhost:8180` và JWK nội bộ `keycloak:8080` |
| database `does not exist` | PostgreSQL volume có từ trước khi thêm DB | Nếu dữ liệu disposable, `down -v` rồi dựng lại |
| `kafka-init` khác exit 0 | Kafka chưa healthy hoặc topic config xung đột | Xem log `kafka` và `kafka-init` |
| Payment kẹt `PROCESSING` | consumer/outbox tắt hoặc event retry/DLT | Kiểm tra switch trong `.env`, log và DLT runbook |
| `MANUAL_REVIEW_REQUIRED` | Saga hết bounded retry | Theo [saga-manual-review.md](saga-manual-review.md), không sửa DB tùy ý |
| Notification không `SENT` | consumer/delivery tắt hoặc lease lỗi | Xem [notification-delivery-failure.md](notification-delivery-failure.md) |
| Account không pristine | happy path đã chạy | Giữ để debug hoặc chủ ý reset toàn bộ volume |

## 12. Giới hạn hiện tại

- Email là mock in-memory; `SENT` chưa chứng minh SMTP/provider thật.
- Profile `mvp` không chạy Reporting; muốn có read model và topology tách thì dùng profile `full`
  ([`phase2-local.md`](phase2-local.md)). Webhook delivery chạy ở cả hai profile, bật/tắt bằng
  `PAYFLOW_WEBHOOK_ENABLED` chứ không phụ thuộc profile.
- Chưa có observability stack, Kubernetes hay Settlement ở bất kỳ profile nào.
- Image/Compose chỉ trở thành bằng chứng runtime sau khi chính các lệnh trên chạy thành công.
