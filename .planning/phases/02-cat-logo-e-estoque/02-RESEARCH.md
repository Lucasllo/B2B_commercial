# Phase 2: Catálogo e Estoque - Research

**Researched:** 2026-09-19
**Domain:** Spring Data JPA optimistic locking + Spring Retry, real-HTTP concurrency testing with Testcontainers/virtual threads, idempotent REST design
**Confidence:** MEDIUM-HIGH (core mechanism verified against the actual Spring Boot 3.5.16 BOM used by this repo; some blog-sourced pitfalls are cross-corroborated but not from official Spring docs)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Semântica de Reserva de Estoque**
- **D-08:** Estoque modelado com duas colunas — `quantity_on_hand` e `quantity_reserved` — em vez de uma quantidade única. Disponível = `on_hand - reserved`. — Reversibility: costly.
- **D-09:** O inventory-service expõe endpoints de **reservar** (incrementa `reserved`) e **liberar** (decrementa `reserved`) nesta fase. Não há endpoint de "confirmar saída" (decrementar `on_hand`) — isso só entra na Fase 5. — Reversibility: reversible.
- **D-10:** Reservar mais do que o disponível (`on_hand - reserved`) retorna **409 Conflict** com corpo explicando a insuficiência (disponível X, solicitado Y) — distinto de 400. — Reversibility: reversible.
- **D-11:** O endpoint de reservar aceita um `reservation_id` **fornecido pelo chamador**, não gerado internamente. Reservar/liberar são idempotentes por esse ID. — Reversibility: one-way (contrato usado pela saga da Fase 5, TEST-03).
- **D-12:** Estoque por **produto único**, sem depósito/local. — Reversibility: costly.
- **D-13:** Sem TTL/expiração automática de reservas órfãs nesta fase. — Reversibility: reversible.
- **D-14:** Liberar uma reserva já liberada (ou inexistente) é **idempotente** — sucesso, no-op silencioso. — Reversibility: reversible.

**Acoplamento Catalog-Inventory**
- **D-15:** O inventory-service **não valida sincronamente** (via HTTP) que um `product_id` existe no catalog-service. `product_id` é referência opaca. — Reversibility: reversible.
- **D-16:** Listagem de catálogo do BUYER e consulta de disponibilidade são **duas chamadas HTTP separadas** pelo Gateway. — Reversibility: reversible.
- **D-17:** Criar um produto **não cria** automaticamente registro de estoque. — Reversibility: reversible.
- **D-18:** Definir/atualizar quantidade de estoque é **upsert**. Reservar contra `product_id` sem linha de estoque retorna **404**. — Reversibility: reversible.
- **D-19:** Inativar produto **não propaga** para inventory-service. — Reversibility: reversible.

**Conflito de Lock Otimista**
- **D-20:** Conflito de lock otimista (JPA `@Version`) dispara **retry automático interno** (Spring Retry, `@Retryable`, 3–5 tentativas). — Reversibility: reversible.
- **D-21:** Retries esgotados → API retorna **503/409 "tente novamente"**, distinto do 409 de estoque insuficiente (D-10). — Reversibility: reversible.
- **D-22:** O teste de concorrência (Success Criteria 3) usa **HTTP real concorrente via Testcontainers** com virtual threads do Java 21, exercitando a pilha completa (controller, filtros de segurança, transação) — não chamada direta ao service/repository em threads. — Reversibility: reversible (decisão de teste).

**Modelo de Produto e Visibilidade de Estoque**
- **D-23:** Produto tem SKU e status `active`/`discontinued`. Remoção é sempre soft-delete via status, nunca `DELETE` físico. — Reversibility: one-way.
- **D-24:** Listagem do BUYER filtra `status=active` por padrão; SELLER_ADMIN vê todos. — Reversibility: reversible.
- **D-25:** BUYER vê **quantidade exata disponível** (`on_hand - reserved`), não booleano. — Reversibility: reversible.
- **D-26:** Listagem de catálogo aceita paginação (`page`/`size`, Spring Data `Pageable`). — Reversibility: reversible.

**Herdado da Fase 1**
- Preço com `BigDecimal` escala 2, `NUMERIC(19,2)`.
- Catalog-service e inventory-service usam a mesma instância Postgres, cada um com schema próprio (`catalog`, `inventory`).
- Cada serviço valida o JWT localmente (JWKS do auth-service), sem chamada síncrona por requisição.

### Claude's Discretion
- Nome exato dos endpoints REST (ex.: `POST /products`, `PUT /products/{id}/stock`).
- Estrutura exata de pacotes Java dentro de `com.orderflow.catalog` / `com.orderflow.inventory` — seguir convenção de `com.orderflow.auth`.
- Formato exato da mensagem de erro 409 (JSON body com `available`/`requested`).
- Valor exato do limite de tentativas de retry (3 vs 5) e backoff.

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CAT-01 | Vendedor cria/atualiza produtos com nome, preço e descrição | Reuses auth-service's `@Entity` + Flyway + `BigDecimal`/`NUMERIC(19,2)` pattern (see Architecture Patterns, Code Examples). SKU + status enum added per D-23. |
| CAT-02 | Comprador lista e visualiza produtos e preços disponíveis | `Pageable` listing filtered by `status='active'` for BUYER (D-24, D-26); role-based `@PreAuthorize` identical to `CompanyController` pattern. |
| INV-01 | Vendedor define/atualiza a quantidade em estoque por produto | Upsert semantics (D-18); see Code Examples for `findByProductId(...).orElseGet(...)` pattern. |
| INV-02 | Reserva de estoque usa atualização atômica/lock otimista para evitar overselling sob concorrência | Core of this research: JPA `@Version` + Spring Retry `@Retryable` ordering pitfall (Pitfall 1), idempotent reservation via unique constraint (Pitfall 3), and the real-HTTP virtual-thread concurrency test pattern (Pitfall 2, Validation Architecture). |
</phase_requirements>

## Summary

This phase's technical risk is concentrated in one place: making JPA optimistic locking (`@Version`) actually retry correctly under Spring's AOP proxy model, and proving it with a literal, real-HTTP concurrency test rather than a unit test that fakes concurrency. Both are well-trodden but easy-to-get-subtly-wrong problems in the Spring ecosystem, and this codebase already has every other building block it needs (Flyway migration pattern, `@Entity`/`@Service`/`@Controller` layering, `@PreAuthorize` role guards, Testcontainers singleton-container base class) proven working in `auth-service`.

