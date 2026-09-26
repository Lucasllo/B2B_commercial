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
| 5-TBD | TBD | TBD | ORD-04 | — | Outbox row written in the same transaction as the RESERVING transition; published only after commit | unit + integration | `./mvnw -B -pl order-service verify -Dit.test=OutboxRelayIT` | ❌ W0 | ⬜ pending |
| 5-TBD | TBD | TBD | ORD-05 | — | Result event drives order to CONFIRMED / CANCELLED with reason | integration | `./mvnw -B -pl order-service verify -Dit.test=ReservationResultListenerIT` | ❌ W0 | ⬜ pending |
| 5-TBD | TBD | TBD | ORD-06 | — | Redelivered reservation command decrements stock exactly once | integration | `./mvnw -B -pl inventory-service verify -Dit.test=IdempotentReservationIT` | ❌ W0 | ⬜ pending |
| 5-TBD | TBD | TBD | TEST-03 | — | Full saga success + failure paths (failure path written first) | E2E | `./mvnw -B -pl e2e-tests -am verify` | ❌ W0 | ⬜ pending |

*Rows are refined by the planner once task IDs exist.*

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `order-service/pom.xml` — add `spring-cloud-aws-starter-sqs`, `testcontainers:localstack`, `awaitility`
- [ ] `order-service` — `SqsMessagingConfig` mirroring inventory-service's converter + timeout customizer beans
- [ ] `e2e-tests` module scaffold — `pom.xml`, root `<modules>` entry, context-load smoke test settling the classpath-collision risk (RESEARCH Pitfall 2) before the saga test is written
- [ ] `localstack-init/ready.d/02-create-order-saga-resources.sh` — `inventory-commands-queue`, `order-events-queue` and their DLQs
- [ ] `docker-compose.yml` — order-service gets `SPRING_CLOUD_AWS_ENDPOINT` and `depends_on: localstack: condition: service_healthy`; localstack healthcheck probes the new queues

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
