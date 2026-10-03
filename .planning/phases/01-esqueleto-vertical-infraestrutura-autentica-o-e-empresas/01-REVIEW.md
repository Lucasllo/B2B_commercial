---
status: clean
phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
files_reviewed: 51
depth: standard
findings:
  critical: 0
  warning: 0
  info: 4
  total: 4
resolution: "WR-1 and WR-2 fixed in commit 671fbf9 (see Resolution section below); 46/46 tests still green after fix."
---

# Code Review: Phase 1

Reviewed the full Phase 1 vertical slice: auth-service (JWT issuance/validation, company/credit-limit
management, tenant isolation via `CompanyGuard`), the gateway, docker-compose, migrations, and the
integration/unit test suites. Overall the code is careful and well-documented — most of the classic
pitfalls (mass assignment, SQL injection, password leakage, JWKS private-key exposure, stack-trace
leakage, monetary scale/rounding, user-enumeration via login and via 403/404) are explicitly addressed
in code comments *and* proven by adversarial tests (`CompanyControllerIT`, `AuthControllerIT`,
`JwksContractIT`). No critical vulnerabilities were found. The items below are quality/consistency
gaps, not exploitable holes.

## Critical

None found.

## Warning

### WR-1: `AuthController` and `GlobalExceptionHandler` both handle `AuthenticationException` with different response shapes
**File:** `auth-service/src/main/java/com/orderflow/auth/auth/AuthController.java:67-71` and
`auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java:63-67`
**Issue:** `GlobalExceptionHandler`'s class Javadoc states the service-wide invariant: "sempre as
chaves `error` (código curto e estável) e `message`" for every error body. `AuthController` declares
its own local `@ExceptionHandler(AuthenticationException.class)` that returns
`Map.of("error", "Credenciais inválidas")` — no `message` key at all, and a different `error` value
than the global handler's `unauthorized`/"Authentication is required" pair. Because Spring resolves
`@ExceptionHandler` methods declared directly on the controller that threw the exception *before*
falling back to `@RestControllerAdvice` beans, `AuthController`'s local handler wins for every
`AuthenticationException` thrown from `/auth/login` (e.g. `BadCredentialsException` from
`AuthenticationManager.authenticate`). This makes `GlobalExceptionHandler.handleAuthentication`
effectively dead code (unreachable from any current endpoint) and means the actual login-failure body
shape silently diverges from the documented uniform contract. `CompanyControllerIT`'s
`errorBodiesForValidationForbiddenAndConflictShareUniformShapeWithoutLeakage` test does not cover the
login path, so this divergence isn't caught by any existing test.
**Fix:** Remove the local `@ExceptionHandler` in `AuthController` and let `GlobalExceptionHandler`
handle all `AuthenticationException`s uniformly (it already returns 401 with the same generic,
non-enumerating message), or if a distinct login-failure message is intentional, give it the same
`error`/`message` key shape as every other handler so the "uniform error body" contract actually holds
service-wide.

### WR-2: `CreateCompanyRequest.BuyerUser.email` has no `@Size(max=...)`, unlike the sibling `name` field
**File:** `auth-service/src/main/java/com/orderflow/auth/company/dto/CreateCompanyRequest.java:26-39`
**Issue:** `name` is validated with `@Size(max = 255)` to match the `VARCHAR(255)` column, but
`BuyerUser.email` (also backed by `users.email VARCHAR(255)`, see
`V1__init_auth_schema.sql:13`) has only `@NotBlank @Email` with no upper bound. An email longer than
255 characters bypasses Bean Validation (400) and reaches the database, where it fails at the
`VARCHAR(255)` limit. Depending on how Spring's SQL error-code translator classifies PostgreSQL's
"value too long for type character varying" (SQLSTATE 22001, a *data exception*, not the *integrity
constraint violation* class 23xxx that `GlobalExceptionHandler.handleConflict` is built for), this
either (a) gets misclassified as `DataIntegrityViolationException` and returns a misleading
`409 email_already_used` for a value that was never actually a duplicate, or (b) isn't caught by any
handler and falls through to the default Spring Boot error page — inconsistent with the rest of the
service's uniform error contract either way. `LoginRequest.email` has the same gap, though it's lower
risk there since login is a read path (an overlong email just fails to match a user → clean 401).
**Fix:** Add `@Size(max = 255)` to `BuyerUser.email` (and optionally `LoginRequest.email` for
consistency) so oversized input is rejected as a clean 400 `validation_failed` before it ever reaches
the database.

