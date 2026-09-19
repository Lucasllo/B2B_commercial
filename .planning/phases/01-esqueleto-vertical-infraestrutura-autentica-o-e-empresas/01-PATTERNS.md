# Phase 1: Esqueleto Vertical — Pattern Map

**Mapped:** 2026-09-16
**Files analyzed:** 24 (new — greenfield phase)
**Analogs found:** 0 / 24 in-repo (no source code exists yet)

## Repository State

Confirmed via `git ls-files` (tracked-source gate, #3645): the only tracked file outside `.planning/` is `.claude/CLAUDE.md`. There is **no `pom.xml`, no `src/`, no `docker-compose.yml`, no gateway/auth-service module** anywhere in the repository, tracked or gitignored. This is a genuine greenfield phase — the first Maven modules, first Spring Boot service, and first docker-compose file are created in this phase.

**Consequence for the planner:** there are no in-repo code analogs to copy from for any file in this phase. Every "Pattern Assignment" below points to the **documented convention in RESEARCH.md / CLAUDE.md** instead of a codebase analog, exactly as instructed for greenfield phases. Do not treat the RESEARCH.md code blocks as "already-working code" — they are synthesized reference patterns (mostly `[CITED]` from Spring/Testcontainers/LocalStack official docs, a few `[ASSUMED]`) that the planner and executor must verify compile/run, not proven analogs.

Once this phase's `auth-service` module exists, **Phase 2+ pattern-mapping should treat `auth-service` as the canonical analog** for `catalog-service`, `inventory-service`, `order-service`, `notification-service` (same package structure, same `SecurityConfig`/resource-server wiring, same Flyway layout, same Testcontainers base class). This phase is the one that establishes those conventions — get the structure right here because it propagates forward.

## File Classification

| New File | Role | Data Flow | Analog Status |
|----------|------|-----------|----------------|
| `pom.xml` (parent reactor) | config | batch (build-time) | No analog — first pom in repo. Follow CLAUDE.md's "multi-module Maven reactor" convention. |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/*` | config | — | No analog — generate via `mvn wrapper:wrapper` (standard Maven tool, not hand-written) |
| `docker-compose.yml` | config | event-driven (container orchestration) | No analog — follow RESEARCH.md §Code Examples docker-compose excerpt |
| `.env.example` | config | — | No analog — plain key=value placeholders |
| `gateway/pom.xml` | config | — | No analog — child module pom, inherits parent BOM |
| `gateway/src/main/java/.../GatewayApplication.java` | config (Spring Boot entrypoint) | request-response | No analog — standard `@SpringBootApplication` main class |
| `gateway/src/main/resources/application.yml` | config | request-response | No analog — follow RESEARCH.md static-route pattern (Spring Cloud Gateway Server WebMVC) |
| `auth-service/pom.xml` | config | — | No analog — child module pom |
| `auth-service/src/main/java/.../AuthServiceApplication.java` | config (Spring Boot entrypoint) | request-response | No analog — standard `@SpringBootApplication` main class |
| `auth-service/.../config/SecurityConfig.java` | middleware | request-response | No analog — follow RESEARCH.md Pattern 2 (`SecurityFilterChain` + `oauth2ResourceServer`) |
| `auth-service/.../config/JwtIssuerConfig.java` | service (config/bean provider) | request-response | No analog — follow RESEARCH.md Pattern 1 (`NimbusJwtEncoder` + RSA keypair) |
| `auth-service/.../config/JwksController.java` | controller | request-response | No analog — follow RESEARCH.md Pattern 1 (JWKS endpoint) |
| `auth-service/.../auth/AuthController.java` | controller | request-response | No analog — follow RESEARCH.md architecture diagram (`POST /auth/login`) |
| `auth-service/.../auth/dto/LoginRequest.java`, `LoginResponse.java` | model (DTO) | request-response | No analog — plain record/POJO with Jakarta Bean Validation annotations |
| `auth-service/.../company/Company.java` | model (JPA entity) | CRUD | No analog — follow RESEARCH.md Flyway schema (`companies` table) for field shape; `BigDecimal creditLimit` per D-06 |
| `auth-service/.../company/CompanyRepository.java` | model (Spring Data repository) | CRUD | No analog — standard `interface CompanyRepository extends JpaRepository<Company, UUID>` |
| `auth-service/.../company/CompanyController.java` | controller | CRUD | No analog — follow RESEARCH.md architecture diagram (`POST /companies`, `GET/PUT /companies/{id}/credit-limit`) |
| `auth-service/.../company/CompanyService.java` | service | CRUD | No analog — standard service layer wrapping repository calls + BigDecimal validation |
| `auth-service/.../company/CompanyGuard.java` | middleware (authorization bean) | request-response | No analog — follow RESEARCH.md Pattern 3 verbatim (`@PreAuthorize`-backed tenant guard); **this is the one pattern that is itself `[ASSUMED]`** — planner should treat the SpEL/bean wiring as needing a compile-time check, not a proven recipe |
| `auth-service/.../company/dto/*.java` | model (DTO) | CRUD | No analog — plain records/POJOs |
| `auth-service/.../user/User.java` | model (JPA entity) | CRUD | No analog — follow RESEARCH.md Flyway schema (`users` table); `Role` enum (BUYER, SELLER_ADMIN) |
| `auth-service/.../user/UserRepository.java` | model (Spring Data repository) | CRUD | No analog — standard Spring Data JPA repository |
| `auth-service/.../user/CustomUserDetailsService.java` | service (Spring Security SPI) | request-response | No analog — standard `UserDetailsService` implementation loading `User` by email |
| `auth-service/src/main/resources/application.yml` | config | — | No analog — datasource, Flyway schema, `jwk-set-uri` config |
| `auth-service/src/main/resources/db/migration/V1__init_auth_schema.sql` | migration | batch | No analog — copy RESEARCH.md §Code Examples SQL verbatim as starting point |
| `auth-service/src/main/resources/db/migration/V2__seed_seller_admin.sql` | migration | batch | No analog — follow RESEARCH.md Pitfall 5 warning (never edit after applied; pre-computed BCrypt hash, D-07) |
| `auth-service/src/test/.../AbstractIntegrationTest.java` | test | request-response | No analog — follow RESEARCH.md Testcontainers skeleton (`@Testcontainers` + `@ServiceConnection PostgreSQLContainer`) |
| `auth-service/src/test/.../AuthControllerIT.java` | test | request-response | No analog — follow RESEARCH.md Testcontainers skeleton (`expiredTokenIsRejectedWith401`) |
| `auth-service/src/test/.../CompanyControllerIT.java` | test | request-response | No analog — follow RESEARCH.md Testcontainers skeleton (`buyerCannotReadAnotherCompanysCreditLimit`) |

## Pattern Assignments

Since no in-repo analog exists, each assignment below cites the **RESEARCH.md section** (not a file:line pair) as the copy source, per the greenfield-phase instruction. Excerpts are reproduced from RESEARCH.md's "Code Examples" and "Architecture Patterns" sections for the planner's direct use.

### `auth-service/.../config/JwtIssuerConfig.java` + `JwksController.java`

**Source:** RESEARCH.md → Architecture Patterns → Pattern 1 (lines 262-307 of 01-RESEARCH.md)

```java
@Configuration
public class JwtIssuerConfig {
    private final RSAKey rsaKey;
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
    public RSAKey rsaKey() { return rsaKey; }
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

Token issuance excerpt (for `AuthController.java` / a service it delegates to), RESEARCH.md lines 308-320:

```java
JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("orderflow-auth-service")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)) // D-04
        .subject(user.getId().toString())
        .claim("role", user.getRole().name())
        .claim("company_id", user.getCompanyId())
        .build();
