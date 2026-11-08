---
phase: 02-cat-logo-e-estoque
reviewed: 2026-09-20T13:50:57Z
depth: standard
files_reviewed: 44
files_reviewed_list:
  - README.md
  - auth-service/Dockerfile
  - catalog-service/Dockerfile
  - catalog-service/pom.xml
  - catalog-service/src/main/java/com/orderflow/catalog/config/GlobalExceptionHandler.java
  - catalog-service/src/main/java/com/orderflow/catalog/config/SecurityConfig.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/Product.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/ProductRepository.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/ProductService.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/ProductStatus.java
  - catalog-service/src/main/resources/application.yml
  - catalog-service/src/main/resources/db/migration/V1__init_catalog_schema.sql
  - catalog-service/src/test/java/com/orderflow/catalog/AbstractIntegrationTest.java
  - catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java
  - catalog-service/src/test/java/com/orderflow/catalog/support/TestJwt.java
  - docker-compose.yml
  - gateway/Dockerfile
  - gateway/src/main/resources/application.yml
  - inventory-service/Dockerfile
  - inventory-service/pom.xml
  - inventory-service/src/main/java/com/orderflow/inventory/InventoryServiceApplication.java
  - inventory-service/src/main/java/com/orderflow/inventory/config/GlobalExceptionHandler.java
  - inventory-service/src/main/java/com/orderflow/inventory/config/RetryConfig.java
  - inventory-service/src/main/java/com/orderflow/inventory/config/SecurityConfig.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InsufficientStockException.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/Inventory.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryNotFoundException.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryRepository.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/ReservationConflictException.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/StockBelowReservedException.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservation.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservationRepository.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/ReserveStockRequest.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/SetStockRequest.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockResponse.java
  - inventory-service/src/main/resources/application.yml
  - inventory-service/src/main/resources/db/migration/V1__init_inventory_schema.sql
  - inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/InventoryRetryContentionIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java
  - inventory-service/src/test/resources/application-test.yml
  - pom.xml
findings:
  critical: 1
  warning: 5
  info: 2
  total: 8
status: issues_found
critical_fixed: 1
critical_open: 0
---

# Phase 02: Code Review Report

**Reviewed:** 2026-09-20T13:50:57Z
**Depth:** standard
**Files Reviewed:** 44
**Status:** issues_found

## Summary

Reviewed the catalog-service and inventory-service source (entities, controllers, services,
security, exception handling, Flyway migrations, Docker/compose wiring) plus their test suites,
at standard depth. The domain logic (status transitions, upsert semantics, idempotent
reserve/release, uniform error bodies) is generally careful and well-commented, and the
integration/concurrency test suites are unusually rigorous for a portfolio project.

However, one finding is a genuine **Critical** defect: the `RetryConfig` change that is explicitly
documented as fixing the AOP-advisor-ordering race between `@Retryable` and `@Transactional`
actually reintroduces the exact ambiguity it claims to solve — verified by decompiling the actual
`spring-retry` and `spring-tx` jars resolved by this project's Maven build (see CR-01 for the
byte-level evidence). The apparent test-suite stability is very likely incidental (current
Spring/Spring Retry bean-registration order happening to favor the correct outcome), not a
guaranteed contract — this is precisely the kind of "no error, just silently wrong behavior in
some future spring-boot/spring-retry version" class of bug the code's own comments warn about
elsewhere in the same file.

Additional findings cover a Gateway/Location-header mismatch, dead exception-handling code for
401 responses, an overly broad SKU-conflict exception mapping, a silent idempotency-mismatch gap
in stock reservation, and duplication between the two services' security/error/test-support
scaffolding.

## Critical Issues

### CR-01: `RetryConfig`'s explicit advisor order reintroduces the exact AOP-ordering ambiguity it claims to fix

**File:** `inventory-service/src/main/java/com/orderflow/inventory/config/RetryConfig.java:20`

**Issue:** The class comment (lines 11–17) asserts that setting `@EnableRetry(order =
Ordered.LOWEST_PRECEDENCE)` is required so that the retry aspect wraps the transactional aspect
"from the outside," guaranteeing a fresh transaction and a fresh read on every retry attempt —
and that *without* this explicit order, the relative ordering between `@Retryable` and
`@Transactional` is undefined.

That reasoning is backwards for the *value chosen*. I decompiled the exact jars this project's
`pom.xml` resolves (`spring-retry-2.0.13.jar`, `spring-tx`/`spring-context 6.2.19.jar`, both
present in the local `~/.m2` cache used by this build) and confirmed via
`AnnotationDefault` bytecode inspection:

