---
phase: 02-cat-logo-e-estoque
verified: 2026-09-20T18:30:00Z
status: passed
score: 4/4 must-haves verified
covered_files: [".planning/REQUIREMENTS.md", ".planning/ROADMAP.md", ".planning/phases/02-cat-logo-e-estoque/02-01-PLAN.md", ".planning/phases/02-cat-logo-e-estoque/02-01-SUMMARY.md", ".planning/phases/02-cat-logo-e-estoque/02-02-PLAN.md", ".planning/phases/02-cat-logo-e-estoque/02-02-SUMMARY.md", ".planning/phases/02-cat-logo-e-estoque/02-03-PLAN.md", ".planning/phases/02-cat-logo-e-estoque/02-03-SUMMARY.md", ".planning/phases/02-cat-logo-e-estoque/02-REVIEW.md", ".planning/phases/02-cat-logo-e-estoque/02-SECURITY.md", ".planning/phases/02-cat-logo-e-estoque/02-VALIDATION.md", "README.md", "catalog-service/src/main/java/com/orderflow/catalog/config/GlobalExceptionHandler.java", "catalog-service/src/main/java/com/orderflow/catalog/config/SecurityConfig.java", "catalog-service/src/main/java/com/orderflow/catalog/product/Product.java", "catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java", "catalog-service/src/main/java/com/orderflow/catalog/product/ProductService.java", "docker-compose.yml", "gateway/src/main/resources/application.yml", "inventory-service/src/main/java/com/orderflow/inventory/config/RetryConfig.java", "inventory-service/src/main/java/com/orderflow/inventory/config/SecurityConfig.java", "inventory-service/src/main/java/com/orderflow/inventory/stock/Inventory.java", "inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java", "inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java", "inventory-service/src/test/java/com/orderflow/inventory/InventoryRetryContentionIT.java", "inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java"]
covered_digest: "v1:sha256:161a9565a68dd715c2f8411b45acd319a139a5085908a8309bafa1a971b264ee"
overrides_applied: 0
behavior_unverified: 0
coincidental_reliance_items: []
---

# Phase 2: Catálogo e Estoque — Verification Report

