---
phase: "5"
slug: "saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: true) (#2117)
status: validated
nyquist_compliant: false
wave_0_complete: true
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
| 05-01-T1 | 05-01 | 1 | ORD-04 | T-05-01, T-05-04 | Auto-approved order answers RESERVING; ReserveStock row written in the same transaction; relay delivers it to inventory-commands-queue; queues have DLQ RedrivePolicy | integration | `./mvnw -B -pl order-service verify -Dit.test=ReservationCommandPublishingIT` | ✅ | ✅ green |
| 05-01-T2 | 05-01 | 1 | ORD-04 | T-05-02, T-05-05 | Manual approval enters the same saga; legacy APPROVED rows migrate to RESERVING + outbox (D-51); a failing send never blocks the batch | integration + unit | `./mvnw -B -pl order-service verify -Dit.test=OrderApprovalIT,OrderDecisionConcurrencyIT,OrderSagaMigrationIT` and `./mvnw -B -pl order-service test -Dtest=OutboxRelayTest,OrderDomainTest` | ✅ | ✅ green |
| 05-02-T1 | 05-02 | 2 | ORD-06, ORD-05 | T-05-06, T-05-07, T-05-09 | Insufficient / not-stocked / partial multi-item → StockReservationFailed via inventory outbox, nothing reserved; malformed dropped (failure path first) | integration + unit | `./mvnw -B -pl inventory-service verify -Dit.test=ReservationCommandConsumptionIT` and `./mvnw -B -pl inventory-service test -Dtest=SagaCommandParserTest` | ✅ | ✅ green |
| 05-02-T2 | 05-02 | 2 | ORD-06 | T-05-08, T-05-10 | Success reserves all items; redelivered ReserveStock decrements once (Success Criteria 4); concurrent orders never oversell | integration | `./mvnw -B -pl inventory-service verify -Dit.test=ReservationCommandConsumptionIT,IdempotentReservationIT` | ✅ | ✅ green |
| 05-03-T1 | 05-03 | 3 | ORD-05, ORD-06 | T-05-14, T-05-15 | Failure result → CANCELLED with code and server-side readable reason; duplicate is a no-op (failure path first) | integration + unit | `./mvnw -B -pl order-service verify -Dit.test=ReservationResultListenerIT` and `./mvnw -B -pl order-service test -Dtest=SagaEventParserTest,CancellationReasonsTest,OrderDomainTest,OrderCreationServiceTest` | ✅ | ✅ green |
| 05-03-T2 | 05-03 | 3 | ORD-05, ORD-06 | T-05-12, T-05-13, T-05-16 | StockReserved → CONFIRMED; duplicate no-op; late success for CANCELLED → ReleaseStock via outbox; forged items rejected | integration | `./mvnw -B -pl order-service verify -Dit.test=ReservationResultListenerIT` | ✅ | ✅ green |
| 05-04-T1 | 05-04 | 4 | ORD-05 | T-05-17, T-05-18 | RESERVING past the timeout → CANCELLED(RESERVATION_TIMEOUT) + ReleaseStock in the same transaction; in-time orders untouched | integration + unit | `./mvnw -B -pl order-service verify -Dit.test=SagaTimeoutIT` and `./mvnw -B -pl order-service test -Dtest=SagaTimeoutJobTest,CancellationReasonsTest` | ✅ | ✅ green |
| 05-04-T2 | 05-04 | 4 | ORD-06 | T-05-19 | ReleaseStock returns stock idempotently; early ReleaseStock leaves a tombstone and the late ReserveStock answers RESERVATION_CANCELLED | integration | `./mvnw -B -pl inventory-service verify -Dit.test=TombstoneReleaseIT,IdempotentReservationIT,ReservationCommandConsumptionIT` | ✅ | ✅ green |
| 05-04-T3 | 05-04 | 4 | ORD-04 | T-05-20 | STOCK_ADJUSTED written to the inventory outbox in the same transaction; direct publisher removed; send failure keeps the event pending | integration + unit | `./mvnw -B -pl inventory-service verify` and `./mvnw -B -pl inventory-service test -Dtest=OutboxRelayTest` | ✅ | ✅ green |
| 05-05-T1 | 05-05 | 5 | TEST-03 | T-05-22, T-05-23, T-05-24 | Both services boot in one JVM with isolated config (spike); failure path E2E → CANCELLED | E2E | `./mvnw -B -pl e2e-tests -am verify -Dit.test='E2eContextsSmokeIT,OrderReservationSagaE2EIT' -Dit.failIfNoSpecifiedTests=false` | ✅ | ✅ green |
| 05-05-T2 | 05-05 | 5 | TEST-03, ORD-05 | T-05-24 | Happy path create → reserve → CONFIRMED with reserved reflected; republish reserves once; manual approval confirms | E2E | `./mvnw -B -pl e2e-tests -am verify` | ✅ | ✅ green |
| 05-06-T1 | 05-06 | 6 | ORD-04, ORD-05 | T-05-25 | Real compose stack via Gateway: CONFIRMED with reservation, CANCELLED with reason, manual approval, STOCK_ADJUSTED via outbox | smoke (manual stack) | `docker compose up -d --build --wait` then `bash scripts/smoke-order-saga.sh`, `bash scripts/smoke-order-flow.sh`, `bash scripts/smoke-notification-flow.sh`, then `docker compose down` | ✅ | ✅ green |
| 05-06-T2 | 05-06 | 6 | ORD-04, ORD-05, TEST-03 | T-05-26 | Docs explain the saga without claiming exactly-once/ordered delivery | doc grep + human check | `grep -c "Limitações conhecidas (Fase 5)" README.md` | ✅ | ✅ green |