The single most important finding is an aspect-ordering pitfall: when `@Retryable` and `@Transactional` sit on the same method (the natural, idiomatic thing to do here), Spring's default AOP advisor ordering can put the transactional advice *inside* the retry advice in the wrong way, or — more precisely — the risk is that if `@Transactional` wraps outside `@Retryable`'s exception handling, every retry attempt runs inside the *same, already-doomed* transaction. Multiple independent sources (a GitHub issue thread, two blog write-ups, and Spring's own retry/transaction advisor precedence docs) confirm the fix: declare `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` on the retry configuration class so the retry aspect wraps *outside* the transactional aspect, guaranteeing each retry attempt opens a fresh transaction against a freshly-read `@Version`. This must be an explicit line in the inventory-service's `@Configuration` class, not left to Spring defaults.

The second finding governs the concurrency test itself (Success Criteria 3, D-22): it must not use `MockMvc` (the pattern already used by `AbstractIntegrationTest` in auth-service) because MockMvc dispatches requests in-process without a real socket round-trip. The user's stated intent ("força probatória maior para o avaliador externo") requires a literal TCP-level HTTP client — Java's built-in `java.net.http.HttpClient` or Spring's `RestClient` — hitting `@LocalServerPort` on a `RANDOM_PORT`-bound embedded Tomcat, driven by `Executors.newVirtualThreadPerTaskExecutor()`, with a `CyclicBarrier` to force genuinely simultaneous request submission rather than a fast sequential loop that never contends.

**Primary recommendation:** Put `@Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 4, backoff = @Backoff(delay = 25, multiplier = 2))` and `@Transactional` on the same public `InventoryService.reserve(...)` method (called from the controller, so no self-invocation issue), add `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` on a dedicated `RetryConfig` class, enforce reservation idempotency via a `UNIQUE` constraint on `(product_id, reservation_id)` caught as `DataIntegrityViolationException`, and write the Success Criteria 3 test as a new `*IT` class using `HttpClient` + virtual threads + `CyclicBarrier` against a Testcontainers-backed Postgres — not `MockMvc`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Product catalog CRUD (CAT-01, CAT-02) | API / Backend (catalog-service) | Database (Postgres `catalog` schema) | Straightforward REST + JPA CRUD; no client-side or gateway logic beyond routing. |
| Stock level management (INV-01) | API / Backend (inventory-service) | Database (Postgres `inventory` schema) | Same as above; owns `quantity_on_hand`/`quantity_reserved`. |
| Atomic stock reservation (INV-02) | API / Backend (inventory-service) | Database (row version + unique constraint) | Concurrency correctness is enforced at the DB row level (`@Version` column) and surfaced through the service's retry loop — the API layer only translates outcomes to HTTP status codes. |
| JWT validation per request | API / Backend (each new service, independently) | — | Already locked by Phase 1 (D-02/D-03/D-05): local JWKS validation, no gateway-side auth logic. |
| Request routing to new services | API Gateway (Spring Cloud Gateway Server WebMVC) | — | Static route addition only (`Path=/api/products/**`, `Path=/api/inventory/**`), same pattern as the existing `auth-service-route`. |
| Concurrency proof (Success Criteria 3) | Test tier (Testcontainers IT, real HTTP client) | API / Backend (exercised, not mocked) | Must traverse the full stack per D-22 — placing this logic in the test tier (not a unit test double) is itself the architectural decision the phase requires. |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|---------------|
| `org.springframework.retry:spring-retry` | **2.0.13** [VERIFIED: Maven Central — `spring-boot-dependencies-3.5.16.pom`, `<spring-retry.version>2.0.13</spring-retry.version>`] | Declarative `@Retryable`/`@Recover` retry of optimistic-lock failures on reserve/release | Version is already managed transitively by the `spring-boot-dependencies` BOM this project's root `pom.xml` imports at 3.5.16 (`pom.xml:47-51`) — **do not declare an explicit `<version>` in the child module's `<dependency>`**, let the BOM resolve it. |
| `org.springframework.boot:spring-boot-starter-aop` | **3.5.16** [VERIFIED: Maven Central — `spring-boot-dependencies-3.5.16.pom`, `<artifactId>spring-boot-starter-aop</artifactId><version>3.5.16</version>`] | Provides the AspectJ/Spring AOP proxying infrastructure `@Retryable` needs (Spring Retry's annotation support is proxy-based, same mechanism as `@Transactional`) | Required dependency for `@Retryable`/`@EnableRetry` to function — without it the annotation is silently ignored (no compile error, method just never retries). |
| `spring-boot-starter-data-jpa` | 3.5.16 (already in `auth-service/pom.xml:41-43`) | `@Version`-based optimistic locking on the `inventory` and `product` entities | Already proven in this repo; no new dependency, just a new `@Version` column. |
| `org.postgresql:postgresql` | **42.7.11** [VERIFIED: Maven Central — `spring-boot-dependencies-3.5.16.pom`, `<postgresql.version>42.7.11</postgresql.version>`] | JDBC driver for both new services | Already the pinned driver via BOM; version ≥ 42.6.0 matters directly for this phase — see Pitfall 5 (virtual-thread pinning). |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `flyway-core`, `flyway-database-postgresql`, `lombok` | Same versions as `auth-service/pom.xml` (BOM-managed) | Identical role/purpose to `auth-service` | Copy the exact dependency block from `auth-service/pom.xml:18-75` into both new modules' `pom.xml` — this repo already made and verified these choices in Phase 1. |
| `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`, `spring-boot-testcontainers` | 1.20.4 (parent BOM, `pom.xml:35`) | Real Postgres in integration tests | Same singleton-container pattern as `AbstractIntegrationTest.java` — reuse, do not reinvent. |
| Java 21 built-in `java.net.http.HttpClient` | JDK 21 (no dependency) | Real socket-level concurrent HTTP calls for the Success Criteria 3 test | Zero new dependency; fully virtual-thread-friendly (NIO-based, does not pin). Preferred over adding `TestRestTemplate`/`RestClient` complexity for a test that only needs to fire POSTs and read status codes. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JPA optimistic locking (`@Version`) + Spring Retry | Pessimistic locking (`SELECT ... FOR UPDATE` / `@Lock(PESSIMISTIC_WRITE)`) | INV-02 already locks in "lock otimista" as the mechanism (REQUIREMENTS.md), and pessimistic locking would hold DB row locks across the whole request, which is worse for this B2B catalog's read-heavy access pattern; optimistic + retry is also the more common interview talking point for the target role. |
| Spring Retry (`@Retryable`) | Manual `while` loop with `try/catch(ObjectOptimisticLockingFailureException)` around a `TransactionTemplate.execute(...)` call | Manual loop is what Vlad Mihalcea's reference implementation actually uses and sidesteps the `@Retryable`+`@Transactional` aspect-ordering pitfall entirely (Pitfall 1) — a legitimate, arguably *simpler* alternative if the ordering issue proves fragile in practice; Claude's Discretion in CONTEXT.md leaves retry mechanics open, but D-20 explicitly names Spring Retry `@Retryable` as the locked choice, so this is documented as a fallback only. |
| Java `HttpClient` for the concurrency test | `TestRestTemplate` (Spring Boot test starter, already on the classpath) | `TestRestTemplate` is equally real-HTTP and arguably more idiomatic for Spring Boot tests, but its underlying `RestTemplate` is blocking/synchronous per call in a way that's less obviously "real HTTP" to a reviewer skimming code; either is acceptable, `HttpClient` was chosen for zero-dependency simplicity. |

**Installation (per new module's `pom.xml`, added to `catalog-service`/`inventory-service` alongside the `auth-service`-identical block):**
```xml
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
    <!-- no <version> — managed by spring-boot-dependencies BOM (root pom.xml) at 2.0.13 -->
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
    <!-- no <version> — managed by spring-boot-dependencies BOM at 3.5.16 -->
</dependency>
```

**Version verification:** confirmed directly against `spring-boot-dependencies-3.5.16.pom` (the exact BOM version pinned in this repo's `pom.xml:31`), downloaded from `repo1.maven.org` and grepped this session — not training-data recall.

## Package Legitimacy Audit

> The `gsd-tools package-legitimacy check` seam only supports `npm`/`pypi`/`crates` ecosystems (confirmed this session: `Usage: gsd-tools package-legitimacy check --ecosystem <npm|pypi|crates> <pkg1> ...`) — it does **not** cover Maven. Both new dependencies were instead verified manually against Maven Central directly.

| Package | Registry | Age | Group Ownership | Verdict | Disposition |
|---------|----------|-----|------------------|---------|-------------|
| `org.springframework.retry:spring-retry` | Maven Central | Latest release 2.0.12 published 2025-05-16 [VERIFIED: `search.maven.org` solr query this session]; project itself dates to 2013 (formerly Spring Batch's retry module) | `org.springframework.retry` — official Spring project group ID, also directly referenced and version-pinned inside the official `spring-boot-dependencies` BOM | OK | Approved |
| `org.springframework.boot:spring-boot-starter-aop` | Maven Central | Ships with every Spring Boot release since 1.x | `org.springframework.boot` — the Spring Boot project's own group ID | OK | Approved |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none. Both artifacts are owned by the same official Spring group IDs already used throughout this codebase (`org.springframework.boot`, and `org.springframework.retry` is the canonical, only publisher of the `spring-retry` artifact) — no third-party or unverified publisher risk.

## Architecture Patterns

### System Architecture Diagram

```
                         ┌─────────────────────────┐
 BUYER / SELLER_ADMIN ──▶│   API Gateway (8080)     │
   (JWT: role, company)  │  Spring Cloud Gateway    │
                         │  Server WebMVC (static   │
                         │  routes, StripPrefix=1)  │
                         └──────┬──────────┬────────┘
                    /api/products/**   /api/inventory/**
                                │              │
                                ▼              ▼
                   ┌────────────────────┐ ┌────────────────────┐
                   │  catalog-service    │ │ inventory-service   │
                   │  (new, port 808x)   │ │ (new, port 808y)    │
                   │                     │ │                     │
                   │ JwtAuthConverter    │ │ JwtAuthConverter    │
                   │ (local JWKS check,  │ │ (local JWKS check,  │
                   │  copied from auth)  │ │  copied from auth)  │
                   │        │            │ │        │            │
                   │ @PreAuthorize role  │ │ @PreAuthorize role  │
                   │        │            │ │        │            │
                   │ ProductController   │ │ InventoryController │
                   │        │            │ │        │            │
                   │ ProductService      │ │ InventoryService    │
                   │  (plain @Transact.) │ │  @Retryable +        │
                   │        │            │ │  @Transactional      │
                   │        ▼            │ │  (fresh tx/attempt)  │
                   │ ProductRepository   │ │        │            │
                   │        │            │ │        ▼            │
                   │        ▼            │ │ InventoryRepository │
                   │  Postgres schema    │ │        │            │
                   │  `catalog`          │ │        ▼            │
                   └─────────────────────┘ │  Postgres schema    │
                                            │  `inventory`         │
                                            │  (@Version column,   │
                                            │   UNIQUE(product_id, │
                                            │   reservation_id))   │
                                            └─────────────────────┘
No synchronous HTTP call crosses the catalog↔inventory boundary (D-15) — product_id
is an opaque UUID reference on both sides; each service validates JWT independently.
```

### Recommended Project Structure

```
catalog-service/
├── src/main/java/com/orderflow/catalog/
│   ├── CatalogServiceApplication.java
│   ├── config/
│   │   ├── SecurityConfig.java        # copy of auth-service's, no login/JWKS issuing here
│   │   └── GlobalExceptionHandler.java
│   └── product/
│       ├── Product.java               # @Entity, status enum, SKU
│       ├── ProductStatus.java         # enum ACTIVE, DISCONTINUED
│       ├── ProductController.java
│       ├── ProductService.java
│       ├── ProductRepository.java
│       └── dto/
│           ├── CreateProductRequest.java
│           ├── UpdateProductRequest.java
│           └── ProductResponse.java
└── src/main/resources/db/migration/
    └── V1__init_catalog_schema.sql

inventory-service/
├── src/main/java/com/orderflow/inventory/
│   ├── InventoryServiceApplication.java
│   ├── config/
│   │   ├── SecurityConfig.java
│   │   ├── RetryConfig.java            # @EnableRetry(order = Ordered.LOWEST_PRECEDENCE)
│   │   └── GlobalExceptionHandler.java
│   └── stock/
│       ├── Inventory.java              # @Entity, @Version, quantity_on_hand/reserved
│       ├── InventoryController.java
│       ├── InventoryService.java       # @Retryable + @Transactional reserve()/release()
│       ├── InventoryRepository.java
│       ├── InsufficientStockException.java   # -> 409
│       ├── InventoryNotFoundException.java   # -> 404
│       └── dto/
│           ├── SetStockRequest.java
│           ├── ReserveStockRequest.java      # includes reservationId
│           └── StockResponse.java
└── src/main/resources/db/migration/
    └── V1__init_inventory_schema.sql
```

### Pattern 1: `@Retryable` + `@Transactional` on the same method, with explicit advisor ordering

**What:** Retry optimistic-lock failures by re-executing the whole read-modify-write against a fresh transaction each time, without a manual retry loop.
**When to use:** `InventoryService.reserve(...)` and `InventoryService.release(...)` — anywhere a JPA `@Version` check can fail under contention.
**Why the ordering annotation is required, not optional:** By default, Spring applies AOP advisors without a guaranteed order between `@Retryable`'s advisor and `@Transactional`'s advisor. Multiple independently-authored sources agree on the failure mode and the fix: if the transactional advice ends up *inside* the retry loop's own transaction boundary rather than wrapping a *fresh* transaction per attempt, the first `ObjectOptimisticLockingFailureException` marks that (shared) transaction rollback-only, and every subsequent retry attempt fails immediately with an `UnexpectedRollbackException` instead of getting a clean re-read of the row — silently defeating D-20/D-21 (source: [CITED: tmsvr.com — "Why Your @Retryable Fails with @Transactional in Spring"], corroborated by [CITED: javacodegeeks.com — "Spring @Retryable With @Transactional"], both independently describing the same `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` fix; MEDIUM confidence — blog sources, not official Spring documentation, but two independent write-ups reach byte-identical conclusions).

```java
// Source: pattern synthesized from CITED blog sources above + this repo's existing
// CompanyService.java (auth-service/src/main/java/com/orderflow/auth/company/CompanyService.java:36-60)
// for the controller->service @Transactional convention already established in this codebase.

// config/RetryConfig.java
@Configuration
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE) // retry advice must wrap OUTSIDE @Transactional
public class RetryConfig {
}

// stock/InventoryService.java
@Service
public class InventoryService {

    private final InventoryRepository repository;

    @Retryable(
        retryFor = ObjectOptimisticLockingFailureException.class,
        maxAttempts = 4,
        backoff = @Backoff(delay = 25, multiplier = 2))
    @Transactional
    public StockResponse reserve(UUID productId, int quantity, String reservationId) {
        Inventory inventory = repository.findByProductId(productId)
                .orElseThrow(InventoryNotFoundException::new);        // -> 404 (D-18)

        if (repository.existsByProductIdAndReservationId(productId, reservationId)) {
            return StockResponse.from(inventory);                     // idempotent replay (D-11)
        }

        int available = inventory.getQuantityOnHand() - inventory.getQuantityReserved();
        if (quantity > available) {
            throw new InsufficientStockException(available, quantity); // -> 409 (D-10)
        }

        inventory.reserve(quantity, reservationId);
        repository.saveAndFlush(inventory); // flush forces the versioned UPDATE now,
                                             // inside THIS attempt's transaction
        return StockResponse.from(inventory);
    }

    @Recover
    public StockResponse recoverFromExhaustedRetries(ObjectOptimisticLockingFailureException ex,
                                                       UUID productId, int quantity, String reservationId) {
        throw new ReservationConflictException(); // -> 503/409 "try again" (D-21)
    }
}
```

**Critical corollary — no self-invocation:** because `InventoryController` calls `inventoryService.reserve(...)` from a *different* Spring bean, both the `@Retryable` and `@Transactional` advice on `reserve(...)` are correctly applied by the proxy. This only works because the annotated method is called cross-bean; do **not** later refactor `reserve(...)` to be called internally from another method on `InventoryService` itself — that call would bypass both proxies silently, with no compile-time or runtime error (source: [CITED: GitHub spring-attic/spring-retry issue #180 — "@Retryable methods are not intercepted when called from same bean"]).

### Pattern 2: Idempotency via database-level unique constraint, not check-then-act

**What:** `reservation_id` (D-11) is the idempotency key. Uniqueness is enforced by a `UNIQUE(product_id, reservation_id)` constraint at the database level; the application catches the resulting `DataIntegrityViolationException` rather than relying solely on an application-level "does it exist?" check before insert.
**When to use:** Any endpoint receiving a caller-provided ID meant to be safe against retries/redelivery — directly reusable in Phase 5's SQS consumer idempotency (TEST-03), per D-11's explicit note that this is the same mechanism.
**Why not check-then-act alone:** a `findByReservationId` check followed by a separate `save` is a classic TOCTOU race — two concurrent requests with the same `reservation_id` can both pass the check before either commits. The unique constraint is the actual safety net; the "check first" step (as shown in Pattern 1 above, `existsByProductIdAndReservationId`) is only a fast-path to make the *legitimate* (non-racing) replay case return the prior result cleanly, matching this codebase's existing pattern in `CompanyService.createCompanyWithBuyer` (`auth-service/src/main/java/com/orderflow/auth/company/CompanyService.java:38-42`), whose own comment already states this exact philosophy: *"Checagem antecipada, defensiva... mas checar antes evita gravar... em cenários sem condição de corrida"* — i.e., the pre-check is a UX/perf optimization, the constraint is the correctness guarantee (source: [CITED: general REST idempotency-key pattern write-ups converging on "unique constraint + catch IntegrityError, not app-level pre-check alone" — Zuplo, dev.to, multiple 2025/2026 sources]).

```sql
-- V1__init_inventory_schema.sql (pattern from auth-service's V1 migration,
-- auth-service/src/main/resources/db/migration/V1__init_auth_schema.sql:1-20)
CREATE TABLE inventory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL UNIQUE,
    quantity_on_hand INTEGER NOT NULL DEFAULT 0,
    quantity_reserved INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency ledger for reserve/release (D-11, D-14). A separate table (not a column
-- on `inventory`) because a single product can have many concurrent reservation attempts,
-- and this is exactly the shape Phase 5's saga will read/write for SQS redelivery.
CREATE TABLE stock_reservations (
    reservation_id VARCHAR(255) NOT NULL,
    product_id UUID NOT NULL REFERENCES inventory(product_id),
    quantity INTEGER NOT NULL,
    released BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, reservation_id)
);
```

```java
// GlobalExceptionHandler.java addition (inventory-service) — same shape convention as
// auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java:45-49
@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<Map<String, Object>> handleDuplicateReservation(DataIntegrityViolationException ex) {
    // Constraint violation on (product_id, reservation_id) means a concurrent request won
    // the race for the same idempotency key — re-read and return the now-committed state
    // rather than surfacing a raw 409/500 for what is, semantically, a successful replay.
    ...
}
```

### Pattern 3: Real-HTTP, virtual-thread concurrency test (Success Criteria 3, D-22)

**What:** Fire N genuinely simultaneous HTTP requests against the running embedded server (not `MockMvc`, not direct service calls) targeting the last unit(s) of stock, and assert exactly the available quantity was reserved.
**When to use:** The one mandatory test for INV-02's atomicity claim.
**Why not the existing `AbstractIntegrationTest`/`MockMvc` base as-is:** `AbstractIntegrationTest` (auth-service, lines 31-33) uses `@AutoConfigureMockMvc` — `MockMvc` dispatches directly into the `DispatcherServlet` in-process without a real socket, so while it *does* traverse the security filter chain and controller, it is not "HTTP real" in the literal sense D-22 and the CONTEXT.md specifics call for ("força probatória maior para o avaliador externo"). A new IT class must instead bind a real port (`webEnvironment = RANDOM_PORT` + `@LocalServerPort`) and drive it with a genuine HTTP client.
**Synchronization requirement:** a naive `for` loop submitting tasks to an executor does not guarantee the requests actually overlap in time — the first request may fully complete (including its DB transaction) before the second even starts, silently turning a "concurrency test" into a sequential one that always passes for the wrong reason. Use a `CyclicBarrier` sized to the thread count so every thread blocks until all are ready, then releases simultaneously (source: [CITED: multiple 2025/2026 concurrency-testing write-ups converging on "set stock to 1, fire N concurrent buys for 1 unit, synchronize thread start" as the standard pattern for this exact race]).

```java
// Source: pattern synthesized from CITED web sources on virtual-thread + CyclicBarrier
// concurrency testing, adapted to this repo's AbstractIntegrationTest conventions
// (auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java:31-46).

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StockReservationConcurrencyIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");
    static { postgres.start(); }

    @LocalServerPort
    int port;

    @Test
    void concurrentReservationsAgainstLastUnitsNeverOversell() throws Exception {
        int stock = 1;               // last unit
        int threadCount = 20;        // far more contenders than available stock
        setStock(productId, stock);

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Integer>> futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            barrier.await(); // all threads release together -> real contention
                            return reserve(productId, 1, "reservation-" + i);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, executor))
                    .toList();

            long successCount = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(status -> status == 200)
                    .count();

            assertThat(successCount).isEqualTo(stock); // exactly 1 succeeded, never more
        }
    }

    private int reserve(UUID productId, int quantity, String reservationId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/inventory/" + productId + "/reserve"))
                .header("Authorization", "Bearer " + sellerAdminToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"quantity\":%d,\"reservationId\":\"%s\"}".formatted(quantity, reservationId)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
```

### Anti-Patterns to Avoid

- **Manual `while` retry loop competing with `@Retryable` on the same code path:** pick one mechanism (Spring Retry, per D-20) and let `@Recover` handle the exhausted case — mixing both makes the actual retry count and backoff impossible to reason about.
- **Checking `if (available >= quantity)` in Java and then calling `save()` without relying on `@Version`:** this reintroduces the exact race the phase exists to close; the `@Version` column and its `ObjectOptimisticLockingFailureException` on conflicting `UPDATE` are the actual enforcement mechanism, the Java-level check is only for producing the clean 409 (D-10) in the *uncontended* case.
- **Testing the concurrency requirement with `MockMvc` or by calling `InventoryService.reserve(...)` directly from multiple `Thread`s:** explicitly excluded by D-22; also `MockMvc`'s shared request/response cycle is not designed for concurrent invocation guarantees the way a real socket-per-request model is.
- **Firing concurrent test requests without a barrier/latch:** produces a flaky-green test that "passes" because the requests never actually overlapped — worse than no test, because it looks like proof of atomicity while proving nothing.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|--------------|-----|
| Retrying on optimistic lock conflict | Custom `try/catch` loop with manual `Thread.sleep` backoff | Spring Retry's `@Retryable`/`@Backoff`/`@Recover` (D-20 already locks this choice) | Handles exponential backoff, max-attempts, and exception-type filtering declaratively; a hand-rolled loop needs to reinvent all of this and is easy to get wrong on the transaction-boundary issue (Pitfall 1) even when done carefully. |
| Pagination | Manual `LIMIT`/`OFFSET` SQL and total-count math | Spring Data's `Pageable`/`Page<T>` (D-26 already specifies this) | Already idiomatic in this stack; hand-rolled paging usually gets the total-count query or the last-page edge case wrong. |
| Idempotency bookkeeping | An in-memory `Set<String>` of seen reservation IDs, or a "check then insert" without a DB constraint | A `UNIQUE` database constraint + catch `DataIntegrityViolationException` (Pattern 2) | In-memory dedup does not survive a service restart and does not work across the multiple app instances this architecture could scale to; the DB constraint is the only correctness guarantee available under real concurrency. |
| Monetary values | `double`/`float` for `price` | `BigDecimal` + `NUMERIC(19,2)` (already D-06 from Phase 1, reused here for `Product.price`) | Already an established, correct convention in this codebase — no reason to deviate for the new `price` column. |

**Key insight:** every hand-roll temptation in this phase (retry loops, pagination, idempotency dedup) has a name-brand Spring/JPA mechanism that already exists in this project's dependency tree or one dependency away — the actual engineering work is wiring them together correctly (the ordering/proxy pitfalls above), not building new primitives.

## Common Pitfalls

### Pitfall 1: `@Retryable` + `@Transactional` aspect ordering silently defeats retries
**What goes wrong:** All retry attempts fail immediately after the first `ObjectOptimisticLockingFailureException`, typically surfacing as `UnexpectedRollbackException` or the retries "not seeming to happen" even though `maxAttempts` is configured correctly.
**Why it happens:** Without an explicit ordering, Spring's transactional advice can end up wrapping the exception inside the *same* transaction the retry loop is (wrongly) reusing, rather than each retry attempt getting a fresh transaction and a fresh read of the entity's `@Version`.
**How to avoid:** `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` on the retry configuration class, so the retry advisor is guaranteed to wrap *outside* the transactional advisor (Pattern 1).
**Warning signs:** In manual testing, forcing two concurrent updates always results in a failure on the *second* request even though retry is configured with `maxAttempts > 1`; logs show the same transaction ID/PersistenceContext across "retry" attempts.
**Confidence:** MEDIUM — [CITED: tmsvr.com, javacodegeeks.com], cross-corroborated by two independent sources but not confirmed against official Spring Framework/Spring Retry documentation this session. Given the criticality of this exact mechanism to INV-02, the planner should add a task that explicitly verifies retry-under-contention behavior with a small, cheap unit-level trigger (e.g., a test that manually causes a version conflict between two sequential saves and confirms a `@Recover` or success path, before relying on the full concurrency IT to prove it end-to-end).

### Pitfall 2: Self-invocation silently bypasses both `@Retryable` and `@Transactional`
**What goes wrong:** Calling a `@Retryable`/`@Transactional` method from another method on the *same* Spring bean instance does not go through the AOP proxy, so neither retry nor transaction semantics apply — with no compile error and no runtime exception, just silently wrong behavior.
**Why it happens:** Spring's default (non-AspectJ-weaving) AOP is proxy-based; only calls arriving from *outside* the bean (e.g., controller → service) pass through the proxy.
**How to avoid:** Keep `reserve(...)`/`release(...)` as the entry point called directly by the controller (already the existing convention in this codebase, e.g. `CompanyController` → `CompanyService`); never refactor to have `InventoryService` call its own `reserve` method internally from another method in the same class.
**Warning signs:** A refactor that extracts a "helper" method inside `InventoryService` and has `reserve()` call it — if that helper is where `@Transactional`/`@Retryable` end up, they stop working.
**Confidence:** HIGH — this is a long-standing, extensively documented Spring AOP proxy limitation [CITED: GitHub spring-attic/spring-retry#180], not specific to this version of Spring.

### Pitfall 3: Testing "concurrency" without forcing real overlap
**What goes wrong:** A test that submits N tasks to an `ExecutorService` in a simple loop can have all N requests complete sequentially before any of them actually contend for the same row, especially with virtual threads on a fast local machine — the test passes, but proves nothing about the actual race condition.
**Why it happens:** Task submission order and JVM scheduling do not guarantee simultaneous start; a fast-enough first request can commit before a second one begins.
**How to avoid:** Use a `CyclicBarrier` (or `CountDownLatch` released after all threads are parked) sized to the thread count, forcing every thread to block until all are ready and then release together (Pattern 3).
**Warning signs:** The test passes even when the `@Version`/retry mechanism is deliberately broken (a good sanity check while writing the test: temporarily remove `@Version` and confirm the test now fails by over-reserving).
**Confidence:** MEDIUM — [CITED: multiple 2025/2026 concurrency-testing articles], general software testing knowledge, not Spring-specific documentation.

### Pitfall 4: `@Enumerated` defaulting to `ORDINAL` for the product `status` field
**What goes wrong:** If `Product.status` (D-23, `active`/`discontinued`) is mapped with a bare `@Enumerated` (no argument), JPA defaults to storing the enum's ordinal position (0, 1, ...) as an integer. Reordering the enum's declared values later — or adding a third status — silently corrupts existing rows' meaning.
**Why it happens:** `EnumType.ORDINAL` is JPA's default when `@Enumerated`'s `value` is omitted.
**How to avoid:** Always specify `@Enumerated(EnumType.STRING)` explicitly, or (matching this codebase's existing `Role` column, `auth-service/.../V1__init_auth_schema.sql:15`, which stores role as `VARCHAR(20)` with a `CHECK` constraint rather than relying on JPA's enum mapping alone) map `status` as `VARCHAR` with a Postgres `CHECK (status IN ('active','discontinued'))` constraint, giving a second layer of defense against invalid values written outside the JPA layer.
**Warning signs:** none visible until an enum reorder/addition, which is exactly why this must be locked in from the first migration.
**Confidence:** HIGH — this is standard, universally-documented JPA behavior, and the `VARCHAR`+`CHECK` mitigation is directly verified against this repo's own existing convention [VERIFIED: `auth-service/src/main/resources/db/migration/V1__init_auth_schema.sql:15`, `role VARCHAR(20) NOT NULL CHECK (role IN ('BUYER', 'SELLER_ADMIN'))`].

### Pitfall 5: Virtual-thread pinning under blocking JDBC calls (Java 21, pre-JEP 491)
**What goes wrong:** On Java 21–23 (JEP 491's un-pinning fix ships only in Java 24+), a virtual thread that blocks inside a `synchronized` block — common in older JDBC drivers and connection pool internals — stays pinned to its OS carrier thread for the duration of the blocking call, defeating the scalability benefit of virtual threads and, under enough concurrent load, potentially starving the small carrier-thread pool.
**Why it happens:** JEP 444 (Java 21's virtual threads) does not yet fix pinning on `synchronized`; that fix is JEP 491, landing in Java 24.
**How to avoid:** This project already uses PostgreSQL JDBC driver 42.7.11 (verified above), which is past the 42.6.0 threshold where the driver replaced its internal `synchronized` methods with reentrant locks — so the *driver* itself should not cause pinning. This mitigates but does not eliminate the risk: HikariCP and Hibernate internals are not fully audited here. This matters most if the *server-side* request-handling thread pool is switched to virtual threads (`spring.threads.virtual.enabled=true`) — which is **not** locked by any CONTEXT.md decision for this phase. The concurrency test's *client-side* executor (`Executors.newVirtualThreadPerTaskExecutor()`, per D-22) making outbound HTTP calls via `java.net.http.HttpClient` is not at risk (that client is fully NIO-based, non-blocking).
**Warning signs:** run with `-Djdk.tracePinnedThreads=full` during the concurrency test if request latency under load looks anomalous.
**Confidence:** MEDIUM — the general virtual-thread-pinning mechanism is well documented [CITED: multiple 2025/2026 virtual-threads articles]; the specific "driver version 42.6+ fixed this" claim and the exact 42.7.11 version in use are both [VERIFIED: Maven Central `spring-boot-dependencies-3.5.16.pom`] this session, but whether `spring.threads.virtual.enabled` should be turned on for catalog-service/inventory-service at all is an open question, not a locked decision — see Open Questions.

## Code Examples

### Optimistic-locking entity with `@Version`
```java
// Source: pattern from official JPA/Hibernate @Version semantics, adapted to this repo's
// entity conventions (auth-service/src/main/java/com/orderflow/auth/company/Company.java:24-32).
@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false, unique = true)
    private UUID productId;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved;

    @Version
    @Column(nullable = false)
    private long version;

    public void reserve(int quantity, String reservationId) {
        this.quantityReserved += quantity;
    }

    public void release(int quantity) {
        this.quantityReserved = Math.max(0, this.quantityReserved - quantity);
    }
}
```

### Recover method signature (must match the `@Retryable` method's parameters, plus the exception first)
```java
// Source: pattern converging across CITED @Recover write-ups (Medium, DZone) — parameter
// order/type-matching is what Spring uses to bind the recovery method at runtime.
@Recover
public StockResponse recoverFromExhaustedRetries(
        ObjectOptimisticLockingFailureException ex, UUID productId, int quantity, String reservationId) {
    throw new ReservationConflictException(); // mapped to 503/409 by GlobalExceptionHandler (D-21)
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|-------------------|---------------|--------|
| Platform-thread pools (`Executors.newFixedThreadPool`) for concurrency tests | `Executors.newVirtualThreadPerTaskExecutor()` (Java 21+) | JDK 21 (2023), locked by D-22 for this project | Cheap to spin up 20+ concurrent "requesters" without pool-sizing tuning; must still watch for pinning (Pitfall 5) on the server side, not the test client side. |
| `synchronized`-based JDBC drivers pinning virtual threads | Reentrant-lock-based drivers (PostgreSQL JDBC ≥ 42.6.0) | pgjdbc 42.6.0 release | Removes the most common pinning source for this stack's specific driver; project is already on 42.7.11. |
| Manual retry loops around `OptimisticLockException` | Spring Retry's `@Retryable`/`@Recover` | Long-standing (Spring Retry has existed since ~2013), but its correct interaction with `@Transactional` ordering remains a live gotcha even in 2025/2026 write-ups | Declarative retry is standard, but the ordering pitfall (Pitfall 1) is not something Spring "fixed" — it is a structural consequence of proxy-based AOP that must be handled explicitly every time these two annotations combine. |

**Deprecated/outdated:** none directly relevant beyond the general Spring Boot 4.0/Jackson 3 migration noise already flagged in `.claude/CLAUDE.md`, which this phase does not touch (staying on Boot 3.5.16 per that document's locked decision).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` is sufficient to guarantee retry-wraps-transaction ordering in this project's exact Spring Boot 3.5.16 / Spring Framework 6.x combination | Pattern 1, Pitfall 1 | If the ordering behavior differs subtly in this exact version combination, retries could still silently fail after the first conflict; mitigated by recommending an explicit small-scale verification task before relying on the full concurrency IT (see Pitfall 1). |
| A2 | `MockMvc` does not satisfy D-22's "HTTP real" requirement and a real socket-level client is needed instead | Pattern 3 | If the user actually considers `MockMvc` (which does traverse the full filter chain/controller/transaction) sufficient, the recommended new test class adds unneeded complexity — low risk either way since both approaches produce a valid concurrency test, but worth confirming during planning since it affects test-authoring effort. |
| A3 | HikariCP (the default Spring Boot connection pool) does not itself reintroduce virtual-thread pinning independent of the JDBC driver version | Pitfall 5 | If HikariCP internals still use `synchronized` in a way that pins under Java 21, the mitigation claim (driver-only fix) is incomplete; risk is limited because the *test client* thread pool (the only place D-22 mandates virtual threads) is not what talks to HikariCP — the server-side request thread is, and virtual threads are not mandated there by any locked decision. |

## Open Questions

1. **Should catalog-service/inventory-service enable `spring.threads.virtual.enabled=true` for their own request-handling thread pool (Tomcat), independent of the test client's virtual threads?**
   - What we know: D-22 only mandates virtual threads for the *test's* HTTP-firing executor, not the server's request thread model. CLAUDE.md's tech-stack doc frames virtual threads as a general Java 21 selling point but only explicitly wires them into the Gateway (via Spring Cloud Gateway Server WebMVC).
   - What's unclear: whether the planner should also flip this flag on the two new services purely for the "modern concurrency" portfolio narrative, given Pitfall 5's caveat that doing so increases (not eliminates) pinning exposure on Java 21.
   - Recommendation: leave it off for this phase (matches CLAUDE.md's existing choice for `auth-service`, which does not set this flag either) — treat it as a candidate for a later "hardening" phase discussion, not INV-02's correctness requirement.

2. **Exact retry `maxAttempts`/backoff values (3 vs 5, delay/multiplier)** — explicitly left to Claude's Discretion in CONTEXT.md.
   - What we know: `@Retryable`'s own documented default is 3 total attempts (1 initial + 2 retries) with a 1-second fixed delay if unconfigured.
   - What's unclear: whether the phase's concurrency test (20 contenders for 1 unit) needs a higher `maxAttempts` to reliably converge to exactly 1 success without flaking, since 19 of 20 requests will hit a conflict and need at least one retry each.
   - Recommendation: start with `maxAttempts = 4`, exponential backoff (`delay = 25ms, multiplier = 2`) as shown in Pattern 1's code example — short enough not to slow the test suite, generous enough to absorb 19-way contention on a single row; adjust empirically once the test is written (this is exactly why it's Claude's Discretion, not a locked value).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|--------------|-----------|---------|----------|
| Java (JDK) | Compile/run all new modules | ✓ | 21.0.10 LTS [VERIFIED: `java -version` this session] | — |
| Docker | Testcontainers (Postgres) + docker-compose | ✓ | 29.8.0 [VERIFIED: `docker --version` this session] | — |
| Maven (`mvnw`) | Multi-module reactor build | ✓ | 3.9.9, using JDK 21.0.10 [VERIFIED: `./mvnw -v` this session] | — |
| PostgreSQL (via docker-compose, existing) | New `catalog`/`inventory` schemas on the same instance | ✓ (already running per Phase 1, `docker-compose.yml:2-17`) | `postgres:16.15` (pinned) | — |

**Missing dependencies with no fallback:** none — this phase adds no new external service dependency (D-15 explicitly avoids any new synchronous integration), only new Maven modules and DB schemas on already-provisioned infrastructure.
**Missing dependencies with fallback:** none.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito (`spring-boot-starter-test`, BOM-managed) + Testcontainers 1.20.4, identical to `auth-service` |
| Config file | No standalone config file — behavior mirrors `auth-service/src/test/resources/application-test.yml` (currently empty of overrides; profile `test` activated via `@ActiveProfiles`) |
| Quick run command | `./mvnw -pl catalog-service,inventory-service -am test` (Surefire — unit tests, `*Test.java`) |
| Full suite command | `./mvnw -pl catalog-service,inventory-service -am verify` (Surefire + Failsafe — includes `*IT.java` Testcontainers integration tests, per `pom.xml:124-141`'s existing Failsafe binding) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|---------------------|--------------|
| CAT-01 | SELLER_ADMIN creates/updates product; role enforced | integration (`*IT`, Testcontainers) | `./mvnw -pl catalog-service -am verify -Dit.test=ProductControllerIT` | ❌ Wave 0 |
| CAT-02 | BUYER lists/views active products, paginated | integration (`*IT`) | `./mvnw -pl catalog-service -am verify -Dit.test=ProductControllerIT` | ❌ Wave 0 |
| INV-01 | SELLER_ADMIN sets/updates stock quantity (upsert) | integration (`*IT`) | `./mvnw -pl inventory-service -am verify -Dit.test=InventoryControllerIT` | ❌ Wave 0 |
| INV-02 | Reservation is atomic under concurrency; never oversells | integration (`*IT`, real HTTP + virtual threads) | `./mvnw -pl inventory-service -am verify -Dit.test=StockReservationConcurrencyIT` | ❌ Wave 0 |
| INV-02 (idempotency) | Repeated `reservation_id` does not double-decrement | unit + integration | `./mvnw -pl inventory-service test -Dtest=InventoryServiceTest` + `-Dit.test=InventoryControllerIT` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./mvnw -pl <module> -am test` (fast unit tests only)
- **Per wave merge:** `./mvnw -pl catalog-service,inventory-service -am verify` (full suite, including the concurrency IT)
- **Phase gate:** Full suite green (including `StockReservationConcurrencyIT`) before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `catalog-service/src/test/java/com/orderflow/catalog/AbstractIntegrationTest.java` — new module, no test infra yet; copy the singleton-container pattern from `auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java:31-46` verbatim (schema differs: `catalog` not `auth`).
- [ ] `inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java` — same, for `inventory` schema.
- [ ] `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java` — new class, does **not** extend the `MockMvc`-based `AbstractIntegrationTest`; needs its own `RANDOM_PORT` + real `HttpClient` setup (Pattern 3).
- [ ] Both new modules' `pom.xml` — need the full `auth-service`-identical test dependency block (`spring-boot-starter-test`, `spring-boot-testcontainers`, `testcontainers:junit-jupiter`, `testcontainers:postgresql`) plus `spring-retry`/`spring-boot-starter-aop` for inventory-service's main code.
- [ ] Framework install: none — Testcontainers/JUnit5/Mockito are already proven working via `auth-service`'s existing test suite; this is pure module duplication, not new tooling.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|----------------|---------|--------------------|
| V2 Authentication | Indirect (inherited) | JWT validated locally via `spring-boot-starter-oauth2-resource-server` + JWKS from auth-service — already implemented Phase 1 pattern, copied verbatim into both new services' `SecurityConfig`. |
| V3 Session Management | No | Stateless JWT-only API, no server-side session (same as Phase 1). |
| V4 Access Control | Yes | `@PreAuthorize("hasRole('SELLER_ADMIN')")` / `hasRole('BUYER')` per endpoint (same pattern as `CompanyController.java:38,51`); no per-company scoping needed in this phase since catalog/stock are seller-wide, not per-buyer-company data (unlike Phase 1's `CompanyGuard`). |
| V5 Input Validation | Yes | Jakarta Bean Validation (`@NotBlank`, `@Positive`, `@DecimalMin`) on all request DTOs, identical convention to `CreateCompanyRequest`/`UpdateCreditLimitRequest`. |
| V6 Cryptography | No | No new cryptographic material in this phase (JWT signing/verification already Phase 1's concern). |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|------------------------|
| Overselling via race condition on stock reservation | Tampering (of business invariant, not data itself) | `@Version` optimistic lock + Spring Retry (this phase's core mechanism, Pattern 1) — this *is* the security-relevant control, not just a functional one: uncontrolled overselling is a business-logic integrity failure. |
| Replay of a captured reservation request | Repudiation / Tampering (double-spend of stock via retransmission) | Idempotent `reservation_id` handling (Pattern 2) — directly closes this class of issue as a side effect of the concurrency-safety design. |
| Role escalation via client-supplied `status`/role-like fields on product creation | Elevation of Privilege | Mirror the existing mitigation already proven in `CompanyService.createCompanyWithBuyer` (`auth-service/.../CompanyService.java:46-47`, comment: *"Role.BUYER é fixado aqui por literal... não há valor de cliente a ignorar"*): `ProductStatus` on creation should default server-side (e.g., always `ACTIVE`), never trust a client-supplied `status` field on `POST /products`. |
| Insecure direct object reference — BUYER querying/reserving stock for a `product_id` they should not see | Information Disclosure | Not applicable in the same way as Phase 1's per-company isolation (`CompanyGuard`) — this catalog is seller-wide and visible to all authenticated BUYERs by design (D-16, D-24); no per-buyer product scoping exists or is needed in this phase. |

## Sources

### Primary (HIGH confidence)
- `repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom` — downloaded and grepped this session; confirms `spring-retry.version=2.0.13`, `spring-boot-starter-aop` at `3.5.16`, `postgresql.version=42.7.11`.
- `search.maven.org` Solr API query for `org.springframework.retry:spring-retry` — confirms latest published artifact `2.0.12` (2025-05-16).
- This repository's own `auth-service` source (`Company.java`, `CompanyService.java`, `CompanyController.java`, `SecurityConfig.java`, `GlobalExceptionHandler.java`, `AbstractIntegrationTest.java`, `V1__init_auth_schema.sql`, `Role.java`, `pom.xml`, `docker-compose.yml`, `Dockerfile`) — read directly this session, all quoted patterns above are verbatim or line-cited.

### Secondary (MEDIUM confidence)
- [tmsvr.com — "Why Your @Retryable Fails with @Transactional in Spring (and How to Fix It)"](https://tmsvr.com/why-your-retryable-fails-with-transactional-in-spring-and-how-to-fix-it/)
- [javacodegeeks.com — "Spring @Retryable With @Transactional"](https://www.javacodegeeks.com/spring-retryable-with-transactional.html)
- [vladmihalcea.com — "How to retry JPA transactions after an OptimisticLockException"](https://vladmihalcea.com/optimistic-locking-retry-with-jpa/)
- [GitHub spring-attic/spring-retry issue #180](https://github.com/spring-attic/spring-retry/issues/180)

### Tertiary (LOW confidence, general web search only)
- General virtual-thread-pinning articles (Medium/DEV.to) on JEP 444/491 and PostgreSQL JDBC driver 42.6.0+ synchronized-to-lock migration — directionally consistent across multiple independent posts but not sourced from an official JDK/pgjdbc changelog this session.
- General REST idempotency-key pattern articles (Zuplo, various Medium/dev.to posts) converging on "unique constraint + catch integrity error" — well-established industry pattern, no single canonical citation.

## Metadata

**Confidence breakdown:**
- Standard stack (spring-retry/spring-boot-starter-aop versions): HIGH — verified directly against the exact BOM POM this repo pins.
- `@Retryable`+`@Transactional` ordering mechanism: MEDIUM — cross-corroborated blog sources, not official Spring docs; flagged with a recommended verification task in the Assumptions Log.
- Concurrency test pattern (virtual threads + `CyclicBarrier` + real HTTP): MEDIUM — general industry pattern, adapted specifically to this repo's existing Testcontainers conventions.
- Idempotency pattern (unique constraint): HIGH — directly consistent with this repo's own already-proven `EmailAlreadyUsedException`/`DataIntegrityViolationException` handling in `GlobalExceptionHandler.java:45-49`.
- Security domain: MEDIUM — role/validation controls are direct reuse of Phase 1's proven patterns; no new cryptographic or session-management surface in this phase.

**Research date:** 2026-09-19
**Valid until:** ~30 days (stable Spring ecosystem; re-verify `spring-retry`/`spring-boot-starter-aop` versions if Spring Boot patch version changes before implementation)
