---
phase: "4"
slug: "n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-24"
---

# Phase 4 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito (`spring-boot-starter-test`) + Testcontainers 1.20.x (Postgres module only — no LocalStack this phase) |
| **Config file** | none — Wave 0 installs `order-service/src/test/java/com/orderflow/order/AbstractIntegrationTest.java` (copy of inventory-service base minus LocalStack) |
| **Quick run command** | `./mvnw -B -pl order-service test` |
| **Full suite command** | `./mvnw -B -pl order-service verify` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run the task's own `<automated>` command (see map below); unit-only tasks use `./mvnw -B -pl order-service test -Dtest=...`
- **After every plan wave:** Run `./mvnw -B -pl order-service verify` (includes `CreditLimitBoundaryConcurrencyIT` and, from wave 3 on, `OrderDecisionConcurrencyIT`)
- **Before `/gsd-verify-work`:** Full suite must be green, plus the two-demo-order smoke script against `docker compose up`
- **Max feedback latency:** 180 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | ORD-01, ORD-02, ORD-08, ORD-09 | T-04-02, T-04-03, T-04-04, T-04-06, T-04-07 | BUYER-only create; companyId only from JWT; JWT forwarded to catalog/auth; APPROVED/PENDING by exposure; cross-company detail → 404 | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderControllerIT` | ❌ W0 (created by this task) | ⬜ pending |
| 04-01-02 | 01 | 1 | ORD-02 (SC5) | T-04-05 | 10 concurrent boundary orders → exactly 1 (limit 100) / 3 (limit 180) APPROVED; exposure sums exactly APPROVED/CONFIRMED/SHIPPED/DELIVERED | integration (real socket) | `./mvnw -B -pl order-service verify -Dit.test=CreditLimitBoundaryConcurrencyIT,CreditLockAndExposureIT` | ❌ W0 | ⬜ pending |
| 04-02-01 | 02 | 2 | ORD-01 | T-04-14 | All-or-nothing: duplicate productId → 400, invalid items → 422 listing all ids, nothing saved, auth not called | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderCreationEdgeCasesIT` | ❌ W0 | ⬜ pending |
| 04-02-02 | 02 | 2 | ORD-01, ORD-02 | T-04-12, T-04-13, T-04-08, T-04-01, T-04-10 | Catalog/auth 500/slow/malformed/refused → 503 fail-closed, no leak; adversarial tokens → 401; token never logged | integration + unit | `./mvnw -B -pl order-service verify -Dit.test=OrderCreationEdgeCasesIT` + `./mvnw -B -pl order-service test -Dtest=DownstreamClientsTest` | ❌ W0 | ⬜ pending |
| 04-02-03 | 02 | 2 | ORD-01, ORD-02 | T-04-11, T-04-15 | Input limits (50 items, 1000000 qty), total out of range → 422, snapshot frozen, credit boundary unit tests | unit + integration | `./mvnw -B -pl order-service test -Dtest=OrderDomainTest,OrderCreationServiceTest` + `./mvnw -B -pl order-service verify` | ❌ W0 | ⬜ pending |
| 04-03-01 | 03 | 2 | ORD-08, ORD-09 | T-04-16 | BUYER lists only own company (content and totalElements); SELLER lists all; createdAt desc | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderListIT` | ❌ W0 | ⬜ pending |
| 04-03-02 | 03 | 2 | ORD-08, ORD-09 | T-04-17, T-04-18, T-04-19 | `?status=` queue, client sort ignored, size capped at 100, invalid status → 400 | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderListIT` | ❌ W0 | ⬜ pending |
| 04-04-01 | 04 | 3 | ORD-03, ORD-02 | T-04-21, T-04-24 | SELLER approve records decidedBy=sub/decidedAt/reason; approved over limit consumes (D-38); no auth call | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderApprovalIT` | ❌ W0 | ⬜ pending |
| 04-04-02 | 04 | 3 | ORD-03 | T-04-20, T-04-25 | Reject requires reason; non-pending → 409 with audit untouched; BUYER → 403 | integration + unit | `./mvnw -B -pl order-service verify -Dit.test=OrderApprovalIT` + `./mvnw -B -pl order-service test -Dtest=OrderDomainTest` | ❌ W0 | ⬜ pending |
| 04-04-03 | 04 | 3 | ORD-03, ORD-02 | T-04-22, T-04-23 | Concurrent approve/reject → one 200 + one 409; manual approval serialized with creations | integration (real socket) | `./mvnw -B -pl order-service verify -Dit.test=OrderDecisionConcurrencyIT` | ❌ W0 | ⬜ pending |
| 04-05-01 | 05 | 4 | ORD-01, ORD-02, ORD-03, ORD-08, ORD-09 | T-04-26, T-04-28, T-04-29 | Gateway 401 without token; SC1–SC4 on the real stack (real catalog rejects DISCONTINUED) | e2e (real stack) | `docker compose up -d --build --wait` + `bash scripts/smoke-order-flow.sh` + `docker compose down` | ❌ W0 | ⬜ pending |
| 04-05-02 | 05 | 4 | ORD-01, ORD-02, ORD-03, ORD-08, ORD-09 | T-04-26 | Swagger without token, `GET /orders` still requires token | integration | `./mvnw -B -pl order-service verify -Dit.test=OpenApiDocsIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

*Task IDs are filled in by the planner / validate-phase once PLAN.md files exist.*

---

## Wave 0 Requirements

All Wave 0 items are created inside plan `04-01` (Task 1 is the tracer that scaffolds the module and its test infrastructure; Task 2 adds the concurrency harness):

- [ ] `order-service/pom.xml`, `order-service/Dockerfile`, root `pom.xml` `<modules>` entry — module scaffolding (04-01 Task 1)
- [ ] `order-service/src/test/java/com/orderflow/order/AbstractIntegrationTest.java` + `support/OrderTestInfrastructure.java` — Postgres-only singleton container registered by `@DynamicPropertySource` (04-01 Task 1)
- [ ] `order-service/src/test/java/com/orderflow/order/support/TestJwt.java` — copy of catalog/inventory `TestJwt` plus `sub`-fixing overloads (04-01 Task 1)
- [ ] `order-service/src/test/java/com/orderflow/order/support/DownstreamStubServer.java` — JDK `HttpServer` stand-in for auth-service/catalog-service (chosen over `MockRestServiceServer` so real timeouts and header forwarding are exercised over a socket; rationale in 04-01) (04-01 Task 1; failure modes added in 04-02 Task 2)
- [ ] `order-service/src/test/java/com/orderflow/order/support/ConcurrentRequests.java` + `CreditLimitBoundaryConcurrencyIT.java` — real-socket, virtual-thread, `CyclicBarrier` harness modeled on `StockReservationConcurrencyIT` (04-01 Task 2)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Swagger UI of order-service is navigable and the README explains the credit rule and the phase limitations | ORD-01..ORD-09 (presentation) | Readability for an external reviewer is not grep-verifiable | `docker compose up -d --wait`, open `http://localhost:8085/swagger-ui.html`, Authorize with a BUYER token, run `POST /orders` and `GET /orders`; read README "Como a aprovação por crédito funciona" and "Limitações conhecidas (Fase 4)" (04-05 Task 2 `<human-check>`) |

The two demo orders of SC2 (one APPROVED, one PENDING_APPROVAL through the Gateway) are now automated by `scripts/smoke-order-flow.sh` (04-05 Task 1) — they need the full stack, not a human.

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 180s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
