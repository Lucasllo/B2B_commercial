---
phase: "02"
slug: "cat-logo-e-estoque"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: validated
nyquist_compliant: true
wave_0_complete: true
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
| 02-01-01 | 01 | 1 | CAT-01 | — | SELLER_ADMIN creates/updates product; role enforced via `@PreAuthorize` | integration | `./mvnw -pl catalog-service -am verify -Dit.test=ProductControllerIT` | ✅ | ✅ green |
| 02-01-02 | 01 | 1 | CAT-02 | — | BUYER lists/views active products, paginated, status=active filter | integration | `./mvnw -pl catalog-service -am verify -Dit.test=ProductControllerIT` | ✅ | ✅ green |
| 02-02-01 | 02 | 1 | INV-01 | — | SELLER_ADMIN sets/updates stock quantity (upsert semantics) | integration | `./mvnw -pl inventory-service -am verify -Dit.test=InventoryControllerIT` | ✅ | ✅ green |
| 02-02-02 | 02 | 2 | INV-02 | T-02-01, T-02-21 | Reservation atomic under concurrency; never oversells (real HTTP + virtual threads) | integration | `./mvnw -pl inventory-service -am verify -Dit.test=StockReservationConcurrencyIT` | ✅ | ✅ green |
| 02-02-03 | 02 | 1 | INV-02 | T-02-02 | Repeated `reservation_id` is idempotent — no double-decrement | integration | `./mvnw -pl inventory-service -am verify -Dit.test=InventoryControllerIT#reservationSucceedsAndRepeatingSameReservationIdDoesNotDuplicateAndThirdReservationExceedingStockReturns409` | ✅ | ✅ green |
| 02-03-01 | 03 | 3 | CAT-02, INV-02 | T-02-20 | Gateway routes `/api/products/**` and `/api/inventory/**` to their services, stripping prefix; unauthenticated request rejected with 401 at both | manual (documented, gateway-level; no automated gateway IT) | `docker compose exec -T gateway curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/products` (and the `/api/inventory/{id}` equivalent) | ✅ | ✅ green (verified live by orchestrator, see Manual-Only section) |
| 02-03-02 | 03 | 3 | INV-02 | T-02-22 | Optimistic-lock retry on version conflict actually re-executes (not silently swallowed by advisor order) | integration | `./mvnw -pl inventory-service -am verify -Dit.test=InventoryRetryContentionIT` | ✅ | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] `catalog-service/src/test/java/com/orderflow/catalog/AbstractIntegrationTest.java` — done in plan 02-01.
- [x] `inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java` — done in plan 02-02.
- [x] `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java` — done in plan 02-03 (RANDOM_PORT, real `java.net.http.HttpClient`, virtual-thread-per-task executor, `CyclicBarrier`).
- [x] Both new modules' `pom.xml` — done in plans 02-01/02-02.

*Framework install: none — Testcontainers/JUnit5/Mockito are already proven working via `auth-service`'s existing test suite; this is pure module duplication, not new tooling.*

---

## Manual-Only Verifications

Plan 02-03's Task 1 (docker-compose + Gateway routing) has no dedicated automated integration test — it is verified via `docker compose` CLI checks (config validity, health, HTTP status codes through the live Gateway) plus a documented human-check demo flow in README.md, per the plan's own `<verify>` block (CAT-02/INV-02 flagged-assumption: "encadear login, extração de token e chamada autenticada dentro de um comando de verificação portável entre shells é frágil"). This was executed live by the orchestrator (outside the sandboxed worktree executor, since it required a populated `.env` and a running Docker daemon) on 2026-09-20:

- `docker compose config --quiet` — OK
- `docker compose up -d --wait` — all 6 services healthy
- `docker compose ps` — all healthy
- Unauthenticated `GET /api/products` and `GET /api/inventory/{id}` through the Gateway — both 401
- `POST /api/auth/login` through the Gateway — no regression (200 valid creds, 400 invalid body)
- Full seller demo flow (login → create product → set stock 100 → check availability → reserve 10 → availability 90) — matches README exactly
- Full buyer flow (`POST /companies` creates company+buyer → buyer login → list catalog 200, check availability 200, create product 403, set stock 403)

All other phase behaviors have automated verification (unit + Testcontainers integration, including the real-HTTP concurrency test for INV-02 and the isolated retry-contention test).

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies (02-03-01 is the one documented manual exception, per above)
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** validated 2026-09-20 — 6/7 tasks automated, 1 documented manual-only (gateway E2E), zero gaps.

## Validation Audit 2026-09-20

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 (all requirements already covered by tests written during execution) |
| Escalated | 1 (02-03-01 gateway E2E — manual-only by design, not a gap) |