## Resolution

Both warnings were fixed immediately after this review, in commit `671fbf9`:

- **WR-1**: Removed `AuthController`'s local `@ExceptionHandler(AuthenticationException.class)`.
  Login failures now fall through to `GlobalExceptionHandler.handleAuthentication`, which returns
  the same `{"error": "unauthorized", "message": "Authentication is required"}` envelope as every
  other 401 in the service. `loginWithWrongPasswordAndUnknownEmailReturnIdenticalUnauthorizedBody`
  only asserts the two bodies are equal to each other (not a specific literal), so this change is
  covered by the existing test without modification.
- **WR-2**: Added `@Size(max = 255)` to `CreateCompanyRequest.BuyerUser.email`, matching the
  `users.email VARCHAR(255)` column and the sibling `name` field's existing bound.

Full suite re-verified green after the fix: `./mvnw -B -pl auth-service verify` — 46/46 tests,
`BUILD SUCCESS`.

Info items (IN-1 through IN-4) are documented, non-blocking, forward-looking notes — left as-is
per their own `<Fix>` recommendation ("not urgent for Phase 1" / "optional").

## Info

### IN-1: `GlobalExceptionHandler.handleConflict` maps *any* `DataIntegrityViolationException` to `email_already_used`
**File:** `auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java:45-49`
**Issue:** Today the only unique/integrity constraint in the schema is `users.email UNIQUE`
(`V1__init_auth_schema.sql:13`), so the mapping is currently accurate. But the handler is written
generically for the exception *type*, not the specific constraint, so any future integrity violation
in this service (a new unique constraint, a `CHECK`, a stricter FK) would also be reported to the
client as `"email is already in use"`, which would be actively misleading and would complicate
debugging as the schema grows in later phases.
**Fix:** Not urgent for Phase 1, but worth a follow-up note: either inspect the constraint name in the
underlying `SQLException` to report a more specific `error` code, or narrow the current handler's
Javadoc/name (`handleConflict` → `handleEmailConflict`) so a future contributor doesn't assume it's a
generic integrity-violation handler.

### IN-2: RSA signing key is regenerated in memory on every restart, with no persistence
**File:** `auth-service/src/main/java/com/orderflow/auth/config/JwtIssuerConfig.java:37-47`
**Issue:** This is a deliberate, documented Phase 1 decision (D-02) and is not a bug — but worth
flagging for the roadmap: because the RSA keypair (and its random `kid`) is generated fresh in the
`JwtIssuerConfig` constructor on every JVM start, (1) every `auth-service` restart invalidates all
previously issued tokens (silent logout for every user), and (2) the design does not support running
more than one `auth-service` replica behind a load balancer, since each instance would sign with a
different key and reject tokens issued by its siblings. Fine for a single-instance portfolio
deployment; would need a persisted/shared key (e.g. loaded from a mounted secret or a KMS-backed
store) before any future phase relies on horizontal scaling of `auth-service`.
**Fix:** No action needed for Phase 1; consider revisiting if/when a later phase introduces multiple
`auth-service` instances.