**Phase Goal:** O vendedor mantém o catálogo de produtos e os níveis de estoque, o comprador autenticado enxerga o catálogo disponível, e a reserva de estoque já nasce protegida contra concorrência — domínio e persistência sólidos antes de qualquer mensageria entrar em cena.
**Verified:** 2026-09-20
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth (ROADMAP Success Criteria) | Status | Evidence |
|---|---|---|---|
| 1 | SELLER_ADMIN cria/atualiza produtos (nome, preço, descrição); BUYER lista/consulta produtos e preços pelo Gateway; cada operação restrita ao papel correto pelo JWT | ✓ VERIFIED | `ProductController.java` declares `@PreAuthorize("hasRole('SELLER_ADMIN')")` on `POST`, `PUT /{id}`, `PUT /{id}/status`; `GET` routes open to any authenticated role, filtered by `isSellerAdmin(authentication)` derived from `Authentication.getAuthorities()` (never from client input). `ProductControllerIT` (27 tests, all green) exercises 201/200/403/401/409/400 paths against a real PostgreSQL Testcontainer. Orchestrator independently exercised the live path through the Gateway: unauthenticated `GET /api/products` → 401; full seller flow (login → create product) and buyer flow (list catalog 200, create product 403) matched exactly. |
| 2 | Vendedor define/atualiza quantidade em estoque por produto e consulta disponibilidade atual de qualquer item | ✓ VERIFIED | `InventoryController.PUT /{productId}` gated `@PreAuthorize("hasRole('SELLER_ADMIN')")`; `GET /{productId}` open to any authenticated caller, returns `quantityOnHand`/`quantityReserved`/`quantityAvailable` as numbers (never a boolean), matching D-25. `InventoryService.setStock` is a genuine upsert (`findByProductId(...).orElse(null)` → insert or `setOnHand`), covered by `InventoryControllerIT` (21 tests green). Orchestrator confirmed live: set stock 100 → GET returns 100/0/100. |
| 3 | A reserva de estoque é atômica: teste de concorrência disparando requisições paralelas contra as últimas unidades nunca reserva mais do que o disponível | ✓ VERIFIED | `StockReservationConcurrencyIT` (read in full) genuinely does what it claims: `@SpringBootTest(webEnvironment=RANDOM_PORT)`, a shared `java.net.http.HttpClient` (real socket, not `MockMvc`), `Executors.newVirtualThreadPerTaskExecutor()`, and a `CyclicBarrier` sized to the exact contender count so every request is released simultaneously. Three contention scenarios (1/3/5 units, up to 20 contenders) assert exactly the right number of successes and that `quantityReserved` never exceeds `quantityOnHand`. The mandatory sanity experiment was actually performed (not just claimed): `@Version` was removed from `Inventory.java`, the 1-unit scenario was re-run and produced **10 successes instead of 1**, `@Version` was restored, and the round-trip was confirmed clean via `git diff` before commit — this is exactly the falsifiability check the plan's `must_haves.prohibitions` required ("MUST NOT declarar prova de atomicidade um teste que não força sobreposição real"), and it was honored, not skipped. A genuine Critical defect (CR-01: `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` tied with, rather than beat, `@EnableTransactionManagement`'s own default order — verified by decompiling the actual `spring-retry-2.0.13.jar`/`spring-tx-6.2.19.jar` resolved by this build) was found by code review and is **fixed in the current source** (`RetryConfig.java` now reads `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)`, commit `4b5d486`, confirmed present in `git log`). |
| 4 | catalog-service e inventory-service sobem no mesmo `docker-compose up`, cada um com seu próprio schema, acessíveis somente com JWT válido | ✓ VERIFIED | `docker-compose.yml` has `catalog-service` (port 8082, schema `catalog`) and `inventory-service` (port 8083, schema `inventory`) blocks, each depending on `postgres`+`auth-service` healthy, no floating tags, ports restricted to `127.0.0.1`. `gateway/application.yml` has `catalog-service-route` (`/api/products/**`) and `inventory-service-route` (`/api/inventory/**`), both `StripPrefix=1`, alongside the untouched `auth-service-route`. Orchestrator personally ran `docker compose up -d --wait` and confirmed all 6 services healthy, confirmed 401 on both new routes without a token, confirmed no regression on `/api/auth/login`. |

**Score:** 4/4 ROADMAP Success Criteria verified, 0 present-but-behavior-unverified.

### Requirement-Level Truths (from PLAN must_haves, spot-checked against source)

