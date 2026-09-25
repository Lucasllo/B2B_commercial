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
| **Quick run command** | `./mvnw -B -pl order-service -am test` |
| **Full suite command** | `./mvnw -B -pl order-service -am verify` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw -B -pl order-service -am test`
- **After every plan wave:** Run `./mvnw -B -pl order-service -am verify` (includes `CreditLimitBoundaryConcurrencyIT`)
- **Before `/gsd-verify-work`:** Full suite must be green, plus the two-demo-order smoke script against `docker compose up`
- **Max feedback latency:** 180 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 4-xx-xx | TBD | TBD | ORD-01 | — | BUYER-only create; items validated all-or-nothing against catalog | integration | `./mvnw -B -pl order-service -am verify -Dit.test=OrderControllerIT` | ❌ W0 | ⬜ pending |
| 4-xx-xx | TBD | TBD | ORD-02 | T-4 credit race | Over-limit → PENDING_APPROVAL, under-limit → APPROVED | integration | `./mvnw -B -pl order-service -am verify -Dit.test=OrderControllerIT` | ❌ W0 | ⬜ pending |
| 4-xx-xx | TBD | TBD | ORD-02 (SC5) | T-4 credit race | Concurrent boundary orders: at most one auto-approves (pessimistic lock) | integration (real socket) | `./mvnw -B -pl order-service -am verify -Dit.test=CreditLimitBoundaryConcurrencyIT` | ❌ W0 | ⬜ pending |
| 4-xx-xx | TBD | TBD | ORD-03 | T-4 repudiation | SELLER_ADMIN-only decide; decidedBy/decidedAt/reason recorded | integration | `./mvnw -B -pl order-service -am verify -Dit.test=OrderApprovalIT` | ❌ W0 | ⬜ pending |
| 4-xx-xx | TBD | TBD | ORD-08 | T-4 cross-tenant | BUYER sees only own-company orders; other company's order → 404 | integration | `./mvnw -B -pl order-service -am verify -Dit.test=OrderControllerIT` | ❌ W0 | ⬜ pending |
| 4-xx-xx | TBD | TBD | ORD-09 | — | SELLER_ADMIN lists/views all orders | integration | `./mvnw -B -pl order-service -am verify -Dit.test=OrderControllerIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

*Task IDs are filled in by the planner / validate-phase once PLAN.md files exist.*

---

## Wave 0 Requirements

- [ ] `order-service/pom.xml`, `order-service/Dockerfile`, root `pom.xml` `<modules>` entry — module scaffolding
- [ ] `order-service/src/test/java/com/orderflow/order/AbstractIntegrationTest.java` — Postgres-only singleton container
- [ ] `order-service/src/test/java/com/orderflow/order/support/TestJwt.java` — copy of inventory-service `TestJwt`
- [ ] Test stand-in for auth-service/catalog-service HTTP responses (`MockRestServiceServer` or equivalent)
- [ ] `order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java` — real-socket, virtual-thread, `CyclicBarrier` harness modeled on `StockReservationConcurrencyIT`

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Two demo orders with different totals through the Gateway (one PENDING_APPROVAL, one auto-approved) | ORD-02 (SC2) | Needs full `docker compose up` stack | Run the phase smoke script (modeled on `scripts/smoke-notification-flow.sh`) against the running stack |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 180s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
