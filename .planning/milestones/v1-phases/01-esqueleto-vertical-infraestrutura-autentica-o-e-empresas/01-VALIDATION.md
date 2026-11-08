---
phase: "1"
slug: "esqueleto-vertical-infraestrutura-autentica-o-e-empresas"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-09-16"
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + `spring-boot-starter-test` (Mockito, AssertJ, MockMvc) + Testcontainers `postgresql` module |
| **Config file** | none yet — greenfield; created in Wave 0 of this phase's plan (`pom.xml` test dependencies + `application-test.yml`) |
| **Quick run command** | `./mvnw -pl auth-service test` (unit tests only, no Docker required) |
| **Full suite command** | `./mvnw -pl auth-service verify` (runs Testcontainers-backed integration tests via `*IT.java` naming + failsafe plugin, requires Docker running) |
| **Estimated runtime** | ~60-90 seconds (Testcontainers Postgres startup dominates) |

---

## Sampling Rate

- **After every task commit:** Run `./mvnw -pl auth-service test`
- **After every plan wave:** Run `./mvnw -pl auth-service verify`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-0X-0X | TBD | 0 | INFRA-01 | — | `docker-compose up` brings up all 4 services healthy | smoke (manual/CI, not JUnit) | `docker compose up -d && docker compose ps` (all healthy) | ❌ Wave 0 |
| 01-0X-0X | TBD | 1+ | AUTH-01 | T-1-01 | SELLER_ADMIN creates company + BUYER user via REST | integration | `./mvnw -pl auth-service test -Dtest=CompanyControllerIT#sellerAdminCreatesCompanyWithBuyer` | ❌ Wave 0 |
| 01-0X-0X | TBD | 1+ | AUTH-02 | T-1-02 | Login returns JWT with role + company_id claims | integration | `./mvnw -pl auth-service test -Dtest=AuthControllerIT#loginReturnsSignedJwtWithClaims` | ❌ Wave 0 |
| 01-0X-0X | TBD | 1+ | AUTH-03 | T-1-03 | Invalid/expired token rejected 401, no runtime call to auth-service | integration | `./mvnw -pl auth-service test -Dtest=AuthControllerIT#expiredTokenIsRejectedWith401` | ❌ Wave 0 |
| 01-0X-0X | TBD | 1+ | COMP-01 | — | Company stored with name + credit limit | integration (implicit) | covered by `CompanyControllerIT#sellerAdminCreatesCompanyWithBuyer` | ❌ Wave 0 |
| 01-0X-0X | TBD | 1+ | COMP-02 | — | SELLER_ADMIN views/updates credit limit | integration | `./mvnw -pl auth-service test -Dtest=CompanyControllerIT#sellerAdminUpdatesCreditLimit` | ❌ Wave 0 |
| 01-0X-0X | TBD | 1+ | COMP-03 | T-1-04 (BOLA/IDOR) | BUYER cannot read another company's data | integration | `./mvnw -pl auth-service test -Dtest=CompanyControllerIT#buyerCannotReadAnotherCompanysCreditLimit` | ❌ Wave 0 |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky — Task IDs and exact wave numbers finalized once PLAN.md is authored (this table is a pre-plan skeleton, per RESEARCH.md's Phase Requirements → Test Map).*

---

## Wave 0 Requirements

- [ ] `auth-service/pom.xml` test dependencies — `spring-boot-starter-test`, `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`
- [ ] `auth-service/src/test/java/.../AbstractIntegrationTest.java` — shared `@Testcontainers`/`@ServiceConnection` base class
- [ ] `auth-service/src/test/resources/application-test.yml` — test-profile config
- [ ] Maven Wrapper (`./mvnw`) at repo root — no system Maven is installed in this dev environment; every command in this file depends on it existing first

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Full docker-compose stack health (Gateway + auth-service + Postgres + LocalStack) | INFRA-01 | Full CI-grade compose smoke automation is formally scoped to Phase 7 (INFRA-02) per REQUIREMENTS.md traceability; this phase proves it manually/via `docker compose ps` | Run `docker compose up -d`, then `docker compose ps` and confirm all 4 services show `healthy` |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