### IN-3: Maven Wrapper is downloaded without checksum verification
**File:** `.mvn/wrapper/maven-wrapper.properties`
**Issue:** `distributionUrl`/`wrapperUrl` are pinned to exact versions (good), but the properties file
has no `wrapperSha256Sum` entry, so `mvnw`/`mvnw.cmd` will download the wrapper jar over HTTPS without
verifying its integrity against a known-good hash. This is a minor supply-chain hardening gap common
to many Maven Wrapper setups, not specific to this project.
**Fix:** Optionally add `wrapperSha256Sum=<hash>` (from `maven-wrapper-3.3.2.jar`'s published checksum)
to pin integrity as well as version.

### IN-4: Not every error path returns the "uniform" error envelope
**File:** `auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java`
**Issue:** `GlobalExceptionHandler` covers validation, conflict, not-found, forbidden, and
authentication exceptions, but there's no catch-all for other exception types that can legitimately
occur (e.g. `MethodArgumentTypeMismatchException` from a malformed `@PathVariable UUID`,
`HttpMessageNotReadableException` from malformed JSON, or any unexpected `RuntimeException`). These
fall through to Spring Boot's default `BasicErrorController`, which — with this project's default
`server.error.*` settings — does *not* leak stack traces or exception messages, so there's no security
issue, but the response body shape differs from the rest of the API (no `error`/`message` keys),
undermining the "uniform error body" claim in the class Javadoc for those specific inputs.
**Fix:** Optional for Phase 1; a catch-all `@ExceptionHandler(Exception.class)` returning the same
generic envelope (with a generic `error: "internal_error"`) would make the uniform-shape guarantee
airtight without changing any currently-tested behavior.

## What Was Confirmed Clean

- **CompanyGuard SpEL authorization (`isSelfOrSeller`)**: correctly checks role first (SELLER_ADMIN
  bypass), then compares the JWT's `company_id` claim to the path variable as strings; defensively
  returns `false` (never throws) for a `null` `Authentication` or a non-`Jwt` principal, avoiding a
  500-instead-of-403 failure mode. Confirmed by adversarial tests including the "403 is byte-identical
  for another company vs. a nonexistent company" oracle test.
- **JWT issuance/validation**: RS256, 2048-bit key, `toPublicJWK()` used for the JWKS endpoint (no
  private key material `d/p/q/dp/dq/qi` ever serialized — proven by test), issuer validated by
  `JwtValidators.createDefaultWithIssuer`, `company_id` claim correctly omitted (not null) for
  SELLER_ADMIN. Signature-mismatch, expired-token, tampered-payload, and malformed-header cases are
  all proven to 401 via tests in `AuthControllerIT`/`CompanyControllerIT`/`JwksContractIT`.
- **No SQL injection risk**: all queries are Spring Data derived queries (`findByEmail`) or JPA
  entity operations; no native/concatenated SQL anywhere in the reviewed code.
- **No mass assignment**: `CreateCompanyRequest`/`BuyerUser` never carry a role field;
  `@JsonIgnoreProperties(ignoreUnknown = true)` explicitly discards a client-supplied `"role"`, and
  `CompanyService` hardcodes `Role.BUYER` server-side — proven by
  `createCompanyIgnoresClientSuppliedRoleAndAlwaysCreatesBuyer`.
- **No credential/password leakage**: `CompanyResponse` never includes password/hash fields;
  `createCompanyResponseNeverLeaksPassword` asserts the raw password and `"password"`/`"passwordHash"`
  keys never appear in the response body.
- **Monetary precision**: `Company.creditLimit` is `BigDecimal` mapped to `NUMERIC(19,2)`;
  `@Digits(integer = 17, fraction = 2)` on both `CreateCompanyRequest` and `UpdateCreditLimitRequest`
  rejects (400) rather than silently rounds a three-decimal value, and `Company.changeCreditLimit`/
  the seed migration never call `setScale`/`round`. Verified by
  `createCompanyWithThreeDecimalCreditLimitReturns400AndCreatesNoCompany` and the equivalent update
  test.
- **docker-compose secrets/health-gating**: `POSTGRES_PASSWORD`, `POSTGRES_USER`, `POSTGRES_DB`, and
  `LOCALSTACK_AUTH_TOKEN` all use the `${VAR:?}` syntax (hard failure if unset via `.env`), no secret
  is hardcoded in `docker-compose.yml`; `.env.example` ships with an empty password placeholder; `.env`
  is gitignored. `postgres`/`localstack`/`auth-service`/`gateway` are wired with `depends_on: condition:
  service_healthy` in the correct dependency order, and only the `gateway` port is exposed on all
  interfaces — `postgres`, `localstack`, and `auth-service` are all bound to `127.0.0.1` only.
- **User enumeration defenses**: wrong-password and unknown-email logins return byte-identical 401
  bodies (proven by test); this relies on Spring Security's default behavior of hiding
  `UsernameNotFoundException` as `BadCredentialsException`.