- `@EnableRetry.order()` defaults to **2147483646** (`Ordered.LOWEST_PRECEDENCE - 1`).
- `@EnableTransactionManagement.order()` (the annotation backing Spring Boot's `@Transactional`
  advisor autoconfiguration) defaults to **2147483647** (`Ordered.LOWEST_PRECEDENCE`).

In other words: **with no `order` override at all**, Spring Retry's advisor already has a
strictly *lower* order value (higher precedence) than the transactional advisor's default, which
is exactly what makes the retry aspect wrap the transactional aspect from the outside — the
desired behavior the comment is trying to guarantee.

By explicitly writing `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` (i.e. `2147483647`), this
code overrides that safe default and **ties** Retry's order with the transactional advisor's
default order. Two advisors with equal `order` have framework-undefined relative ordering (exactly
the "ordem... fica indefinida" scenario the comment itself warns about) — the code does not fix
the pitfall, it reintroduces it.

The reason the test suite (`InventoryRetryContentionIT`, `StockReservationConcurrencyIT`) still
appears to pass is almost certainly that the *current* Spring/Spring Boot bean registration order
happens to resolve the tie favorably in this exact dependency-version combination — not because
the code enforces it. That is indistinguishable, from the outside, from the exact "no compile
error, no startup error, retries just silently stop working the way the comment says they must"
failure mode this file's own Task-1 commit history was written to prevent (see the comment on
`RETRY_MAX_ATTEMPTS` referencing "02-RESEARCH.md Pitfall 1"). A minor library/Spring Boot upgrade
that changes either advisor's default order, or changes bean registration order, can silently flip
this back to the broken behavior with no compile-time or startup-time signal.

**Fix:** Either remove the explicit `order` entirely (rely on Spring Retry's own default, which is
already correct), or make the intent explicit and unambiguous by using a value guaranteed to be
lower than the transactional advisor's order:

```java
// Option A — trust Spring Retry's own correct default (2147483646, already lower than
// @EnableTransactionManagement's default of 2147483647):
@EnableRetry
public class RetryConfig {
}

// Option B — if the override should stay for documentation purposes, pick a value that is
// unambiguously lower than Ordered.LOWEST_PRECEDENCE, not equal to it:
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)
public class RetryConfig {
}
```
Whichever is chosen, add a regression test that asserts the *advisor order itself* (e.g. resolve
both `Advisor` beans from the `ApplicationContext` and assert
`retryAdvisor.getOrder() < transactionAdvisor.getOrder()`), so a future dependency bump that
changes either default breaks the build loudly instead of silently.

