# Phase 4: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito - Research

**Researched:** 2026-09-25
**Domain:** Spring Boot microservice with synchronous cross-service REST calls (RestClient), pessimistic DB locking for a serialized business invariant (credit exposure), and role/company-scoped REST resources — no messaging in this phase.
**Confidence:** MEDIUM-HIGH (all conventions verified by reading the four existing services' source; the two genuinely new techniques for this codebase — outbound `RestClient` and JPA pessimistic locking — are cross-checked web findings, tagged accordingly)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Semântica do crédito**
- **D-36:** O limite é de exposição acumulada: `soma dos pedidos que consomem crédito da empresa + total do novo pedido > credit_limit` → PENDING_APPROVAL; caso contrário (`<=`) → aprovado automaticamente. É o que dá sentido ao critério 5 do roadmap (concorrência na fronteira).
- **D-37:** Consomem crédito os pedidos em APPROVED, CONFIRMED, SHIPPED e DELIVERED. PENDING_APPROVAL não consome enquanto espera; REJECTED e CANCELLED não consomem (liberam). Sem pagamento modelado, DELIVERED continua consumindo. O cálculo deve ser uma consulta de soma (fonte única de verdade nos próprios pedidos), não um saldo desnormalizado. — Reversibility: costly.
- **D-38:** Pedido acima do limite aprovado pelo vendedor passa a consumir crédito, mesmo estourando o limite (o vendedor assume o risco). A aprovação manual não reavalia o limite nem altera o `credit_limit` no auth-service.

**Origem do limite e concorrência**
- **D-39:** O limite é lido por REST no auth-service: `GET /companies/{companyId}/credit-limit` repassando o JWT do próprio BUYER (o `companyGuard.isSelfOrSeller` já permite). Nenhuma cópia local do limite e nenhum claim novo no JWT. Auth-service fora do ar → a criação falha com erro 503 claro, sem criar pedido.
- **D-40:** A checagem é serializada por empresa com uma linha de trava: tabela `company_credit_lock(company_id PK)` no schema `order`; a transação de criação faz upsert/`SELECT ... FOR UPDATE` nessa linha, soma a exposição, decide o status e insere o pedido — tudo na mesma transação. Empresas diferentes não se bloqueiam. Um teste de concorrência (pedidos paralelos da mesma empresa na fronteira) prova que no máximo um é aprovado automaticamente. — Reversibility: costly.
- **D-41:** As chamadas HTTP (auth-service e catalog-service) acontecem antes de abrir a transação com trava — nunca segurar o lock durante I/O de rede.

**Validação no catálogo**
- **D-42:** Um `GET /products/{id}` por item, repassando o JWT do BUYER. Como o catalog-service devolve 404 para produto DISCONTINUED quando quem pede é BUYER (D-24), isso cobre a D-19 sem lógica extra. Nenhum endpoint novo no catalog-service.
- **D-43:** Cada `order_item` grava um snapshot: `productId`, `sku`, `name`, `unitPrice` (BigDecimal 19,2 — D-06), `quantity` e `subtotal`; o pedido grava `total`. Preço/nome consultados no momento da criação; nunca recalculados depois. — Reversibility: one-way.
- **D-44:** Tudo ou nada. Pedido sem itens, `quantity <= 0` ou `productId` repetido → 400 de validação (formato `{"error","message","fields"}` já usado). Produto inexistente/descontinuado → erro de negócio (422, ou 409 a critério do planejador) listando os ids problemáticos; nenhum pedido é criado.

**Estados e decisão**
- **D-45:** Dentro do limite, o pedido vai CREATED → APPROVED na mesma transação, com decisão registrada como automática (`decidedBy = SYSTEM`, `decidedAt`, motivo "dentro do limite de crédito"). Acima do limite: CREATED → PENDING_APPROVAL. A Fase 5 parte de APPROVED para disparar a reserva. Os status existentes nesta fase: CREATED, PENDING_APPROVAL, APPROVED, REJECTED (o enum pode já prever os demais da ORD-10, sem transições para eles).
- **D-46:** Decisão do vendedor por dois endpoints: `POST /orders/{id}/approve` (motivo opcional) e `POST /orders/{id}/reject` (motivo obrigatório). Só SELLER_ADMIN. Decidir pedido que não está em PENDING_APPROVAL → 409. Grava `decidedBy` (claim `sub` do JWT), `decidedAt` e `reason`. A aprovação manual também passa pela trava da empresa (D-40), porque muda a exposição.
- **D-47:** `GET /orders` paginado no envelope `Page<T>` do Spring Data (mesmo contrato do catálogo), ordenado por `createdAt desc`, com `?status=` opcional (fila de aprovação do vendedor). BUYER vê apenas pedidos da própria empresa (`company_id` do JWT); SELLER_ADMIN vê todos. `GET /orders/{id}` de outra empresa para um BUYER → 404 (não 403), para não revelar existência. Só BUYER cria pedidos; SELLER_ADMIN não cria.

### Claude's Discretion
- Timeouts das chamadas síncronas (RestClient) e se usa Resilience4j nesta fase — PITFALLS.md trata circuit breaker como nice-to-have; timeout explícito é obrigatório (lição do WR-03 da Fase 3).
- Formato exato de `OrderResponse`/`OrderSummary`, nomes de colunas, código HTTP do erro de produto inválido (422 vs 409).
- Dados/script de demonstração dos dois pedidos (abaixo e acima do limite) para o critério 2 — seguir o estilo do `scripts/smoke-notification-flow.sh`.
- Se o `companyId` do pedido vem só do JWT (recomendado: sim, nunca do corpo).

### Deferred Ideas (OUT OF SCOPE)
- Endpoint em lote no catalog-service (`GET /products?ids=`) — só se o número de chamadas por pedido virar problema.
- Evento de alteração de limite / cópia local do limite no order-service — descartado agora (D-39).
- Circuit breaker completo (Resilience4j) nas chamadas síncronas — pode entrar na Fase 7 (endurecimento).
- Eventos de ciclo de vida do pedido no notification-service — Fase 6.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|--------------------|
| ORD-01 | Comprador cria pedido selecionando produtos/quantidades do catálogo | Pattern 1 (RestClient + JWT passthrough to catalog-service), Code Examples (order/order_items schema), Common Pitfall 6 (404 collapse) |
| ORD-02 | Pedido acima do limite de crédito entra em PENDING_APPROVAL; abaixo segue rumo à confirmação | Pattern 2 (pessimistic sentinel-row lock, live SUM), Common Pitfall 2 (BigDecimal.compareTo), Validation Architecture (`CreditLimitBoundaryConcurrencyIT` for the concurrency criterion) |
| ORD-03 | Vendedor aprova ou rejeita pedidos pendentes | Architecture Patterns Pattern 2 (same lock reused for manual decision per D-46), Common Pitfall 3 (decidedBy polymorphism) |
| ORD-08 | Comprador lista e visualiza detalhe dos próprios pedidos | Pattern 3 (service-layer company scoping, 404-not-403) |
| ORD-09 | Vendedor lista e visualiza detalhe de todos os pedidos | Pattern 3 (sellerView bypass, same as `ProductService`) |

</phase_requirements>

## Summary

Phase 4 introduces the fifth microservice, `order-service` (port 8085, schema `order`), and it is the **first** service in this codebase to make outbound synchronous HTTP calls to sibling services (`auth-service` for the credit limit, `catalog-service` for product validation/pricing) and the **first** to need a deliberately-held row lock instead of the optimistic-lock-and-retry pattern `inventory-service` established in Phase 2. Everything else — package layout, error envelope, security config, Flyway/Hibernate split, Testcontainers base class, OpenAPI, Dockerfile — is a direct, verified copy of the pattern already proven four times over in `auth-service`/`catalog-service`/`inventory-service`/`notification-service`.

The phase has **zero messaging** (no SQS, no LocalStack dependency for `order-service` itself) — the Transactional Outbox and the saga are explicitly Phase 5 (D-28–D-30). This makes the module lighter than `inventory-service`/`notification-service`: no `spring-cloud-aws-starter-sqs`, no `spring-retry`/`spring-boot-starter-aop`, no LocalStack Testcontainers module.

**Primary recommendation:** Build two thin outbound REST clients (`AuthServiceClient`, `CatalogServiceClient`) around Spring's `RestClient` with **explicit** connect/read timeouts (Spring's defaults are unbounded — this bit the project once already per WR-03 in Phase 3, now elevated to a locked decision, D-41), call them **before** opening any transaction, then run the credit check + order insert inside a single `@Transactional` method that acquires a row-level lock (`SELECT ... FOR UPDATE` via `@Lock(LockModeType.PESSIMISTIC_WRITE)`) on a lazily-created `company_credit_lock` row — this is the direct architectural answer to Success Criterion 5 and to PITFALLS.md Pitfall 5, which this project's own prior research explicitly predicted for this exact phase.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Order creation (item selection, pricing) | API / Backend (`order-service`) | API / Backend (`catalog-service`, read-only) | `order-service` owns the order aggregate; catalog is consulted synchronously per item but never writes |
| Credit-limit read | API / Backend (`order-service` reads via REST) | API / Backend (`auth-service`, source of truth) | `auth-service` owns `Company.creditLimit`; no copy/cache in `order-service` (D-39) |
| Credit exposure calculation & serialization | API / Backend (`order-service`) | Database / Storage (Postgres row lock) | Exposure is computed on demand by summing `order-service`'s own orders table; the row lock lives in the same schema/transaction (D-40) |
| Approval/rejection decision | API / Backend (`order-service`) | — | SELLER_ADMIN-only mutation, no other tier involved |
| Order visibility (list/detail) | API / Backend (`order-service`) | — | Company-scoped filtering happens in the service layer, not at the Gateway or DB filter level (established precedent: `CompanyGuard`/`ProductService` pattern) |
| Gateway routing | CDN / Static-equivalent (Gateway) | — | Pure path-based routing + `StripPrefix=1`, no JWT validation at the gateway (D-05, unchanged) |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.16 (BOM-managed, matches every existing module) | Application framework for `order-service` | Locked project-wide decision (01-01-SUMMARY.md); no reason to deviate for a fifth module `[VERIFIED: pom.xml:34]` — quoted verbatim: `<spring-boot.version>3.5.16</spring-boot.version>` |
| Spring Web (`spring-boot-starter-web`) | BOM-managed | REST controllers + **`RestClient`** for outbound calls | `RestClient` ships inside `spring-web` (transitively pulled by `spring-boot-starter-web`, already a dependency of every other service) — no new dependency needed, just new usage `[CITED: docs.spring.io/spring-framework/reference/integration/rest-clients.html]` |
| Spring Data JPA | BOM-managed | `Order`/`OrderItem`/`CompanyCreditLock` entities, repositories, `@Lock(PESSIMISTIC_WRITE)` | Same as all four existing services `[VERIFIED: inventory-service/pom.xml:36-38]` |
| Spring Security + OAuth2 Resource Server | BOM-managed | JWT validation (`jwk-set-uri` + `issuer-uri`), `@PreAuthorize` role checks | Identical resource-server-only pattern as catalog/inventory/notification `[VERIFIED: catalog-service SecurityConfig.java:23-65]` |
| Flyway (`flyway-core` + `flyway-database-postgresql`) | BOM-managed | Owns the `order` schema DDL; Hibernate `ddl-auto: validate` only | Same convention as all three prior Postgres-backed services `[VERIFIED: inventory-service/pom.xml:72-81]` |
| PostgreSQL driver | BOM-managed | JDBC driver, schema `order` in the shared instance | D-01, unchanged |
| Lombok | 1.18.34 (BOM/property-managed) | Boilerplate reduction on entities | Same convention `[VERIFIED: pom.xml:40]` |
| springdoc-openapi-starter-webmvc-ui | 2.9.1 (property-managed) | Swagger UI for `order-service` | Same convention, same fixed version `[VERIFIED: pom.xml:44-48]` |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `spring-boot-starter-validation` | BOM-managed | Jakarta Bean Validation on request DTOs (`@NotEmpty` items list, `@Positive` quantity) | Structural validation of `CreateOrderRequest`/`DecisionRequest` — same as every existing service |
| `spring-boot-starter-actuator` | BOM-managed | `/actuator/health` for docker-compose healthcheck | Mandatory per existing convention (`curl -f http://localhost:8085/actuator/health`) |
| `spring-boot-starter-test` + `spring-boot-testcontainers` + `testcontainers:postgresql` + `testcontainers:junit-jupiter` | BOM-managed | Integration tests against a real Postgres (no LocalStack module needed — no messaging this phase) | `AbstractIntegrationTest` clone, same singleton-container pattern |
| `MockRestServiceServer` (part of `spring-test`, no new dependency) | ships with `spring-boot-starter-test` | Stub `auth-service`/`catalog-service` HTTP responses in unit/slice tests without a real network call | Bind via `MockRestServiceServer.bindTo(RestClient.Builder)` before building the client under test — **do not** add WireMock as a new dependency; `spring-test` already provides this and no existing service uses WireMock |

