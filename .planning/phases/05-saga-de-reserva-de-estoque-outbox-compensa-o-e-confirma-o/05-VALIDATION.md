---
phase: "5"
slug: "saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-25"
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito (unit) + Testcontainers 1.20.4 (PostgreSQL + LocalStack) + Awaitility (integration/E2E) |
| **Config file** | `order-service/src/test/resources/application-test.yml`, `inventory-service/src/test/resources/application-test.yml` (extend, don't replace) |
| **Quick run command** | `./mvnw -B -pl order-service,inventory-service test` |
| **Full suite command** | `docker compose down` then `./mvnw -B verify` (whole reactor, including `e2e-tests`) |
| **Estimated runtime** | ~300 seconds (full suite); quick run ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw -B -pl order-service,inventory-service test`
- **After every plan wave:** Run `./mvnw -B -pl order-service,inventory-service verify`
- **Before `/gsd-verify-work`:** `docker compose down` then `./mvnw -B verify` must be green (LocalStack Hobby session is single per token — the compose stack must be down for Testcontainers)
- **Max feedback latency:** 300 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 05-01-T1 | 05-01 | 1 | ORD-04 | T-05-01, T-05-04 | Auto-approved order answers RESERVING; ReserveStock row written in the same transaction; relay delivers it to inventory-commands-queue; queues have DLQ RedrivePolicy | integration | `./mvnw -B -pl order-service verify -Dit.test=ReservationCommandPublishingIT` | ❌ W0 (created in task) | ⬜ pending |
| 05-01-T2 | 05-01 | 1 | ORD-04 | T-05-02, T-05-05 | Manual approval enters the same saga; legacy APPROVED rows migrate to RESERVING + outbox (D-51); a failing send never blocks the batch | integration + unit | `./mvnw -B -pl order-service verify -Dit.test=OrderApprovalIT,OrderDecisionConcurrencyIT,OrderSagaMigrationIT` and `./mvnw -B -pl order-service test -Dtest=OutboxRelayTest,OrderDomainTest` | ❌ W0 (created in task) | ⬜ pending |
| 05-02-T1 | 05-02 | 2 | ORD-06, ORD-05 | T-05-06, T-05-07, T-05-09 | Insufficient / not-stocked / partial multi-item → StockReservationFailed via inventory outbox, nothing reserved; malformed dropped (failure path first) | integration + unit | `./mvnw -B -pl inventory-service verify -Dit.test=ReservationCommandConsumptionIT` and `./mvnw -B -pl inventory-service test -Dtest=SagaCommandParserTest` | ❌ W0 (created in task) | ⬜ pending |
| 05-02-T2 | 05-02 | 2 | ORD-06 | T-05-08, T-05-10 | Success reserves all items; redelivered ReserveStock decrements once (Success Criteria 4); concurrent orders never oversell | integration | `./mvnw -B -pl inventory-service verify -Dit.test=ReservationCommandConsumptionIT,IdempotentReservationIT` | ❌ W0 (created in task) | ⬜ pending |
| 05-03-T1 | 05-03 | 3 | ORD-05, ORD-06 | T-05-14, T-05-15 | Failure result → CANCELLED with code and server-side readable reason; duplicate is a no-op (failure path first) | integration + unit | `./mvnw -B -pl order-service verify -Dit.test=ReservationResultListenerIT` and `./mvnw -B -pl order-service test -Dtest=SagaEventParserTest,CancellationReasonsTest,OrderDomainTest,OrderCreationServiceTest` | ❌ W0 (created in task) | ⬜ pending |
| 05-03-T2 | 05-03 | 3 | ORD-05, ORD-06 | T-05-12, T-05-13, T-05-16 | StockReserved → CONFIRMED; duplicate no-op; late success for CANCELLED → ReleaseStock via outbox; forged items rejected | integration | `./mvnw -B -pl order-service verify -Dit.test=ReservationResultListenerIT` | ✅ (from 05-03-T1) | ⬜ pending |
| 05-04-T1 | 05-04 | 4 | ORD-05 | T-05-17, T-05-18 | RESERVING past the timeout → CANCELLED(RESERVATION_TIMEOUT) + ReleaseStock in the same transaction; in-time orders untouched | integration + unit | `./mvnw -B -pl order-service verify -Dit.test=SagaTimeoutIT` and `./mvnw -B -pl order-service test -Dtest=SagaTimeoutJobTest,CancellationReasonsTest` | ❌ W0 (created in task) | ⬜ pending |
| 05-04-T2 | 05-04 | 4 | ORD-06 | T-05-19 | ReleaseStock returns stock idempotently; early ReleaseStock leaves a tombstone and the late ReserveStock answers RESERVATION_CANCELLED | integration | `./mvnw -B -pl inventory-service verify -Dit.test=TombstoneReleaseIT,IdempotentReservationIT,ReservationCommandConsumptionIT` | ❌ W0 (created in task) | ⬜ pending |
| 05-04-T3 | 05-04 | 4 | ORD-04 | T-05-20 | STOCK_ADJUSTED written to the inventory outbox in the same transaction; direct publisher removed; send failure keeps the event pending | integration + unit | `./mvnw -B -pl inventory-service verify` and `./mvnw -B -pl inventory-service test -Dtest=OutboxRelayTest` | ✅ (existing IT extended) | ⬜ pending |
| 05-05-T1 | 05-05 | 5 | TEST-03 | T-05-22, T-05-23, T-05-24 | Both services boot in one JVM with isolated config (spike); failure path E2E → CANCELLED | E2E | `./mvnw -B -pl e2e-tests -am verify -Dit.test='E2eContextsSmokeIT,OrderReservationSagaE2EIT' -Dit.failIfNoSpecifiedTests=false` | ❌ W0 (created in task) | ⬜ pending |
| 05-05-T2 | 05-05 | 5 | TEST-03, ORD-05 | T-05-24 | Happy path create → reserve → CONFIRMED with reserved reflected; republish reserves once; manual approval confirms | E2E | `./mvnw -B -pl e2e-tests -am verify` | ✅ (from 05-05-T1) | ⬜ pending |
| 05-06-T1 | 05-06 | 6 | ORD-04, ORD-05 | T-05-25 | Real compose stack via Gateway: CONFIRMED with reservation, CANCELLED with reason, manual approval, STOCK_ADJUSTED via outbox | smoke (manual stack) | `docker compose up -d --build --wait` then `bash scripts/smoke-order-saga.sh`, `bash scripts/smoke-order-flow.sh`, `bash scripts/smoke-notification-flow.sh`, then `docker compose down` | ❌ W0 (created in task) | ⬜ pending |
| 05-06-T2 | 05-06 | 6 | ORD-04, ORD-05, TEST-03 | T-05-26 | Docs explain the saga without claiming exactly-once/ordered delivery | doc grep + human check | `grep -c "Limitações conhecidas (Fase 5)" README.md` | ✅ | ⬜ pending |

*Rows refined by the planner (2026-09-25) with the real task IDs of plans 05-01 to 05-06. All plans run sequentially — the LocalStack Hobby session is single per token.*

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `order-service/pom.xml` — add `spring-cloud-aws-starter-sqs`, `testcontainers:localstack`, `awaitility` → 05-01-T1
- [ ] `order-service` — `SqsMessagingConfig` mirroring inventory-service's converter + timeout customizer beans → 05-01-T1
- [ ] `e2e-tests` module scaffold — `pom.xml`, root `<modules>` entry, context-load smoke test settling the classpath-collision risk (RESEARCH Pitfall 2) before the saga test is written → 05-05-T1 (`E2eContextsSmokeIT` written and green before the saga tests)
- [ ] `localstack-init/ready.d/02-create-order-saga-resources.sh` — `inventory-commands-queue`, `order-events-queue` and their DLQs → 05-01-T1
- [ ] `docker-compose.yml` — order-service gets `SPRING_CLOUD_AWS_ENDPOINT` and `depends_on: localstack: condition: service_healthy`; localstack healthcheck probes the new queues → 05-01-T2

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Saga works end to end in the full docker-compose stack | ORD-04, ORD-05 | Compose stack and Testcontainers can't share the LocalStack Hobby session; the compose smoke is a UAT step | `docker compose up -d`, create an order as BUYER via gateway, confirm it reaches CONFIRMED and inventory reserved quantity increases |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