String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
```

---

### `auth-service/.../config/SecurityConfig.java`

**Source:** RESEARCH.md → Architecture Patterns → Pattern 2 (lines 322-347)

```yaml
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
        .csrf(AbstractHttpConfigurer::disable)
        .build();
}
```

**Forbidden pattern (CLAUDE.md What NOT to Use):** do not use `WebSecurityConfigurerAdapter` — removed in Spring Security 6/Boot 3.x. Use `SecurityFilterChain @Bean` as above.

---

### `auth-service/.../company/CompanyGuard.java`

**Source:** RESEARCH.md → Architecture Patterns → Pattern 3 (lines 349-371). Flagged `[ASSUMED]` — no official doc example matches exactly; planner/executor must verify this compiles and the SpEL resolves correctly against the JWT claim shape.

```java
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

**Anti-pattern to avoid (RESEARCH.md Common Pitfalls, Pitfall 6):** do not implement tenant isolation via a Hibernate `@Filter`/global filter — silently disabled filters return unfiltered data with 200 instead of 403, and native/JPQL queries bypass filters entirely. Use this explicit guard as the sole enforced mechanism for COMP-03.

---

### `auth-service/src/main/resources/db/migration/V1__init_auth_schema.sql`, `V2__seed_seller_admin.sql`

