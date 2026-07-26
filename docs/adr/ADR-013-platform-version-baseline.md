# ADR-013: Platform version baseline là Spring Boot 4.0.7 + Spring Cloud 2025.1.2

- Status: ACCEPTED
- Date: 2026-07-26
- Decision owners: Repository owner (Tien le)
- Supersedes: N/A
- Superseded by: N/A

## Context

`PAYFLOW_MICROSERVICE_PROJECT_SPEC.md` §3.1 khai báo baseline `Spring Boot 4.1.x` +
`Spring Cloud 2025.1.x`. Cặp này không tồn tại: release train Spring Cloud
`2025.1.x` được build trên Spring Boot 4.0.x.

Bằng chứng trực tiếp từ BOM đã tải về local:

```bash
grep -n "spring-boot.version" ~/.m2/repository/org/springframework/cloud/spring-cloud-dependencies/2025.1.2/spring-cloud-dependencies-2025.1.2.pom
```

```text
94:    <spring-boot.version>4.0.7</spring-boot.version>
```

Cần chốt trước khi viết dòng `<parent>` đầu tiên, vì mọi module sau đó thừa hưởng
managed version từ lựa chọn này. Ép Boot 4.1.x lên Spring Cloud 2025.1.x là dùng
một tổ hợp chưa từng được release train test — failure mode là
`NoSuchMethodError`/`NoClassDefFoundError` tại runtime chứ không phải lỗi build,
nên nó không xuất hiện ở compile time mà xuất hiện khi gateway route request thật.

Boot 4 còn kéo theo một loạt thay đổi API mà mọi ADR/skill/tài liệu sau này phải
biết, nên chúng được ghi lại ngay tại đây thay vì để mỗi lần implement lại phải
tự phát hiện.

## Decision drivers

- Reproducible build: một tổ hợp version đã được upstream test, không phải tổ hợp
  do repo này tự ghép.
- Spec §3.1 là backlog kỹ thuật, không phải hợp đồng version; nhưng sai lệch với
  spec phải được ghi lại, không được âm thầm bỏ qua.
- Gateway phụ thuộc Spring Cloud; business service phụ thuộc Boot. Hai bên phải
  nằm trên cùng một Spring Framework/Security minor, nếu không classpath của
  reactor build sẽ có hai phiên bản Spring Security.
- Java 21 LTS là ràng buộc cứng từ spec §3.1 và là JDK tối thiểu của Boot 4.

## Options considered

### Option A — Spring Boot 4.0.7 + Spring Cloud 2025.1.2 (chọn)

Cặp version mà release train tự khai báo.

- Ưu: không có version override nào; `spring-cloud-dependencies` import sạch vào
  `dependencyManagement`; Spring Framework 7.0.x và Spring Security 7.0.6 nhất
  quán trên toàn reactor.
- Nhược: lệch nhãn với spec §3.1, nên spec cần được đọc kèm ADR này.
- Rủi ro: khi Boot 4.1.x có release train Spring Cloud tương ứng, phải làm một
  lần upgrade có chủ đích thay vì trôi dần.

### Option B — Spring Boot 4.1.x + Spring Cloud 2025.1.2, override `spring-boot.version`

Giữ đúng chữ trong spec bằng cách ép Boot lên 4.1.x.

- Ưu: khớp spec §3.1 theo nghĩa từng chữ.
- Nhược: chạy Spring Cloud Gateway trên một Boot minor mà nó chưa được test với.
  Bất tương thích binary không xuất hiện lúc build.
- Rủi ro: cao và khó chẩn đoán. Với một dự án portfolio, chi phí debug loại lỗi
  này không đổi lấy được bất kỳ capability nào.

### Option C — Spring Boot 3.5.x + Spring Cloud 2025.0.x

Hạ về stack Boot 3 quen thuộc hơn.

- Ưu: Jackson 2, `@MockBean`, `WebTestClient` auto-configuration, Testcontainers
  1.x — hầu hết tài liệu và ví dụ trên mạng khớp trực tiếp.
- Nhược: đi ngược spec (§3.1 yêu cầu Boot 4/Cloud 2025.1) và chọn một baseline sẽ
  cũ ngay trong vòng đời của chính dự án này.
- Rủi ro: thấp về kỹ thuật, nhưng làm mất một phần giá trị portfolio.

## Decision

Lock baseline ở **Spring Boot 4.0.7** + **Spring Cloud 2025.1.2** + **Java 21**,
khai báo tại `pom.xml` gốc (`<parent>` là `spring-boot-starter-parent:4.0.7`,
property `spring-cloud.version=2025.1.2`).

Phạm vi áp dụng: toàn bộ reactor (`libs/*`, `services/*`) và mọi module thêm sau.

Quy tắc kèm theo:

