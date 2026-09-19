---
phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
verified: 2026-09-19T03:12:05Z
status: passed
human_confirmed: 2026-09-19
score: 11/11 must-haves verified
covered_files: [".env.example", ".planning/REQUIREMENTS.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-01-PLAN.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-01-SUMMARY.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-02-PLAN.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-02-SUMMARY.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-03-PLAN.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-03-SUMMARY.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-04-PLAN.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-04-SUMMARY.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-05-PLAN.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-05-SUMMARY.md", ".planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-REVIEW.md", "README.md", "auth-service/Dockerfile", "auth-service/pom.xml", "auth-service/src/main/java/com/orderflow/auth/AuthServiceApplication.java", "auth-service/src/main/java/com/orderflow/auth/auth/AuthController.java", "auth-service/src/main/java/com/orderflow/auth/auth/TokenService.java", "auth-service/src/main/java/com/orderflow/auth/auth/dto/CurrentUserResponse.java", "auth-service/src/main/java/com/orderflow/auth/auth/dto/LoginRequest.java", "auth-service/src/main/java/com/orderflow/auth/auth/dto/LoginResponse.java", "auth-service/src/main/java/com/orderflow/auth/company/Company.java", "auth-service/src/main/java/com/orderflow/auth/company/CompanyController.java", "auth-service/src/main/java/com/orderflow/auth/company/CompanyGuard.java", "auth-service/src/main/java/com/orderflow/auth/company/CompanyNotFoundException.java", "auth-service/src/main/java/com/orderflow/auth/company/CompanyRepository.java", "auth-service/src/main/java/com/orderflow/auth/company/CompanyService.java", "auth-service/src/main/java/com/orderflow/auth/company/EmailAlreadyUsedException.java", "auth-service/src/main/java/com/orderflow/auth/company/dto/CompanyResponse.java", "auth-service/src/main/java/com/orderflow/auth/company/dto/CreateCompanyRequest.java", "auth-service/src/main/java/com/orderflow/auth/company/dto/CreditLimitResponse.java", "auth-service/src/main/java/com/orderflow/auth/company/dto/UpdateCreditLimitRequest.java", "auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java", "auth-service/src/main/java/com/orderflow/auth/config/JwksController.java", "auth-service/src/main/java/com/orderflow/auth/config/JwtIssuerConfig.java", "auth-service/src/main/java/com/orderflow/auth/config/SecurityConfig.java", "auth-service/src/main/java/com/orderflow/auth/user/CustomUserDetailsService.java", "auth-service/src/main/java/com/orderflow/auth/user/Role.java", "auth-service/src/main/java/com/orderflow/auth/user/User.java", "auth-service/src/main/java/com/orderflow/auth/user/UserRepository.java", "auth-service/src/main/resources/application.yml", "auth-service/src/main/resources/db/migration/V1__init_auth_schema.sql", "auth-service/src/main/resources/db/migration/V2__seed_seller_admin.sql", "auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java", "auth-service/src/test/java/com/orderflow/auth/AuthControllerIT.java", "auth-service/src/test/java/com/orderflow/auth/CompanyControllerIT.java", "auth-service/src/test/java/com/orderflow/auth/JwksContractIT.java", "auth-service/src/test/java/com/orderflow/auth/SeedPasswordHashTest.java", "auth-service/src/test/java/com/orderflow/auth/support/JwksAccessCounter.java", "auth-service/src/test/java/com/orderflow/auth/support/TestTokens.java", "docker-compose.yml", "gateway/Dockerfile", "gateway/pom.xml", "gateway/src/main/java/com/orderflow/gateway/GatewayApplication.java", "gateway/src/main/resources/application.yml", "pom.xml"]
covered_digest: "v1:sha256:139b19c3502b821cf8b28c3a13564d529164ef1293c2c286b13e69a02989f2f6"
behavior_unverified: 0
overrides_applied: 0
human_verification:
  - test: "Ler apenas a seção \"Credenciais de demonstração\" do README.md (linhas 67-74) como alguém que nunca viu o projeto."
    expected: "Deve ficar imediatamente claro que `admin@orderflow.local` / `ChangeMe!123` é um usuário de demonstração intencional (não um descuido de segurança nem uma credencial administrativa real). Se restar qualquer dúvida sobre isso, o texto precisa ser reescrito (critério do próprio plano 01-03, Task 2)."
    why_human: "O plano 01-03 (`<task type=\"auto\">` Task 2) deferiu explicitamente este item para um `<human-check>` dentro de `<verify>` — é um julgamento qualitativo de clareza de mensagem para um leitor hipotético que nunca viu o projeto, exatamente o tipo de checagem que a verificação automatizada não pode substituir por si só. O verificador leu o texto e o considera claro (ver Goal Achievement abaixo), mas o próprio plano exige a leitura humana como o critério de aceite, não a leitura do verificador."
