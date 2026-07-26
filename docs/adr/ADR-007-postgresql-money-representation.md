# ADR-007: PostgreSQL với `NUMERIC(19,4)` và `BigDecimal` cho mọi giá trị tiền

- Status: ACCEPTED
- Date: 2026-07-26
- Decision owners: Repository owner (Tien le)
- Supersedes: N/A
- Superseded by: N/A

## Context

PayFlow ghi số tiền ở payment, refund, ledger entry, fee và settlement. Sai một
đơn vị nhỏ nhất ở bất kỳ điểm nào cũng phá vỡ invariant "tổng debit bằng tổng
credit" của `.agent/AGENTS.md`, và loại lỗi này không tự lộ ra: nó tích luỹ im
lặng cho tới lúc reconciliation lệch.

Ba quyết định thường bị gộp làm một và cần tách rõ:

1. Dùng hệ quản trị CSDL nào.
2. Biểu diễn tiền bằng kiểu gì trong database.
3. Biểu diễn tiền bằng kiểu gì trong Java.

Quyết định lúc này là bắt buộc vì `spring.jpa.hibernate.ddl-auto=validate` đã được
bật ở Phase 0: Hibernate sẽ so mapping với schema đã migrate và fail startup khi
lệch. Chọn kiểu tiền sau khi đã có bảng nghĩa là phải viết migration đổi kiểu cột
trên dữ liệu đang có — chính xác là loại migration mà một hệ thống tài chính không
nên phải làm.

Chủ repository đã cân nhắc chuyển sang Oracle và quyết định giữ PostgreSQL; ADR
này ghi lại cả lựa chọn đó và lý do.

## Decision drivers

- Invariant kép-vào-sổ (`AGENTS.md` §5): mọi phép cộng/trừ tiền phải chính xác
  tuyệt đối, không có sai số biểu diễn.
- Test phải chạy trên đúng engine của production. `.docs/TESTING_STRATEGY.md` cấm
  H2, nên engine được chọn phải chạy được trong Testcontainers trên máy dev.
- Database-per-service (ADR-005) cần chi phí vận hành thấp cho nhiều database.
- Phase 1A cần `SELECT ... FOR UPDATE`, partial unique index và `JSONB` cho outbox
  payload/audit snapshot.
- Licensing: đây là dự án portfolio, không có ngân sách license.

## Options considered

### Option A — PostgreSQL + `NUMERIC(19,4)` + `BigDecimal` (chọn)

- Ưu: `NUMERIC` là decimal chính xác tuỳ ý, không phải nhị phân — `0.1 + 0.2` cho
  đúng `0.3`. `BigDecimal` là ánh xạ Java tự nhiên và Hibernate map hai bên không
  cần converter. Có sẵn `FOR UPDATE`, partial unique index, `JSONB`,
  `INSERT ... ON CONFLICT`, đúng những gì Phase 1A cần. Testcontainers khởi động
  một instance sạch trong vài giây, nên test chạy trên đúng engine production.
  Miễn phí, image chính thức nhỏ.
- Nhược: `NUMERIC` chậm hơn số nguyên/floating point. Ở khối lượng của PayFlow,
  điều này không đo được.
- Rủi ro: `BigDecimal.equals()` so cả scale, nên `2.50` không `equals` `2.5`. Phải
  dùng `compareTo()` khi so tiền — đây là bug thật và cần được chặn bằng test.

### Option B — Oracle Database (Free/XE) + `NUMBER(19,4)`

- Ưu: `NUMBER` cũng là decimal chính xác, nên tính tiền không kém an toàn.
  Kinh nghiệm Oracle có giá trị trên thị trường tuyển dụng ngành tài chính.
- Nhược: image nặng hơn nhiều và khởi động chậm hơn nhiều, nên vòng lặp
  Testcontainers dài ra ở mọi lần chạy `verify`. Không có `JSONB` tương đương trực
  tiếp cho outbox payload. Không có `INSERT ... ON CONFLICT`, nên inbox
  insert-if-new (OD-007) phải dùng cơ chế khác. Flyway community hỗ trợ Oracle
  nhưng nhiều lệnh DDL không portable. `SERIAL`/`IDENTITY`, `RETURNING` và kiểu
  boolean khác nhau đủ để mọi migration phải viết riêng.
