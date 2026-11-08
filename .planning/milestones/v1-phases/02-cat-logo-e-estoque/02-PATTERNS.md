# Phase 2: Catálogo e Estoque - Pattern Map

**Mapped:** 2026-09-19
**Files analyzed:** 30 (catalog-service: 13, inventory-service: 15, shared: 2)
**Analogs found:** 30 / 30 (all new files mirror the proven `auth-service` module structure — no true "no analog" gaps)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `catalog-service/pom.xml` | config | request-response | `auth-service/pom.xml` | exact (minus `spring-security-oauth2-jose` issuer bits) |
| `catalog-service/Dockerfile` | config | file-I/O | `auth-service/Dockerfile` | exact |
| `catalog-service/src/main/resources/application.yml` | config | request-response | `auth-service/src/main/resources/application.yml` | exact |
| `catalog-service/src/main/resources/db/migration/V1__init_catalog_schema.sql` | migration | CRUD | `auth-service/.../V1__init_auth_schema.sql` | role-match |
| `catalog-service/.../CatalogServiceApplication.java` | config | request-response | `auth-service/.../AuthServiceApplication.java` | exact |
| `catalog-service/.../config/SecurityConfig.java` | config | request-response | `auth-service/.../config/SecurityConfig.java` | exact (drop JWT-issuing beans, keep resource-server validation) |
| `catalog-service/.../config/GlobalExceptionHandler.java` | middleware | request-response | `auth-service/.../config/GlobalExceptionHandler.java` | exact |
| `catalog-service/.../product/Product.java` | model | CRUD | `auth-service/.../company/Company.java` | exact |
| `catalog-service/.../product/ProductStatus.java` | model | CRUD | `auth-service/.../user/Role.java` (enum pattern) | role-match |
| `catalog-service/.../product/ProductRepository.java` | model | CRUD | `auth-service/.../company/CompanyRepository.java` | exact |
| `catalog-service/.../product/ProductService.java` | service | CRUD | `auth-service/.../company/CompanyService.java` | exact |
| `catalog-service/.../product/ProductController.java` | controller | CRUD | `auth-service/.../company/CompanyController.java` | exact |
| `catalog-service/.../product/ProductNotFoundException.java` | utility | request-response | `auth-service/.../company/CompanyNotFoundException.java` | exact |
| `catalog-service/.../product/dto/CreateProductRequest.java` | utility | request-response | `auth-service/.../company/dto/CreateCompanyRequest.java` | exact |
| `catalog-service/.../product/dto/UpdateProductRequest.java` | utility | request-response | `auth-service/.../company/dto/UpdateCreditLimitRequest.java` | exact |
| `catalog-service/.../product/dto/ProductResponse.java` | utility | request-response | `auth-service/.../company/dto/CompanyResponse.java` | exact |
| `catalog-service/src/test/.../AbstractIntegrationTest.java` | test | request-response | `auth-service/src/test/.../AbstractIntegrationTest.java` | exact |
| `catalog-service/src/test/.../ProductControllerIT.java` | test | request-response | (no direct `CompanyControllerIT` file was read, but referenced by AbstractIntegrationTest javadoc as the sibling `*IT` class) `auth-service/.../AbstractIntegrationTest.java` usage pattern | role-match |
| `inventory-service/pom.xml` | config | request-response | `auth-service/pom.xml` | role-match (adds `spring-retry` + `spring-boot-starter-aop`, drops `spring-security-oauth2-jose`) |
| `inventory-service/Dockerfile` | config | file-I/O | `auth-service/Dockerfile` | exact |
| `inventory-service/src/main/resources/application.yml` | config | request-response | `auth-service/src/main/resources/application.yml` | exact |
| `inventory-service/.../db/migration/V1__init_inventory_schema.sql` | migration | CRUD | `auth-service/.../V1__init_auth_schema.sql` | role-match (adds `@Version` column + idempotency ledger table) |
| `inventory-service/.../InventoryServiceApplication.java` | config | request-response | `auth-service/.../AuthServiceApplication.java` | exact |
| `inventory-service/.../config/SecurityConfig.java` | config | request-response | `auth-service/.../config/SecurityConfig.java` | exact |
| `inventory-service/.../config/RetryConfig.java` | config | event-driven | none in codebase (new pattern, `@EnableRetry`) | no analog — use RESEARCH.md Pattern 1 |
| `inventory-service/.../config/GlobalExceptionHandler.java` | middleware | request-response | `auth-service/.../config/GlobalExceptionHandler.java` | exact |
| `inventory-service/.../stock/Inventory.java` | model | CRUD | `auth-service/.../company/Company.java` | role-match (adds `@Version`, no Lombok setters needed either) |
| `inventory-service/.../stock/InventoryRepository.java` | model | CRUD | `auth-service/.../company/CompanyRepository.java` | role-match (adds `findByProductId`, `existsByProductIdAndReservationId`) |
| `inventory-service/.../stock/InventoryService.java` | service | CRUD | `auth-service/.../company/CompanyService.java` | role-match (adds `@Retryable`/`@Recover`, no direct analog for retry) |
| `inventory-service/.../stock/InventoryController.java` | controller | CRUD | `auth-service/.../company/CompanyController.java` | exact |
| `inventory-service/.../stock/InsufficientStockException.java` | utility | request-response | `auth-service/.../company/CompanyNotFoundException.java` | exact |
| `inventory-service/.../stock/InventoryNotFoundException.java` | utility | request-response | `auth-service/.../company/CompanyNotFoundException.java` | exact |
| `inventory-service/.../stock/dto/SetStockRequest.java` | utility | request-response | `auth-service/.../company/dto/UpdateCreditLimitRequest.java` | exact |
| `inventory-service/.../stock/dto/ReserveStockRequest.java` | utility | request-response | `auth-service/.../company/dto/UpdateCreditLimitRequest.java` | role-match |
| `inventory-service/.../stock/dto/StockResponse.java` | utility | request-response | `auth-service/.../company/dto/CompanyResponse.java` | exact |
| `inventory-service/src/test/.../AbstractIntegrationTest.java` | test | request-response | `auth-service/src/test/.../AbstractIntegrationTest.java` | exact |
| `inventory-service/src/test/.../InventoryControllerIT.java` | test | request-response | `auth-service/src/test/.../AbstractIntegrationTest.java` (base class usage) | role-match |
| `inventory-service/src/test/.../StockReservationConcurrencyIT.java` | test | event-driven (concurrency) | none in codebase (new pattern, real HTTP + virtual threads) | no analog — use RESEARCH.md Pattern 3 |
| `gateway/src/main/resources/application.yml` (MODIFIED) | route | request-response | same file, existing `auth-service-route` entry | exact (add sibling routes) |
| `README.md` (MODIFIED) | config | request-response | existing README Phase 1 section | exact |

