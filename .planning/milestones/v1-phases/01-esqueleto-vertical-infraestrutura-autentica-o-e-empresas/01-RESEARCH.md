# Phase 1: Esqueleto Vertical — Infraestrutura, Autenticação e Empresas - Research

**Researched:** 2026-09-16
**Domain:** Spring Boot microservices bootstrap — API Gateway routing, self-issued JWT auth (RSA/JWKS), multi-tenant company isolation, Postgres + LocalStack via docker-compose
**Confidence:** MEDIUM (no premium research providers enabled in this project — see Metadata)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01 (Postgres topology):** Uma única instância Postgres no `docker-compose`, com um schema separado por serviço transacional (auth, catalog, inventory, order) em vez de um container por serviço. Reversibility: costly.
- **D-02 (JWT keys):** O par de chaves RSA para assinar/validar o JWT é gerado pelo auth-service no startup, com a chave pública exposta via endpoint JWKS; os demais serviços buscam e cacheiam essa chave (Spring Security OAuth2 Resource Server). Reversibility: costly.
- **D-03 (locked interpretation of success criterion 3):** "token inválido ou expirado é rejeitado com 401 sem nenhuma chamada em tempo de execução ao auth-service" significa validação stateless por requisição (sem chamada síncrona por token validado); buscar/cachear a chave pública via JWKS no boot ou periodicamente está dentro do critério. Downstream agents devem usar esta leitura.
- **D-04 (tokens):** Apenas access token, sem refresh token. TTL curto-médio (referência: ~1h). Reversibility: reversible.
- **D-05 (Gateway):** O API Gateway apenas roteia (rotas estáticas por serviço) e não valida o JWT — cada serviço downstream valida localmente e de forma independente, conforme AUTH-03. Reversibility: reversible.
- **D-06 (dinheiro):** Valores monetários (limite de crédito na Fase 1; total do pedido nas Fases 4/5) usam `BigDecimal` com escala fixa de 2 casas decimais, mapeado para `NUMERIC(19,2)` no Postgres. Reversibility: one-way.
- **D-07 (bootstrap SELLER_ADMIN):** O primeiro usuário SELLER_ADMIN é criado via migração Flyway do auth-service, com email/senha fixos (hash BCrypt) inseridos na primeira subida do banco. Reversibility: reversible.

### Claude's Discretion

- Nome exato dos schemas Postgres por serviço (ex.: `auth`, `catalog`, `inventory`, `order`) — convenção a definir no planejamento.
- Formato exato do payload de criação de empresa + usuário BUYER vinculado (endpoint único vs dois passos) — decisão de design de API, não afeta arquitetura.
- Credenciais exatas do SELLER_ADMIN seedado via Flyway (email/senha) — definir no planejamento, documentar claramente por ser específico de portfólio/demo.

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| INFRA-01 | Todo o sistema (microsserviços + Postgres + LocalStack) sobe localmente com um único comando `docker-compose up` | §Architecture Patterns → docker-compose wiring; §Code Examples → healthchecks; §Environment Availability |
| AUTH-01 | Vendedor (seller admin) cria contas de empresas compradoras com credenciais de usuário | §Architecture Patterns → Company+User model; §Code Examples → Company/User creation endpoint |
| AUTH-02 | Usuário faz login com email/senha e recebe um JWT contendo papel (BUYER/SELLER_ADMIN) e, se comprador, o ID da empresa | §Architecture Patterns → JWT issuance pattern; §Code Examples → NimbusJwtEncoder + login flow |
| AUTH-03 | Cada serviço valida o JWT localmente (stateless), rejeitando tokens inválidos/expirados | §Architecture Patterns → JWKS validation pattern; §Common Pitfalls → JWKS/key rotation gotchas |
| COMP-01 | Empresa compradora é armazenada com nome e limite de crédito | §Code Examples → Flyway schema migration |
| COMP-02 | Vendedor pode visualizar/atualizar o limite de crédito de uma empresa compradora | §Code Examples → CompanyController; §Don't Hand-Roll → BigDecimal handling |
| COMP-03 | Usuários compradores são restritos aos dados da própria empresa (não veem dados de outras empresas) | §Architecture Patterns → tenant isolation pattern (CompanyGuard); §Validation Architecture → isolation test mapping |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

Extracted from `./.claude/CLAUDE.md` — treated with the same authority as locked CONTEXT.md decisions:

- **Language/runtime:** Java 21 (Temurin distribution), not 17, not 25.
- **Framework:** Spring Boot 3.5.x (final/most mature 3.x minor) — explicitly **not** Spring Boot 4.0.
- **Release train:** Spring Cloud 2025.0.x "Northfields" — must match the Spring Boot 3.5.x line; mixing 2023.0.x/2024.0.x with Boot 3.5 is called out as a common, hard-to-debug mistake.
- **Gateway:** Spring Cloud Gateway **Server WebMVC** (servlet + virtual threads), not the classic reactive/WebFlux Gateway — static routes only, no service discovery (fixed docker-compose service names/ports).
- **Auth:** Spring Security's built-in OAuth2 Resource Server JWT support (`NimbusJwtEncoder`/`JwtEncoder` for issuing, `spring-boot-starter-oauth2-resource-server` for validating) — explicitly **not** `jjwt` (only as a discretionary alternative, not the default).
- **Persistence:** PostgreSQL for transactional data; DynamoDB via LocalStack reserved for notification-service (Phase 3, not this phase).
- **LocalStack:** since 2026.03.0, requires `LOCALSTACK_AUTH_TOKEN` even on the free Hobby tier — must be designed into docker-compose and CI from day one; do not assume an anonymous image.
- **Forbidden:** `WebSecurityConfigurerAdapter` (removed in Spring Security 6/Boot 3.x) — use `SecurityFilterChain` `@Bean` + `HttpSecurity` DSL. Netflix Hystrix/Eureka/Zuul/Ribbon (maintenance mode/EOL) — not applicable to this phase but noted for consistency. Legacy standalone `docker-compose` v1 binary — use `docker compose` (V2 CLI plugin).
- **Build tool:** Maven, multi-module reactor (parent pom + one module per service) recommended over independent poms, to keep BOM versions (Spring Boot, Spring Cloud, Testcontainers, AWS SDK) DRY.
- **Process constraint (PROJECT.md):** nenhuma tecnologia implementada sem explicação prévia do motivo de seu uso — the plan must surface *why* before *what* for anything new introduced in this phase (RSA/JWKS, Hibernate multi-tenancy pattern, Testcontainers).
- **Language convention (PROJECT.md):** documentation/planning in Brazilian Portuguese; code, class/method/variable names, endpoints, filenames, DB objects, and git commits in English; GSD structural headers stay in English.

## Summary