- Rủi ro: thời gian dành cho việc thích nghi Oracle bị lấy khỏi thời gian dành cho
  Saga, outbox và failure recovery — vốn là phần thực sự thể hiện năng lực trong
  portfolio này. Edition Free có giới hạn tài nguyên; nếu về sau muốn chạy load
  test thì giới hạn đó thành một biến số phải giải thích.

### Option C — Số nguyên minor unit (`BIGINT` cents) + `long`

- Ưu: nhanh nhất, chính xác tuyệt đối với phép cộng/trừ, không có bẫy scale.
- Nhược: tiền tệ không có 2 chữ số thập phân (JPY 0, KWD/BHD 3) buộc phải mang
  theo exponent của từng currency và mọi phép tính đều phải qua đó. Fee theo tỉ lệ
  phần trăm sinh ra phân số phải làm tròn thủ công ở mọi call site, tức là chuyển
  rounding policy từ một chỗ thành nhiều chỗ.
- Rủi ro: rounding rải rác là nguồn sai lệch reconciliation kinh điển. Đúng cho hệ
  thống có một currency và một chính sách rounding, không đúng cho một hệ thống
  đang xây fee/refund/settlement.

### Option D — `DOUBLE PRECISION` / `double`

Loại ngay, ghi lại để không ai đề xuất lại: IEEE-754 nhị phân không biểu diễn được
`0.01`. Bất kỳ hệ thống tiền dùng floating point đều sai, chỉ là chưa lộ.

## Decision

Dùng **PostgreSQL 17.10** làm engine cho mọi service database. Mọi giá trị tiền là
**`NUMERIC(19,4)`** trong database và **`java.math.BigDecimal`** trong Java. Mọi
cột tiền là `NOT NULL` và đi kèm một cột currency `CHAR(3)` (ISO 4217).

Phạm vi: toàn bộ service database của PayFlow, mọi phase.

Quy tắc bắt buộc kèm theo:

- Không dùng `float`/`double`/`Double`/`REAL`/`DOUBLE PRECISION` cho tiền, kể cả
  trong DTO, event payload hay test fixture.
- So sánh tiền bằng `compareTo()`, không bằng `equals()`.
- `scale` 4, không phải 2: fee theo tỉ lệ và phân bổ refund cần chữ số trung gian.
  Rounding sang đơn vị nhỏ nhất của currency chỉ xảy ra ở ranh giới đã được định
  nghĩa, không ở giữa chuỗi tính toán.
- Amount không được là số âm ở API/event level; hướng của dòng tiền được biểu
  diễn bằng debit/credit ở ledger, không bằng dấu của số.
- Không thay PostgreSQL bằng H2 trong bất kỳ test nào
  (`.docs/TESTING_STRATEGY.md`).

Điều **không** được suy diễn từ ADR này:

- Nó **không** quyết định rounding policy, fee snapshot lịch sử hay cách refund
  phân bổ/reverse fee. Đó là **OD-004**, vẫn `OPEN`, và vẫn chặn scope fee/refund
  economics/settlement. `scale = 4` chỉ cấp chỗ chứa; nó không định nghĩa quy tắc
  làm tròn.
- Nó **không** quyết định inbox insert-if-new semantics (**OD-007**) dù
  `ON CONFLICT` được nêu như một lợi thế của PostgreSQL.
- Nó **không** phê duyệt bất kỳ bảng nghiệp vụ nào. Phase 0 baseline migration cố
  tình không tạo bảng nào (xem `DELIVERY_ROADMAP.md`).

## Consequences

### Positive

- Số tiền chính xác từ tầng lưu trữ trở lên, không phụ thuộc kỷ luật của call site.
- Test tài chính chạy trên đúng engine production, nên kết quả nói về database mà
  người dùng thật sự chạy.
