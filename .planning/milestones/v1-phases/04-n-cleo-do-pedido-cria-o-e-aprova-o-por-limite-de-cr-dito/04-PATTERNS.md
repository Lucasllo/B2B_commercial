# Phase 4: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito - Pattern Map

**Mapped:** 2026-09-24
**Files analyzed:** ~24 (new `order-service` module)
**Analogs found:** 22 / 24 (2 genuinely new techniques: outbound `RestClient`, pessimistic sentinel-row lock — no direct in-repo analog, RESEARCH.md Code Examples used instead)

All analogs below are git-tracked source files (`catalog-service`, `auth-service`, `inventory-service` under repo root) — no gitignored/mirror paths involved.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `order-service/pom.xml` | config | batch | `inventory-service/pom.xml` (minus AWS/retry/aop) | role-match |
| `order-service/Dockerfile` | config | batch | `inventory-service/Dockerfile` | exact |
| `order-service/src/main/resources/application.yml` | config | request-response | `inventory-service/src/main/resources/application.yml` | role-match |
| `order-service/src/main/resources/db/migration/V1__init_order_schema.sql` | migration | CRUD | `catalog-service/src/main/resources/db/migration/V1__init_catalog_schema.sql` | exact |
| `com.orderflow.order.OrderServiceApplication` | config | request-response | `*ServiceApplication` (any) | exact |
| `com.orderflow.order.config.SecurityConfig` | config/middleware | request-response | `catalog-service/config/SecurityConfig.java` | exact |
| `com.orderflow.order.config.GlobalExceptionHandler` | middleware | request-response | `catalog-service/config/GlobalExceptionHandler.java` | exact |
| `com.orderflow.order.config.OpenApiConfig` | config | request-response | `catalog-service/config/OpenApiConfig.java` | exact |
| `com.orderflow.order.config.ClientConfig` | config | request-response | none in-repo (new technique) — RESEARCH.md Pattern 1 | no analog |
| `com.orderflow.order.order.Order` (entity) | model | CRUD | `catalog-service/product/Product.java` | role-match |
| `com.orderflow.order.order.OrderItem` (entity) | model | CRUD | `inventory-service` `StockReservation`-style child entity | role-match |
| `com.orderflow.order.order.OrderStatus` (enum) | model | CRUD | `catalog-service/product/ProductStatus.java` | exact |
| `com.orderflow.order.order.CompanyCreditLock` (entity) | model | CRUD | none in-repo (new technique) — RESEARCH.md Pattern 2 | no analog |
| `com.orderflow.order.order.OrderRepository` | model | CRUD | `catalog-service/product/ProductRepository.java` | role-match |
| `com.orderflow.order.order.OrderItemRepository` | model | CRUD | `catalog-service/product/ProductRepository.java` | role-match |
| `com.orderflow.order.order.CompanyCreditLockRepository` | model | CRUD | RESEARCH.md Pattern 2 (`@Lock` example) | no analog |
| `com.orderflow.order.order.OrderController` | controller | request-response | `catalog-service/product/ProductController.java` + `auth-service/company/CompanyController.java` (guard pattern) | exact |
| `com.orderflow.order.order.OrderService` (pricing step, non-`@Transactional`) | service | request-response | `inventory-service/InventoryService` (javadoc on same-bean self-invocation pitfall) | role-match |
| `com.orderflow.order.order.OrderService` (`decideAndCreate`, `@Transactional`) | service | CRUD | `catalog-service/product/ProductService.java` (`@Transactional` CRUD shape) | role-match |
| `com.orderflow.order.order.dto.*` (CreateOrderRequest, OrderItemRequest, OrderResponse, DecisionRequest) | model (DTO) | request-response | `catalog-service/product/dto/*.java` | exact |
| `com.orderflow.order.order.exception.*` (OrderNotFoundException, OrderNotPendingException, InvalidOrderItemsException, AuthServiceUnavailableException, CatalogServiceUnavailableException) | model (exception) | request-response | `catalog-service/product/ProductNotFoundException.java`, `SkuAlreadyUsedException.java` | exact |
| `com.orderflow.order.client.AuthServiceClient` | service | request-response | RESEARCH.md Pattern 1 (no in-repo precedent — first outbound caller in this codebase) | no analog |
| `com.orderflow.order.client.CatalogServiceClient` | service | request-response | RESEARCH.md Pattern 1 | no analog |
| `com.orderflow.order.client.dto.ProductResponse` / `CreditLimitResponse` (mirrored records) | model (DTO) | transform | `auth-service/company/dto/CreditLimitResponse.java` (shape to mirror, NOT import) | role-match |
| `order-service/src/test/.../AbstractIntegrationTest.java` | test | CRUD | `inventory-service/.../AbstractIntegrationTest.java` (minus LocalStack) | exact |
| `order-service/src/test/.../support/TestJwt.java` | test | request-response | `inventory-service/.../support/TestJwt.java` | exact (copy verbatim) |
| `order-service/src/test/.../OrderControllerIT.java` | test | request-response | `catalog-service` `ProductControllerIT`-style (via `AbstractIntegrationTest` + `MockMvc`) | role-match |
| `order-service/src/test/.../OrderApprovalIT.java` | test | request-response | same as above | role-match |
| `order-service/src/test/.../CreditLimitBoundaryConcurrencyIT.java` | test | event-driven (concurrency) | `inventory-service/.../StockReservationConcurrencyIT.java` | exact |
| Root `pom.xml` (`<modules>` entry) | config | batch | existing 4 `<module>` entries | exact |
| `docker-compose.yml` (order-service block) | config | batch | `inventory-service` service block | exact |
| `gateway/src/main/resources/application.yml` (route entry) | route/config | request-response | existing `/api/products/**`, `/api/inventory/**` routes | exact |
| `scripts/smoke-order-flow.sh` (or similar) | utility | request-response | `scripts/smoke-notification-flow.sh` | exact |