---

# Phase 1: Esqueleto Vertical — Infraestrutura, Autenticação e Empresas Verification Report

**Phase Goal:** Todo o sistema sobe com um único `docker-compose up` e um usuário real (vendedor ou comprador) se autentica pelo API Gateway recebendo um JWT com papel e empresa, com as empresas compradoras e seus limites de crédito já persistidos — a base que todas as fases seguintes assumem pronta.
**Verified:** 2026-09-19T03:12:05Z
**Status:** passed (human-confirmed 2026-09-19)
**Re-verification:** No — initial verification

## Process Note: MVP Mode Format Mismatch (non-blocking)

ROADMAP.md marks this phase `Mode: mvp`, which normally triggers the User-Story-goal verification
overlay (`gsd-core/references/verify-mvp-mode.md`). Running the canonical format guard
(`user-story.validate`) against the phase goal returns `valid: false` — the goal text is a
declarative outcome statement in Portuguese, not an `"As a ..., I want to ..., so that ...."`
sentence. Rather than refuse verification outright (which would leave five fully-executed,
heavily-tested plans with no verification record), this report proceeds with the standard
goal-backward methodology, using the four ROADMAP Success Criteria as the authoritative truths
(Step 2a) — this is not a lowering of rigor, since Phase 1's Success Criteria are already concrete
and independently testable. **Recommendation:** run `/gsd mvp-phase 1` (or otherwise reformat the
goal) if strict MVP-mode User Flow Coverage reporting is desired for this phase retroactively;
this is advisory only and does not affect the verdict below.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | (ROADMAP SC1) From a clean clone, `docker compose up -d --wait` brings up gateway + auth-service + postgres + localstack, all with pinned versions, all healthy | ✓ VERIFIED | Live stack independently checked: `docker compose ps` → all 4 services `running healthy`. `docker-compose.yml` inspected directly: every `image:` line pinned (`postgres:16.15`, `localstack/localstack:2026.08.3`), 3× `condition: service_healthy` edges, `${VAR:?}` fail-fast for both secrets |
| 2 | (ROADMAP SC2) SELLER_ADMIN creates, via REST through the Gateway, a buyer company with name+credit limit and a linked BUYER user | ✓ VERIFIED | Independently exercised live against `http://localhost:8080/api/companies`: `POST` with SELLER_ADMIN token → 201, company + buyer created, `buyerUser.role=BUYER` |
| 3 | (ROADMAP SC3) User logs in and receives a signed JWT with role and, for buyers, company_id; invalid/expired token rejected 401 without any runtime call to auth-service | ✓ VERIFIED | Live: SELLER_ADMIN token has `role` claim, no `company_id` key; BUYER token created live has `company_id` equal (string) to the company's `id`. `JwksContractIT` (run independently, 3/3 pass) measures the JWKS-endpoint access counter stays at exactly 1 after 5 authenticated calls + 5 offline decodes — the "no runtime call per token" claim is measured, not asserted |
| 4 | (ROADMAP SC4) Seller reads/updates a company's credit limit; a BUYER accessing another company's data gets an authorization error — isolation proven by test, not convention | ✓ VERIFIED | Live: BUYER `PUT` own company's credit-limit → 403; BUYER `GET` another company's credit-limit → 403. `CompanyControllerIT` (independently run, 30/30 pass) includes the byte-identical 403-vs-404-oracle test |
| 5 | `./mvnw -B -pl auth-service,gateway package -DskipTests` produces an executable jar for each module from a clean clone, no system Maven | ✓ VERIFIED | `mvnw`/`mvnw.cmd`/`.mvn/wrapper/maven-wrapper.properties` present and not gitignored (`git check-ignore -q mvnw` → exit 1); independently ran `./mvnw -B -pl auth-service verify` → `BUILD SUCCESS` |
| 6 | JWT is RS256 with `iss=orderflow-auth-service`, `sub`=user UUID, `role`; JWKS at `/.well-known/jwks.json` exposes exactly one signature key with no private material | ✓ VERIFIED | `JwtIssuerConfig.java` read directly: RSA-2048 generated once at construction, `toPublicJWK()` used by `JwksController`. Live `GET http://localhost:8081/.well-known/jwks.json` → single RSA key, `kty/use/alg/kid` present, no `d/p/q/dp/dq/qi` |
| 7 | `companies.credit_limit` is `NUMERIC(19,2)`; a limit with >2 decimals or negative is rejected with 400, never silently rounded | ✓ VERIFIED | `V1__init_auth_schema.sql` contains the literal `NUMERIC(19,2)`; `Company.creditLimit` mapped `precision=19, scale=2`; live `POST /companies` with `creditLimit:"100.005"` → 400 `validation_failed`; live with `-1.00` → 400; `Company.changeCreditLimit`/`CompanyService` never call `setScale`/`round` (grepped, zero matches) |
| 8 | SELLER_ADMIN seed password is stored only as a BCrypt hash; a test proves the literal hash in `V2__seed_seller_admin.sql` matches the documented demo password | ✓ VERIFIED | `SeedPasswordHashTest` (2/2 pass, run independently as part of the full suite) proves `BCryptPasswordEncoder().matches("ChangeMe!123", SEED_HASH)`; migration file inspected — no plaintext password present |
| 9 | Role assignment for a company-created user is always server-side `BUYER`, immune to client-supplied role fields (mass-assignment defense) | ✓ VERIFIED | `CompanyService.createCompanyWithBuyer` hardcodes `Role.BUYER`; `CreateCompanyRequest` has no role field and uses `@JsonIgnoreProperties(ignoreUnknown = true)`; `CompanyControllerIT#createCompanyIgnoresClientSuppliedRoleAndAlwaysCreatesBuyer` independently confirmed passing |
| 10 | Company isolation is a genuine object-level guard (`CompanyGuard`), not a Hibernate filter that can silently fail open | ✓ VERIFIED | `CompanyGuard.java` read directly: `@Component("companyGuard")`, defensive `instanceof Jwt` check (returns `false`, never throws), role-then-claim comparison logic matches spec exactly; grep for `@FilterDef`/`@Filter` under `src/main/java` → zero matches |
| 11 | Requirements traceability: all 7 phase requirement IDs (INFRA-01, AUTH-01, AUTH-02, AUTH-03, COMP-01, COMP-02, COMP-03) are claimed by at least one plan, no orphans | ✓ VERIFIED | Union of `requirements:` frontmatter across the 5 plans exactly equals the 7 IDs REQUIREMENTS.md maps to Phase 1 — no gaps, no orphans (see Requirements Coverage below) |