## Pattern Assignments

### `catalog-service/.../product/Product.java` (model, CRUD)

**Analog:** `auth-service/src/main/java/com/orderflow/auth/company/Company.java`

**Imports pattern** (lines 1-17):
```java
package com.orderflow.auth.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.GenerationTime;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
```

**Core entity pattern** (lines 24-60): `@Entity` + `@Table` + `@Getter` + `@NoArgsConstructor(access = PROTECTED)`, `@Id @GeneratedValue(strategy = GenerationType.UUID)`, `BigDecimal` money column mapped `precision = 19, scale = 2`, `@Generated(GenerationTime.INSERT)` for DB-defaulted `created_at`, a small package-private mutation method (`changeCreditLimit`) instead of a public setter. For `Product`, replicate this shape adding `sku` (`String`, unique) and `status` mapped as `@Enumerated(EnumType.STRING)` (per RESEARCH.md Pitfall 4 — never bare `@Enumerated`), with a `deactivate()`/`activate()` mutator instead of a setter, matching the "no public setters, explicit intention-revealing methods" convention already established here.

**Enum pattern** — analog `auth-service/src/main/java/com/orderflow/auth/user/Role.java` (BUYER/SELLER_ADMIN enum, stored as `VARCHAR(20)` + `CHECK` constraint in SQL, `@Enumerated(EnumType.STRING)` in Java) — replicate identically for `ProductStatus` (ACTIVE/DISCONTINUED).

---

### `catalog-service/.../product/ProductController.java` (controller, CRUD/request-response)

**Analog:** `auth-service/src/main/java/com/orderflow/auth/company/CompanyController.java`