## Pattern Assignments

### `com.orderflow.order.order.OrderController` (controller, request-response)

**Analogs:** `catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java` (role/authority derivation, `sellerView` pattern) + `auth-service/src/main/java/com/orderflow/auth/company/CompanyController.java` (guard-vs-service-layer authorization split)

**Imports pattern** (ProductController.java lines 1-23):
```java
package com.orderflow.catalog.product;

import com.orderflow.catalog.product.dto.CreateProductRequest;
import com.orderflow.catalog.product.dto.ProductResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;
```
For `OrderController`, add `com.orderflow.order.order.dto.*`, drop `PutMapping`, keep `GetMapping`/`PostMapping`.

**Role-derivation pattern** (ProductController.java lines 33-84) — copy verbatim, this is exactly `isSellerAdmin`/`sellerView` from Pattern 3 of RESEARCH.md:
```java
@RestController
@RequestMapping("/products")
public class ProductController {
    private static final String SELLER_ADMIN_AUTHORITY = "ROLE_SELLER_ADMIN";

    @PostMapping
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) { ... }

    @GetMapping("/{productId}")
    public ProductResponse getById(@PathVariable UUID productId, Authentication authentication) {
        return productService.getById(productId, isSellerAdmin(authentication));
    }

    @GetMapping
    public Page<ProductResponse> list(Pageable pageable, Authentication authentication) {
        return productService.list(pageable, isSellerAdmin(authentication));
    }

    private boolean isSellerAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SELLER_ADMIN_AUTHORITY::equals);
    }
}
```
Apply for `OrderController`: `POST /orders` restricted `@PreAuthorize("hasRole('BUYER')")`; `GET /orders`, `GET /orders/{orderId}` open to both roles, passing `isSellerAdmin(authentication)` + `extractCompanyId(authentication)` into the service (Pattern 3, RESEARCH.md lines 340-345). Add `extractCompanyId` reading the `company_id` claim (null for SELLER_ADMIN) — new helper, same style as `isSellerAdmin`.