### NOT needed this phase (deliberately deferred)

| Library | Why it's tempting | Why to skip now |
|---------|--------------------|--------------------|
| `spring-cloud-aws-starter-sqs` | Every other "new service" phase (2, 3) added SQS | This phase has zero messaging (D-28–D-30 push outbox/SQS to Phase 5); adding it now is unused surface area |
| `spring-retry` + `spring-boot-starter-aop` | `inventory-service` uses `@Retryable` for its optimistic-lock conflicts | This phase uses **pessimistic** locking (`SELECT ... FOR UPDATE`), which serializes instead of racing — there is nothing to retry; adding retry infrastructure here would be solving a problem this design doesn't have |
| Resilience4j (`resilience4j-spring-boot3`) | CLAUDE.md's stack research recommends it for cross-service calls | CONTEXT.md marks it "Claude's Discretion" and PITFALLS.md/ROADMAP treat circuit breakers as nice-to-have, not core, for this MVP mode phase; explicit timeouts (mandatory) already bound the failure window. Revisit in the hardening phase (Phase 7) |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Hand-rolled `RestClient` wrapper per downstream service | Spring Cloud OpenFeign (declarative HTTP client, available via the Spring Cloud 2025.0.3 BOM already imported) | Feign is one more abstraction layer/annotation-processing step for two simple GET calls; `RestClient`'s fluent builder is simpler to explain and test, and is what Spring's own current guidance recommends for new code (`RestTemplate` is in maintenance mode, `WebClient` pulls in the reactive stack for no benefit in a blocking MVC app) |
| Pessimistic row lock (`SELECT ... FOR UPDATE`) on a dedicated lock table | Optimistic locking (`@Version`) + retry, as `inventory-service` does | Optimistic+retry fits `inventory-service` because contention is on the *same row being mutated*; here the "row" being protected (aggregate credit exposure) is a computed value across many order rows, not a single mutable counter — pessimistic locking on a sentinel row is the standard way to serialize a computed aggregate check without introducing a denormalized running-total column (which D-37 explicitly rules out: "não um saldo desnormalizado") |
| A dedicated `company_credit_lock` sentinel table | `SELECT ... FOR UPDATE` directly on a row in the `orders` table, or an advisory lock (`pg_advisory_xact_lock`) | A sentinel table survives even when a company has zero orders yet (first order needs something to lock); `pg_advisory_xact_lock(hashtext(company_id))` is a valid, table-free alternative worth mentioning to the planner as a lower-ceremony option, but the sentinel-row approach is more consistent with this project's existing style (real rows, not opaque lock IDs) and self-documents in `\d` |