**Imports + role guard pattern** (lines 1-19, 37-42):
```java
@RestController
@RequestMapping("/companies")
public class CompanyController {
    ...
    @PostMapping
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public ResponseEntity<CompanyResponse> create(@Valid @RequestBody CreateCompanyRequest request) {
        CompanyResponse response = companyService.createCompanyWithBuyer(request);
        return ResponseEntity.created(URI.create("/companies/" + response.id())).body(response);
    }
```
Apply verbatim to `ProductController`: `POST /products` guarded `@PreAuthorize("hasRole('SELLER_ADMIN')")`, returns `201` with `Location` header via `URI.create(...)`. `GET /products` (list, paginated `Pageable`) has **no** `@PreAuthorize` role restriction beyond `authenticated()` from `SecurityConfig` — both BUYER and SELLER_ADMIN call it, but `ProductService` filters by `status=active` for BUYER per D-24 (no per-object guard like `CompanyGuard` is needed here — see Security Domain note below, this catalog is seller-wide, not per-company scoped).

**No `CompanyGuard`-style analog needed:** unlike Phase 1's `CompanyGuard` (SpEL `@PreAuthorize("@companyGuard.isSelfOrSeller(#companyId)")`), CAT-01/CAT-02/INV-01/INV-02 require only flat role checks (`hasRole('SELLER_ADMIN')` / `hasRole('BUYER')`) — do not introduce a guard bean for this phase (RESEARCH.md Security Domain V4 explicitly says no per-buyer scoping applies here).

---

### `catalog-service/.../product/ProductService.java` (service, CRUD)

**Analog:** `auth-service/src/main/java/com/orderflow/auth/company/CompanyService.java`

**Core pattern** (lines 21-77): plain `@Service` with constructor injection (no `@Autowired` field injection anywhere in this codebase), `@Transactional` on writes, `@Transactional(readOnly = true)` on reads, `orElseThrow(() -> new XxxNotFoundException(...))` for lookups, defensive pre-checks before delegating final correctness to a DB constraint (see `EmailAlreadyUsedException` pre-check pattern, lines 38-42) — for `ProductService.create(...)`, mirror this by **always** setting `status = ProductStatus.ACTIVE` server-side regardless of client input (RESEARCH.md Security Domain: "Role escalation via client-supplied `status`" — same mitigation as `Role.BUYER` being fixed by literal in `CompanyService.java:46-47`, never trust a client-supplied privileged-looking field).

---

### `catalog-service/.../product/dto/*` (utility, request-response — validation)

**Analog:** `auth-service/src/main/java/com/orderflow/auth/company/dto/UpdateCreditLimitRequest.java`

```java
public record UpdateCreditLimitRequest(
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal creditLimit
) {
}
```
Records with Jakarta Bean Validation annotations directly on components — apply the same `@DecimalMin`/`@Digits(integer = 17, fraction = 2)` constraint to `Product.price` (D-06 inherited convention), `@NotBlank` for `name`/`sku`, `@Positive` for any quantity field in inventory DTOs.

---

### `catalog-service/.../config/SecurityConfig.java` and `inventory-service/.../config/SecurityConfig.java` (config, request-response)

**Analog:** `auth-service/src/main/java/com/orderflow/auth/config/SecurityConfig.java` (full file, 81 lines)