**Guard-annotation pattern for a simple self-or-seller read** (CompanyController.java lines 44-48) — reference only; **not directly usable for `GET /orders/{id}`** because the guard would need the order row's `companyId`, which isn't known from the path variable alone (RESEARCH.md Pattern 3 explains why to use service-layer scoping instead of `@PreAuthorize` SpEL here):
```java
@GetMapping("/{companyId}/credit-limit")
@PreAuthorize("@companyGuard.isSelfOrSeller(#companyId)")
public CreditLimitResponse getCreditLimit(@PathVariable UUID companyId) { ... }
```
This pattern **is** directly reusable, however, inside `AuthServiceClient` — it's the guard that already permits the BUYER's own-company credit-limit read (D-39), no new auth-service change needed.

**Approve/reject endpoints** — model as two `@PostMapping` handlers mirroring `CompanyController.updateCreditLimit`'s shape (`@PreAuthorize("hasRole('SELLER_ADMIN')")`, `@Valid @RequestBody`, path variable + body → service call), not the `create`/`URI.create` shape (no `Location` header needed for a state-transition endpoint).

---

### `com.orderflow.order.order.OrderService` (service, CRUD — split into non-transactional pricing + transactional decide-and-create per D-41/Pitfall 1)

**Analog:** `catalog-service/src/main/java/com/orderflow/catalog/product/ProductService.java`

**Imports pattern** (lines 1-11):
```java
package com.orderflow.catalog.product;

import com.orderflow.catalog.product.dto.CreateProductRequest;
import com.orderflow.catalog.product.dto.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
```

**Core CRUD + not-found + role-scoped read pattern** (lines 17-55) — direct template for `OrderService.getById`/`list`:
```java
@Service
public class ProductService {
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new SkuAlreadyUsedException("SKU already in use");
        }
        Product product = productRepository.save(new Product(...));
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(UUID productId, boolean sellerView) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));
        if (!sellerView && product.getStatus() == ProductStatus.DISCONTINUED) {
            throw new ProductNotFoundException("Product not found");
        }
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Pageable pageable, boolean sellerView) {
        Page<Product> page = sellerView
                ? productRepository.findAll(pageable)
                : productRepository.findByStatus(ProductStatus.ACTIVE, pageable);
        return page.map(ProductResponse::from);
    }
}
```
For `OrderService.getById(orderId, callerCompanyId, sellerView)` swap the `DISCONTINUED` check for the company-ownership check (RESEARCH.md Pattern 3, lines 348-358) — same "collapse to not-found" idiom, never 403.