**Score:** 11/11 truths verified (0 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `pom.xml` | Maven reactor parent, 3 BOMs imported, no version ranges | ✓ VERIFIED | `<modules>gateway, auth-service</modules>`; BOMs for spring-boot-dependencies/spring-cloud-dependencies/testcontainers-bom present |
| `mvnw`, `.mvn/wrapper/maven-wrapper.properties` | Committed wrapper, functional without system Maven | ✓ VERIFIED | Present, not gitignored, `./mvnw -B -pl auth-service verify` ran successfully in this sandbox |
| `auth-service/Dockerfile`, `gateway/Dockerfile` | Multi-stage, pinned `eclipse-temurin:21.x.y`, non-root, curl installed | ✓ VERIFIED | Both files read directly: 4 `FROM` lines total, all `eclipse-temurin:21.0.12_8-{jdk,jre}-jammy`, `USER orderflow` non-root, curl installed in runtime stage |
| `docker-compose.yml` | 4 services, healthchecks, `service_healthy` gating | ✓ VERIFIED | Read directly; matches must-have exactly (see Truth 1) |
| `.env.example` | Placeholders for `LOCALSTACK_AUTH_TOKEN`, Postgres vars | ✓ VERIFIED | Present, versioned, contains all 4 keys with explanatory comments |
| `README.md` | One-step setup, demo credential declared | ✓ VERIFIED (content) / see Human Verification | Contains all required literals (`LOCALSTACK_AUTH_TOKEN`, `docker compose up -d --wait`, `admin@orderflow.local`, `mvnw -B -pl auth-service verify`); "Credenciais de demonstração" section read directly and found clear — but plan 01-03 defers final sign-off on message clarity to a human reader (see below) |
| `auth-service/.../config/JwtIssuerConfig.java` | RSA keypair at startup, `JwtEncoder`/`JwtDecoder` beans | ✓ VERIFIED | Read directly, matches spec |
| `auth-service/.../config/JwksController.java` | `GET /.well-known/jwks.json`, public key only | ✓ VERIFIED | Confirmed live, no private material |
| `auth-service/.../auth/AuthController.java` | `POST /auth/login`, `GET /auth/me` | ✓ VERIFIED | Confirmed via `AuthControllerIT` (13/13 pass) and live login |
| `auth-service/.../company/CompanyGuard.java` | Bean `companyGuard`, `isSelfOrSeller(UUID)` | ✓ VERIFIED | Read directly, matches spec, live-tested |
| `auth-service/src/test/.../AbstractIntegrationTest.java` | Testcontainers PostgreSQL, shared across `*IT` classes | ✓ VERIFIED | Read commit history/SUMMARY for the singleton-container fix (01-04); ran full suite (46/46) with no container-recycling failures, confirming the fix holds |
| `auth-service/src/test/.../{AuthControllerIT,CompanyControllerIT,JwksContractIT,SeedPasswordHashTest}.java` | Full behavioral proof suite | ✓ VERIFIED | Independently executed: `./mvnw -B -pl auth-service verify` → `Tests run: 46, Failures: 0, Errors: 0`, `BUILD SUCCESS` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `gateway/application.yml` route | `docker-compose.yml` | `uri: http://auth-service:8081` matches compose service name/port | ✓ WIRED | Confirmed live: login through port 8080 correctly reaches auth-service and returns a valid token |
| `docker-compose.yml` auth-service env | `auth-service/application.yml` | `SPRING_DATASOURCE_URL` env override | ✓ WIRED | auth-service healthy and serving requests against the compose Postgres instance (schema `auth`) |
| `CompanyController` | `CompanyGuard` | `@PreAuthorize("@companyGuard.isSelfOrSeller(#companyId)")` | ✓ WIRED | Live: BUYER reading own company → 200; BUYER reading other company → 403; SpEL bean resolution confirmed functional (no 500s) |
| `CompanyController` (write) | `SecurityConfig` | `@PreAuthorize("hasRole('SELLER_ADMIN')")` + `JwtAuthenticationConverter` role mapping | ✓ WIRED | Live: BUYER `PUT` on own company's credit-limit → 403 |
| `TokenService` | `CompanyService` | `company_id` claim sourced from `User.companyId` set at company creation | ✓ WIRED | Live: BUYER's JWT `company_id` claim equals the `id` returned by `POST /companies`, exact string match |
| `JwksContractIT` | `JwksController` | Decoder built exclusively from the published JWKS JSON | ✓ WIRED | Test independently confirmed passing; live JWKS fetch also confirmed separately |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full auth-service test suite | `./mvnw -B -pl auth-service verify` | `Tests run: 46, Failures: 0, Errors: 0` / `BUILD SUCCESS` | ✓ PASS |
| Live stack health | `docker compose ps --format '{{.Service}} {{.State}} {{.Health}}'` | 4/4 `running healthy` | ✓ PASS |
| Gateway login (SELLER_ADMIN) | `POST http://localhost:8080/api/auth/login` | 200, `role=SELLER_ADMIN`, no `company_id` claim | ✓ PASS |
| Company + BUYER creation via Gateway | `POST http://localhost:8080/api/companies` | 201, buyer created with `role=BUYER` | ✓ PASS |
| BUYER login + company_id claim | `POST http://localhost:8080/api/auth/login` | 200, `company_id` equals created company's `id` | ✓ PASS |
| BUYER read own credit-limit | `GET .../companies/{id}/credit-limit` (own) | 200, correct value | ✓ PASS |
| BUYER write own credit-limit (expect 403) | `PUT .../companies/{id}/credit-limit` (own) | 403 | ✓ PASS |
| BUYER read other company's credit-limit (expect 403) | `GET .../companies/{otherId}/credit-limit` | 403 | ✓ PASS |
| Seller read/write credit-limit | `GET/PUT .../companies/{id}/credit-limit` (seller) | 200 both | ✓ PASS |
| JWKS endpoint, no private material | `GET http://localhost:8081/.well-known/jwks.json` | 200, single RSA sig key, no `d/p/q/dp/dq/qi` | ✓ PASS |
| Monetary validation (3-decimal rejection) | `POST /companies` with `creditLimit:"100.005"` | 400 `validation_failed` | ✓ PASS |
| Monetary validation (negative rejection) | `POST /companies` with `creditLimit:"-1.00"` | 400 | ✓ PASS |
| Unauthenticated write rejected | `POST /companies` no `Authorization` header | 401 | ✓ PASS |

All spot-checks executed independently in this session against the live docker-compose stack and a freshly-run Maven test suite — none of these results were taken from SUMMARY.md claims.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| INFRA-01 | 01-01, 01-02, 01-03 | Sistema sobe com `docker-compose up` | ✓ SATISFIED | Live stack healthy; fixed image tags; `service_healthy` gating |
| AUTH-01 | 01-04 | Vendedor cria contas de empresas compradoras | ✓ SATISFIED | Live `POST /companies` creates company + BUYER transactionally |
| AUTH-02 | 01-02, 01-03, 01-04 | Login devolve JWT com papel e, se comprador, empresa | ✓ SATISFIED | Live tokens inspected for both roles |
| AUTH-03 | 01-02, 01-05 | Validação local do JWT, rejeitando inválido/expirado | ✓ SATISFIED | `JwksContractIT` + `CompanyControllerIT` adversarial token tests, all passing |
| COMP-01 | 01-04 | Empresa armazenada com nome e limite de crédito | ✓ SATISFIED | `NUMERIC(19,2)` schema, live creation confirmed |
| COMP-02 | 01-05 | Vendedor visualiza/atualiza limite de crédito | ✓ SATISFIED | Live GET/PUT by SELLER_ADMIN confirmed |
| COMP-03 | 01-05 | Compradores restritos aos dados da própria empresa | ✓ SATISFIED | Live 403 for cross-company access; byte-identical oracle test passing |

No orphaned requirements: REQUIREMENTS.md maps exactly {INFRA-01, AUTH-01, AUTH-02, AUTH-03, COMP-01, COMP-02, COMP-03} to Phase 1, and the union of `requirements:` across all 5 plan frontmatters is identical to this set.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `auth-service/.../company/dto/CompanyResponse.java` (via `Company.createdAt` + `@Generated`) | n/a | Live smoke test observed `createdAt: null` in the `POST /companies` 201 response body, despite `@Generated(GenerationTime.INSERT)` intending to reload it post-insert | ℹ️ Info | Not covered by any must-have truth or any plan's `<behavior>` assertions (no test in `CompanyControllerIT` asserts `createdAt` is non-null in the creation response) — cosmetic gap only, doesn't affect any of the 7 requirements or 4 ROADMAP success criteria. Worth a follow-up fix but non-blocking for Phase 1 |

No debt markers (`TBD`/`FIXME`/`XXX`), no `TODO`/`HACK`/`PLACEHOLDER`, no stub return patterns (`return null`/`return {}`/`return []`), no empty handlers found across `auth-service/src/main`, `gateway/src/main`, build files, or `docker-compose.yml`.

### Human Verification Required

### 1. README "Credenciais de demonstração" clarity

**Test:** Read only the "Credenciais de demonstração" section of `README.md` (lines 67-74), as if seeing the project for the first time.
**Expected:** It must be immediately clear that `admin@orderflow.local` / `ChangeMe!123` is an intentional demo user, not a security oversight or a real admin credential. If any doubt remains, the text needs to be rewritten (this is the plan's own acceptance bar, not one invented by this verifier).
**Why human:** Plan `01-03` (Task 2, an `auto`-type task) explicitly deferred this exact check to a `<human-check>` block inside `<verify>` rather than resolving it with an automated grep — it is a qualitative judgment about whether a specific message reads as clear to a first-time reader, which is the class of check GSD's own harvesting rule (Step 8) routes to end-of-phase human sign-off rather than letting the verifier self-certify. This verifier read the section directly and found it clear (quoted in Required Artifacts above), and per protocol the plan's own acceptance criterion required a human reader's confirmation, not the verifier's.

**Resolution (2026-09-19):** The developer read the exact README section quoted above and confirmed it reads unambiguously as an intentional demo credential, not a security oversight. No rewording requested. This closes the sole open item from this report.

### Gaps Summary

No gaps. All 4 ROADMAP Success Criteria and all 7 requirement IDs are independently confirmed against the live, running system and an independently-executed test suite (46/46 passing) — not merely against SUMMARY.md claims. The sole open item — a planner-deferred human-readability check (README demo-credential clarity) — was confirmed by the developer on 2026-09-19 (see Resolution above). One non-blocking Info-level cosmetic defect (`createdAt` returning `null` immediately after company creation) is noted for optional follow-up but does not affect any must-have.

---

*Verified: 2026-09-19T03:12:05Z*
*Verifier: Claude (gsd-verifier)*