**Installation:** No new external Maven coordinates — every dependency above already appears in `dependencyManagement` (root `pom.xml`) or is a submodule of an already-declared starter. `order-service/pom.xml` only needs to **declare** (not add versions for) the starters listed in Core/Supporting above, following the exact `inventory-service/pom.xml` template minus the AWS/retry/aop block.

**Version verification:** No new versions to verify — every dependency is already pinned by the root `pom.xml`'s `dependencyManagement`, itself verified in Phase 1 (`spring-boot.version`, checkpoint-locked per the pom's own comment) and Phase 3 (`spring-cloud-aws.version`, unused by this module). `[VERIFIED: pom.xml:28-58]`

## Package Legitimacy Audit

No new external package is introduced by this phase — `order-service` reuses only groupIds/artifactIds already declared and audited in the root `pom.xml`'s `dependencyManagement` and in `inventory-service`/`catalog-service`'s own `pom.xml` (Phase 2/3 Package Legitimacy Audits). The Maven ecosystem is not covered by this project's automated `package-legitimacy check` tool (npm/PyPI/crates only), so verification here is by direct inspection of already-shipped, already-running modules rather than a fresh registry lookup.

| Package | Registry | Prior Audit | Verdict | Disposition |
|---------|----------|--------------|---------|-------------|
| `org.springframework.boot:spring-boot-starter-web` (brings `RestClient`) | Maven Central | Used by all 4 existing services | OK | Approved — new *usage* (`RestClient`), not a new dependency |
| `org.springframework.data:spring-data-jpa` (via `spring-boot-starter-data-jpa`) | Maven Central | Used by all 4 existing services | OK | Approved — new *usage* (`@Lock`), not a new dependency |
| `org.flywaydb:flyway-core` / `flyway-database-postgresql` | Maven Central | 02-RESEARCH.md §Package Legitimacy Audit | OK | Approved |
| `org.postgresql:postgresql` | Maven Central | 01-RESEARCH.md (implied by Phase 1 infra) | OK | Approved |
| `org.projectlombok:lombok` | Maven Central | Used by all 4 existing services | OK | Approved |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | Maven Central | quick 260920-g6c | OK | Approved |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none.

## Architecture Patterns

### System Architecture Diagram

```
BUYER / SELLER_ADMIN (via Gateway, JWT Bearer)
        │
        ▼
┌───────────────────────────────────────────────────────────────┐
│ order-service (:8085, schema "order")                         │
│                                                                 │
│  POST /orders (BUYER)                                          │
│   1. Parse items from request body                             │
│   2. FOR EACH item: RestClient → catalog-service                │
│        GET /products/{id}  (JWT forwarded)                      │
│        404 → collect as "invalid item"                          │
│        200 → snapshot sku/name/price                            │
│   3. If ANY item invalid → 422/409, NO db write (all-or-nothing) │
│   4. RestClient → auth-service                                  │
│        GET /companies/{companyId}/credit-limit (JWT forwarded)   │
│        503 if auth-service unreachable, NO db write               │
│  ── transaction boundary starts here (D-41: no I/O inside) ──    │
│   5. ensureLockRow(companyId)  (INSERT ... ON CONFLICT DO NOTHING)│
│   6. SELECT ... FOR UPDATE company_credit_lock WHERE company_id  │
│   7. SUM(total) FROM orders WHERE company_id=? AND status IN      │
│        (APPROVED, CONFIRMED, SHIPPED, DELIVERED)                  │
│   8. if sum + newTotal > creditLimit → PENDING_APPROVAL            │
│      else → APPROVED (decidedBy=SYSTEM, decidedAt=now)             │
│   9. INSERT orders + order_items (snapshot)                        │
│  ── transaction commits, lock row released ──                     │
│                                                                 │
│  POST /orders/{id}/approve | /reject (SELLER_ADMIN)             │
│   same lock (step 5-6) + same status-must-be-PENDING_APPROVAL    │
│   check + INSERT decidedBy=sub/decidedAt/reason                   │
│                                                                 │
│  GET /orders, GET /orders/{id}                                    │
│   BUYER: filtered by company_id from JWT (service-layer, D-47)     │
│   SELLER_ADMIN: unfiltered                                          │
│   BUYER querying another company's order → 404 (D-47)               │
└───────────────┬──────────────────────────────┬────────────────┘
                │ REST (sync, read-only)         │ REST (sync, read-only)
                ▼                                ▼
   ┌─────────────────────┐          ┌─────────────────────────┐
   │ catalog-service      │          │ auth-service              │
   │ GET /products/{id}   │          │ GET /companies/{id}/       │
   │ (404 hides            │          │     credit-limit           │
   │  DISCONTINUED, D-24)  │          │ (@companyGuard.isSelfOr    │
   └─────────────────────┘          │  Seller already permits    │
                                     │  the BUYER's own company)   │
                                     └─────────────────────────┘
```

### Recommended Project Structure