*Rows refined by the planner (2026-09-25) with the real task IDs of plans 05-01 to 05-06. All plans run sequentially — the LocalStack Hobby session is single per token.*

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] `order-service/pom.xml` — add `spring-cloud-aws-starter-sqs`, `testcontainers:localstack`, `awaitility` → 05-01-T1
- [x] `order-service` — `SqsMessagingConfig` mirroring inventory-service's converter + timeout customizer beans → 05-01-T1
- [x] `e2e-tests` module scaffold — `pom.xml`, root `<modules>` entry, context-load smoke test settling the classpath-collision risk (RESEARCH Pitfall 2) before the saga test is written → 05-05-T1 (`E2eContextsSmokeIT` written and green before the saga tests)
- [x] `localstack-init/ready.d/02-create-order-saga-resources.sh` — `inventory-commands-queue`, `order-events-queue` and their DLQs → 05-01-T1
- [x] `docker-compose.yml` — order-service gets `SPRING_CLOUD_AWS_ENDPOINT` and `depends_on: localstack: condition: service_healthy`; localstack healthcheck probes the new queues → 05-01-T2

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Saga works end to end in the full docker-compose stack | ORD-04, ORD-05 | Compose stack and Testcontainers can't share the LocalStack Hobby session; the compose smoke is a UAT step | `docker compose up -d`, create an order as BUYER via gateway, confirm it reaches CONFIRMED and inventory reserved quantity increases |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 300s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** validated 2026-09-30

---

## Validation Audit 2026-09-30

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |

Evidence: `docker compose down` then `./mvnw -B verify` on 2026-09-30, after the code-review fixes WR-01/WR-02/WR-04 (84fa7c9, 9855a5c, 08673c6) → BUILD SUCCESS on all 8 reactor modules, 346 tests, 0 failures / 0 errors / 0 skipped. Phase 5 modules: inventory-service 32 unit + 51 integration; order-service 58 unit + 70 integration (incl. ReservationCommandPublishingIT 5, OrderSagaMigrationIT 1, ReservationResultListenerIT 11, SagaTimeoutIT 5, OrderDecisionConcurrencyIT 3); e2e-tests 12 (E2eContextsSmokeIT 7, OrderReservationSagaE2EIT 5). All 19 test classes named in the Per-Task Map exist and ran; all Wave 0 items are present (order-service SQS deps + SqsMessagingConfig, e2e-tests module in the root reactor, 02-create-order-saga-resources.sh, compose endpoint + LocalStack healthcheck on the new queues). Row 05-06-T1 (compose smoke) and row 05-06-T2 (doc human check), plus the Manual-Only row, passed in 05-UAT.md tests 1-4.
