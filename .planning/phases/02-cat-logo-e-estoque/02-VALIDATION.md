---
phase: "02"
slug: "cat-logo-e-estoque"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-19"
---

# Phase 02 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito (`spring-boot-starter-test`, BOM-managed) + Testcontainers 1.20.4, identical to `auth-service` |
| **Config file** | No standalone config file — behavior mirrors `auth-service/src/test/resources/application-test.yml` (profile `test` activated via `@ActiveProfiles`) |
| **Quick run command** | `./mvnw -pl catalog-service,inventory-service -am test` |
| **Full suite command** | `./mvnw -pl catalog-service,inventory-service -am verify` |
| **Estimated runtime** | ~90 seconds (Testcontainers Postgres startup + concurrency IT) |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw -pl <module> -am test` (fast unit tests only)
- **After every plan wave:** Run `./mvnw -pl catalog-service,inventory-service -am verify` (full suite, including the concurrency IT)
- **Before `/gsd-verify-work`:** Full suite must be green (including `StockReservationConcurrencyIT`)
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 1 | CAT-01 | — | SELLER_ADMIN creates/updates product; role enforced via `@PreAuthorize` | integration | `./mvnw -pl catalog-service -am verify -Dit.test=ProductControllerIT` | ❌ W0 | ⬜ pending |
| 02-01-02 | 01 | 1 | CAT-02 | — | BUYER lists/views active products, paginated, status=active filter | integration | `./mvnw -pl catalog-service -am verify -Dit.test=ProductControllerIT` | ❌ W0 | ⬜ pending |
| 02-02-01 | 02 | 1 | INV-01 | — | SELLER_ADMIN sets/updates stock quantity (upsert semantics) | integration | `./mvnw -pl inventory-service -am verify -Dit.test=InventoryControllerIT` | ❌ W0 | ⬜ pending |
| 02-02-02 | 02 | 2 | INV-02 | T-02-01 | Reservation atomic under concurrency; never oversells (real HTTP + virtual threads) | integration | `./mvnw -pl inventory-service -am verify -Dit.test=StockReservationConcurrencyIT` | ❌ W0 | ⬜ pending |
| 02-02-03 | 02 | 1 | INV-02 | T-02-02 | Repeated `reservation_id` is idempotent — no double-decrement | unit + integration | `./mvnw -pl inventory-service test -Dtest=InventoryServiceTest` + `./mvnw -pl inventory-service -am verify -Dit.test=InventoryControllerIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `catalog-service/src/test/java/com/orderflow/catalog/AbstractIntegrationTest.java` — new module, no test infra yet; copy the singleton-container pattern from `auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java:31-46` verbatim (schema differs: `catalog` not `auth`).
- [ ] `inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java` — same, for `inventory` schema.
- [ ] `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java` — new class, does **not** extend the `MockMvc`-based `AbstractIntegrationTest`; needs its own `RANDOM_PORT` + real `HttpClient` + virtual-thread executor setup.
- [ ] Both new modules' `pom.xml` — need the full `auth-service`-identical test dependency block (`spring-boot-starter-test`, `spring-boot-testcontainers`, `testcontainers:junit-jupiter`, `testcontainers:postgresql`) plus `spring-retry`/`spring-boot-starter-aop` for inventory-service's main code.

*Framework install: none — Testcontainers/JUnit5/Mockito are already proven working via `auth-service`'s existing test suite; this is pure module duplication, not new tooling.*

---

## Manual-Only Verifications

*None — all phase behaviors have automated verification (unit + Testcontainers integration, including the real-HTTP concurrency test for INV-02).*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
