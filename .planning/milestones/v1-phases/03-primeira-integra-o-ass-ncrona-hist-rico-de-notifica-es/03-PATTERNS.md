# Phase 3: Primeira Integração Assíncrona — Histórico de Notificações - Pattern Map

**Mapped:** 2026-09-22
**Files analyzed:** 24
**Analogs found:** 21 / 24

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `pom.xml` (root, add module + BOM) | config | batch | `pom.xml` (root, existing `<modules>`/`dependencyManagement`) | exact (self-edit) |
| `notification-service/pom.xml` | config | request-response | `inventory-service/pom.xml` | role-match (swap JPA/Postgres deps for SQS/DynamoDB starters) |
| `notification-service/Dockerfile` | config | file-I/O | `inventory-service/Dockerfile` | exact |
| `notification-service/src/main/java/.../NotificationServiceApplication.java` | config | request-response | `inventory-service/.../InventoryServiceApplication.java` (not read; trivial `@SpringBootApplication` boilerplate, same pattern in every service) | role-match |
| `notification-service/.../config/SecurityConfig.java` | middleware | request-response | `inventory-service/.../config/SecurityConfig.java` | exact |
| `notification-service/.../config/OpenApiConfig.java` | config | request-response | `inventory-service/.../config/OpenApiConfig.java` | exact |
| `notification-service/.../config/GlobalExceptionHandler.java` | middleware | request-response | `inventory-service/.../config/GlobalExceptionHandler.java` | exact (structure); needs new SQS/DynamoDB-specific handlers added |
| `notification-service/.../history/NotificationRecord.java` (`@DynamoDbBean`) | model | CRUD | `inventory-service/.../stock/Inventory.java` (JPA `@Entity`) | role-match, data-flow differs (Dynamo item vs JPA entity — see Shared Patterns note on Lombok style diff) |
| `notification-service/.../history/NotificationRepository.java` | service | CRUD | `inventory-service/.../stock/InventoryRepository.java` (not read; thin Spring Data interface) — closer conceptual analog is RESEARCH.md Pattern 2 code example (`DynamoDbTemplate` wrapper) since no Dynamo repo exists yet in codebase | no analog (new tech) |
| `notification-service/.../history/NotificationService.java` | service | event-driven | `inventory-service/.../stock/InventoryService.java` | role-match (constructor injection convention only; business logic is new) |
| `notification-service/.../history/NotificationController.java` | controller | request-response | `inventory-service/.../stock/InventoryController.java` | exact (structure: `@RestController`, constructor injection, thin delegation to service) |
| `notification-service/.../history/dto/NotificationResponse.java` | model | transform | `inventory-service/.../stock/dto/StockResponse.java` | exact (record + static `from(...)` factory) |
| `notification-service/.../history/dto/StockAdjustedEvent.java` | model | event-driven | `inventory-service/.../stock/dto/StockResponse.java` (record DTO convention) | role-match |
| `notification-service/.../history/messaging/StockAdjustedEventListener.java` | service | event-driven | none in codebase (first `@SqsListener` in project) — use RESEARCH.md Pattern 1 code example | no analog (new tech) |
| `inventory-service/.../stock/dto/StockAdjustedEvent.java` | model | event-driven | `inventory-service/.../stock/dto/StockResponse.java` (record DTO convention) | role-match |
| `inventory-service/.../stock/messaging/StockEventPublisher.java` | service | event-driven | none in codebase (first `SqsTemplate` producer) — use RESEARCH.md Pattern 1 producer example | no analog (new tech) |
| `inventory-service/.../stock/InventoryController.java` (modified: call publisher after `setStock`) | controller | request-response | itself, pre-modification (see excerpt below) | exact (self-edit) |
| `docker-compose.yml` (add `notification-service`) | config | request-response | existing `inventory-service`/`catalog-service` blocks in same file | exact |
| `localstack-init/*.sh` (init hook, queue+table provisioning) | config | batch | none in codebase (first LocalStack init hook) — pattern from RESEARCH.md Pitfall D | no analog (new tech) |
| `gateway/src/main/resources/application.yml` (add route) | route | request-response | existing `inventory-service-route` block in same file | exact |
| `notification-service/src/test/java/.../AbstractIntegrationTest.java` | test | request-response | `inventory-service/src/test/java/.../AbstractIntegrationTest.java` | role-match (swap `@ServiceConnection PostgreSQLContainer` for manual `@DynamicPropertySource` + `LocalStackContainer`, per RESEARCH.md Pitfall A) |
| `notification-service/src/test/java/.../NotificationControllerIT.java` | test | request-response | `inventory-service/src/test/java/.../InventoryControllerIT.java` (not read; same MockMvc + JWT pattern via `TestJwt`) | role-match |
| `notification-service/src/test/java/.../NotificationEventFlowIT.java` | test | event-driven | none in codebase (first end-to-end SQS→DynamoDB test) — use Awaitility per CLAUDE.md/RESEARCH.md Wave 0 Gaps | no analog (new tech) |
| `notification-service/src/test/java/.../support/TestJwt.java` | test | request-response | `inventory-service/src/test/java/.../support/TestJwt.java` | exact (copy verbatim, same JWT claim shape) |