| # | Truth | Status | Evidence |
|---|---|---|---|
| 5 | Produto nasce sempre `ACTIVE`, status nunca vem do cliente (CAT-01) | ✓ VERIFIED | `CreateProductRequest` has no status field; `Product`'s public constructor fixes `ProductStatus.ACTIVE` by literal — confirmed by reading `ProductController`/`Product.java`. |
| 6 | Preço nunca é arredondado em silêncio; 3 casas decimais é rejeitado (CAT-01) | ✓ VERIFIED | `CreateProductRequest.price` carries `@Digits(integer=17, fraction=2)`; `ProductService.updateDetails` stores `BigDecimal` verbatim, no `setScale`/`round` call present in `Product.java`. |
| 7 | Retirada de produto é sempre soft-delete, nunca remoção física (CAT-01) | ✓ VERIFIED | No `deleteById`/`delete(...)` call exists anywhere in `ProductRepository`/`ProductService`; the only status-change path is `PUT /{id}/status`, and `ProductControllerIT` asserts `productRepository.count()` is unchanged after a status transition. |
| 8 | Reserva de estoque é idempotente por `reservationId` fornecido pelo chamador (INV-02) | ✓ VERIFIED | `InventoryService.reserve` checks `stockReservationRepository.findByProductIdAndReservationId` before mutating state and short-circuits on a hit; backed by DB constraint `UNIQUE(product_id, reservation_id)`. `InventoryControllerIT` proves repeat calls don't double-decrement. (Note: WR-04 below flags a real but non-blocking edge case in this same method.) |
| 9 | Reexecução por conflito de lock otimista de fato reexecuta com transação nova (INV-02) | ✓ VERIFIED | `InventoryRetryContentionIT` (read in full) forces a genuine version conflict deterministically (reads a detached copy via repository, mutates the row through a separate legitimate call, attempts to persist the stale copy) and confirms `ObjectOptimisticLockingFailureException` triggers a real re-execution via a test `RetryListener` bean — not inferred from aggregate success alone. |
| 10 | Inventory-service nunca chama catalog-service; productId é referência opaca (D-15, INV-02) | ✓ VERIFIED | No `RestClient`/`RestTemplate`/`WebClient`/`HttpClient` import exists under `inventory-service/src/main/java/` (confirmed by the plan's own acceptance-criteria grep, re-derivable from the file listing); `InventoryService` methods take a bare `UUID productId` with no service-to-service call. |

## Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `catalog-service/src/main/resources/db/migration/V1__init_catalog_schema.sql` | `products` table, `NUMERIC(19,2)` price, status `CHECK` | ✓ VERIFIED | Present, matches plan spec |
| `catalog-service/.../product/Product.java` | Entity, `EnumType.STRING` | ✓ VERIFIED | Present, `@Enumerated(EnumType.STRING)` confirmed |
| `catalog-service/.../product/ProductController.java` | Role-gated routes | ✓ VERIFIED | `@PreAuthorize` on write verbs confirmed by direct read |
| `catalog-service/.../config/SecurityConfig.java` | Resource-server-only JWT validation | ✓ VERIFIED | Present, replicated correctly into inventory-service |
| `inventory-service/.../stock/Inventory.java` | `@Version` optimistic lock | ✓ VERIFIED | Present |
| `inventory-service/.../config/RetryConfig.java` | `@EnableRetry` with correct advisor order | ✓ VERIFIED | Present, CR-01 fix confirmed live in source and in git history |
| `inventory-service/.../stock/InventoryController.java` | Role-gated reserve/release routes | ✓ VERIFIED | Present |
| `inventory-service/src/test/.../StockReservationConcurrencyIT.java` | Real-HTTP + virtual-thread + `CyclicBarrier` concurrency proof | ✓ VERIFIED | Read in full; matches claims exactly, including the sanity-check discipline |
| `inventory-service/src/test/.../InventoryRetryContentionIT.java` | Isolated proof that retry re-executes | ✓ VERIFIED | Read in full; deterministic forced-conflict scenario is genuinely deterministic, not timing-dependent |
| `docker-compose.yml` | catalog/inventory blocks, healthchecks, no floating tags | ✓ VERIFIED | Present, orchestrator confirmed live |
| `gateway/src/main/resources/application.yml` | Static routes for products/inventory | ✓ VERIFIED | Present, orchestrator confirmed 401 live |
| `README.md` | Endpoints + demo flow | ✓ VERIFIED | Present; the documented demo flow matches exactly what the orchestrator independently exercised end-to-end |

### Key Link Verification

| From | To | Via | Status |
|---|---|---|---|
| `ProductController` | `SecurityConfig` | `@PreAuthorize("hasRole('SELLER_ADMIN')")` works because `@EnableMethodSecurity` + role-claim converter is present | ✓ WIRED |
| `gateway/application.yml` | `docker-compose.yml` | Route host/port (`catalog-service:8082`, `inventory-service:8083`) matches compose service names/ports exactly | ✓ WIRED |
| `InventoryService.reserve` | `RetryConfig` | `@Retryable` on a method called only from the controller (never self-invoked), crossing the Spring proxy | ✓ WIRED |
| `StockReservationConcurrencyIT` | `InventoryService` | Exercises the full stack by real socket (embedded server, security filters, transaction, optimistic lock) — not a direct in-process call | ✓ WIRED |

## Behavioral Spot-Checks

| Behavior | Method | Result | Status |
|---|---|---|---|
| Reservation atomicity under real concurrency | Read `StockReservationConcurrencyIT` source in full; confirmed `HttpClient`, `newVirtualThreadPerTaskExecutor`, `CyclicBarrier` sized to contender count, status classification (200/409/503, no 5xx), final-state assertions | ✓ PASS | Test genuinely proves the claim; not a sequential loop disguised as a concurrency test |
| Retry-advisor-order fix (CR-01) | Read `RetryConfig.java`; confirmed `Ordered.LOWEST_PRECEDENCE - 1`; confirmed commit `4b5d486` exists in `git log` with matching message | ✓ PASS | Fix is real and present, not just claimed in SUMMARY |
| Role guards (`@PreAuthorize`) | Read `ProductController.java` and `InventoryController.java` directly | ✓ PASS | Matches SUMMARY/SECURITY claims exactly |
| Gateway routing + docker-compose wiring | Read `docker-compose.yml` and `gateway/application.yml` directly; cross-checked ports/schema names | ✓ PASS | Matches SUMMARY claims exactly |
| No debt markers (`TBD`/`FIXME`/`XXX`/`HACK`/`PLACEHOLDER`) in phase source | `grep -rn` across `catalog-service/src/main`, `inventory-service/src/main`, `gateway/src/main`, `docker-compose.yml`, `README.md` | ✓ PASS | No matches |
| Full test suite green | Not re-run (avoided redundant execution — orchestrator already ran `./mvnw -B -pl auth-service,catalog-service,inventory-service verify` fresh after CR-01, confirmed BUILD SUCCESS, 27/27 inventory-service tests incl. both concurrency ITs, no auth-service regression) | ✓ PASS (orchestrator-verified, corroborated by reading the source of the passing tests) | — |

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| CAT-01 | 02-01 | Vendedor cria/atualiza produtos | ✓ SATISFIED | `ProductController`/`ProductService`, `ProductControllerIT` (27 green) |
| CAT-02 | 02-01, 02-03 | Comprador lista/consulta produtos e preços | ✓ SATISFIED | `ProductController.list`/`getById` role-filtered; Gateway route verified live |
| INV-01 | 02-02 | Vendedor define/atualiza estoque | ✓ SATISFIED | `InventoryController.setStock`/`getStock`, `InventoryControllerIT` |
| INV-02 | 02-02, 02-03 | Reserva atômica/idempotente contra overselling | ✓ SATISFIED | `InventoryService.reserve`, `@Version`, `StockReservationConcurrencyIT`, `InventoryRetryContentionIT` |

**Note (non-blocking, documentation only):** `.planning/REQUIREMENTS.md` still lists `INV-02` as unchecked (`[ ]`) in the "Active" section and `Pending` in the Traceability table (lines 27 and 115), even though CAT-01, CAT-02, and INV-01 on the same lists were already updated to `[x]`/`Complete`. This is a requirements-tracking bookkeeping gap, not a code gap — the implementation, tests, security audit, and code review all treat INV-02 as fully delivered, and the ROADMAP.md Progress table correctly still shows Phase 2 as "In Progress" pending this verification pass. Recommend updating `REQUIREMENTS.md` for INV-02 as part of phase transition/completion so the tracking docs match reality.

### Anti-Patterns Found

None blocking. No `TBD`/`FIXME`/`XXX` debt markers, no stub returns, no hardcoded empty payloads found in the phase's production source.

Five Warnings and two Info findings from `02-REVIEW.md` remain open by the project's own explicit decision (documented in the review frontmatter: `critical_fixed: 1`, `critical_open: 0`, warnings/info left as "documented future work"). None of them contradict a ROADMAP Success Criterion or a PLAN must-have truth, so none are blockers for this phase goal. Worth carrying forward as known technical debt, in order of interviewer-visibility:

- **WR-01** (`ProductController.java:49`): `Location` header returned by `POST /products` is host-relative to catalog-service (`/products/{id}`), not gateway-relative (`/api/products/{id}`) — a client that follows `Location` literally through the Gateway gets a 404. The documented README demo flow doesn't hit this (it reads `.id` from the JSON body, not `Location`), so it doesn't break any documented capability, but it is a real, unfixed REST-correctness bug.
- **WR-02** (`GlobalExceptionHandler.java`, both services): the class Javadoc's "uniform error body for the whole service" claim doesn't hold for real 401s (rejected earlier in the filter chain by Spring Security's default entry point, never reaching the `@RestControllerAdvice`) — confirmed by the fact that `ProductControllerIT`'s own uniform-shape assertion deliberately excludes the 401 test cases.
- **WR-03**: catalog-service's catch-all `DataIntegrityViolationException` handler mislabels any non-SKU constraint violation as `sku_already_used`; inventory-service's equivalent handler does this correctly (`data_conflict`).
- **WR-04** (`InventoryService.java:120-125`): idempotent-replay short-circuit on `reserve` doesn't verify the replayed quantity matches the original — a caller resending the same `reservationId` with a different quantity is silently masked rather than flagged. Worth resolving before Phase 5 wires the SQS consumer against this exact contract.
- **WR-05**: `SecurityConfig`, `GlobalExceptionHandler`, `TestJwt` duplicated verbatim across catalog-service/inventory-service — no shared module yet.
- **IN-01/IN-02**: `Page<T>` returned directly (Spring-documented anti-pattern, cosmetic WARN log only); `Product` has no `@Version` unlike `Inventory` (asymmetric optimistic-locking decision, undocumented).

## Human Verification Required

None outstanding. The one human-check item the plan deferred (`02-03-PLAN.md` Task 1 `<human-check>`: exercise the full seller and buyer demo flow live through the Gateway) was already executed by the orchestrator prior to this verification pass and is documented in `02-03-SUMMARY.md`/`02-VALIDATION.md` §Manual-Only Verifications, matching the task's context brief exactly (both flows, including the 403s for BUYER attempting write operations).

## Gaps Summary

No gaps block the Phase 2 goal. All four ROADMAP Success Criteria are genuinely achieved, not merely claimed:

- The concurrency proof (Success Criteria 3) is unusually rigorous for this class of project: real sockets, real virtual threads, a real `CyclicBarrier`-forced simultaneous release, and — critically — the mandatory sanity experiment (temporarily removing `@Version` and confirming the test goes red with 10 successes instead of 1) was actually performed and its result is documented, not just asserted.
- A genuine Critical defect was found by code review in the retry/transaction advisor ordering (CR-01) and is verifiably fixed in the current source (not just claimed) — this is exactly the kind of silent-correctness bug that could have undermined the entire Success Criteria 3 story, and it was caught and closed with byte-level evidence (bytecode decompilation of the actual resolved jars).
- Security review closed all 28 threats registered for the phase with zero `high`+ open; the two 401-related findings that are `open` in spirit (issuer-uri not in prod config, no automated Gateway 401 regression gate) are correctly logged as non-blocking findings, not open threats.

The five open Warnings from code review are real, legitimate findings — worth fixing, especially WR-04 given it touches the exact idempotency contract Phase 5's saga will depend on — but none of them falsify a ROADMAP Success Criterion or a PLAN must-have truth, and the project's own review process explicitly triaged them as acceptable to carry forward. The one documentation-only issue (REQUIREMENTS.md not yet marking INV-02 complete) is a bookkeeping gap, trivially fixable at phase transition, and does not reflect a gap in the actual implementation.

---

_Verified: 2026-09-20_
_Verifier: Claude (gsd-verifier)_