- `JSONB`, `FOR UPDATE`, partial unique index, `ON CONFLICT` có sẵn cho outbox,
  inbox, idempotency và audit ở các phase sau.
- Nhiều database cho nhiều service không phát sinh chi phí license hay vận hành
  đáng kể.

### Negative/trade-offs

- Bẫy `BigDecimal.equals()` là thật và phải được chặn bằng review + test.
- `NUMERIC` chậm hơn số nguyên; nếu sau này có load test cho thấy nó là bottleneck
  thì cần một ADR mới, không phải một tối ưu tại chỗ.
- Test tích hợp phụ thuộc Docker daemon. Đây là lý do phần security và error
  contract của Phase 0 được chứng minh bằng slice test không cần Docker.
- Migration bị khoá vào phương ngữ PostgreSQL; đổi engine về sau là viết lại toàn
  bộ Flyway script.

## Contract and data impact

- API: field tiền serialize dưới dạng JSON number có scale cố định hoặc string;
  contract cụ thể do OpenAPI trong `docs/api/` chốt ở Phase 1A. Client không được
  giả định `double`.
- Event: payload tiền phải giữ nguyên precision khi đi qua Kafka. Không dùng kiểu
  floating point trong event schema.
- Database/migration: mọi cột tiền là `NUMERIC(19,4) NOT NULL`. Phase 0
  `V1__baseline.sql` chưa tạo bảng nghiệp vụ nào, nên ràng buộc này áp dụng từ
  migration đầu tiên của Phase 1A trở đi và không cần backfill.
- Security/privacy: không. Số tiền không phải secret; credential database lấy từ
  biến môi trường và không có default cho password (`AGENTS.md` §8).
- Observability/operations: một database mỗi service (`payflow_payment`,
  `payflow_keycloak`, ...) trên cùng một PostgreSQL instance ở local; mỗi service
  có role riêng và `REVOKE ALL ... FROM PUBLIC`. Health check của service phụ
  thuộc database reachability.

## Rollout and rollback

Đã rollout ở Phase 0: `docker-compose.yml` pin `postgres:17.10-alpine`;
`infrastructure/docker/postgres/init/01-create-databases.sh` tạo database + role
cho từng service; `services/payment-service/.../V1__baseline.sql` tạo schema
`payment` với comment ghi quyền sở hữu.

Chưa có dữ liệu nên rollback hiện tại là xoá volume. Sau Phase 1A, đổi engine
không còn là rollback mà là một migration project riêng, cần ADR mới và cần dump
dữ liệu trước khi bắt đầu. Không thay đổi kiểu cột tiền trên bảng đang có dữ liệu
mà không có ADR và không có bước verify tổng số dư trước/sau.

## Verification

Đã chạy thật:

```bash
docker compose --env-file .env.example config --quiet
```

Exit code 0 — compose file hợp lệ với PostgreSQL 17.10 đã pin.

Chưa chạy (cần Docker daemon, chủ repository chưa bật):

```bash
./mvnw -B verify
```

`PaymentServiceFoundationIT` là bài kiểm tra cho ADR này ở Phase 0: nó khởi động
`postgres:17.10-alpine` thật qua Testcontainers, xác nhận Flyway apply baseline,
schema `payment` tồn tại, comment quyền sở hữu có mặt, và Phase 0 chưa tạo bảng
nghiệp vụ nào. Trạng thái của capability này vì vậy là `IMPLEMENTED`, không phải
`VERIFIED_LOCAL` — xem `.docs/IMPLEMENTATION_STATUS.md`.

Verification bắt buộc thêm từ Phase 1A:

- Một invariant test cộng tất cả debit và credit của ledger và assert bằng nhau
  với `compareTo`.
- Một test khẳng định phép tính đi qua giá trị như `0.1 + 0.2` cho đúng `0.30`,
  tức là chứng minh không có floating point lọt vào đường tính tiền.
- Một test kiểm tra scale được giữ nguyên qua vòng persist → read → serialize.