## Pattern Assignments

### `notification-service/pom.xml` (config, request-response)

**Analog:** `inventory-service/pom.xml`

**Structure to copy** (parent block, Lombok, springdoc, actuator, web, security, oauth2-resource-server, test deps — lines 1-53, 88-120 of the analog): keep `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-actuator`, `springdoc-openapi-starter-webmvc-ui`, `lombok`, `spring-boot-starter-test`, `junit-jupiter`.

**Swap out:** `spring-boot-starter-data-jpa`, `spring-retry`, `spring-boot-starter-aop`, `flyway-core`, `flyway-database-postgresql`, `org.postgresql:postgresql`, `spring-boot-testcontainers` (`@ServiceConnection`), `org.testcontainers:postgresql`.

**Swap in (per RESEARCH.md Standard Stack):**
```xml
<dependency>
    <groupId>io.awspring.cloud</groupId>
    <artifactId>spring-cloud-aws-starter-sqs</artifactId>
</dependency>
<dependency>
    <groupId>io.awspring.cloud</groupId>
    <artifactId>spring-cloud-aws-starter-dynamodb</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>localstack</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```
`finalName`/`build` plugin block (lines 122-134 of analog): copy verbatim (`spring-boot-maven-plugin`, `maven-failsafe-plugin`, no version override — inherited from root `pluginManagement`).

---

### `pom.xml` (root, add module + BOM)

**Analog:** itself, `<modules>` (lines 20-25) and `<dependencyManagement>` (lines 52-85)