```
order-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/com/orderflow/order/
    │   ├── OrderServiceApplication.java
    │   ├── config/
    │   │   ├── SecurityConfig.java          # copy of catalog/inventory pattern
    │   │   ├── GlobalExceptionHandler.java  # copy + new handlers (see Common Pitfalls)
    │   │   ├── OpenApiConfig.java
    │   │   └── ClientConfig.java            # RestClient.Builder → AuthServiceClient/CatalogServiceClient beans, timeouts
    │   ├── order/
    │   │   ├── Order.java, OrderItem.java, OrderStatus.java (enum)
    │   │   ├── OrderRepository.java, OrderItemRepository.java
    │   │   ├── OrderController.java
    │   │   ├── OrderService.java             # orchestrates: pricing (non-tx) → credit check (tx)
    │   │   ├── CompanyCreditLock.java, CompanyCreditLockRepository.java
    │   │   ├── dto/ (CreateOrderRequest, OrderItemRequest, OrderResponse, DecisionRequest, ...)
    │   │   └── exception/ (InvalidOrderItemsException, OrderNotFoundException, OrderNotPendingException, AuthServiceUnavailableException, ...)
    │   └── client/
    │       ├── AuthServiceClient.java        # GET credit-limit
    │       ├── CatalogServiceClient.java      # GET product
    │       └── dto/ (CreditLimitResponse mirror, ProductResponse mirror — plain records, NOT shared JPA entities, per ARCHITECTURE.md anti-pattern 3)
    └── test/java/com/orderflow/order/
        ├── AbstractIntegrationTest.java       # Postgres-only singleton container, no LocalStack
        ├── support/TestJwt.java               # copy verbatim from inventory-service
        ├── OrderControllerIT.java
        ├── OrderApprovalIT.java
        ├── CreditLimitBoundaryConcurrencyIT.java   # Success Criteria 5 — real-socket + virtual threads, modeled on StockReservationConcurrencyIT
        └── support/ (MockRestServiceServer-based stand-ins for auth/catalog, or a local WireMock-free stub controller)
```

### Structure Rationale