- Không override version nào mà Boot BOM đã manage.
- Không bump Boot minor cho tới khi có release train Spring Cloud khai báo
  `spring-boot.version` là minor đó. Việc bump là một ADR mới, không phải một
  commit sửa `pom.xml`.
- Không mix hai release train Spring Cloud trong cùng một reactor.

Những API surface đi kèm baseline này đã được xác nhận bằng cách đọc jar/BOM thực
tế (không suy đoán từ tài liệu Boot 3):

| Vùng | Boot 4.0.7 |
| --- | --- |
| JSON | Jackson 3 là default: `tools.jackson.databind.ObjectMapper`. BOM manage cả `jackson-bom 3.1.4` và `jackson-2-bom 2.21.4`, nên `com.fasterxml.jackson` vẫn có thể xuất hiện qua transitive dependency |
| Jackson exception | `tools.jackson.core.JacksonException extends RuntimeException` (unchecked) |
| Gateway starter | `spring-cloud-starter-gateway-server-webflux` (`spring-cloud-starter-gateway` không còn được publish) |
| Reactive security chain | `org.springframework.security.web.server.SecurityWebFilterChain` |
| Web slice test | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| Mock bean | `org.springframework.test.context.bean.override.mockito.MockitoBean` (`@MockBean` đã bị xoá) |
| Live-server `WebTestClient` | Không còn auto-configuration; phải tự dựng bằng `WebTestClient.bindToServer()` + `@LocalServerPort` |
| Health | `org.springframework.boot.health.actuate.endpoint.HealthEndpoint`, `org.springframework.boot.health.contributor.Status` |
| `TestRestTemplate` | `org.springframework.boot.resttestclient.TestRestTemplate` |
| Structured logging | Native `logging.structured.format.console: ecs`, không cần logstash encoder |

Điều **không** được suy diễn từ ADR này: nó không phê duyệt bất kỳ dependency nào
ngoài BOM, không quyết định observability stack (ADR-010) và không nới bất kỳ
invariant nào trong `.agent/AGENTS.md`.

## Consequences

### Positive

- Build reproducible, không có version override cần bảo trì.
- Toàn bộ reactor dùng một Spring Security/Framework duy nhất.
- Bảng API surface ở trên loại bỏ vòng lặp "viết theo Boot 3 → compile fail → tra
  jar" cho mọi phase sau.

### Negative/trade-offs

- Spec §3.1 không còn đọc độc lập được; phải đọc cùng ADR này.
- Boot 4/Jackson 3/Testcontainers 2 còn ít tài liệu ngoài; ví dụ trên mạng thường
  không chạy được nguyên trạng.
- Cần một ADR upgrade riêng khi Spring Cloud có release train cho Boot 4.1.x.

## Contract and data impact

- API: không. Baseline không thay đổi bất kỳ hợp đồng HTTP nào.
- Event: không. Serialization format của event schema chưa được quyết định ở đây
  (Jackson 3 chỉ là default của Boot, không phải hợp đồng wire).
- Database/migration: không.
- Security/privacy: Spring Security 7.0.6 OAuth2 Resource Server là baseline cho
  cả gateway (reactive) và payment-service (servlet). Không dùng
  `spring-security-oauth2-autoconfigure` legacy.
- Observability/operations: structured logging dùng cơ chế native của Boot 4;
  không thêm dependency logging encoder.

## Rollout and rollback

Rollout đã hoàn tất trong Phase 0: `pom.xml` gốc khai báo baseline và bốn module
kế thừa nó. Không có compatibility window vì chưa có gì được deploy.

Rollback nghĩa là đổi baseline, và điều đó có nghĩa là biên dịch lại toàn bộ theo
Option C: `tools.jackson` → `com.fasterxml.jackson`, `@MockitoBean` → `@MockBean`,
`PostgreSQLContainer` quay lại dạng generic. Không có dữ liệu hay event nào cần
bảo toàn ở Phase 0, nên rollback là một thay đổi source-only. Chi phí này chỉ tăng
theo lượng code viết thêm, nên nếu cần đổi thì đổi sớm.

## Verification

Đã chạy thật (kết quả ghi trong `.docs/IMPLEMENTATION_STATUS.md`):

```bash
./mvnw -B clean test
```

`BUILD SUCCESS`, 5 module, 30 test, 0 failure — chứng minh cặp version resolve
được và Jackson 3/Security 7 API dùng trong code là đúng.

```bash
./mvnw -B -pl services/api-gateway -am verify
```

13 integration test pass — chứng minh Spring Cloud Gateway Server WebFlux 5.0.2
thực sự khởi động và route được trên Boot 4.0.7, tức là cặp version hoạt động ở
runtime chứ không chỉ ở dependency resolution.

Kiểm tra thường trực: bất kỳ `<version>` mới nào cho artifact do Boot BOM quản lý
là một vi phạm ADR này và phải bị chặn ở review.