Phase 1 is a walking skeleton: `docker-compose up` must bring up API Gateway + auth-service + PostgreSQL + LocalStack, and a real login must produce a working, locally-verifiable JWT. Nothing in this phase is technically novel by industry standards — every piece (Spring Cloud Gateway Server WebMVC static routes, Spring Security's `NimbusJwtEncoder`/JWKS pattern, Flyway seed migrations, Testcontainers-based integration tests) is a well-documented, mainstream Spring Boot 3.x pattern. The engineering risk in this phase is **not** algorithmic, it is **configuration and sequencing**: getting the Spring Cloud/Boot version pairing right, getting docker-compose healthchecks to actually gate startup order, getting the LocalStack auth-token requirement right from the first commit, and getting the "validate locally, no runtime call to auth-service" requirement (D-03) implemented correctly via JWKS caching rather than a synchronous introspection call.

The one piece that is genuinely a design decision rather than a lookup is **tenant isolation** (COMP-03): because Phase 1's data model is small (only `Company` and `User`), the recommended approach is an explicit, reusable ownership-check pattern (a `CompanyGuard` bean consulted via `@PreAuthorize`) rather than a global Hibernate `@Filter`, because the latter's "silently disabled filter" failure mode is a poor fit for a requirement that must be "comprovado por teste, não apenas por convenção." This same guard pattern is designed to be reused in Phase 4 when Orders become company-scoped.

**Primary recommendation:** Build one Maven multi-module reactor (parent pom + `gateway` + `auth-service` modules) pinned to Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / Java 21; wire docker-compose with `service_healthy` health-gated startup for Postgres and LocalStack; issue JWTs via a hand-rolled `NimbusJwtEncoder` + custom `/.well-known/jwks.json` controller in auth-service; validate JWTs via `spring-boot-starter-oauth2-resource-server`'s `jwk-set-uri` (which caches the public key and does not call auth-service per request); enforce company isolation via an explicit `@PreAuthorize`-backed guard bean, proven with a Testcontainers-backed MockMvc integration test that asserts a cross-company 403.

## Architectural Responsibility Map

This project has no browser/frontend tier in Phase 1 (pure REST backend). Tiers are adapted accordingly: **API Gateway**, **API/Backend (service)**, and **Database/Storage**.

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Request routing (path → service) | API Gateway | — | D-05 locks the Gateway to routing only; Spring Cloud Gateway Server WebMVC owns this exclusively |
| JWT issuance (login) | API/Backend (auth-service) | — | Only the issuer holds the RSA private key (D-02); must never live in the Gateway or another service |
| JWT validation | API/Backend (every resource-server-role service; only auth-service exists in Phase 1) | — | D-05 + AUTH-03: each service validates independently and locally, not the Gateway |
| RSA keypair generation & JWKS exposure | API/Backend (auth-service) | — | D-02: generated at auth-service startup, exposed via JWKS endpoint, never distributed as a static file |
| Company + credit-limit persistence | Database/Storage (Postgres, `auth` schema) | API/Backend (auth-service, validation/business rules) | COMP-01/02 are pure CRUD with a business rule (BigDecimal scale) that belongs in the service layer, not the DB |
| Tenant (company) isolation enforcement | API/Backend (auth-service authorization layer) | Database/Storage (query scoping) | COMP-03 must be provable by test; enforcing only at the DB layer (e.g., RLS) would hide the check from the JWT-driven authorization the rest of the system relies on — enforce in the service/controller layer where the JWT claim lives |
| Container orchestration / health-gated startup | Infra (docker-compose) | — | Not a code tier — a deployment-time concern; still must be designed correctly for INFRA-01 |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|---------------|
| Java | 21 LTS | Runtime for all services | Locked by CLAUDE.md; confirmed installed in this dev environment (`java -version` → `21.0.10`) [VERIFIED: local environment probe, `java -version`, this session] |
| Spring Boot | 3.5.16 (final 3.5.x release) | Application framework | Latest and last 3.5.x patch; Spring Boot 3.5 reached open-source EOL 2026-06-30 [CITED: spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now; herodevs.com blog "Spring Boot Versions, EOL Dates, and Latest Releases"] — see Open Questions, this does not block the phase but is worth flagging to the user since CLAUDE.md's stack research predates this EOL date |
| Spring Cloud | 2025.0.3 "Northfields" | Release train for Gateway | Latest patch in the release train matched to Boot 3.5.x per CLAUDE.md [CITED: spring.io/blog/2026/06/11/spring-cloud-2025-0-3-aka-northfields-has-been-released] |
| Spring Cloud Gateway Server WebMVC | ships with Spring Cloud 2025.0.3 | Static-route API Gateway | Servlet-based (not reactive), fits a project that otherwise uses Spring MVC + JPA everywhere else; per CLAUDE.md, avoids introducing Project Reactor for no domain benefit |
| Spring Security (`spring-boot-starter-security`, `spring-security-oauth2-resource-server`, `spring-security-oauth2-jose`) | ships with Boot 3.5.16 | JWT issuing (auth-service) + JWT validation (any resource-server-role service) | `spring-security-oauth2-jose` provides `NimbusJwtEncoder`/`JwtEncoder`; `spring-security-oauth2-resource-server` provides `jwk-set-uri`-based `NimbusJwtDecoder` — no separate JWT library needed, one dependency set does both roles [CITED: docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html] |
| PostgreSQL (Docker image) | `postgres:16.15` (or `postgres:16.15-alpine`) | Relational store, `auth` schema in Phase 1 | Latest 16.x patch as of this session [CITED: hub.docker.com/_/postgres/tags, fetched this session] — pin exact tag per INFRA-01's "no `latest`" success criterion |
| LocalStack (Docker image) | `localstack/localstack:2026.08.3` | AWS emulation (SQS + DynamoDB provisioned, unused by app logic until Phase 3/5) | Latest numbered calendar-versioned tag as of this session [CITED: hub.docker.com/r/localstack/localstack/tags, fetched this session] — **requires `LOCALSTACK_AUTH_TOKEN`**, see Common Pitfalls |
| Flyway (`flyway-core`, `flyway-database-postgresql`) | latest matching Boot 3.5.16 BOM (verify via `./mvnw dependency:tree` at implementation time) | Schema + seed migrations for `auth` schema | Standard Spring Boot migration tool; per D-07 also carries the SELLER_ADMIN bootstrap seed |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Testcontainers (`testcontainers-bom`, `postgresql` module) | 1.20.x+ (pin exact at implementation time) | Real Postgres in integration tests | Every auth-service integration test proving login, company CRUD, and tenant isolation (this phase's Validation Architecture) |
| springdoc-openapi (`springdoc-openapi-starter-webmvc-ui`) | 2.8.5 (Spring Boot 3.x-compatible line) | OpenAPI/Swagger UI for auth-service | Not a strict Phase 1 success criterion (QUAL-01 is formally Phase 7) but cheap to add now since the endpoints already exist — recommend including per PITFALLS.md's "don't defer docs to the end" guidance already noted in REQUIREMENTS.md |
| Lombok | 1.18.34+ | Boilerplate reduction | Company/User entities, DTOs |
| Jakarta Bean Validation (`jakarta.validation` — ships with `spring-boot-starter-validation`) | ships with Boot 3.5.16 | Input validation on Company/User/Login DTOs | `@NotBlank`, `@Email`, `@DecimalMin` on credit-limit DTOs — required for ASVS V5, see Security Domain |
| spring-boot-starter-actuator | ships with Boot 3.5.16 | `/actuator/health` for docker-compose healthchecks | Gateway and auth-service both need a health endpoint for INFRA-01's "todos respondem saudáveis" criterion |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Explicit `CompanyGuard` ownership check (`@PreAuthorize` + bean method) for tenant isolation | Hibernate `@FilterDef`/`@Filter` global row-level filter | `@Filter` centralizes the rule but its "must be explicitly enabled per session/request" failure mode is silent — a forgotten `session.enableFilter(...)` call quietly returns unfiltered data. For a requirement that must be "comprovado por teste, não apenas por convenção" (COMP-03), the explicit guard is safer and its absence fails loudly (compile-time missing annotation or an obvious 200-instead-of-403 test failure). Also: `@Filter` does not apply to native/JPQL bypass queries or inserts — the explicit guard covers writes too. Recommend the explicit guard as the phase default; revisit `@Filter` only if the number of company-scoped entities grows large enough (Phase 4+ orders) that per-repository checks become repetitive. |
| Manual `/.well-known/jwks.json` `@RestController` | Full Spring Authorization Server (`spring-boot-starter-oauth2-authorization-server`) | Spring Authorization Server is the "correct" production tool for issuing tokens but brings a much larger surface (client registration, consent screens, full OAuth2 grant types) that is overkill for a single first-party login endpoint. CLAUDE.md's own stack table already prescribes the lighter `NimbusJwtEncoder` + manual JWKS approach — this row documents why, it does not reopen the decision. |
| Single shared Postgres instance, per-service schema (D-01, locked) | One Postgres container per service | Already decided (D-01); documented here only so the planner does not accidentally research/plan around per-service containers. |

**Installation (Maven, `auth-service/pom.xml` excerpt — parent BOMs import scope):**
```xml
<properties>
    <java.version>21</java.version>
    <spring-boot.version>3.5.16</spring-boot.version>
    <spring-cloud.version>2025.0.3</spring-cloud.version>
</properties>
<!-- auth-service dependencies -->
<!-- spring-boot-starter-web -->
<!-- spring-boot-starter-security -->
<!-- spring-security-oauth2-resource-server -->
<!-- spring-security-oauth2-jose -->
<!-- spring-boot-starter-data-jpa -->
<!-- spring-boot-starter-validation -->
<!-- spring-boot-starter-actuator -->
<!-- flyway-core, flyway-database-postgresql -->
<!-- org.postgresql:postgresql (runtime) -->
<!-- org.projectlombok:lombok -->
<!-- springdoc-openapi-starter-webmvc-ui:2.8.5 -->
<!-- test: spring-boot-starter-test, org.testcontainers:junit-jupiter, org.testcontainers:postgresql -->
```

**Version verification:** `./mvnw dependency:tree` was not run this session because no system Maven is installed in this dev environment (see Environment Availability) and the project has no `pom.xml`/`mvnw` wrapper yet (greenfield). **The plan must include a first task that runs `mvn archetype` or hand-writes the parent pom and immediately confirms `mvn -pl auth-service dependency:tree` (via the committed `./mvnw` wrapper) resolves cleanly against Maven Central before writing any application code** — this is the actual "version verification" step for this ecosystem, deferred to execution time because the tool did not exist yet at research time.

## Package Legitimacy Audit

The `gsd-tools package-legitimacy check` seam only supports `npm`/`pypi`/`crates` ecosystems; it returned a usage error for `--ecosystem maven` when tested this session. **No automated legitimacy check is available for Maven Central artifacts in this environment.** Manual audit performed instead: every artifact in the Standard Stack table is a `groupId` under `org.springframework.*`, `org.flywaydb`, `org.testcontainers`, `org.projectlombok`, or `org.springdoc` — all long-established (5+ years), high-download, officially-maintained projects with public source repos (github.com/spring-projects/*, github.com/flyway/flyway, github.com/testcontainers/testcontainers-java, github.com/projectlombok/lombok, github.com/springdoc/springdoc-openapi). No newly-published or single-maintainer packages are introduced by this phase.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| org.springframework.boot:* | Maven Central | 10+ yrs | Very high (core ecosystem) | github.com/spring-projects/spring-boot | OK (manual) | Approved |
| org.springframework.cloud:* | Maven Central | 10+ yrs | Very high | github.com/spring-cloud/spring-cloud-release | OK (manual) | Approved |
| org.springframework.security:* | Maven Central | 15+ yrs | Very high | github.com/spring-projects/spring-security | OK (manual) | Approved |
| org.flywaydb:flyway-core | Maven Central | 15+ yrs | Very high | github.com/flyway/flyway | OK (manual) | Approved |
| org.testcontainers:* | Maven Central | 8+ yrs | Very high | github.com/testcontainers/testcontainers-java | OK (manual) | Approved |
| org.projectlombok:lombok | Maven Central | 15+ yrs | Very high | github.com/projectlombok/lombok | OK (manual) | Approved |
| org.springdoc:springdoc-openapi-starter-webmvc-ui | Maven Central | 5+ yrs | High | github.com/springdoc/springdoc-openapi | OK (manual) | Approved |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none.

*All exact patch versions in the Standard Stack table were discovered via WebSearch/WebFetch of vendor blogs and Docker Hub tag pages, not via an authoritative package-registry query tool (Maven Central's own search API returned stale/contradictory data when queried this session — see Assumptions Log A1). Per the package-name provenance rule, treat every specific version number in this document as `[ASSUMED]`-adjacent (tagged `[CITED]` because the source was an official vendor blog/registry page, but not independently cross-checked against `mvn dependency:tree`) until the planner's first execution task confirms it resolves against Maven Central.*

## Architecture Patterns

### System Architecture Diagram

```
[Client / curl / Postman / future frontend]
        |
        v
[API Gateway :8080]  (Spring Cloud Gateway Server WebMVC — static routes only, D-05: no JWT validation here)
        |
        |  routes by path prefix, e.g. /api/auth/** , /api/companies/**  -> http://auth-service:8081/**
        v
[auth-service :8081]
        |
        |-- POST /auth/login {email, password}
        |        |
        |        v
        |   [AuthenticationManager] -> UserDetailsService (loads User by email) -> BCryptPasswordEncoder.matches()
        |        |
        |        v (credentials valid)
        |   [NimbusJwtEncoder] signs JWT (RS256, private key generated at boot per D-02)
        |        claims: sub=userId, role=BUYER|SELLER_ADMIN, company_id=<uuid> (BUYER only), iat, exp (~1h, D-04), iss
        |        |
        |        v
        |   200 { "access_token": "<jwt>" }
        |
        |-- GET /.well-known/jwks.json  (permitAll — no chicken-and-egg auth requirement)
        |        |
        |        v
        |   [RSA public key as JWK] <---- fetched + cached by every resource-server-role service's
        |                                  jwk-set-uri config (including auth-service itself, D-03: no
        |                                  synchronous call per validated token, only on cache miss)
        |
        |-- POST /companies {name, creditLimit, buyerUser:{email,password}}   [requires role=SELLER_ADMIN]
        |        |
        |        v
        |   [CompanyController] -> [CompanyService] -> [CompanyRepository] + [UserRepository]
        |        |
        |        v
        |   PostgreSQL, schema "auth", tables companies / users
        |
        |-- GET/PUT /companies/{companyId}/credit-limit   [requires role=SELLER_ADMIN, OR role=BUYER AND jwt.company_id == companyId]
        |        |
        |        v
        |   [CompanyGuard.isSelfOrSeller(companyId)] (@PreAuthorize) -- mismatch --> 403 Forbidden
        |        |
        |        v (authorized)
        |   [CompanyController] -> [CompanyService] -> Postgres
        v
[PostgreSQL :5432]  (single instance, D-01: one database, schema "auth" this phase)
        ^
        |
[Flyway] -- V1__init_auth_schema.sql (companies, users tables)
        -- V2__seed_seller_admin.sql (D-07: fixed email + pre-computed BCrypt hash, INSERT)

[LocalStack :4566]  (SERVICES=sqs,dynamodb — provisioned so `docker-compose up` matches the final
                      target topology per INFRA-01; NOT consumed by any Phase 1 application code)
```

### Recommended Project Structure

```
orderflow/
├── pom.xml                         # parent reactor pom: BOM imports (Spring Boot, Spring Cloud, Testcontainers)
├── mvnw, mvnw.cmd, .mvn/           # Maven Wrapper — committed so no system Maven is required (see Environment Availability)
├── docker-compose.yml
├── .env.example                    # LOCALSTACK_AUTH_TOKEN=, POSTGRES_PASSWORD= placeholders (.env itself gitignored)
├── gateway/
│   ├── pom.xml
│   └── src/main/
│       ├── java/.../GatewayApplication.java
│       └── resources/application.yml       # static routes, no security config
└── auth-service/
    ├── pom.xml
    └── src/main/
        ├── java/com/orderflow/auth/
        │   ├── AuthServiceApplication.java
        │   ├── config/
        │   │   ├── SecurityConfig.java      # SecurityFilterChain, permitAll() for /auth/login + /.well-known/jwks.json
        │   │   ├── JwtIssuerConfig.java      # RSA KeyPair generation, JwtEncoder bean
        │   │   └── JwksController.java       # GET /.well-known/jwks.json
        │   ├── auth/
        │   │   ├── AuthController.java       # POST /auth/login
        │   │   └── dto/LoginRequest.java, LoginResponse.java
        │   ├── company/
        │   │   ├── Company.java, CompanyRepository.java
        │   │   ├── CompanyController.java, CompanyService.java
        │   │   ├── CompanyGuard.java         # tenant-isolation @PreAuthorize bean (reused Phase 4+)
        │   │   └── dto/CreateCompanyRequest.java, CreditLimitResponse.java, UpdateCreditLimitRequest.java
        │   └── user/
        │       ├── User.java, UserRepository.java, Role.java (enum BUYER, SELLER_ADMIN)
        │       └── CustomUserDetailsService.java
        ├── resources/
        │   ├── application.yml
        │   └── db/migration/
        │       ├── V1__init_auth_schema.sql
        │       └── V2__seed_seller_admin.sql
        └── test/java/com/orderflow/auth/
            ├── AbstractIntegrationTest.java   # @Testcontainers base class, @ServiceConnection PostgreSQLContainer
            ├── AuthControllerIT.java          # login + invalid/expired token
            └── CompanyControllerIT.java       # SELLER_ADMIN CRUD + cross-company 403
```

### Pattern 1: Self-issued JWT via NimbusJwtEncoder + manual JWKS endpoint

**What:** auth-service generates an RSA `KeyPair` once at startup, wraps it in an `RSAKey`/`JWKSet`, exposes a `JwtEncoder` bean for signing and a plain controller for the public JWKS.
**When to use:** Any first-party login endpoint that needs to issue and self-validate JWTs without a full external IdP (matches D-02).
**Example:**
```java
// Source: pattern synthesized from docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
// and Baeldung "JWS + JWK in a Spring Security OAuth2 Application" — [CITED], not copied verbatim from a single doc
@Configuration
public class JwtIssuerConfig {

    private final RSAKey rsaKey; // generated once, held for app lifetime

    public JwtIssuerConfig() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        this.rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public RSAKey rsaKey() {
        return rsaKey;
    }
}

@RestController
public class JwksController {
    private final RSAKey rsaKey;
    public JwksController(RSAKey rsaKey) { this.rsaKey = rsaKey; }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}
```
**Issuing a token:**
```java
// Source: pattern synthesized from Spring Security JwtEncoder reference docs — [CITED]
JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("orderflow-auth-service")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)) // D-04: ~1h TTL, access-token only
        .subject(user.getId().toString())
        .claim("role", user.getRole().name())               // "BUYER" or "SELLER_ADMIN"
        .claim("company_id", user.getCompanyId())            // null/omitted for SELLER_ADMIN
        .build();
String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
```

### Pattern 2: Local JWT validation via jwk-set-uri (satisfies D-03)

**What:** Every resource-server-role service (in this phase, only auth-service itself protecting its own `/companies/**` endpoints) configures `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`. Spring Security's `NimbusJwtDecoder` fetches the JWKS once and caches it; subsequent token validations are pure local signature/claims checks — **no network call to auth-service per request**, matching the D-03 locked interpretation.
**When to use:** Any service, including auth-service itself, that must reject invalid/expired JWTs without a synchronous introspection call.
**Example:**
```yaml
# Source: docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html — [CITED]
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://localhost:8081/.well-known/jwks.json
```
```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/auth/login", "/.well-known/jwks.json", "/actuator/health").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .csrf(AbstractHttpConfigurer::disable) // stateless REST API, no cookies/CSRF token to protect
        .build();
}
```

### Pattern 3: Tenant isolation guard (COMP-03), reusable in later phases

**What:** A small `@Component` bean whose method is called from `@PreAuthorize` SpEL, comparing the JWT's `company_id` claim against a path/method argument. Fails loudly (403, asserted by test) instead of silently (a forgotten global filter).
**When to use:** Any endpoint where a BUYER must be restricted to their own company's data — Phase 1 (credit limit), and again in Phase 4 (orders, ORD-08).
**Example:**
```java
// Design pattern — no single official doc example matches this exactly; synthesized to fit
// the project's specific JWT claim shape. [ASSUMED — verify SpEL/bean wiring compiles as planned]
@Component("companyGuard")
public class CompanyGuard {
    public boolean isSelfOrSeller(UUID companyId) {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String role = jwt.getClaimAsString("role");
        if ("SELLER_ADMIN".equals(role)) return true;
        String tokenCompanyId = jwt.getClaimAsString("company_id");
        return tokenCompanyId != null && tokenCompanyId.equals(companyId.toString());
    }
}

@PreAuthorize("@companyGuard.isSelfOrSeller(#companyId)")
@GetMapping("/companies/{companyId}/credit-limit")
public CreditLimitResponse getCreditLimit(@PathVariable UUID companyId) { ... }
```

### Anti-Patterns to Avoid

- **Validating JWT at the Gateway *and* re-validating downstream with divergent logic:** D-05 locks the Gateway to pure routing. Do not add a Gateway-level JWT filter in this phase "for defense in depth" — it duplicates trust logic and risks the two validators disagreeing on TTL/algorithm. Revisit only as an explicit additive change later (D-05's own reversibility note).
- **Relying on `@Filter`/global tenant filter as the *only* isolation mechanism:** silently disabled filters return unfiltered data with a 200, not a 403 — the opposite of "comprovado por teste." Use the explicit guard (Pattern 3) as the enforced mechanism; a DB-level filter, if added later, is a defense-in-depth extra, not a replacement.
- **Hardcoding the RSA key or JWKS as a static file checked into the repo:** contradicts D-02 explicitly (generated at boot). A static file might feel "simpler" but is the wrong tradeoff the user already rejected.
- **Storing money as `int`/`long` cents anywhere in Phase 1 for "temporary simplicity":** D-06 locks `BigDecimal`/`NUMERIC(19,2)` from day one because switching later is one-way (would require a schema migration touching every monetary column across services, including ones that don't exist yet).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|--------------|-----|
| JWT signing/verification | A custom HMAC/base64 JWT implementation | `spring-security-oauth2-jose` (`NimbusJwtEncoder`) + `spring-boot-starter-oauth2-resource-server` | Battle-tested against algorithm-confusion attacks (e.g., "alg: none"), key rotation, and clock-skew edge cases that a hand-rolled implementation reliably gets wrong |
| Password hashing | A custom hash+salt scheme | Spring Security's `BCryptPasswordEncoder` (default strength) | BCrypt is adaptive and battle-tested; a hand-rolled scheme is a top OWASP ASVS V6 finding |
| Health-gated container startup ordering | A custom wait-for-it shell script polling a port | docker-compose `healthcheck:` + `depends_on: condition: service_healthy` | Native Compose feature since the `service_healthy` condition was introduced; a hand-rolled wait script is one more thing to maintain and gets subtly wrong (TCP port open ≠ service ready) |
| Multi-tenant query scoping infrastructure | A custom AOP/interceptor framework for tenant context propagation | Explicit method-level checks (Pattern 3) for Phase 1's small entity set; reconsider Hibernate's built-in `@Filter` (not a hand-rolled equivalent) only if the number of company-scoped entities grows | Phase 1 has exactly one tenant-scoped resource (Company/credit-limit); building a generic tenant-propagation framework now is premature abstraction for a portfolio project whose goal is demonstrating clarity, not infrastructure depth |

**Key insight:** every "don't hand-roll" item above already has a first-class Spring Security/Spring Boot/Docker mechanism; the actual work for this phase is *wiring*, not *building*.

## Common Pitfalls

### Pitfall 1: LocalStack container fails to start silently (or fails the whole `docker-compose up`) because `LOCALSTACK_AUTH_TOKEN` is unset
**What goes wrong:** Since LocalStack 2026.03.0, the unified image refuses to start without a valid auth token, even the free Hobby tier. A docker-compose file written without this fails for every future clone of the repo, not just the original author's machine — directly threatens INFRA-01's "a partir de um clone limpo" success criterion.
**Why it happens:** Older tutorials/StackOverflow answers (pre-2026.03) show docker-compose files with no token — training data and search results skew toward this stale pattern.
**How to avoid:** Use `environment: [LOCALSTACK_AUTH_TOKEN=${LOCALSTACK_AUTH_TOKEN:?}]` (the `:?` fails fast with a clear message if unset) [CITED: docs.localstack.cloud/aws/getting-started/auth-token, fetched this session] and document in the README that a free account at app.localstack.cloud + `.env` file (gitignored, `.env.example` committed) is a one-time setup step before first `docker-compose up`.
**Warning signs:** `docker-compose up` exits immediately for the `localstack` service with no clear application-level error, or the container restarts in a loop.

### Pitfall 2: Spring Cloud / Spring Boot version mismatch
**What goes wrong:** Spring Cloud releases are matched to specific Spring Boot minor lines; pinning e.g. Spring Cloud 2024.0.x against Spring Boot 3.5.x (or vice versa) produces confusing `NoClassDefFoundError`/bean-creation failures at startup that don't obviously point to a version mismatch.
**Why it happens:** The two projects release on independent cadences; a "latest" search for either in isolation can surface an incompatible pair.
**How to avoid:** Pin Spring Cloud 2025.0.3 with Spring Boot 3.5.16 explicitly (both already verified this session against official release announcements) and re-verify the pairing on spring.io before bumping either dependency later.
**Warning signs:** Gateway module fails to start with bean definition errors mentioning classes that "should" exist.

### Pitfall 3: docker-compose starts services before their dependencies are actually ready
**What goes wrong:** Compose's default `depends_on` (without a `condition`) only waits for the dependency container to be *running*, not *ready* — auth-service can start and fail its first Flyway migration attempt because Postgres is still initializing.
**Why it happens:** This is Docker's own documented behavior, easy to miss when writing a first docker-compose file.
**How to avoid:** Give Postgres a `pg_isready`-based healthcheck and use `depends_on: { postgres: { condition: service_healthy } }` on auth-service; do the same for the Gateway depending on auth-service (via `/actuator/health`) [CITED: general Docker Compose healthcheck pattern, cross-referenced across multiple 2026 blog posts this session].
**Warning signs:** Intermittent Flyway/connection-refused errors on `docker-compose up` that disappear on a `docker-compose restart`.

### Pitfall 4: Ephemeral RSA keypair invalidates tokens (and JWKS caches) across auth-service restarts
**What goes wrong:** D-02 generates a fresh RSA keypair on every auth-service startup. If auth-service restarts (crash, redeploy, or a test harness that restarts the Spring context mid-suite), every previously issued JWT becomes unverifiable, and any service that already cached the *old* JWKS will reject even freshly issued tokens until its cache expires/refreshes.
**Why it happens:** This is the direct, expected consequence of the D-02 decision (accepted tradeoff for a portfolio project — no persistent key store needed), not a bug — but it must be understood, not discovered by surprise.
**How to avoid:** Document this as expected behavior; ensure integration tests run against a single Spring application context per test class (Spring's test context caching does this by default) so the keypair does not rotate mid-test-run. Do not design any test that restarts the Spring context and expects a previously-issued token to remain valid.
**Warning signs:** Tokens that worked before a restart suddenly return 401 with no code change.

### Pitfall 5: Flyway checksum validation breaks when a seed migration is edited after being applied
**What goes wrong:** Once `V2__seed_seller_admin.sql` has been applied to any database (including a developer's local one), editing that file changes its checksum; the next `docker-compose up`/`mvn` run fails Flyway's checksum validation.
**Why it happens:** Flyway is intentionally strict about this — it is the mechanism that prevents silent schema drift.
**How to avoid:** Once V2 is applied anywhere, create `V3__...sql` for any change to the seed data rather than editing V2; document the fixed SELLER_ADMIN credentials clearly (per Claude's Discretion note in CONTEXT.md) so nobody is tempted to "just tweak" the seed file.
**Warning signs:** `FlywayValidateException: Migration checksum mismatch` on any subsequent run.

### Pitfall 6: Native/JPQL queries silently bypass any tenant-scoping mechanism
**What goes wrong:** If a Hibernate `@Filter`-style approach were used instead of the recommended explicit guard (Pattern 3), any native SQL query, JPQL query, or batch update on the `companies`/`users` tables bypasses the filter entirely and returns cross-tenant data.
**Why it happens:** Hibernate filters only apply to entity-graph loads through the session, not to arbitrary queries [CITED: WebSearch synthesis of multiple Hibernate multi-tenancy articles, cross-referenced this session].
**How to avoid:** This is precisely why Pattern 3 (explicit `@PreAuthorize` guard at the controller boundary, checked against the specific resource being accessed) is recommended over a global filter for this phase's small entity set.
**Warning signs:** A tenant-isolation test passes for the "obvious" REST endpoint but a newly added query method (e.g., a custom `@Query` for a report) leaks cross-company data.

## Code Examples

### docker-compose.yml (excerpt — Postgres + LocalStack healthchecks)
```yaml
# Source: pattern synthesized from Docker Compose healthcheck docs + LocalStack official
# docker-compose sample (docs.localstack.cloud/aws/getting-started/installation) — [CITED]
services:
  postgres:
    image: postgres:16.15
    environment:
      POSTGRES_DB: orderflow
      POSTGRES_USER: orderflow
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?}
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U orderflow"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s

  localstack:
    image: localstack/localstack:2026.08.3
    environment:
      - LOCALSTACK_AUTH_TOKEN=${LOCALSTACK_AUTH_TOKEN:?}
      - SERVICES=sqs,dynamodb
      - PERSISTENCE=0
    ports: ["4566:4566"]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4566/_localstack/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 15s

  auth-service:
    build: ./auth-service
    depends_on:
      postgres: { condition: service_healthy }
      localstack: { condition: service_healthy }
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderflow?currentSchema=auth
    ports: ["8081:8081"]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s

  gateway:
    build: ./gateway
    depends_on:
      auth-service: { condition: service_healthy }
    ports: ["8080:8080"]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 15s
```

### Flyway migration — schema + BigDecimal column (D-06)
```sql
-- Source: pattern synthesized from Flyway docs conventions — [CITED]
-- V1__init_auth_schema.sql
CREATE TABLE companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    credit_limit NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('BUYER', 'SELLER_ADMIN')),
    company_id UUID REFERENCES companies(id), -- NULL for SELLER_ADMIN
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
```sql
-- V2__seed_seller_admin.sql (D-07 — password hash pre-computed offline via BCryptPasswordEncoder,
-- never generated inside SQL; exact credentials to be finalized during planning, see CONTEXT.md
-- "Claude's Discretion")
INSERT INTO users (email, password_hash, role, company_id)
VALUES ('admin@orderflow.local', '$2a$10$<precomputed-bcrypt-hash>', 'SELLER_ADMIN', NULL);
```

### Testcontainers integration test skeleton (proving AUTH-02/03 + COMP-03)
```java
// Source: pattern synthesized from testcontainers.com Spring Boot guide + Spring Boot
// @ServiceConnection docs — [CITED]
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CompanyControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @Autowired MockMvc mockMvc;

    @Test
    void buyerCannotReadAnotherCompanysCreditLimit() throws Exception {
        // arrange: two companies, a BUYER JWT scoped to company A
        String buyerTokenForCompanyA = issueTestToken("BUYER", companyA.getId());

        mockMvc.perform(get("/companies/{id}/credit-limit", companyB.getId())
                .header("Authorization", "Bearer " + buyerTokenForCompanyA))
            .andExpect(status().isForbidden());
    }

    @Test
    void expiredTokenIsRejectedWith401() throws Exception {
        String expiredToken = issueTestToken("BUYER", companyA.getId(), Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/companies/{id}/credit-limit", companyA.getId())
                .header("Authorization", "Bearer " + expiredToken))
            .andExpect(status().isUnauthorized());
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|-------------------|---------------|--------|
| `WebSecurityConfigurerAdapter` for security config | `SecurityFilterChain` `@Bean` + `HttpSecurity` DSL | Removed in Spring Security 6.0 (ships with Boot 3.x) | Any tutorial code using the adapter will not compile against this project's stack |
| Reactive Spring Cloud Gateway (WebFlux) as the only Gateway option | Spring Cloud Gateway **Server WebMVC** as a first-class alternative | Introduced as of Spring Cloud 2025.0.0 | Enables a servlet-based Gateway without pulling Project Reactor into an otherwise non-reactive codebase — CLAUDE.md's chosen path |
| LocalStack anonymous/no-signup community image | `LOCALSTACK_AUTH_TOKEN` required even for free Hobby tier | LocalStack 2026.03.0 (March 2026) | Every docker-compose file and CI config must plan for a registered account + secret from day one; this is new enough that most existing tutorials/StackOverflow answers are already stale |

**Deprecated/outdated:**
- `WebSecurityConfigurerAdapter`: removed, not just deprecated, in Spring Security 6 — code using it will not compile.
- Anonymous LocalStack usage: no longer possible as of 2026.03.0; treat any pre-2026 LocalStack docker-compose example found during implementation as needing the auth-token addition.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | Spring Boot 3.5.16 and Spring Cloud 2025.0.3 are the correct current pinned versions | Standard Stack | Maven Central's own search API (`search.maven.org/solrsearch`) returned conflicting, older data (`3.5.3`, `2025.0.0`) when queried this session via direct HTTP call — the API appears to be serving a stale/cached index in this environment. WebSearch of spring.io's own blog posts is the stronger signal, but the discrepancy was not resolved by cross-checking `mvn dependency:tree` (no Maven installed locally, no pom.xml exists yet). If wrong: `pom.xml` build fails or silently picks up a stale minor patch missing bugfixes. **Action for planner:** first execution task must run `./mvnw dependency:tree` (via a committed wrapper) against the real Maven Central and adjust pinned versions if resolution fails. |
| A2 | `postgres:16.15` and `localstack/localstack:2026.08.3` are the latest stable image tags | Standard Stack, Code Examples | Both were fetched directly from Docker Hub tag listing pages this session (WebFetch), but LocalStack shipped a new patch (`2026.08.2` → `2026.08.3`) only 8 days apart from the prior one in the same listing — by the time this phase is executed, a newer patch will likely exist. If wrong: not a functional risk (any recent patch works), only a "not literally the newest" cosmetic gap against INFRA-01's "versão fixada" intent — re-check the tag immediately before writing the final docker-compose.yml. |
| A3 | `springdoc-openapi-starter-webmvc-ui:2.8.5` is compatible with Spring Boot 3.5.16 | Standard Stack | Based on WebSearch synthesis only, not cross-checked against springdoc.org's own compatibility matrix page directly. If wrong: Swagger UI fails to load or throws a bean-conflict error at startup — low risk since this artifact is optional for Phase 1 (QUAL-01 is formally a Phase 7 requirement). |
| A4 | `NimbusJwtDecoder`'s default `jwk-set-uri`-based key source caches the JWKS and does not make a synchronous HTTP call to auth-service on every request | Architecture Patterns (Pattern 2), directly supports D-03 | This is the load-bearing claim for the entire "no runtime call to auth-service" success criterion. It is standard, well-known Spring Security behavior, but was not verified this session by reading Spring Security's actual `RemoteJWKSet`/cache source code or its reference-doc section describing cache TTL — only by WebSearch synthesis. If wrong (e.g., default behavior refetches per request under some configuration): the phase's stated success criterion 3 could fail even though D-03 was interpreted correctly — **strongly recommend the planner add an explicit verification task** (e.g., a test that stops/blocks the auth-service container after first token issuance and confirms subsequent validations still succeed from cache) rather than trusting this assumption alone. |
| A5 | Hibernate `@Filter`/global-filter multi-tenancy approaches do not apply to native/JPQL queries or inserts | Common Pitfalls (Pitfall 6), Alternatives Considered | Synthesized from multiple WebSearch results (Citus Data blog, Medium articles), not from Hibernate ORM's own official reference documentation. This claim is used only to justify *not* choosing `@Filter` as the primary mechanism (Pattern 3 is recommended instead), so the risk if wrong is low — it would only mean `@Filter` was a slightly less bad discretionary alternative than described, not that the recommended approach (explicit guard) is wrong. |
| A6 | Pre-computing a BCrypt hash "offline" (outside the SQL migration) is the standard way to seed a bootstrap admin password via Flyway | Code Examples | General knowledge pattern, not tied to an official Spring/Flyway doc reference confirming this exact recipe. Low risk: this is a very standard practice (SQL cannot compute BCrypt itself), but the exact hash value and generation mechanism (a throwaway `main()` method, an online tool, or a unit test) is left to planning per CONTEXT.md's "Claude's Discretion" note. |
| A7 | Spring Boot 3.5 reached open-source end-of-life on 2026-06-30 | Standard Stack, Open Questions | Cited from a HeroDevs EOL-tracking blog post and Spring's own release-notes wiki via WebSearch, not from Spring's official support-policy page directly. If wrong, the urgency of the Open Question below (flagging this to the user) is lower, but it does not change the recommended pinned version (3.5.16 is still the correct final patch to use either way, per CLAUDE.md's explicit choice of the 3.5 line over 4.0). |

**If this table is empty:** N/A — see entries above; all version-specific and cache-behavior claims in this research carry residual risk that the planner should account for with a verification task rather than treating as settled fact.

## Open Questions

1. **Spring Boot 3.5 has reached (or is very close to) open-source end-of-life — does this change CLAUDE.md's stack choice?**
   - What we know: CLAUDE.md explicitly chose Spring Boot 3.5.x over 4.0.x to avoid Jackson 3/Jakarta EE 11 migration churn, reasoning that 3.5 is "the current mainstream enterprise baseline." WebSearch this session found Spring Boot 3.5's OSS support window ending 2026-06-30, with 3.5.16 as the final OSS patch — and today's date is 2026-09-16, i.e., after that window.
   - What's unclear: whether this materially matters for a portfolio project that will not run in production long-term, versus whether it undermines the "demonstrates current industry practice" framing CLAUDE.md itself uses as a justification.
   - Recommendation: surface this to the user as a confirmation checkpoint before locking the plan's pinned version — the likely answer is "proceed with 3.5.16 anyway, note it in an ADR as an accepted tradeoff" (consistent with CLAUDE.md's own stated preference for stability over bleeding-edge), but this is a decision for the user/planner to make explicitly, not something research should silently override.

2. **Exact SELLER_ADMIN bootstrap credentials (D-07) and exact company/user creation payload shape (single endpoint vs. two-step)**
   - What we know: CONTEXT.md explicitly defers both to "Claude's Discretion" during planning.
   - What's unclear: nothing blocking — these are genuinely open design choices, not information gaps.
   - Recommendation: the planner should decide and document these directly (e.g., a single `POST /companies` endpoint accepting both company and initial BUYER user in one payload, matching the Success Criterion 2 wording "cria... uma empresa... e um usuário BUYER vinculado a ela" which reads as one conceptual operation).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|--------------|-----------|---------|----------|
| Docker Engine | `docker-compose up` (INFRA-01) | ✓ | 29.8.0 | — |
| Docker Compose (V2 CLI plugin) | Orchestration | ✓ | v5.5.1 | — |
| Java (JDK) | Build/run all services | ✓ | 21.0.10 (LTS; Temurin distribution not independently confirmed from `java -version` output alone) | — |
| System Maven (`mvn`) | Build tool | ✗ (`mvn: command not found`) | — | Commit a Maven Wrapper (`./mvnw`) to the repo — no system Maven install required for any contributor or CI runner |
| LocalStack account + `LOCALSTACK_AUTH_TOKEN` | LocalStack container startup (INFRA-01) | Not verifiable from this environment (requires manual signup at app.localstack.cloud) | — | **No fallback** — the LocalStack service will not start at all without this token as of image 2026.08.3; this blocks the "todos respondem saudáveis" success criterion for the LocalStack container specifically. Must be a documented one-time manual setup step (README + `.env.example`) before first `docker-compose up`. |

**Missing dependencies with no fallback:**
- `LOCALSTACK_AUTH_TOKEN` — requires a one-time human action (free account registration) outside this agent's/tool's reach; the plan should include an explicit `checkpoint:human-verify`-style step for "register LocalStack Hobby account and populate `.env`" before the first `docker-compose up` attempt.

**Missing dependencies with fallback:**
- System Maven — resolved via Maven Wrapper, no user action needed beyond committing the wrapper files.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + `spring-boot-starter-test` (Mockito, AssertJ, MockMvc) + Testcontainers `postgresql` module |
| Config file | none yet — greenfield; created in Wave 0 of this phase's plan (`pom.xml` test dependencies + `application-test.yml`) |
| Quick run command | `./mvnw -pl auth-service test` (unit tests only, no Docker required) |
| Full suite command | `./mvnw -pl auth-service verify` (runs Testcontainers-backed integration tests via `*IT.java` naming + failsafe plugin, requires Docker running) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|--------------------|-------------|
| INFRA-01 | `docker-compose up` brings up all 4 services healthy | smoke (manual/CI, not a JUnit test) | `docker compose up -d && docker compose ps` (all healthy) | ❌ Wave 0 — not a code test; document as a manual/CI smoke step, full automation formally lands Phase 7 (INFRA-02) per REQUIREMENTS.md traceability note |
| AUTH-01 | SELLER_ADMIN creates a company + BUYER user via REST | integration | `./mvnw -pl auth-service test -Dtest=CompanyControllerIT#sellerAdminCreatesCompanyWithBuyer` | ❌ Wave 0 |
| AUTH-02 | Login returns a JWT with role + company_id claims | integration | `./mvnw -pl auth-service test -Dtest=AuthControllerIT#loginReturnsSignedJwtWithClaims` | ❌ Wave 0 |
| AUTH-03 | Invalid/expired token rejected with 401, no runtime call to auth-service | integration | `./mvnw -pl auth-service test -Dtest=AuthControllerIT#expiredTokenIsRejectedWith401` | ❌ Wave 0 |
| COMP-01 | Company stored with name + credit limit | integration (implicit in AUTH-01 test) | covered by `CompanyControllerIT#sellerAdminCreatesCompanyWithBuyer` | ❌ Wave 0 |
| COMP-02 | SELLER_ADMIN views/updates credit limit | integration | `./mvnw -pl auth-service test -Dtest=CompanyControllerIT#sellerAdminUpdatesCreditLimit` | ❌ Wave 0 |
| COMP-03 | BUYER cannot read another company's data | integration | `./mvnw -pl auth-service test -Dtest=CompanyControllerIT#buyerCannotReadAnotherCompanysCreditLimit` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./mvnw -pl auth-service test`
- **Per wave merge:** `./mvnw -pl auth-service verify`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `auth-service/pom.xml` test dependencies — `spring-boot-starter-test`, `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`
- [ ] `auth-service/src/test/java/.../AbstractIntegrationTest.java` — shared `@Testcontainers`/`@ServiceConnection` base class
- [ ] `auth-service/src/test/resources/application-test.yml` — test-profile config (e.g., disabling any prod-only bean)
- [ ] Maven Wrapper (`./mvnw`) at repo root — needed before any `mvn`/`./mvnw` command in this table can run (see Environment Availability)

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|----------------|---------|--------------------|
| V2 Authentication | yes | `BCryptPasswordEncoder` for password storage/verification; RS256-signed JWT for session token, never a hand-rolled scheme |
| V3 Session Management | yes (adapted for stateless JWT) | No server-side session; short TTL (~1h, D-04) is the session-management control; no refresh token in this phase (D-04) reduces attack surface at the cost of shorter effective sessions |
| V4 Access Control | yes | Role check (`hasRole('SELLER_ADMIN')`) via `@PreAuthorize`/`SecurityFilterChain`; tenant/ownership check via the `CompanyGuard` bean (Pattern 3) — this is the control that directly satisfies COMP-03 |
| V5 Input Validation | yes | Jakarta Bean Validation (`@NotBlank`, `@Email`, `@DecimalMin("0.00")`) on all request DTOs (login, create-company, update-credit-limit) |
| V6 Cryptography | yes | RSA-2048 keypair for JWT signing (never hand-rolled); BCrypt for password hashing (never a custom hash); never log the private key or raw password |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|-----------------------|
| Broken Object-Level Authorization (BOLA/IDOR) — a BUYER passing another company's UUID in the URL path | Tampering / Elevation of Privilege | Explicit `CompanyGuard.isSelfOrSeller(companyId)` check on every company-scoped endpoint (Pattern 3) — role check alone (`hasRole('BUYER')`) is insufficient, the object-level check is the actual control |
| JWT algorithm confusion (`"alg": "none"` or asymmetric-to-symmetric key confusion) | Tampering | Spring Security's resource server only accepts the algorithm(s) implied by the configured `jwk-set-uri`/key type (RS256 here); do not add a custom `JwtDecoder` that trusts the `alg` header from the token itself |
| Credential stuffing / weak password storage | Information Disclosure | `BCryptPasswordEncoder` (default strength ≥ 10 rounds); rate limiting on `/auth/login` is explicitly out of scope for v1 (GATE-01 is a deferred v2 requirement) — acceptable for a portfolio project, but worth noting as a known gap, not a silent omission |
| Secrets committed to the repository (`LOCALSTACK_AUTH_TOKEN`, DB password, seeded SELLER_ADMIN password) | Information Disclosure | `.env` file gitignored, `.env.example` with placeholders committed instead; GitHub Actions secrets for CI (Phase 7); the seeded SELLER_ADMIN credential is an intentional, documented portfolio-demo exception (README note), not a real secret — but must never be a credential reused anywhere real |
| SQL injection via native/custom queries | Tampering | Spring Data JPA derived queries and parameterized `@Query` only; no string-concatenated SQL anywhere in this phase's scope |

## Sources

### Primary (HIGH confidence)
- None — no premium documentation providers (Context7, Ref, Jina, Exa, Tavily, Brave, Firecrawl, Perplexity) are enabled in this project's `.planning/config.json` (all flags `false`). All research this session used the built-in `WebSearch`/`WebFetch` tools, which the project's `classify-confidence` seam rates LOW by default regardless of source authority.

### Secondary (MEDIUM confidence, treated as CITED where the underlying source is official documentation)
- docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html — JWT resource server / `jwk-set-uri` configuration
- docs.localstack.cloud/aws/getting-started/auth-token/ and /aws/getting-started/installation/ — `LOCALSTACK_AUTH_TOKEN` requirement and docker-compose sample (fetched directly this session via WebFetch)
- hub.docker.com/r/localstack/localstack/tags and hub.docker.com/_/postgres/tags — image tag verification (fetched directly this session via WebFetch)
- spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/ and spring.io/blog/2026/06/11/spring-cloud-2025-0-3-aka-northfields-has-been-released/ — version pinning
- github.com/spring-projects/spring-security (issue #13808) — multi-`jwk-set-uri` limitations (not needed for this phase, single issuer only, but confirms single-issuer is the simple/default path)

### Tertiary (LOW confidence, marked for validation at implementation time)
- WebSearch synthesis on Hibernate `@Filter`/multi-tenancy patterns (Citus Data blog, Medium articles) — used only to justify *not* choosing this approach (see Assumptions Log A5)
- WebSearch synthesis on Flyway seed-data + BCrypt bootstrap pattern (no single authoritative doc found)
- Maven Central `search.maven.org` solrsearch API — returned stale/contradictory version data this session (`spring-boot-dependencies` latestVersion `3.5.3`, `spring-cloud-dependencies` latestVersion `2025.0.0`), contradicted by direct WebSearch of spring.io's own blog; **do not trust this API's `latestVersion` field without cross-checking** — see Assumptions Log A1

## Metadata

**Confidence breakdown:**
- Standard stack: MEDIUM — core version numbers (Spring Boot 3.5.16, Spring Cloud 2025.0.3, LocalStack 2026.08.3, Postgres 16.15) were cross-checked against official vendor blogs/Docker Hub this session, but the project's research-provider seam rates all WebSearch/WebFetch findings LOW by default (no context7/exa/tavily/brave/firecrawl enabled); treat as MEDIUM in practice since sources were official, not LOW as the seam mechanically reports
- Architecture: MEDIUM — JWT issuance/validation patterns are standard, well-documented Spring Security mechanics; the tenant-isolation guard (Pattern 3) is an original design synthesis for this project's specific claim shape, not copied from a single canonical source
- Pitfalls: MEDIUM-HIGH — the LocalStack auth-token pitfall and Flyway checksum pitfall are well-documented, current (2026) findings; the ephemeral-keypair-on-restart pitfall (Pitfall 4) is a direct logical consequence of D-02, reasoned from the decision itself rather than sourced externally

**Research date:** 2026-09-16
**Valid until:** ~14 days (LocalStack ships new calendar-versioned patches roughly weekly per observed tag history; Spring Boot/Cloud patch cadence is monthly — re-verify pinned versions if planning is delayed beyond two weeks)
