---
phase: "06"
slug: "ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-30"
---

# Phase 06 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito (surefire `*Test`) + Testcontainers 1.20.x Postgres 16 / LocalStack (failsafe `*IT`) + Awaitility |
| **Config file** | `order-service/src/test/resources/application-test.yml`, `inventory-service/src/test/resources/application-test.yml`, `notification-service/src/test/java/.../AbstractIntegrationTest.java` |
| **Quick run command** | `./mvnw -B -pl order-service,inventory-service,notification-service test` |
| **Full suite command** | `docker compose down` e depois `./mvnw -B verify` (reactor inteiro, inclusive `e2e-tests`) |
| **Estimated runtime** | ~600 seconds (full suite com Testcontainers) |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw -B -pl <módulo afetado> test`
- **After every plan wave:** Run `./mvnw -B -pl order-service,inventory-service,notification-service verify`
- **Before `/gsd-verify-work`:** `docker compose down` + `./mvnw -B verify` verde e os smokes (`smoke-order-lifecycle.sh`, `smoke-order-saga.sh`, `smoke-order-flow.sh`, `smoke-notification-flow.sh`) verdes
- **Max feedback latency:** 120 seconds (unit)

---

## Per-Task Verification Map

> Task IDs are filled in by the planner/validate-phase once PLAN.md files exist. Requirement → test map from RESEARCH.md:

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | ORD-10 | — | Transições inválidas rejeitadas (81 pares) | unit | `./mvnw -B -pl order-service test -Dtest=OrderStatusTransitionsTest` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | — | Diagrama == `OrderStatus.transitions()` | unit | `./mvnw -B -pl order-service test -Dtest=OrderStatusDiagramConsistencyTest` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | — | Domínio respeita a tabela | unit | `./mvnw -B -pl order-service test -Dtest=OrderDomainTest` | ✅ | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | authz | status × ação → 200/409; 403 BUYER; 404 | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderLifecycleTransitionsIT` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-07 | — | Rastreio determinístico + dígito S10 | unit | `./mvnw -B -pl order-service test -Dtest=SimulatedCarrierGatewayTest,TrackingCodesTest` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-07 | — | CONFIRMED grava carrier/tracking na mesma transação | integration | `./mvnw -B -pl order-service verify -Dit.test=ReservationResultListenerIT` | ✅ | ⬜ pending |
| TBD | TBD | TBD | ORD-07 | — | V3 backfill + CHECK em base legada | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderSagaMigrationIT` | ✅ | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | — | ship/deliver + outbox + roteamento do relay | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderShipmentIT,OrderTimelinePublishingIT` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | — | `resolveQueue` novos tipos | unit | `./mvnw -B -pl order-service test -Dtest=OutboxRelayTest` | ✅ | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | — | Regressão PENDING_APPROVAL/REJECTED sem ReserveStock | integration | `./mvnw -B -pl order-service verify -Dit.test=OrderApprovalIT,ReservationCommandPublishingIT` | ✅ | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | — | ShipStock idempotente; release ignora expedida | integration | `./mvnw -B -pl inventory-service verify -Dit.test=ShipStockConsumptionIT` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | input validation | Parser aceita/rejeita ShipStock | unit | `./mvnw -B -pl inventory-service test -Dtest=SagaCommandParserTest` | ✅ | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | input validation | Timeline: válido grava, inválido descartado, rank, idempotência | unit + integration | `./mvnw -B -pl notification-service test -Dtest=NotificationServiceTest` / `verify -Dit.test=OrderTimelineIT` | ✅ / ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | authz (IDOR) | BUYER de outra empresa → 404; sem token 401 | integration | `./mvnw -B -pl notification-service verify -Dit.test=OrderTimelineControllerIT` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | — | Regressão PK genérica (Fase 3) | integration | `./mvnw -B -pl notification-service verify` | ✅ | ⬜ pending |
| TBD | TBD | TBD | ORD-07, ORD-10 | — | E2E: ship baixa on_hand/reserved | E2E | `./mvnw -B -pl e2e-tests -am verify -Dit.test=OrderShipmentE2EIT -Dit.failIfNoSpecifiedTests=false` | ❌ W0 | ⬜ pending |
| TBD | TBD | TBD | ORD-10 | — | Documentação com diagrama | doc grep | `grep -c "stateDiagram-v2" README.md` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `order-service/.../order/OrderStatusTransitionsTest.java`, `OrderStatusDiagramConsistencyTest.java` — ORD-10
- [ ] `order-service/.../OrderLifecycleTransitionsIT.java`, `OrderShipmentIT.java`, `OrderTimelinePublishingIT.java` — ORD-10
- [ ] `order-service/.../shipping/SimulatedCarrierGatewayTest.java`, `TrackingCodesTest.java` — ORD-07
- [ ] `order-service/src/test/.../support/LocalStackTestSupport.java` — provisionar `notification-events-queue` (+ DynamoDB, hook 01)
- [ ] `inventory-service/.../ShipStockConsumptionIT.java` — ORD-10
- [ ] `notification-service/.../OrderTimelineIT.java`, `OrderTimelineControllerIT.java` — ORD-10
- [ ] `e2e-tests/.../OrderShipmentE2EIT.java` — ORD-07, ORD-10
- [ ] `scripts/smoke-order-lifecycle.sh`

Framework install: nenhum (infraestrutura existente).

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Jornada completa na stack real (compose) | ORD-07, ORD-10 | Exige `LOCALSTACK_AUTH_TOKEN` e a stack docker completa | `docker compose up -d --build --wait` → `bash scripts/smoke-order-lifecycle.sh` → `docker compose down` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