**Source:** RESEARCH.md → Code Examples (lines 492-518)

```sql
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
    company_id UUID REFERENCES companies(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

```sql
-- V2__seed_seller_admin.sql (D-07 — hash pre-computed offline, never inside SQL)
INSERT INTO users (email, password_hash, role, company_id)
VALUES ('admin@orderflow.local', '$2a$10$<precomputed-bcrypt-hash>', 'SELLER_ADMIN', NULL);
```

**Critical constraint (Pitfall 5):** once `V2` is applied to any database, never edit it — create `V3__...sql` for changes, or Flyway checksum validation fails.

**Money constraint (D-06):** `NUMERIC(19,2)` / `BigDecimal` everywhere monetary values are stored — never `int`/`long` cents, and this applies to every future monetary column too (Phase 4/5 order totals).

---

### `docker-compose.yml`

**Source:** RESEARCH.md → Code Examples (lines 431-490)

```yaml
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

**Critical constraint (Pitfall 1):** `LOCALSTACK_AUTH_TOKEN` must use the `:?` fail-fast syntax — a missing token must break `docker-compose up` loudly, not silently, per INFRA-01's "clean clone" success criterion. Document `.env.example` + free LocalStack Hobby account signup in the README.

**Critical constraint (Pitfall 3):** use `depends_on: condition: service_healthy`, not bare `depends_on`, for every service-to-dependency edge — bare `depends_on` only waits for "running," not "ready."

---

### `auth-service/src/test/.../*.IT.java`

**Source:** RESEARCH.md → Code Examples (lines 520-553)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CompanyControllerIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @Autowired MockMvc mockMvc;

    @Test
    void buyerCannotReadAnotherCompanysCreditLimit() throws Exception {
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

**High-risk assumption to verify (RESEARCH.md Assumption A4):** `jwk-set-uri`-based `NimbusJwtDecoder` caching (no per-request call to auth-service) is the load-bearing claim for AUTH-03/D-03. Planner should add an explicit test task (e.g., stop/block auth-service after first token issuance, confirm subsequent validation still succeeds from cache) rather than trusting this by inspection alone.

---

## Shared Patterns

### Package/module naming convention
**Source:** RESEARCH.md → Recommended Project Structure (lines 218-260)
Apply to all new files: root package `com.orderflow.auth` (or `com.orderflow.gateway`), sub-packages by feature (`config`, `auth`, `company`, `user`), not by layer. Every future service module (catalog, inventory, order, notification) should mirror this exact structure once auth-service exists — this phase sets the convention for all downstream phases.

### Language convention (CLAUDE.md / PROJECT.md)
Code identifiers, endpoints, filenames, DB objects, git commits: English. Documentation/planning: Portuguese. Applies to every file listed above.

### Error handling / validation
No analog exists yet; RESEARCH.md does not provide an explicit `@ControllerAdvice` example. Planner should specify a lightweight global exception handler (`@RestControllerAdvice`) returning structured JSON errors (`{"error": "..."}`) for 400/401/403/404, using Jakarta Bean Validation (`@NotBlank`, `@Email`, `@DecimalMin`) on request DTOs — this is implied by RESEARCH.md's Standard Stack (`spring-boot-starter-validation`) but not spelled out as a code example. Flag this as a planning decision, not a proven pattern.

### Health-gated startup
**Source:** RESEARCH.md Pitfall 3 + Code Examples docker-compose section. Applies to every service in `docker-compose.yml`.

## No Analog Found

Every file in this phase has no in-repo analog (see Repository State above). All 26 files above rely on RESEARCH.md's documented conventions/`[CITED]` patterns rather than existing code. This is expected and correct for the first phase of a greenfield project.

## Metadata

**Analog search scope:** entire repository (`git ls-files`) — confirmed only `.claude/CLAUDE.md` and `.planning/**` are tracked; no `src/`, `pom.xml`, or `docker-compose.yml` exist.
**Files scanned:** full tracked-file listing (1 non-planning file: `.claude/CLAUDE.md`)
**Pattern extraction date:** 2026-09-16
**Tracked-source gate:** N/A this phase — no analog paths are emitted, only RESEARCH.md section references. Re-verify with `git ls-files` before Phase 2 pattern-mapping treats `auth-service/**` as an analog (confirm it was actually committed, not left gitignored/untracked).