**RESOLVED 2026-09-20 (commit `3b820dc`):** Independently re-verified the byte-level claim by
decompiling `spring-retry-2.0.13.jar` (`EnableRetry.order()` default `2147483646`) and
`spring-tx-6.2.19.jar` (`EnableTransactionManagement.order()` default `2147483647`) from this
project's own `~/.m2` cache — confirmed correct. Applied Option B: `@EnableRetry(order =
Ordered.LOWEST_PRECEDENCE - 1)`, with the class Javadoc updated to explain the corrected value.
The suggested dedicated advisor-order assertion test was not added (the exact Spring Retry
internal advisor bean name was not worth the risk of a brittle, implementation-detail-coupled
test); `InventoryRetryContentionIT`'s existing deterministic forced-version-conflict scenario with
a test `RetryListener` already gives direct behavioral proof that the retry aspect wraps the
transactional aspect correctly, which is what actually matters. Full inventory-service suite
(27/27) re-verified green after the fix.

## Warnings

### WR-01: `Location` header from `POST /products` does not survive the API Gateway's path stripping

**File:** `catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java:49`

**Issue:** `ResponseEntity.created(URI.create("/products/" + response.id()))` returns a relative
`Location: /products/{id}` header. Per the gateway routes
(`gateway/src/main/resources/application.yml:20-25`), `catalog-service` is only reachable behind
the gateway at `/api/products/**` (the `StripPrefix=1` filter removes the leading `/api` segment
on the way in, but nothing rewrites the response `Location` header on the way out). A client that
resolves this relative reference against the gateway's base URL (`http://localhost:8080`), which
is the only URL the client actually used per the README's documented flow, gets
`http://localhost:8080/products/{id}` — a path with no matching gateway route, i.e. a 404. The
existing test only asserts `Location` `endsWith(productId)` (`ProductControllerIT.java:78`),
which passes whether or not the prefix is externally resolvable, so this was never caught.

**Fix:** Either build the URI relative to the current servlet-mapped request (still wrong through
the gateway, since Spring MVC has no visibility into the gateway's stripped prefix) or — more
robustly — read the prefix back from a forwarded header set by the gateway, or simply drop the
`Location` header's dependency on gateway topology by keeping it host-relative to the service and
documenting that gateway consumers must reconstruct `/api` + path themselves. A pragmatic fix
consistent with this codebase's "gateway is dumb routing only" design (D-05) is to add an
`X-Forwarded-Prefix`-aware `UriComponentsBuilder.fromCurrentContextPath()`-based location, or to
simply not assert/rely on `Location` being directly followable and document that limitation.

### WR-02: `@ExceptionHandler(AuthenticationException.class)` is unreachable for real 401s — the documented uniform error body does not apply to missing/invalid/expired tokens

**File:** `catalog-service/src/main/java/com/orderflow/catalog/config/GlobalExceptionHandler.java:68-72` (and identically `inventory-service/src/main/java/com/orderflow/inventory/config/GlobalExceptionHandler.java:92-96`)

**Issue:** The class Javadoc states: "Corpo de erro uniforme para todo o serviço — sempre as
chaves `error` e `message`" and explicitly includes `AuthenticationException` in that promise.
In practice, for a Spring Security OAuth2 Resource Server, a missing/malformed/expired/wrong-key
JWT is rejected by `BearerTokenAuthenticationFilter`/`ExceptionTranslationFilter` *inside the
security filter chain*, which runs before `DispatcherServlet` — the default
`BearerTokenAuthenticationEntryPoint` writes the 401 response directly and the exception never
reaches a `@RestControllerAdvice`. No custom `AuthenticationEntryPoint` is wired in either
`SecurityConfig` to route through this uniform body. Corroborating evidence: `ProductControllerIT`
has a dedicated test asserting the uniform `{error, message}` shape for 400/403/404/409
(`errorBodiesFor400And403And404And409ShareUniformShapeWithoutLeakage`, lines 547-589) but
deliberately does **not** include any of the several 401 tests in that assertion — because the
actual 401 body does not have that shape.

**Fix:** Either remove the dead handler (and the misleading Javadoc claim) to avoid the false
impression that 401 responses follow the same contract, or make the claim true by configuring a
custom `AuthenticationEntryPoint` bean in `SecurityConfig` that writes the same `{error, message}`
JSON shape used elsewhere, e.g.:
```java
.oauth2ResourceServer(oauth2 -> oauth2
        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
        .authenticationEntryPoint((request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"error":"unauthorized","message":"Authentication is required"}""");
        }))
```

### WR-03: catalog-service's catch-all `DataIntegrityViolationException` handler always reports "SKU already in use," even for unrelated constraint violations

**File:** `catalog-service/src/main/java/com/orderflow/catalog/config/GlobalExceptionHandler.java:50-54`

**Issue:** `@ExceptionHandler({SkuAlreadyUsedException.class, DataIntegrityViolationException.class})`
unconditionally returns `sku_already_used` / "SKU is already in use" for *any*
`DataIntegrityViolationException`, not just the `sku` unique-constraint violation. The `products`
table also has `CHECK (price >= 0)`, `NOT NULL` on `sku`/`name`/`price`, and length limits on
`sku VARCHAR(64)` and `name VARCHAR(255)`/`description VARCHAR(1000)`. If any client payload
slips past the bean-validation layer and hits one of those *other* constraints at the database
(for example a `name`/`description` longer than the column limit, since no `@Size` maximum was
confirmed in scope on `CreateProductRequest`/`UpdateProductRequest`), the client receives a
confusing `409 sku_already_used` error that has nothing to do with the SKU field, actively
misleading debugging. Contrast with `inventory-service`'s equivalent handler
(`inventory-service/.../GlobalExceptionHandler.java:80-84`), which correctly uses a generic
`data_conflict` code for the same catch-all case — the two services are inconsistent with each
other for what should be the same kind of safety net.

**Fix:** Only map the true SKU-uniqueness case to `sku_already_used`; use a distinct generic code
(matching inventory-service's `data_conflict` pattern) for any other
`DataIntegrityViolationException`:
```java
@ExceptionHandler(SkuAlreadyUsedException.class)
public ResponseEntity<Map<String, Object>> handleSkuConflict(SkuAlreadyUsedException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(errorBody("sku_already_used", "SKU is already in use"));
}

@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(errorBody("data_conflict", "The request conflicts with existing data"));
}
```

### WR-04: Idempotent reservation replay does not verify the replayed quantity matches the original reservation

**File:** `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java:120-125`

**Issue:**
```java
var existingReservation = stockReservationRepository.findByProductIdAndReservationId(productId, reservationId);
if (existingReservation.isPresent()) {
    return StockResponse.from(inventory);
}
```
The idempotent-replay short-circuit only checks that a row exists for `(productId, reservationId)`
— it never compares `existingReservation.get().getQuantity()` against the `quantity` argument of
the current call. If a caller (a future SQS consumer in Phase 5, per the class Javadoc, or a buggy
retry client today) resends the same `reservationId` with a *different* quantity than the original
request, the call silently succeeds and returns the stock state from the *original* reservation,
never informing the caller that the requested quantity was ignored. This is the class of bug that
idempotency-key implementations elsewhere (e.g. Stripe's `Idempotency-Key`) explicitly guard
against by returning a conflict when the replayed payload doesn't match the original. As written,
a producer-side bug that changes the quantity between retries (e.g. an order line edited before
the SQS message was fully acknowledged) is masked rather than surfaced.

**Fix:** Compare the requested quantity against the stored reservation's quantity on replay, and
raise a conflict (or at minimum log a warning) on mismatch:
```java
if (existingReservation.isPresent()) {
    if (existingReservation.get().getQuantity() != quantity) {
        throw new ReservationConflictException(); // or a dedicated "idempotency key reused with different payload" exception
    }
    return StockResponse.from(inventory);
}
```

### WR-05: Near-identical `SecurityConfig`, `GlobalExceptionHandler`, and test `TestJwt` duplicated verbatim between catalog-service and inventory-service

**Files:** `catalog-service/src/main/java/com/orderflow/catalog/config/SecurityConfig.java` vs.
`inventory-service/src/main/java/com/orderflow/inventory/config/SecurityConfig.java`;
`catalog-service/.../config/GlobalExceptionHandler.java` vs.
`inventory-service/.../config/GlobalExceptionHandler.java` (shared handlers, lines 1-48 and 62-79
in each); `catalog-service/src/test/java/com/orderflow/catalog/support/TestJwt.java` vs.
`inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java` (both ~100 lines,
identical except package name).

**Issue:** These three files are copy-pasted between the two services rather than shared via a
common module. With `auth-service` and (per README) upcoming order/notification services planned
for later phases, this duplication will keep growing; a fix to the JWT role-claim converter, the
uniform error-body shape, or the test JWT issuer logic (e.g. WR-02 above) has to be applied N
times and will drift if any one copy is missed — which is exactly what already happened for WR-03
(catalog and inventory's `DataIntegrityViolationException` handling diverged).

**Fix:** Extract a small shared module (e.g. `common-security`/`common-web`) hosting
`JwtAuthenticationConverter` construction, the uniform error-body `GlobalExceptionHandler` base,
and a shared `TestJwt` test-fixture artifact, and have each resource-server module depend on it.
If a shared module is out of scope for this phase, at minimum leave a code comment cross-linking
the duplicated files so a fix in one prompts a check of the other.

## Info

### IN-01: `ProductController.list()` returns `Page<ProductResponse>` directly, which Spring Data documents as unsupported/discouraged for direct JSON serialization

**File:** `catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java:70-73`

**Issue:** Returning `Page<T>` (backed by `PageImpl`) straight from a `@RestController` method is
flagged by Spring Data's own `PageImpl` deprecation-style guidance ("Serializing PageImpl
instances as-is is not supported... use Spring Data's PagedModel instead") and emits a runtime WARN
log on every request. It currently works and the tests rely on the resulting `content`/
`totalElements` shape, but it's relying on incidental Jackson serialization of an internal Spring
Data class rather than a supported contract, and is a recognized anti-pattern.

**Fix:** Wrap the response in `PagedModel`/`org.springframework.data.web.PagedModel` (via
`PagedResourcesAssembler` or `new PagedModel<>(page)`), or expose a small dedicated DTO with
`content`, `page`, `size`, `totalElements` fields.

### IN-02: `Product` entity has no optimistic-locking version column, unlike `Inventory`

**File:** `catalog-service/src/main/java/com/orderflow/catalog/product/Product.java` (contrast
with `inventory-service/.../stock/Inventory.java:48-50`, which has `@Version private long version;`)

**Issue:** `ProductService.update()` (`ProductService.java:57-63`) reads the entity, mutates it,
and relies on implicit JPA dirty-checking to persist — with no `@Version` field, two concurrent
`PUT /products/{id}` calls from different SELLER_ADMIN sessions silently produce a last-write-wins
outcome with no conflict signal, unlike the deliberate optimistic-locking design applied to
`Inventory` for the same kind of concurrent-write scenario. Given this phase already went to
considerable lengths to prove concurrency-safety for inventory (`StockReservationConcurrencyIT`,
`InventoryRetryContentionIT`), the asymmetry is worth a deliberate decision (documented "no lock
needed here because...") rather than an omission.

**Fix:** If lost updates on `Product` are acceptable for this phase (e.g. because SELLER_ADMIN
edits are expected to be low-concurrency), document that decision explicitly near
`Product.updateDetails`/`changeStatus`; otherwise add `@Version` the same way `Inventory` does.

---

_Reviewed: 2026-09-20T13:50:57Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