Add `<module>notification-service</module>` to the list. Add the Spring Cloud AWS BOM import exactly as shown in RESEARCH.md "Installation" section:
```xml
<properties>
    <spring-cloud-aws.version>3.4.2</spring-cloud-aws.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.awspring.cloud</groupId>
            <artifactId>spring-cloud-aws-dependencies</artifactId>
            <version>${spring-cloud-aws.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

### `notification-service/Dockerfile` (config, file-I/O)

**Analog:** `inventory-service/Dockerfile` (full file, 37 lines)

Copy verbatim, replacing every `inventory-service` token with `notification-service` (module name in `COPY`/`mvnw -pl` commands, jar name `notification-service-exec.jar`/`app.jar` target). Keep the pinned `eclipse-temurin:21.0.12_8-jdk-jammy`/`-jre-jammy` tags, the `COPY */pom.xml` cache-priming block (add `COPY notification-service/pom.xml notification-service/pom.xml` alongside the four existing module pom copies), and the non-root `orderflow` user + `curl` install for the healthcheck.

---

### `notification-service/.../config/SecurityConfig.java` (middleware, request-response)

**Analog:** `inventory-service/.../config/SecurityConfig.java` (full file, 67 lines)

Copy verbatim except the package name. Same `jwtAuthenticationConverter()` bean reading the `role` claim into `ROLE_` + value, same `SecurityFilterChain` DSL: `csrf().disable()`, `STATELESS` session, `permitAll()` on `/actuator/health/**` + Swagger paths, `.anyRequest().authenticated()`, `oauth2ResourceServer().jwt(...)`. This directly resolves RESEARCH.md Open Question 1 (recommendation: no role restriction, `authenticated()` only, consistent with `InventoryController.getStock`).

---

### `notification-service/.../config/OpenApiConfig.java` (config, request-response)

**Analog:** `inventory-service/.../config/OpenApiConfig.java` (full file, 35 lines)

Copy verbatim except package. Same `bearerAuth` `SecurityScheme` bean reading `orderflow.openapi.title`/`orderflow.openapi.description` from properties.

---

### `notification-service/.../config/GlobalExceptionHandler.java` (middleware, request-response)

**Analog:** `inventory-service/.../config/GlobalExceptionHandler.java` (full file, 105 lines)

**Keep the shared skeleton:** `@RestControllerAdvice`, uniform `errorBody(error, message)` helper (`error`/`message` keys, `fields` added only for validation), handlers for `MethodArgumentNotValidException` (400), `HttpMessageNotReadableException` (400), `AccessDeniedException` (403), `AuthenticationException` (401).

**Drop:** inventory-specific handlers (`InventoryNotFoundException`, `InsufficientStockException`, `StockBelowReservedException`, `ReservationConflictException`, `DataIntegrityViolationException`).

**Add:** a handler for whatever "not found" exception the query endpoint throws when `productId` has no history (RESEARCH.md doesn't mandate 404-vs-empty-list; Pattern 2 example returns an empty list for no matches, so a dedicated not-found exception may be unnecessary — planner's call).

---

### `notification-service/.../history/NotificationRecord.java` (model, CRUD)

**Analog (structural contrast, not code copy):** `inventory-service/.../stock/Inventory.java` (full file, 94 lines) — shows the project's *JPA* entity convention (`@Entity`, `@Getter`, `@NoArgsConstructor(PROTECTED)`, business methods on the entity).

**Do NOT replicate that Lombok style for this file.** Per RESEARCH.md Anti-Patterns and Supporting Libraries table: `@DynamoDbBean` requires public mutable getters/setters, not a `record`, and not `@NoArgsConstructor(PROTECTED)`. Use:
```java
@Getter
@Setter
@NoArgsConstructor
@DynamoDbBean
public class NotificationRecord {

    private String productId;      // @DynamoDbPartitionKey — D-32
    private String sortKey;         // @DynamoDbSortKey — eventType#eventId, D-33
    private String eventType;
    private String eventId;
    private String rawPayload;      // D-34
    private String message;         // D-34
    private Instant occurredAt;
    private Instant recordedAt;
}
```
Field-level annotations (`@DynamoDbPartitionKey` on the `productId` getter, `@DynamoDbSortKey` on the `sortKey` getter) per RESEARCH.md Pattern 1/2 and Anti-Patterns section.

---

### `notification-service/.../history/NotificationRepository.java` (service, CRUD)

**No analog in codebase** — first DynamoDB repository in the project. Use RESEARCH.md Pattern 2 code example verbatim as the starting point (`DynamoDbTemplate.save(...)` + `query(NotificationRecord.class, condition)`), with the documented fallback if `DynamoDbTemplate.query(...)` signature doesn't compile: `DynamoDbEnhancedClient.table(...).query(condition)` (RESEARCH.md Assumptions Log A2).

**Structural convention to copy from `InventoryRepository`-style thin Spring Data wrappers used elsewhere in the codebase:** constructor injection, `@Repository` stereotype, one method per query shape — no generic CRUD interface inheritance (Dynamo has no Spring Data repository interface equivalent here; use `DynamoDbTemplate` directly as shown in RESEARCH.md).

---

### `notification-service/.../history/NotificationService.java` (service, event-driven)

**Analog (constructor-injection convention only):** `inventory-service/.../stock/InventoryService.java` — `private final` fields, constructor injection, no field `@Autowired`, `@Service` stereotype (lines 28-60).

**Core logic:** use RESEARCH.md Pattern 1 code example verbatim (`recordStockAdjustment(StockAdjustedEvent event)` — builds `sortKey`, human-readable `message`, populates `NotificationRecord`, calls `notificationRepository.save(record)`). No `@Transactional`/`@Retryable` annotations apply here (DynamoDB `PutItem` is the idempotency mechanism per Don't Hand-Roll table — do not copy `InventoryService`'s `@Retryable`/`@Recover` pattern, it doesn't apply).

---

### `notification-service/.../history/NotificationController.java` (controller, request-response)

**Analog:** `inventory-service/.../stock/InventoryController.java` (full file, 59 lines)

**Copy the shape:** `@RestController @RequestMapping("/notifications")`, constructor injection, thin one-line delegation per endpoint, `@PathVariable` binding. No `@PreAuthorize` needed (Open Question 1 resolved above — plain `authenticated()` from `SecurityConfig`, no role restriction, matching `InventoryController.getStock` which also has no `@PreAuthorize`).

```java
// Analog shape (InventoryController.java lines 43-46), adapted:
@GetMapping("/{productId}")
public List<NotificationResponse> getHistory(@PathVariable String productId) {
    return notificationRepository.findByProductId(productId).stream()
            .map(NotificationResponse::from)
            .toList();
}
```
(Per RESEARCH.md Pattern 2 — could route through `NotificationService` instead of `NotificationRepository` directly, for symmetry with `InventoryController → InventoryService`; planner's call.)

---

### `notification-service/.../history/dto/NotificationResponse.java` (model, transform)

**Analog:** `inventory-service/.../stock/dto/StockResponse.java` (full file, 27 lines)

Copy the `record` + static factory convention exactly:
```java
public record NotificationResponse(
        String productId,
        String eventType,
        String message,
        String rawPayload,
        Instant occurredAt
) {
    public static NotificationResponse from(NotificationRecord record) {
        return new NotificationResponse(
                record.getProductId(),
                record.getEventType(),
                record.getMessage(),
                record.getRawPayload(),
                record.getOccurredAt());
    }
}
```

---

### `inventory-service/.../stock/dto/StockAdjustedEvent.java` and `notification-service/.../history/dto/StockAdjustedEvent.java` (model, event-driven)

**No analog for the event-DTO concept itself** — first cross-service message contract in the project. Use RESEARCH.md Code Examples verbatim (`record StockAdjustedEvent(UUID eventId, String eventType, UUID productId, int previousQuantityOnHand, int newQuantityOnHand, Instant occurredAt)`, `EVENT_TYPE = "STOCK_ADJUSTED"` constant). Duplicate the same shape in both modules per RESEARCH.md "Alternatives Considered" (no shared `common` Maven module this phase).

**Record DTO style convention:** `inventory-service/.../stock/dto/StockResponse.java` (already using Java `record` for immutable DTOs — same idiom applies here).

---

### `inventory-service/.../stock/messaging/StockEventPublisher.java` (service, event-driven)

**No analog in codebase** — first SQS producer. Use RESEARCH.md Code Examples verbatim (`@Component`, constructor-injected `SqsTemplate`, `publishStockAdjusted(event)` → `sqsTemplate.send(to -> to.queue(QUEUE_NAME).payload(event))`). Per RESEARCH.md Anti-Patterns/Pitfall B: **do not** inject this into `InventoryService` or call it from inside `@Transactional setStock(...)` — call it from `InventoryController`, after `inventoryService.setStock(...)` has already returned.

---

### `inventory-service/.../stock/InventoryController.java` (controller, request-response) — MODIFIED

**Analog:** itself, pre-modification (full file already read, 59 lines above)

**Modification pattern** (per RESEARCH.md "Publication (inventory-service, after commit)" + Pitfall C):
```java
@PutMapping("/{productId}")
@PreAuthorize("hasRole('SELLER_ADMIN')")
public StockResponse setStock(@PathVariable UUID productId, @Valid @RequestBody SetStockRequest request) {
    StockResponse response = inventoryService.setStock(productId, request.quantityOnHand());
    // publish AFTER the transactional method has returned/committed — see Pitfall B
    stockEventPublisher.publishStockAdjusted(new StockAdjustedEvent(...));
    return response;
}
```
Per RESEARCH.md Pitfall C, `previousQuantityOnHand` cannot be captured by a separate GET before/after this call — `InventoryService.setStock` needs to return it too (an internal result type, not exposed on the public `StockResponse` contract). This requires a small additional modification to `InventoryService.setStock` (out of scope for this pattern file to prescribe exactly — planner decides the internal return-type shape, e.g. `StockAdjustmentResult(StockResponse response, int previousQuantityOnHand)`).

**Existing constructor injection convention to preserve:** add `StockEventPublisher` as a new constructor parameter alongside `InventoryService`, same style as line 33-35 of the original file.

---

### `docker-compose.yml` (config, request-response) — MODIFIED

**Analog:** existing `inventory-service`/`catalog-service` blocks in the same file (lines 56-100)

Add a `notification-service` block following the exact same shape: `build.context: .` / `dockerfile: notification-service/Dockerfile`, `depends_on` with `condition: service_healthy` for `auth-service` **and** `localstack` (new — no existing service depends on `localstack` yet, only `auth-service` does at lines 38-42), `environment` block with `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` (same JWKS pattern) plus new `SPRING_CLOUD_AWS_*`/`AWS_ENDPOINT_URL` vars pointing at `http://localstack:4566` (per RESEARCH.md `application.yml` example), a new host port (`127.0.0.1:8084:8084` — next in sequence after `8083`), and the same `/actuator/health` healthcheck pattern (lines 95-100).

Also add a `volumes:` mount for the LocalStack init script under the `localstack` service block (lines 19-32): `- ./localstack-init:/etc/localstack/init/ready.d` (per RESEARCH.md Pitfall D).

---

### `gateway/src/main/resources/application.yml` (route, request-response) — MODIFIED

**Analog:** existing `inventory-service-route` entry (lines 26-31 of the file)

```yaml
- id: notification-service-route
  uri: http://notification-service:8084
  predicates:
    - Path=/api/notifications/**
  filters:
    - StripPrefix=1
```
Same `Path=/api/<segment>/**` + `StripPrefix=1` convention as every other route in the file.

---

### `notification-service/src/test/java/.../AbstractIntegrationTest.java` (test, request-response)

**Analog:** `inventory-service/src/test/java/.../AbstractIntegrationTest.java` (full file, 46 lines)

Copy the singleton-container pattern (`static final` container, `static { container.start(); }` block, NOT `@Testcontainers`/`@Container` — see the doc comment explaining why, lines 13-28 of the analog) and `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Import(TestJwt.Config.class)`.

**Swap:** `@ServiceConnection static final PostgreSQLContainer<?> postgres = ...` → per RESEARCH.md Pitfall A, `@ServiceConnection` does NOT work for `LocalStackContainer` yet. Use:
```java
static final LocalStackContainer localstack =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:2026.08.3"))
                .withServices(LocalStackContainer.Service.SQS, LocalStackContainer.Service.DYNAMODB);

static {
    localstack.start();
}

@DynamicPropertySource
static void awsProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
    registry.add("spring.cloud.aws.credentials.access-key", () -> "test");
    registry.add("spring.cloud.aws.credentials.secret-key", () -> "test");
    registry.add("spring.cloud.aws.endpoint", localstack::getEndpoint);
}
```

---

### `notification-service/src/test/java/.../support/TestJwt.java` (test, request-response)

**Analog:** `inventory-service/src/test/java/.../support/TestJwt.java`

Not read this session (file not opened — large-file/analog-count budget), but per CONTEXT.md/RESEARCH.md this is a straight copy: same `role` claim shape, same test JWT signing config, only the package changes. Planner should `Read` it directly during implementation to confirm exact method signatures before copying.

## Shared Patterns

### Constructor injection, no field `@Autowired`
**Source:** `inventory-service/.../stock/InventoryService.java` lines 56-60, `InventoryController.java` lines 33-35
**Apply to:** every new class in `notification-service` and the modified `InventoryController`/new `StockEventPublisher` in `inventory-service`.

### Uniform error body shape
**Source:** `inventory-service/.../config/GlobalExceptionHandler.java` lines 98-103 (`errorBody(error, message)` helper, `error`/`message` keys, no stack trace/SQL/class names ever in the response body)
**Apply to:** `notification-service`'s `GlobalExceptionHandler`.

### JWT role-claim security config
**Source:** `inventory-service/.../config/SecurityConfig.java` (full file)
**Apply to:** `notification-service`'s `SecurityConfig` — copy verbatim, no role restriction on the notification query endpoint (resolves RESEARCH.md Open Question 1).

### OpenAPI bearer-auth wiring
**Source:** `inventory-service/.../config/OpenApiConfig.java` (full file)
**Apply to:** `notification-service`'s `OpenApiConfig` — copy verbatim.

### Record DTO + static `from(...)` factory
**Source:** `inventory-service/.../stock/dto/StockResponse.java` (full file)
**Apply to:** `NotificationResponse.java`, both `StockAdjustedEvent.java` copies.

### Pinned Docker base image + non-root user + curl healthcheck dependency
**Source:** `inventory-service/Dockerfile` (full file)
**Apply to:** `notification-service/Dockerfile`.

### docker-compose service block shape (`depends_on` + `service_healthy` + `/actuator/health` healthcheck)
**Source:** `docker-compose.yml` `inventory-service`/`catalog-service` blocks
**Apply to:** new `notification-service` block.

### Singleton-container integration test base (not `@Testcontainers`/`@Container`)
**Source:** `inventory-service/src/test/java/.../AbstractIntegrationTest.java` (full file, especially the doc comment lines 13-28 explaining why)
**Apply to:** `notification-service`'s `AbstractIntegrationTest.java`, swapping Postgres for LocalStack + manual `@DynamicPropertySource` (RESEARCH.md Pitfall A).

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `notification-service/.../history/messaging/StockAdjustedEventListener.java` | service | event-driven | First `@SqsListener` consumer in the project — use RESEARCH.md Pattern 1 code example directly |
| `inventory-service/.../stock/messaging/StockEventPublisher.java` | service | event-driven | First `SqsTemplate` producer in the project — use RESEARCH.md Code Examples directly |
| `notification-service/.../history/NotificationRepository.java` | service | CRUD | First DynamoDB repository in the project — use RESEARCH.md Pattern 2 code example, with documented `DynamoDbEnhancedClient` fallback if `DynamoDbTemplate.query(...)` signature mismatches (Assumptions Log A2) |
| `localstack-init/*.sh` | config | batch | First LocalStack init hook in the project — follow RESEARCH.md Pitfall D guidance (`/etc/localstack/init/ready.d/`, `awslocal sqs create-queue`/`awslocal dynamodb create-table`, `chmod +x`) |
| `notification-service/src/test/java/.../NotificationEventFlowIT.java` | test | event-driven | First end-to-end async (SQS→DynamoDB) test in the project — use Awaitility per RESEARCH.md Wave 0 Gaps, no existing async-assertion test to copy from |

## Metadata

**Analog search scope:** `inventory-service/` (primary source of role/data-flow analogs, most structurally similar existing service — resource server, no synchronous outbound calls until this phase), `gateway/`, root `pom.xml`, `docker-compose.yml`
**Files scanned:** `inventory-service/src/main` (13 files), `inventory-service/src/test` (6 files), `inventory-service/pom.xml`, `inventory-service/Dockerfile`, root `pom.xml`, `docker-compose.yml`, `gateway/src/main/resources/application.yml`
**Pattern extraction date:** 2026-09-22