**Transactional decide-and-persist pattern (new technique, no in-repo analog)** — use RESEARCH.md Pattern 2 verbatim (lines 284-329): `OrderService.decideAndCreate(companyId, items, total)` is the **only** `@Transactional` method; it must be in a different bean than the pricing step per Pitfall 1/Anti-Patterns (same rule already documented in this codebase as `InventoryService`'s javadoc on same-bean self-invocation, `inventory-service/InventoryService.java` — cite that javadoc directly in `OrderService`'s own javadoc, continuing the project's convention of pointing back at the originating pitfall).

**Error handling pattern:** exceptions are plain unchecked classes (`ProductNotFoundException extends RuntimeException`), thrown and caught only in `GlobalExceptionHandler` — never caught inside the service itself.

---

### `com.orderflow.order.config.SecurityConfig` (config/middleware, request-response)

**Analog:** `catalog-service/src/main/java/com/orderflow/catalog/config/SecurityConfig.java` — copy verbatim, only the package changes.

**Full core pattern** (lines 23-66):
```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null) {
                return List.<GrantedAuthority>of();
            }
            return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
```

---

### `com.orderflow.order.config.GlobalExceptionHandler` (middleware, request-response)

**Analog:** `catalog-service/src/main/java/com/orderflow/catalog/config/GlobalExceptionHandler.java` — copy structure, swap exception classes.

**Full pattern** (lines 24-80): keep `handleValidation` (MethodArgumentNotValidException → 400 + `fields`), `handleMalformedRequest` (HttpMessageNotReadableException → 400), `handleAccessDenied` (403), `handleAuthentication` (401), and the `errorBody(error, message)` helper unchanged. Add new handlers:
- `OrderNotFoundException` → 404 `order_not_found`
- `OrderNotPendingException` → 409 `order_not_pending` (D-46: decide a non-PENDING_APPROVAL order)
- `InvalidOrderItemsException` → 422 (or 409, planner's discretion per A1) `invalid_order_items`, with `fields`/item-id list in the body — extend `errorBody` with an extra key the same way `handleValidation` adds `fields`
- `AuthServiceUnavailableException`, `CatalogServiceUnavailableException` → 503 `auth_service_unavailable` / `catalog_service_unavailable` — **never** leak connection details (ASVS V13, RESEARCH.md Security Domain), same discipline as every existing handler (no stack trace, no exception class name, no SQL fragment in the body).

Error envelope shape to keep uniform everywhere: `{"error": "...", "message": "...", "fields"?: {...}}`.

---

### `com.orderflow.order.client.AuthServiceClient` / `CatalogServiceClient` (service, request-response — new technique)

**No in-repo analog** — this is the first outbound synchronous caller in the codebase. Use RESEARCH.md Pattern 1 verbatim (lines 236-275):
```java
@Configuration
public class ClientConfig {
    @Bean
    RestClient catalogServiceRestClient(
            @Value("${orderflow.clients.catalog-service.base-url}") String baseUrl,
            @Value("${orderflow.clients.catalog-service.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${orderflow.clients.catalog-service.read-timeout-ms:3000}") long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}

public ProductSnapshot getProduct(UUID productId, String bearerToken) {
    return catalogServiceRestClient.get()
            .uri("/products/{id}", productId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                throw new ProductUnavailableException(productId);
            })
            .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                throw new CatalogServiceUnavailableException();
            })
            .body(ProductResponse.class);
}
```
**Mandatory:** explicit connect/read timeouts (Boot's autoconfigured builder has none) — this is a locked decision (D-41 discretion note), and the project already paid for this lesson once (WR-03, Phase 3). Property naming: follow `orderflow.clients.<service>.base-url` per RESEARCH.md Open Question 1, mirroring the existing `orderflow.messaging.*`/`jwk-set-uri` env-override convention.

**DTO mirroring rule:** `client/dto/ProductResponse` and `client/dto/CreditLimitResponse` are **new, independent records** in `order-service` matching the JSON shape of `catalog-service/product/dto/ProductResponse.java` and `auth-service/company/dto/CreditLimitResponse.java` — never import the originals across service boundaries (ARCHITECTURE.md anti-pattern, reinforced in RESEARCH.md "Structure Rationale").

---

### `com.orderflow.order.order.CompanyCreditLockRepository` / lock pattern (model, CRUD — new technique)

**No in-repo analog** (inventory-service uses optimistic `@Version` + retry for a different concurrency shape — do not copy that pattern here, RESEARCH.md "Alternatives Considered"). Use RESEARCH.md Pattern 2 verbatim (lines 286-329, already quoted in RESEARCH.md itself) for `ensureExists` (native `INSERT ... ON CONFLICT DO NOTHING`), `lockForUpdate` (`@Lock(LockModeType.PESSIMISTIC_WRITE)`), and `sumExposure` (live `SUM(...)` query, never a denormalized column, D-37).

**Critical detail carried over from `inventory-service` regardless:** never call a `@Transactional` method on `this` from another method of the same bean — the Spring proxy is bypassed silently. This is the exact reason pricing (RestClient calls) and `decideAndCreate` (the lock+insert) must live in **different beans**.

---

### Testing patterns

**`AbstractIntegrationTest`** — analog `inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java` (lines 1-59). Copy verbatim, **remove** the `LocalStackTestSupport`/`awsProperties` block entirely (no messaging this phase — RESEARCH.md explicitly calls out "Postgres-only singleton container"). Keep the singleton-container-via-static-block pattern (not `@Testcontainers`/`@Container`) and its javadoc rationale about premature container teardown across `*IT` classes.

**`TestJwt`** — analog `inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java` (full file, lines 1-109). Copy verbatim (RESEARCH.md/CONTEXT.md both say "copiar para o order-service"): in-memory RSA key pair, `sellerAdminToken()`, `buyerToken(UUID companyId)`, `JwkSetUriJwtDecoderBuilderCustomizer`-based `Config` that only swaps the key source, leaving issuer/algorithm validation on the real production path.

**`CreditLimitBoundaryConcurrencyIT`** — analog `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java` (full file, lines 1-260). Model directly on this class's structure:
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Import(TestJwt.Config.class)`, **does not** extend `AbstractIntegrationTest` (Pitfall 5: `MockMvc` never actually races)
- Real `java.net.http.HttpClient`, `Executors.newVirtualThreadPerTaskExecutor()`, `CyclicBarrier` sized to the exact contender count
- `fireContenders`-style helper returning a list of `(id, statusCode)` records
- Assert "at most one auto-approves" instead of "exact reservation count" — the domain assertion changes, the concurrency-harness code does not.

Do not reuse the JSON hand-parsing helpers (`extractString`/`extractInt`) unless the test genuinely avoids an injected `ObjectMapper` for the same reason (this class also doesn't extend `AbstractIntegrationTest`, so no Jackson bean is available for convenience — same constraint applies to the order-service version).

---

## Shared Patterns

### Error envelope
**Source:** `catalog-service/src/main/java/com/orderflow/catalog/config/GlobalExceptionHandler.java` lines 74-79
**Apply to:** every controller/exception in `order-service`
```java
private Map<String, Object> errorBody(String error, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("message", message);
    return body;
}
```

### JWT role → authority conversion + stateless resource-server config
**Source:** `catalog-service/src/main/java/com/orderflow/catalog/config/SecurityConfig.java` lines 33-44
**Apply to:** `order-service/config/SecurityConfig.java` (verbatim copy)

### Role/company-scoped read via service layer, not `@PreAuthorize` SpEL
**Source:** RESEARCH.md Pattern 3 (lines 332-358), grounded in `catalog-service/product/ProductService.getById`'s `sellerView` boolean (lines 47-55)
**Apply to:** `OrderController.getById` + `OrderService.getById`; `OrderController.list` + `OrderService.list`

### Pagination via Spring Data `Page<T>`
**Source:** `catalog-service/product/ProductController.list` (lines 70-73) + `ProductService.list` (lines 82-88)
**Apply to:** `GET /orders`

### Singleton Testcontainers Postgres + TestJwt RSA-in-memory
**Source:** `inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java` (minus LocalStack), `.../support/TestJwt.java` (verbatim)
**Apply to:** all `order-service` test classes

### Real-socket virtual-thread concurrency test with `CyclicBarrier`
**Source:** `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java`
**Apply to:** `CreditLimitBoundaryConcurrencyIT` (Success Criterion 5)

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `com.orderflow.order.config.ClientConfig` | config | request-response | First outbound-`RestClient`-with-explicit-timeouts config in this codebase; use RESEARCH.md Pattern 1 (cross-checked Spring docs) instead of an in-repo analog |
| `com.orderflow.order.client.AuthServiceClient` / `CatalogServiceClient` | service | request-response | Same — first synchronous cross-service caller; RESEARCH.md Pattern 1 |
| `com.orderflow.order.order.CompanyCreditLock` + `CompanyCreditLockRepository` | model | CRUD | First pessimistic sentinel-row lock in this codebase (inventory-service uses optimistic `@Version`+retry for a different concurrency shape); RESEARCH.md Pattern 2 |

## Metadata

**Analog search scope:** `catalog-service/src/main/java`, `auth-service/src/main/java/.../company`, `inventory-service/src/main/java/.../config` + `src/test/java` (AbstractIntegrationTest, TestJwt, StockReservationConcurrencyIT), root `pom.xml`, `docker-compose.yml`, `gateway/.../application.yml`, `scripts/smoke-notification-flow.sh`
**Files scanned:** ~15 read directly this session (plus RESEARCH.md's own already-verified reads of the same files, cross-referenced)
**Pattern extraction date:** 2026-09-24