- **`client/` package, separate from `order/`:** the two outbound REST clients are integration/anti-corruption-layer code, not domain logic — keeping them in their own package makes it obvious that `OrderService` depends on an interface/abstraction (constructor-injected client beans), not directly on `RestClient` internals, and makes them easy to swap for `MockRestServiceServer` stubs in tests.
- **DTOs mirrored, not shared:** `client/dto/ProductResponse` is a *new*, minimal record in `order-service`, not an import of `catalog-service`'s `ProductResponse` class — per ARCHITECTURE.md's explicit anti-pattern (never share JPA-adjacent classes across service boundaries). Only the JSON shape needs to match; the classes are independent.
- **`OrderService` split into a non-transactional pricing step and a transactional decision step:** this is the single most important structural decision of the phase (see Common Pitfalls #1) — it is what makes D-41 ("never hold the lock during network I/O") achievable without fighting Spring's proxy model.

### Pattern 1: Outbound RestClient with explicit timeouts and JWT passthrough

**What:** A `RestClient` bean per downstream service, built once with `baseUrl` + explicit `ClientHttpRequestFactorySettings` timeouts, invoked per-request with the caller's own Bearer token forwarded.

**When to use:** Every synchronous read from `auth-service`/`catalog-service` in this phase.

**Example:**
```java
// Source: docs.spring.io/spring-framework/reference/integration/rest-clients.html (RestClient section)
// + spring.io/blog/2023/07/13/new-in-spring-6-1-restclient (official Spring blog)
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

// CatalogServiceClient — forwards the caller's own Bearer token (D-42), never a service-account token
// (identidade de serviço entre microsserviços é explicitamente Fase 5, javadoc InventoryController)
public ProductSnapshot getProduct(UUID productId, String bearerToken) {
    return catalogServiceRestClient.get()
            .uri("/products/{id}", productId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                throw new ProductUnavailableException(productId); // caller decides 422 vs 409
            })
            .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                throw new CatalogServiceUnavailableException();
            })
            .body(ProductResponse.class);
}
```
**Important:** Spring Boot's autoconfigured `RestClient.Builder` has **no default timeout** — an unreachable/hanging downstream service blocks the calling thread indefinitely unless a timeout is set explicitly `[CITED: docs.spring.io/spring-framework/reference/integration/rest-clients.html; laszlosimon.dev/articles/java-and-enterprise/configuring-rest-clients-timeout]`. This is the same lesson already learned once in this project (Phase 3, WR-03: latency without timeout), now a locked requirement (D-41/CONTEXT.md discretion note: "timeout explícito é obrigatório").

### Pattern 2: Serializing a computed aggregate check with a pessimistic sentinel-row lock

**What:** A one-row-per-company `company_credit_lock` table, lazily created on first order, locked with `SELECT ... FOR UPDATE` (via Spring Data's `@Lock(LockModeType.PESSIMISTIC_WRITE)`) for the duration of the credit-exposure sum + decision + insert.

**When to use:** Exactly Success Criterion 5 — any place a *computed* invariant (sum over rows, not a single mutable field) must be checked-then-acted-on atomically across concurrent transactions, per PITFALLS.md Pitfall 5 (this project's own prior research, written before this phase existed, predicting this exact mechanism).

**Example:**
```java
// CompanyCreditLockRepository
public interface CompanyCreditLockRepository extends JpaRepository<CompanyCreditLock, UUID> {

    // Idempotent row creation — safe if two concurrent requests for a brand-new company both
    // attempt it; the loser's INSERT is a silent no-op, never an exception (D-40).
    @Modifying
    @Query(value = "INSERT INTO company_credit_lock(company_id) VALUES (:companyId) "
                  + "ON CONFLICT (company_id) DO NOTHING", nativeQuery = true)
    void ensureExists(@Param("companyId") UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM CompanyCreditLock l WHERE l.companyId = :companyId")
    Optional<CompanyCreditLock> lockForUpdate(@Param("companyId") UUID companyId);
}

// OrderRepository — the exposure sum, run AFTER the lock is held so it sees every previously
// committed decision for this company (D-37: computed from the orders themselves, never a
// denormalized running balance).
@Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o "
     + "WHERE o.companyId = :companyId AND o.status IN "
     + "('APPROVED','CONFIRMED','SHIPPED','DELIVERED')")
BigDecimal sumExposure(@Param("companyId") UUID companyId);

// OrderService — the ONLY method in this class annotated @Transactional; called with
// already-priced/already-validated items, no RestClient call happens inside this method (D-41).
@Transactional
public Order decideAndCreate(UUID companyId, List<PricedItem> items, BigDecimal total) {
    companyCreditLockRepository.ensureExists(companyId);
    companyCreditLockRepository.lockForUpdate(companyId)
            .orElseThrow(() -> new IllegalStateException("lock row must exist after ensureExists"));

    BigDecimal creditLimit = /* passed in, read via RestClient BEFORE this method was called */;
    BigDecimal exposure = orderRepository.sumExposure(companyId);

    // BigDecimal: compareTo, never equals — .equals() also compares scale (D-06 pitfall).
    boolean overLimit = exposure.add(total).compareTo(creditLimit) > 0;

    Order order = overLimit
            ? Order.pendingApproval(companyId, items, total)
            : Order.autoApproved(companyId, items, total); // decidedBy="SYSTEM"

    return orderRepository.save(order);
}
```
**Important nuance on lock timeout:** Hibernate's `javax.persistence.lock.timeout` query hint is **not honored by PostgreSQL** — only `0` (`NOWAIT`) is portable; a positive millisecond wait value is effectively an Oracle-only hint `[CITED: cross-checked Baeldung "Pessimistic Locking in JPA" + PostgreSQL project mailing list thread on `SELECT...FOR UPDATE [WAIT n]`]`. If the planner wants a bounded wait instead of accepting indefinite blocking, the correct mechanism for Postgres is `SET LOCAL lock_timeout = '<n>ms'` issued as a native statement at the very start of the `@Transactional` method (before the lock query), which raises Postgres error `57014` (query_canceled) that must be caught and mapped to a 503/409. **For this phase's actual contention window (no network I/O held under the lock, per D-41), indefinite blocking is very likely acceptable and simpler to ship** — flag this as a discretionary call for the planner, not a hard requirement.

### Pattern 3: Company-scoped visibility resolved in the service layer, not via `@PreAuthorize` SpEL

**What:** Unlike `CompanyGuard` (Phase 1, works because the `companyId` is a path variable) or `ProductService.getById(id, sellerView)` (Phase 2, works because there's no company scoping at all — only a status filter), `order-service`'s `GET /orders/{id}` only has an `orderId` in the path. The owning `companyId` is not known until the order row is loaded. The visibility decision must happen inside `OrderService`, after the repository fetch, exactly mirroring `ProductService.getById`'s `sellerView` boolean parameter — never a `@PreAuthorize("@someGuard.isSelfOrSeller(#orderId)")`, which cannot work here (the guard bean would need to hit the database itself before the method body runs, duplicating the fetch).

**When to use:** `GET /orders/{id}`.

**Example:**
```java
// OrderController — same isSellerAdmin() derivation as ProductController (authorities, never body/query)
@GetMapping("/{orderId}")
public OrderResponse getById(@PathVariable UUID orderId, Authentication authentication) {
    UUID callerCompanyId = extractCompanyId(authentication); // null for SELLER_ADMIN
    return orderService.getById(orderId, callerCompanyId, isSellerAdmin(authentication));
}

// OrderService
@Transactional(readOnly = true)
public OrderResponse getById(UUID orderId, UUID callerCompanyId, boolean sellerView) {
    Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found"));
    // A BUYER fetching another company's order is indistinguishable from a nonexistent order —
    // never reveal existence (D-47, same rationale as ProductService's DISCONTINUED handling, D-24).
    if (!sellerView && !order.getCompanyId().equals(callerCompanyId)) {
        throw new OrderNotFoundException("Order not found");
    }
    return OrderResponse.from(order);
}
```

### Anti-Patterns to Avoid

- **Calling `RestClient` from inside the `@Transactional` method:** holds a DB connection (and, in this design, the credit-lock row) open for the duration of one or more network round trips — exactly the anti-pattern D-41 was written to forbid. Split pricing/validation (no transaction) from decide-and-persist (transaction, no I/O).
- **Same-class internal calls expecting `@Transactional`/`@Retryable` to apply:** already documented in this codebase (02-RESEARCH.md Pitfall 2, referenced directly in `InventoryService`'s own javadoc) — a method calling another method of the *same* Spring bean bypasses the proxy silently. Keep the pricing step and the transactional decide-step in **different** beans (e.g., `OrderController` → `OrderPricingService` (no `@Transactional`) → `OrderService.decideAndCreate` (the only `@Transactional` entry point)), never one method calling another on `this`.
- **A denormalized `creditUsed` column on `Company` or a local copy in `order-service`:** explicitly ruled out by D-37 ("não um saldo desnormalizado") and D-39 ("Nenhuma cópia local do limite"). Exposure is always a live `SUM(...)` query inside the locked transaction.
- **Treating catalog's 404 as a technical error:** a 404 from `GET /products/{id}` for a BUYER means "not found or discontinued" (D-24) — this is expected input validation, not a downstream outage. Map it to the business "invalid item" list (422/409), not to the same `CatalogServiceUnavailableException` used for a 5xx/timeout.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|--------------|-----|
| Stubbing `auth-service`/`catalog-service` HTTP responses in tests | A custom fake HTTP server, or adding WireMock as a new test dependency | `MockRestServiceServer` (already available transitively via `spring-boot-starter-test`, used with `RestClient.Builder`) | Zero new dependencies; consistent with "no existing service uses WireMock" — introducing it here would be the only place in the codebase that does |
| Credit exposure running total | A cached/denormalized balance column, manually kept in sync on every status transition | A live `SUM(...)` query over `orders` inside the locked transaction (Pattern 2) | D-37 explicitly forbids the denormalized approach; the row lock already makes the live-sum approach safe under concurrency, so the "faster" cached approach buys nothing but a new synchronization bug surface |
| Pagination envelope for `GET /orders` | A custom `{ "items": [...], "total": N }` shape | Spring Data's `Page<T>` (`.map(OrderResponse::from)`), same as `GET /products` | Already the established, reused contract in this codebase (`02-CONTEXT.md`: "GET /products devolve o envelope padrão Page<T>... contrato consumido pela Fase 4") — consistency matters more than any marginal shape improvement |
| Idempotency-safe reservation IDs for orders | A `reservationId`/dedupe key on `POST /orders` | Nothing this phase — no messaging exists yet; idempotent SQS consumption is explicitly Phase 5/6 (ORD-04-06) | Building idempotency infrastructure for a synchronous, single-attempt REST endpoint with no retry/at-least-once delivery concern is solving a problem this phase doesn't have |

**Key insight:** every "don't hand-roll" item above is really the same insight in different clothes — this project has *already* built the right answer to each of these problems in a prior phase (Page envelope in Phase 2, `MockRestServiceServer` ships free with Spring, optimistic-lock+retry solved a *different* concurrency shape than this phase needs). The main risk in Phase 4 is under-reusing what already exists, not needing to invent anything new.

## Common Pitfalls

### Pitfall 1: `@Transactional` method also performing the outbound RestClient calls

**What goes wrong:** If `OrderService.createOrder(...)` is a single method annotated `@Transactional` that both calls `catalogServiceClient.getProduct(...)` N times, calls `authServiceClient.getCreditLimit(...)`, *and* does the lock+insert, the database transaction (and, once the lock code is reached, the `company_credit_lock` row) stays open for the full duration of every network round trip. Under load this serializes unrelated companies' orders behind one slow/hanging downstream call and directly violates D-41.

**Why it happens:** It's the most natural way to write a single "create order" method — one function, one clear top-to-bottom read.

**How to avoid:** Structure as two beans/methods: a non-transactional pricing/validation step (all RestClient calls happen here, and only here) that returns already-priced, already-validated data, followed by a `@Transactional` decide-and-persist method that takes only in-memory data as input and touches only the database.

**Warning signs:** A `@Transactional` method importing `RestClient`, `AuthServiceClient`, or `CatalogServiceClient`; a test that has to mock the HTTP layer just to test the credit-limit branch logic.

### Pitfall 2: `BigDecimal.equals()` instead of `.compareTo()` at the credit boundary

**What goes wrong:** `new BigDecimal("100.00").equals(new BigDecimal("100.0"))` is `false` (different scale) even though they represent the same value. If any comparison in the boundary check (`exposure + total > creditLimit`) accidentally uses `.equals()` — or if a boundary unit test asserts equality this way — the criterion 5 concurrency test can pass or fail for the wrong reason.

**Why it happens:** `equals()` is the more familiar Java idiom; `BigDecimal`'s scale-sensitivity is a well-known but easy-to-forget gotcha.

**How to avoid:** Always `.compareTo(...) > 0` / `<= 0` for the credit-limit decision; never `.equals()` for numeric comparison. Since both `Company.creditLimit` and `Order.total` are `NUMERIC(19,2)` (D-06), scale will usually match in practice, but `compareTo` is the only comparison that is correct *unconditionally*.

**Warning signs:** Any `.equals(` on a `BigDecimal` in the credit-check code path.

### Pitfall 3: `decidedBy` typed as a `UUID`/FK column

**What goes wrong:** D-45 requires the automatic-approval path to record `decidedBy = "SYSTEM"` (a literal string, not a user ID), while D-46 requires the manual path to record the SELLER_ADMIN's `sub` claim (a UUID string). If the column is typed `UUID` (or a real FK to a `users` table that doesn't even exist in this schema), the automatic path cannot be represented.

**Why it happens:** "Who decided this" instinctively looks like a foreign key to a user, and the polymorphism (system vs. human) is easy to miss when skimming the requirements quickly.

**How to avoid:** `decided_by VARCHAR(64)` (plain text), storing either the literal `"SYSTEM"` or the `sub` claim's UUID-as-string — never a typed `UUID` column, never a cross-service FK (there is no `users` table in the `order` schema, same opaque-reference rule as `product_id`/`company_id`, D-15 pattern).

**Warning signs:** A migration with `decided_by UUID`; a NOT NULL FK constraint referencing a table that doesn't exist in this schema.

### Pitfall 4: Under-declaring the `status` `CHECK` constraint to only the values reachable this phase

**What goes wrong:** If the `orders.status` column's `CHECK` constraint lists only `('CREATED','PENDING_APPROVAL','APPROVED','REJECTED')` (the four values reachable in Phase 4 per D-45), Phase 5/6 will need an `ALTER TABLE ... DROP CONSTRAINT ... ADD CONSTRAINT` migration just to add `CONFIRMED`/`CANCELLED`/`SHIPPED`/`DELIVERED` later — extra migration ceremony for values already known today from ORD-10.

**Why it happens:** It's tempting to constrain the DB to exactly what's used right now.

**How to avoid:** Follow the exact precedent already set by `catalog-service`'s `products.status` column (02-RESEARCH.md Pitfall 4: defend against silent enum-reordering corruption with a `VARCHAR` + `CHECK`, not relying on JPA's enum mapping alone) — but declare the **full** ORD-10 status list in the `CHECK` constraint now (`CREATED, PENDING_APPROVAL, APPROVED, REJECTED, CONFIRMED, CANCELLED, SHIPPED, DELIVERED`), even though the Java code and the state-machine logic only reach the first four this phase (D-45 explicitly permits this: "o enum pode já prever os demais... sem transições para eles").

**Warning signs:** A Flyway migration whose `CHECK (status IN (...))` list has fewer than 8 values.

### Pitfall 5: Testing Success Criterion 5 with `MockMvc` instead of a real socket

**What goes wrong:** `MockMvc` dispatches requests in-process, synchronously, without any real network/thread-pool overlap — two "concurrent" `MockMvc` calls from the same test thread never actually race against each other, so a concurrency test written this way can pass even if the locking is broken.

**Why it happens:** `MockMvc` is the default/easiest tool reached for in every other test in this codebase (`AbstractIntegrationTest` uses it) — it's natural to keep using it for "just one more" test.

**How to avoid:** This project already solved this exact problem in Phase 2 for `INV-02` — `StockReservationConcurrencyIT` deliberately does **not** extend `AbstractIntegrationTest`, instead starting the embedded server on a random port and firing real requests via `java.net.http.HttpClient` from a virtual-thread-per-task executor, synchronized with a `CyclicBarrier` sized to the exact number of contenders. Model `CreditLimitBoundaryConcurrencyIT` directly on that class (same javadoc rationale applies verbatim: "força a sobreposição real das requisições").

**Warning signs:** A test class named `*ConcurrencyIT` that extends `AbstractIntegrationTest` or uses `mockMvc.perform(...)`.

### Pitfall 6: Forgetting that a downstream 404 from catalog can mean two different things

**What goes wrong:** `catalog-service` returns 404 both for a truly nonexistent `productId` and for a `DISCONTINUED` product viewed by a BUYER (D-24) — from `order-service`'s point of view (which always forwards the BUYER's own JWT per D-42), these are indistinguishable, by design. Code that tries to give the BUYER a more specific error message ("this product was discontinued") for one case but not the other cannot actually do so without a second, SELLER_ADMIN-scoped lookup that D-42 explicitly says is unnecessary.

**Why it happens:** It reads like a UX improvement worth making, but doing it would require order-service to authenticate as a privileged caller just to produce a better error message.

**How to avoid:** Accept the collapse — the error response for any invalid item just lists the offending `productId`(s) with a generic "invalid or unavailable" reason (D-44), never a specific "discontinued" vs. "not found" distinction.

## Code Examples

### Order status enum + defensive VARCHAR/CHECK column (mirrors `products.status`)

```sql
-- Source: pattern verified verbatim in catalog-service/src/main/resources/db/migration/V1__init_catalog_schema.sql:1-14
-- Full ORD-10 lifecycle declared now (Pitfall 4 above), only CREATED/PENDING_APPROVAL/APPROVED/REJECTED
-- reachable by application code this phase.
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,                 -- opaque reference to auth-service, no FK (D-15 pattern)
    status VARCHAR(20) NOT NULL CHECK (status IN
        ('CREATED','PENDING_APPROVAL','APPROVED','REJECTED',
         'CONFIRMED','CANCELLED','SHIPPED','DELIVERED')),
    total NUMERIC(19,2) NOT NULL CHECK (total >= 0),
    decided_by VARCHAR(64),                   -- "SYSTEM" or a user sub (UUID-as-text); see Pitfall 3
    decided_at TIMESTAMPTZ,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_company_id ON orders(company_id);
CREATE INDEX idx_orders_status ON orders(status);

CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),  -- same-schema FK is fine; order OWNS its items
    product_id UUID NOT NULL,                 -- opaque reference to catalog-service, no FK
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price >= 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    subtotal NUMERIC(19,2) NOT NULL CHECK (subtotal >= 0)
);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);

-- Sentinel lock table for the credit-exposure serialization (Pattern 2). No FK to auth-service's
-- companies table (cross-schema, no shared FK — same D-15 rationale as product_id/company_id above).
CREATE TABLE company_credit_lock (
    company_id UUID PRIMARY KEY
);
```

### Demo script shape (Success Criterion 2 — below/above limit)

Model directly on `scripts/smoke-notification-flow.sh`'s structure (login → extract token → act → assert observable state via `count_history_entries`-style grep, `set -euo pipefail`, `fail()` helper, `MSYS_NO_PATHCONV=1` for Git Bash on Windows): create one company with a known credit limit, `POST /orders` with a total comfortably below it (assert `APPROVED` in the response/`GET`), then `POST /orders` again with a total that pushes cumulative exposure over the limit (assert `PENDING_APPROVAL`).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| `RestTemplate` for synchronous outbound HTTP | `RestClient` (fluent, since Spring 6.1 / Boot 3.2) | 2023 (Spring 6.1) | `RestTemplate` is in maintenance mode; new Spring guidance is to use `RestClient` for blocking code and reserve `WebClient` for reactive stacks `[CITED: spring.io/blog/2023/07/13/new-in-spring-6-1-restclient]` |

**Deprecated/outdated:** none directly relevant beyond the `RestTemplate`→`RestClient` shift above; every other convention in this phase is a direct continuation of patterns already active in this codebase.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | 422 Unprocessable Entity is the better HTTP status for "order references an invalid/discontinued product" over 409 Conflict | Architecture Patterns / Don't Hand-Roll | Low — CONTEXT.md already flags this as Claude's Discretion ("422, ou 409 a critério do planejador"); either choice is internally consistent as long as it's applied uniformly and documented in the error envelope |
| A2 | Indefinite blocking on the `company_credit_lock` row (no `SET LOCAL lock_timeout`) is acceptable for this phase, since no network I/O is held under the lock | Architecture Patterns, Pattern 2 | Low-medium — if a future phase adds work inside the same transaction that can hang (unlikely, since D-41 forbids I/O under the lock generally), an unbounded wait could accumulate; documented as a discretionary, revisitable choice, not a hard requirement |
| A3 | `pg_advisory_xact_lock` was not chosen over the sentinel-row table, in line with the already-locked D-40 decision, but is mentioned as a lower-ceremony alternative worth knowing | Standard Stack, Alternatives Considered | None — purely informational, D-40 already locks the sentinel-row approach |

**If this table is empty:** N/A — see entries above; none are load-bearing enough to block planning, all are already anticipated/permitted by CONTEXT.md's own discretion markers.

## Open Questions

1. **Exact base-URL property names for the two outbound clients**
   - What we know: `catalog-service`/`auth-service` are reachable at `http://catalog-service:8082` / `http://auth-service:8081` inside docker-compose, and at `http://localhost:8082` / `http://localhost:8081` for local (non-compose) runs — exactly the existing `jwk-set-uri` pattern (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`).
   - What's unclear: the property naming convention for these two new URLs (`orderflow.clients.catalog-service.base-url` was used above as an illustrative default; no precedent exists in this codebase since no prior service calls another synchronously).
   - Recommendation: follow the existing `orderflow.*` custom-property namespace already used for `orderflow.messaging.*`/`orderflow.openapi.*` in `inventory-service/application.yml` — e.g. `orderflow.clients.auth-service.base-url`, `orderflow.clients.catalog-service.base-url`, each with a `${SPRING_...}`-style env var override for docker-compose, matching the `jwk-set-uri` override pattern exactly.

2. **Whether `GET /orders` needs a `?status=` filter usable by BUYER too, or SELLER_ADMIN only**
   - What we know: D-47 says `?status=` is "a fila de aprovação do vendedor" (implying SELLER_ADMIN's primary use case), but doesn't explicitly forbid a BUYER from also filtering their own orders by status.
   - What's unclear: whether restricting `?status=` to SELLER_ADMIN only is intended, or whether it's simply the most common use case without being an access restriction.
   - Recommendation: allow `?status=` for both roles (it's just a `WHERE` clause addition on top of the already-role-scoped company filter) — simpler, and does not weaken any isolation guarantee, since the company scoping (D-47's core requirement) is unaffected by an additional status filter.

## Environment Availability

No new external dependency (no LocalStack, no new AWS resource) is introduced by this phase — `order-service` only needs the already-running Postgres instance and the already-running `auth-service`/`catalog-service` HTTP endpoints, all of which are provisioned by the existing `docker-compose.yml` and already verified healthy in Phases 1-3.

| Dependency | Required By | Available | Version | Fallback |
|------------|--------------|-----------|---------|----------|
| PostgreSQL | `order-service` schema `order` | ✓ (existing container) | 16.15 `[VERIFIED: docker-compose.yml:3]` | — |
| `auth-service` reachability | Credit-limit lookup (D-39) | ✓ (existing service, port 8081) | — | None with a fallback — D-39 requires a hard 503 when unreachable, by design (no local copy of the limit) |
| `catalog-service` reachability | Product validation/pricing (D-42) | ✓ (existing service, port 8082) | — | None with a fallback — same rationale, all-or-nothing order creation |
| Docker / docker-compose | Full-stack demo | ✓ (existing setup) | Compose V2 | — |

**Missing dependencies with no fallback:** none identified — this phase adds no new infrastructure dependency, only new calling code against already-running services.
**Missing dependencies with fallback:** none.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito (`spring-boot-starter-test`, BOM-managed) + Testcontainers 1.20.4 (Postgres module only — no LocalStack module needed this phase) |
| Config file | No standalone config file — mirrors `inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java`'s singleton-container pattern, minus `LocalStackTestSupport` |
| Quick run command | `./mvnw -pl order-service -am test` |
| Full suite command | `./mvnw -pl order-service -am verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|--------------------|--------------|
| ORD-01 | BUYER creates order; catalog validated synchronously; total computed; born CREATED | integration | `./mvnw -pl order-service -am verify -Dit.test=OrderControllerIT` | ❌ Wave 0 |
| ORD-02 | Over-limit → PENDING_APPROVAL; under-limit → auto-progresses (APPROVED) | integration | `./mvnw -pl order-service -am verify -Dit.test=OrderControllerIT` | ❌ Wave 0 |
| ORD-02 (criterion 5) | Two concurrent orders at the credit boundary — at most one auto-approves | integration (real socket) | `./mvnw -pl order-service -am verify -Dit.test=CreditLimitBoundaryConcurrencyIT` | ❌ Wave 0 |
| ORD-03 | SELLER_ADMIN approves/rejects a PENDING_APPROVAL order; records decidedBy/decidedAt/reason | integration | `./mvnw -pl order-service -am verify -Dit.test=OrderApprovalIT` | ❌ Wave 0 |
| ORD-08 | BUYER lists/views only own-company orders; 404 for another company's order | integration | `./mvnw -pl order-service -am verify -Dit.test=OrderControllerIT` | ❌ Wave 0 |
| ORD-09 | SELLER_ADMIN lists/views all orders | integration | `./mvnw -pl order-service -am verify -Dit.test=OrderControllerIT` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./mvnw -pl order-service -am test`
- **Per wave merge:** `./mvnw -pl order-service -am verify` (full suite, including `CreditLimitBoundaryConcurrencyIT`)
- **Phase gate:** Full suite green before `/gsd-verify-work`, plus the two-demo-order smoke script (Success Criterion 2) run against a real `docker compose up` stack, modeled on `scripts/smoke-notification-flow.sh`

### Wave 0 Gaps

- [ ] `order-service/src/test/java/com/orderflow/order/AbstractIntegrationTest.java` — Postgres-only singleton container (no LocalStack), copy of `inventory-service`'s base minus AWS wiring
- [ ] `order-service/src/test/java/com/orderflow/order/support/TestJwt.java` — copy verbatim from `inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java` (identical RSA-key-in-memory + `JwkSetUriJwtDecoderBuilderCustomizer` pattern)
- [ ] `order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java` — real-socket, virtual-thread, `CyclicBarrier` test modeled on `StockReservationConcurrencyIT`
- [ ] A test-side stand-in for `auth-service`/`catalog-service` HTTP responses — `MockRestServiceServer` bound to each `RestClient.Builder` (no new dependency)
- [ ] `order-service/pom.xml`, `order-service/Dockerfile` — new module scaffolding
- [ ] Root `pom.xml` `<modules>` entry, `docker-compose.yml` service block, `gateway/.../application.yml` route entry — infrastructure wiring, not test files, but required before any IT can run through the Gateway

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|-----------------|---------|--------------------|
| V2 Authentication | yes | Unchanged — resource-server JWT validation via `jwk-set-uri` + `issuer-uri`, identical to catalog/inventory/notification `[VERIFIED: catalog-service/application.yml:25-38]` |
| V3 Session Management | no | Stateless JWT, no session, `SessionCreationPolicy.STATELESS` (unchanged pattern) |
| V4 Access Control | yes | `@PreAuthorize("hasRole('BUYER')")` on `POST /orders`; `@PreAuthorize("hasRole('SELLER_ADMIN')")` on approve/reject; service-layer company-scoping for list/detail (Pattern 3 above) |
| V5 Input Validation | yes | Jakarta Bean Validation (`@NotEmpty`, `@Positive`) + service-layer all-or-nothing item validation (D-44); reject duplicate `productId` |
| V6 Cryptography | no new surface | No new crypto — JWT verification key already distributed via existing JWKS endpoint |
| V13 API and Web Service | yes | Downstream service failures (auth-service/catalog-service unreachable) must fail with a clear, generic 503 — never leak connection details/stack traces (same `GlobalExceptionHandler` discipline as all 4 existing services) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|-----------------------|
| Cross-tenant order access (BUYER guesses another company's `orderId`) | Information Disclosure | Service-layer company-scoping + 404-not-403 (Pattern 3); this is PITFALLS.md's own named "Security Mistakes" entry: "Not scoping buyer-company data access" |
| SSRF-adjacent: `baseUrl` for outbound clients coming from untrusted input | Tampering | `baseUrl` is a fixed, env-configured value (`orderflow.clients.*.base-url`), never derived from request data — same discipline as the fixed `jwk-set-uri` |
| JWT passthrough to a downstream service without re-validating trust | Spoofing | Not applicable here — the token is forwarded, not re-issued; `catalog-service`/`auth-service` independently re-validate the same JWT via their own resource-server config exactly as if the BUYER called them directly through the Gateway |
| Credit-limit race condition (two concurrent orders both auto-approve past the limit) | Tampering | Pattern 2 (pessimistic sentinel-row lock) — this is PITFALLS.md's own named Pitfall 5, predicted for exactly this phase |
| Missing audit trail on approval/rejection | Repudiation | `decided_by`/`decided_at`/`reason` columns (D-46) — PITFALLS.md's own named Security Mistake: "Storing the SELLER_ADMIN approval action without an audit trail" |

## Sources

### Primary (HIGH confidence — read directly this session)
- `C:/Users/Lucas Lopes/Documents/GSD/projeto 3/orderflow/pom.xml` — dependency/version management
- `catalog-service/src/main/java/com/orderflow/catalog/**` — `ProductController`, `ProductService`, `SecurityConfig`, `GlobalExceptionHandler`, `ProductResponse`, `ProductNotFoundException`
- `auth-service/src/main/java/com/orderflow/auth/**` — `CompanyController`, `CompanyGuard`, `CreditLimitResponse`, `Company`, `TokenService`
- `inventory-service/src/**` — `InventoryService`, `Inventory`, `RetryConfig`, `GlobalExceptionHandler`, `AbstractIntegrationTest`, `TestJwt`, `StockReservationConcurrencyIT`, `pom.xml`, `Dockerfile`, migration SQL
- `docker-compose.yml`, `gateway/src/main/resources/application.yml`, `.env.example`
- `.planning/research/ARCHITECTURE.md`, `.planning/research/PITFALLS.md` (this project's own prior research, cross-referenced extensively above)
- `scripts/smoke-notification-flow.sh` — demo-script style
- `.planning/phases/04-.../04-CONTEXT.md`, `.planning/REQUIREMENTS.md`, `.planning/STATE.md`

### Secondary (MEDIUM confidence — websearch, cross-checked across ≥2 independent sources)
- [REST Clients :: Spring Framework](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) — official docs, `RestClient` API and `onStatus`
- [New in Spring 6.1: RestClient](https://spring.io/blog/2023/07/13/new-in-spring-6-1-restclient/) — official Spring blog
- [Why Configuring Timeouts for REST Clients Is Critical in Spring Boot Applications](https://laszlosimon.dev/articles/java-and-enterprise/configuring-rest-clients-timeout/)
- [How to Configure Connection Timeout in Spring Boot](https://oneuptime.com/blog/post/2025-12-22-spring-boot-connection-timeout/view)
- [Pessimistic Locking in JPA — Baeldung](https://www.baeldung.com/jpa-pessimistic-locking)
- [How to Implement Pessimistic Locking (@Lock) with Spring Boot + JPA](https://springboot-123.mizucoffee.com/en/blog/spring-boot-jpa-pessimistic-locking-guide/)
- [Handling Pessimistic Locking with JPA on Oracle, MySQL, PostgreSQL, Apache Derby and H2 — mimacom](https://blog.mimacom.com/handling-pessimistic-locking-jpa-oracle-mysql-postgresql-derbi-h2/)
- [PostgreSQL mailing list: SELECT ... FOR UPDATE [WAIT integer | NOWAIT] for 8.5](https://www.postgresql.org/message-id/712F0D2A-A57A-40D6-BCAD-B64BC9DDDD07%40cybertec.at)
- [How to Prevent Runaway Queries with Statement Timeouts](https://oneuptime.com/blog/post/2026-01-16-postgresql-statement-timeouts/view)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every dependency already exists/is version-pinned in this repo, verified by direct file reads
- Architecture: MEDIUM-HIGH — reuses four already-proven patterns directly; the two new techniques (RestClient timeouts, pessimistic locking on Postgres) are cross-checked web findings, not yet exercised in this codebase
- Pitfalls: HIGH — 4 of 6 pitfalls are drawn directly from this project's own prior `PITFALLS.md`/`02-RESEARCH.md`, written before this phase existed and predicting these exact issues; the remaining 2 are direct code-reading findings (decidedBy polymorphism, status CHECK constraint)

**Research date:** 2026-09-25
**Valid until:** 30 days (stable Spring Boot 3.5.x line; no fast-moving dependency in this phase)