Both new services are **resource servers only** — copy the file verbatim but **drop** nothing related to JWT issuing (auth-service doesn't issue from this class either; `JwtIssuerConfig`/`JwksController` are auth-service-only and have no analog needed in catalog/inventory). Keep:
- `@EnableMethodSecurity` (required for `@PreAuthorize` to work at all)
- The custom `jwtAuthenticationConverter()` bean reading the `role` claim as a **single string** (not the default `scope` list) — this is load-bearing; omitting it means `hasRole(...)` checks silently never match.
- `SessionCreationPolicy.STATELESS` + CSRF disabled with the same justification comment.
- `authorizeHttpRequests` permitAll list should be trimmed to just `/actuator/health/**` (no `/auth/login` or `/.well-known/jwks.json` routes exist in these services).
- Add `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` pointing at auth-service's `/.well-known/jwks.json` in `application.yml` (not present in `auth-service`'s own `application.yml` since it's the issuer, not a validator — this line is new, not copied).

---

### `catalog-service/.../config/GlobalExceptionHandler.java` and `inventory-service/.../config/GlobalExceptionHandler.java` (middleware, request-response)

**Analog:** `auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java` (full file, 76 lines)

**Uniform error body pattern** (lines 69-74):
```java
private Map<String, Object> errorBody(String error, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("message", message);
    return body;
}
```

**Validation handler** (lines 33-43) — copy verbatim, swap nothing (works for any `@Valid` DTO).

**Conflict handler pattern** (lines 45-49) — for catalog-service, adapt to whatever uniqueness constraint SKU gets (if any). For inventory-service, this exact `@ExceptionHandler(DataIntegrityViolationException.class)` shape is where the `UNIQUE(product_id, reservation_id)` violation gets translated per RESEARCH.md Pattern 2 — but the body/status differs (idempotent replay success, not a genuine conflict): re-read state and return 200, not automatically the same "already used" 409 text as auth-service's email case. Add distinct handlers for `InsufficientStockException` → 409 with `available`/`requested` fields (D-10), and a new `ReservationConflictException` → 503/409 "try again" (D-21) — both new, no existing analog beyond the shape (`@ExceptionHandler` + `errorBody(...)` helper).

**Not-found handler pattern** (lines 51-55) — copy shape for `ProductNotFoundException` (catalog) and `InventoryNotFoundException` (inventory).

**AccessDenied / Authentication handlers** (lines 57-67) — copy verbatim into both services, unchanged.

---

### `inventory-service/.../config/RetryConfig.java` (config, event-driven) — NO ANALOG

No existing file in this codebase uses `@EnableRetry`. Use RESEARCH.md Pattern 1 directly:
```java
@Configuration
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE) // retry advice must wrap OUTSIDE @Transactional
public class RetryConfig {
}
```

---

### `inventory-service/.../stock/InventoryService.java` (service, CRUD) — hybrid analog

**Analog (structure/conventions):** `auth-service/src/main/java/com/orderflow/auth/company/CompanyService.java` (constructor injection, `@Transactional`, `orElseThrow`, pre-check-then-DB-constraint idempotency philosophy per lines 38-42's own comment).

**Analog (retry/version mechanism):** none in codebase — use RESEARCH.md Pattern 1's full `reserve(...)`/`@Recover` code example verbatim (already reproduced in RESEARCH.md lines 248-283). Key structural rule inherited from `CompanyService`: keep `reserve(...)`/`release(...)` as the sole public entry points called directly from `InventoryController` (no internal self-invocation, matching the existing controller→service call convention and avoiding RESEARCH.md Pitfall 2).

---

### `inventory-service/.../db/migration/V1__init_inventory_schema.sql` (migration, CRUD)

**Analog:** `auth-service/src/main/resources/db/migration/V1__init_auth_schema.sql`

```sql
CREATE TABLE companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    credit_limit NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
Conventions to replicate: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `TIMESTAMPTZ NOT NULL DEFAULT now()` for timestamps, `VARCHAR(20) CHECK (... IN (...))` for enum-like columns (see `role` column, line 15) — apply this exact technique to `product.status` (`VARCHAR(20) CHECK (status IN ('ACTIVE','DISCONTINUED'))`) per RESEARCH.md Pitfall 4, and add the new `version BIGINT NOT NULL DEFAULT 0` column plus the `stock_reservations` idempotency table exactly as specified in RESEARCH.md Pattern 2's SQL block (lines 296-316) — this is new structure with no direct analog but follows the same file's column-naming/constraint conventions.

---

### `catalog-service/pom.xml` and `inventory-service/pom.xml` (config)

**Analog:** `auth-service/pom.xml` (full dependency block, lines 18-100+)

Copy the entire `<dependencies>` block verbatim (web, security, oauth2-resource-server, data-jpa, validation, actuator, flyway-core, flyway-database-postgresql, postgresql runtime, lombok provided, plus the full test block: spring-boot-starter-test, spring-boot-testcontainers, testcontainers junit-jupiter, testcontainers postgresql). **Drop** `spring-security-oauth2-jose` (auth-service-only, used for `NimbusJwtEncoder` token issuing — catalog/inventory only validate, never issue). **For inventory-service only, add** (per RESEARCH.md Standard Stack):
```xml
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```
No `<version>` tags on any dependency — all managed by the root `pom.xml`'s `spring-boot-dependencies` BOM import, matching `auth-service/pom.xml`'s convention throughout.

---

### `catalog-service/Dockerfile` and `inventory-service/Dockerfile` (config, file-I/O)

**Analog:** `auth-service/Dockerfile` (full file, 34 lines)

Copy verbatim, substituting `auth-service` → `catalog-service` (or `inventory-service`) in every `COPY`/`-pl` reference and the final jar name. Keep the exact pinned tag `eclipse-temurin:21.0.12_8-jdk-jammy` / `-jre-jammy` (no floating tags, per ROADMAP Phase 1 criterion still binding), the non-root `orderflow` system user, and the `-exec` classifier jar convention (`COPY --from=build /workspace/<service>/target/<service>-exec.jar app.jar`).

---

### `catalog-service/src/test/.../AbstractIntegrationTest.java` and `inventory-service/src/test/.../AbstractIntegrationTest.java` (test, request-response)

**Analog:** `auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java` (full file)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    static {
        postgres.start();
    }

    @Autowired
    protected MockMvc mockMvc;
}
```
Copy verbatim into both new modules' test packages (only the package declaration changes). Note the javadoc's explicit rationale for the manual `static { postgres.start(); }` block over `@Testcontainers`/`@Container` — this is load-bearing for multi-class test suites in the same module and must not be "simplified" away. For inventory-service, `StockReservationConcurrencyIT` deliberately does **NOT** extend this class (RESEARCH.md Pattern 3) — it needs its own `RANDOM_PORT` + raw `HttpClient` setup instead of `MockMvc`.

---

### `inventory-service/src/test/.../StockReservationConcurrencyIT.java` (test, event-driven/concurrency) — NO ANALOG

No existing test in the codebase does real-socket concurrent HTTP testing. Use RESEARCH.md Pattern 3's full code example verbatim (`@LocalServerPort`, `Executors.newVirtualThreadPerTaskExecutor()`, `CyclicBarrier`, `java.net.http.HttpClient`) — already reproduced in RESEARCH.md lines 342-393.

---

### `gateway/src/main/resources/application.yml` (route, request-response) — MODIFIED

**Analog:** same file's existing `auth-service-route` entry (lines 1-24, full file already read above)

```yaml
      routes:
        - id: auth-service-route
          uri: http://auth-service:8081
          predicates:
            - Path=/api/auth/**,/api/companies/**
          filters:
            - StripPrefix=1
```
Add two sibling entries following the exact same `id`/`uri`/`predicates`/`filters` shape:
```yaml
        - id: catalog-service-route
          uri: http://catalog-service:808x
          predicates:
            - Path=/api/products/**
          filters:
            - StripPrefix=1
        - id: inventory-service-route
          uri: http://inventory-service:808y
          predicates:
            - Path=/api/inventory/**
          filters:
            - StripPrefix=1
```
(Exact ports to be assigned in planning, consistent with `docker-compose.yml`'s existing `8081` convention for `auth-service`.)

---

### `docker-compose.yml` (route/config) — MODIFIED

**Analog:** existing `auth-service:` service block (full block already read above)

```yaml
  auth-service:
    build:
      context: .
      dockerfile: auth-service/Dockerfile
    depends_on:
      postgres:
        condition: service_healthy
      localstack:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderflow?currentSchema=auth
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:?}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?}
    ports:
      - "127.0.0.1:8081:8081"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 40s
```
Add `catalog-service`/`inventory-service` blocks with the same shape, `currentSchema=catalog`/`currentSchema=inventory` respectively, `depends_on: postgres (service_healthy)` only (no `localstack` dependency — D-15/this phase adds no SQS/DynamoDB usage), and update `gateway`'s `depends_on` to also wait on both new services' `service_healthy`.

## Shared Patterns

### Authentication / JWT Validation (resource-server only)
**Source:** `auth-service/src/main/java/com/orderflow/auth/config/SecurityConfig.java`
**Apply to:** `catalog-service` and `inventory-service` `SecurityConfig.java`
Custom `JwtAuthenticationConverter` reading the single-string `role` claim into `ROLE_<value>` authority; `@EnableMethodSecurity` for `@PreAuthorize`; stateless session policy; CSRF disabled with justification comment.

### Uniform Error Body + RestControllerAdvice
**Source:** `auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java`
**Apply to:** All controller files in both new services
`{"error": "<short_stable_code>", "message": "<generic_text>"}` shape via a shared `errorBody(...)` private helper; validation errors additionally carry a `fields` map; never leak stack traces, exception class names, or SQL fragments.

### Constructor Injection, No Field `@Autowired`
**Source:** `auth-service/.../CompanyService.java`, `CompanyController.java`
**Apply to:** Every new `@Service`/`@Controller` in both modules — plain constructor with `private final` fields, no Lombok `@RequiredArgsConstructor` used in this codebase's existing services either (checked: `CompanyService`/`CompanyController` both hand-write constructors) — match this exactly for consistency rather than introducing Lombok constructor generation.

### Server-Side-Only Privileged Fields
**Source:** `auth-service/.../CompanyService.java` lines 46-47 (`Role.BUYER` fixed by literal, never from request body)
**Apply to:** `ProductService.create(...)` (always force `status = ACTIVE` server-side, ignore any client-supplied status field on creation).

### Money as `BigDecimal`/`NUMERIC(19,2)` (D-06, inherited)
**Source:** `auth-service/.../company/Company.java` (`creditLimit` column mapping) + `UpdateCreditLimitRequest.java` (`@DecimalMin`/`@Digits(integer = 17, fraction = 2)`)
**Apply to:** `Product.price` field and its DTOs.

### Enum-as-VARCHAR-with-CHECK-constraint (never `@Enumerated` ORDINAL)
**Source:** `auth-service/.../user/Role.java` + `V1__init_auth_schema.sql:15` (`role VARCHAR(20) NOT NULL CHECK (role IN ('BUYER', 'SELLER_ADMIN'))`)
**Apply to:** `ProductStatus` (catalog-service) — `@Enumerated(EnumType.STRING)` in Java, `VARCHAR(20) CHECK (status IN ('ACTIVE','DISCONTINUED'))` in SQL.

### Testcontainers Singleton-Container Base Class
**Source:** `auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java`
**Apply to:** Both new modules' `AbstractIntegrationTest.java` — manual `static { postgres.start(); }` block, not `@Testcontainers`/`@Container`, to avoid the cross-class container-teardown bug documented in the javadoc.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `inventory-service/.../config/RetryConfig.java` | config | event-driven | No existing use of `@EnableRetry`/Spring Retry anywhere in the codebase — this phase introduces the pattern for the first time. Use RESEARCH.md Pattern 1 verbatim. |
| `inventory-service/src/test/.../StockReservationConcurrencyIT.java` | test | event-driven (concurrency) | No existing test uses real-socket HTTP + virtual threads + `CyclicBarrier`; all existing `*IT` tests use `MockMvc` via `AbstractIntegrationTest`. Use RESEARCH.md Pattern 3 verbatim. |
| `inventory-service/.../stock/Inventory.java` (`@Version` field specifically) | model | CRUD | No existing entity in the codebase uses JPA optimistic locking (`@Version`). Structural conventions (Lombok `@Getter`, `@NoArgsConstructor(PROTECTED)`, UUID `@Id`) still come from `Company.java`; only the `@Version` column itself is new — use RESEARCH.md's `Inventory` code example (lines 456-487). |

## Metadata

**Analog search scope:** `auth-service/src/main/java/com/orderflow/auth/**`, `auth-service/src/main/resources/**`, `auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java`, `auth-service/pom.xml`, `auth-service/Dockerfile`, `gateway/src/main/resources/application.yml`, `docker-compose.yml` (all confirmed git-tracked via `git ls-files`).
**Files scanned:** 18 source files + 3 config files + 1 SQL migration read directly this session; `CompanyControllerIT`/service-level unit test files not present in `auth-service/src/test` beyond `AbstractIntegrationTest.java` at time of this scan (only the base class exists as a committed test file — no sibling `*IT`/`*Test` classes were found under `auth-service/src/test`), so controller/service-level test patterns for the new `*IT`/`*Test` files should follow the base class's own javadoc description of the intended usage plus standard JUnit5/AssertJ/MockMvc idioms already implied by the `spring-boot-starter-test` dependency.
**Pattern extraction date:** 2026-09-19
