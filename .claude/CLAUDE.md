<!-- GSD:project-start source:PROJECT.md -->

## Project

**OrderFlow**

OrderFlow é uma plataforma de gerenciamento de pedidos B2B/atacado: uma empresa vendedora ("seller") disponibiliza um catálogo de produtos para múltiplas empresas compradoras, que criam pedidos sujeitos a aprovação por limite de crédito, reserva de estoque, atribuição de transportadora e acompanhamento até a entrega. É um projeto de portfólio técnico voltado a demonstrar competências exigidas para uma vaga de Desenvolvedor Java Pleno.

**Core Value:** O fluxo de pedido — criação, aprovação condicional por limite de crédito, reserva de estoque e confirmação — funcionando de ponta a ponta entre microsserviços via orquestração por eventos (padrão saga). Se isso não funcionar de forma confiável, o projeto não cumpre seu propósito de demonstrar arquitetura de microsserviços orientada a eventos.

### Constraints

- **Stack**: Java 17+, Spring Boot — exigido pela vaga-alvo de Desenvolvedor Java Pleno
- **Persistência**: PostgreSQL para dados transacionais dos serviços; DynamoDB via LocalStack para o histórico de notificações (NoSQL) — demonstra SQL e NoSQL lado a lado
- **Mensageria**: SQS via LocalStack para comunicação assíncrona entre order-service, inventory-service e notification-service (padrão saga)
- **Containerização**: Docker + docker-compose — todo o sistema deve subir localmente sem custo de nuvem
- **Cloud**: AWS demonstrada via LocalStack (S3, SQS, DynamoDB simulados) — sem exigência de deploy real ativo continuamente
- **CI/CD**: pipeline automatizado (GitHub Actions) validando build e testes a cada push
- **Testes**: unitários (JUnit + Mockito), integração (Testcontainers), contrato/E2E entre microsserviços
- **Confiabilidade de eventos**: padrão Transactional Outbox em order-service e inventory-service — evita o problema de dual-write (gravar no banco e publicar no SQS como operações separadas e não atômicas)
- **Processo**: desenvolvimento incremental, fase a fase; nenhuma tecnologia implementada sem explicação prévia do motivo de seu uso

<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->

## Technology Stack

## TL;DR Recommendation

- **Java 21 (LTS)**, not 17 and not 25. It is the current mainstream enterprise baseline in the Brazilian/global Java job market, ships virtual threads (good talking point in interviews), and every library in this stack has mature support for it. Java 25 is technically newer and has the un-pinned virtual threads fix, but it is a ~1-year-old LTS as of late 2025 — most companies hiring Pleno devs still run 17/21, so targeting 21 keeps the portfolio believable and safe while still being "current."
- **Spring Boot 3.5.x** (the final, most mature 3.x minor), not Spring Boot 4.0. Boot 4.0 (released Nov 2025) is brand-new, forces Jackson 3 + Jakarta EE 11 + Servlet 6.1 migration pain, and the Spring team's own guidance is to land on 3.5 first. A portfolio project should showcase clean, idiomatic use of the stack real job postings ask for today, not bleeding-edge churn.
- **Spring Cloud 2025.0.x ("Northfields")** as the compatible Spring Cloud release train for Boot 3.5.x, giving you Spring Cloud Gateway Server WebMVC, Spring Cloud Contract, and OpenFeign/LoadBalancer if needed.
- **PostgreSQL** per transactional service (auth, catalog, inventory, order), **DynamoDB via LocalStack** for the notification-service history, **SQS via LocalStack** for the order/inventory/notification saga — as mandated by the project constraints.
- **Critical infrastructure risk to flag now:** since LocalStack 2026.03.0 (March 23, 2026), even the free tier requires a `LOCALSTACK_AUTH_TOKEN` from a registered account — there is no more fully anonymous community image. This must be designed into docker-compose and CI from day one (see "What NOT to Use" and Alternatives below).

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Java | 21 (LTS, Temurin distribution) | Language/runtime for all services | Current enterprise standard as of 2026; virtual threads (JEP 444) demonstrate modern concurrency knowledge without requiring the still-fresh Java 25; every library below has first-class 21 support. Confidence: MEDIUM |
| Spring Boot | 3.5.x (latest patch at implementation time) | Application framework for every microservice | Last and most mature 3.x minor; huge tutorial/StackOverflow coverage; avoids Spring Boot 4.0's Jackson 3 / Jakarta EE 11 breaking changes, which would burn learning time on migration noise instead of on the domain (order/saga/credit-limit logic) the portfolio needs to showcase. Confidence: MEDIUM |
| Spring Cloud | 2025.0.x "Northfields" | Release train providing Gateway, Contract, LoadBalancer/Feign for the microservices layer | Northfields is the release train matched to Spring Boot 3.5.x; using a mismatched Spring Cloud/Boot pair is one of the most common and hardest-to-debug setup mistakes in Spring microservices projects. Confidence: MEDIUM |
| Spring Cloud Gateway Server WebMVC | ships with Spring Cloud 2025.0.x | API Gateway in front of auth/catalog/inventory/order/notification services | As of Spring Cloud 2025.0.0, the older reactive/WebFlux-based "Spring Cloud Gateway MVC" is deprecated in favor of Server WebMVC, a non-reactive (servlet + virtual threads) gateway. Avoids forcing the whole team to learn Project Reactor just to run a gateway — a good trade for a portfolio project where the domain logic, not reactive streams, is the thing to demonstrate. Confidence: MEDIUM |
| PostgreSQL | 16.x (Docker image `postgres:16`) | Relational store for auth, catalog, inventory, order services | Required by project constraints; PostgreSQL is the de facto standard OSS relational DB in the Java/Spring ecosystem and pairs cleanly with Testcontainers. Confidence: HIGH (project constraint, not researched) |
| DynamoDB (via LocalStack) | LocalStack-emulated, AWS SDK v2 `dynamodb` + `dynamodb-enhanced` 2.44.x | NoSQL store for notification-service history | Required by project constraints to demonstrate SQL+NoSQL side by side; `dynamodb-enhanced` gives an annotation-based, JPA-like mapping layer (`@DynamoDbBean`) that keeps the notification-service code idiomatic instead of hand-rolling `AttributeValue` maps. Confidence: MEDIUM |
| Amazon SQS (via LocalStack) | LocalStack-emulated, consumed via Spring Cloud AWS | Async messaging backbone for the order/inventory/notification saga | Required by project constraints; Spring Cloud AWS's `SqsTemplate`/`@SqsListener` give Spring-idiomatic send/receive semantics instead of raw AWS SDK boilerplate. Confidence: MEDIUM |
| Docker + Docker Compose | Compose V2 (`docker compose`, not the legacy `docker-compose` binary) | Local orchestration of every service + Postgres + LocalStack | Required by project constraints; Compose V2 is bundled with modern Docker Desktop/Engine and is the current standard — the standalone Python `docker-compose` v1 binary is EOL. Confidence: MEDIUM |
| LocalStack | LocalStack for AWS, Hobby (free) tier, pinned image tag ≥ `4.x`/`2026.03.x` | Local AWS emulation for SQS, DynamoDB, and optionally S3 | Required by project constraints to demonstrate AWS usage without cloud cost. **Since March 23, 2026, the image requires a `LOCALSTACK_AUTH_TOKEN`** from a free registered "Hobby" account (non-commercial use) — plan the docker-compose and CI secrets for this from the start; do not assume an anonymous community image still exists. Confidence: MEDIUM (time-sensitive; verify current LocalStack pricing page before implementation) |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Spring Cloud AWS (`io.awspring.cloud:spring-cloud-aws-starter-sqs`) | 3.3.x–3.4.x (matched to Boot 3.5.x per awspring.io compatibility matrix) | `SqsTemplate` + `@SqsListener` abstractions over SQS | In order-service (publish reservation command), inventory-service (consume command, publish result), notification-service (consume all order lifecycle events) |
| AWS SDK v2 `dynamodb-enhanced` | 2.44.x | Annotation-based DynamoDB object mapping (`@DynamoDbBean`) | notification-service persistence layer only |
| Spring Security (`spring-boot-starter-security` + `spring-security-oauth2-resource-server` + `spring-security-oauth2-jose`) | ships with Boot 3.5.x | JWT issuing (auth-service) and JWT validation (resource servers: catalog, inventory, order, notification) | auth-service issues tokens with `NimbusJwtEncoder`/`JwtEncoder` (self-issued JWT, RSA-signed, no external IdP needed for a portfolio project); every other service validates with the OAuth2 Resource Server support (`spring-security-oauth2-resource-server`) using a shared public key or JWK endpoint exposed by auth-service |
| jjwt (`io.jsonwebtoken:jjwt-api/-impl/-jackson`) | 0.12.x | Alternative/simpler JWT library if you prefer manual control over token creation instead of Spring's Nimbus-based encoder | Use only if you want to hand-roll JWT creation for teaching purposes; otherwise prefer Spring Security's built-in OAuth2 Resource Server support below, since it doubles as your validation layer and avoids maintaining two different JWT libraries |
| Resilience4j (`io.github.resilience4j:resilience4j-spring-boot3`) | 2.2.x+ | Circuit breaker, retry, rate limiter, bulkhead for calls between order-service ↔ inventory-service (and the simulated shipping carrier call) | Any synchronous cross-service HTTP call (e.g., order-service checking current stock before creating a reservation) or the simulated carrier integration; wrap the SQS consumer's business logic with `@Retry` for transient failures |
| springdoc-openapi (`springdoc-openapi-starter-webmvc-ui`) | 2.x line for Boot 3.5.x (check springdoc.org matrix at implementation time) | Auto-generated OpenAPI 3 spec + Swagger UI per service | Every service — a Swagger UI per microservice is a strong, low-effort artifact for interviewers to click through |
| Lombok | 1.18.34+ | Boilerplate reduction (getters/setters/builders/constructors) | All services; combine with `lombok-mapstruct-binding` (0.2.0) when also using MapStruct |
| MapStruct (`org.mapstruct:mapstruct` + `mapstruct-processor`) | 1.6.3 | Compile-time DTO ↔ entity mapping | Any service with a non-trivial DTO/entity split (order-service order creation, catalog-service product responses) |
| Flyway (`flyway-core` + `flyway-database-postgresql`) | latest matching Boot 3.5.x BOM | Versioned SQL schema migrations | Every Postgres-backed service (auth, catalog, inventory, order) — one migration folder per service's own schema |
| Testcontainers (`testcontainers-bom`, `postgresql`, `localstack` modules) | 1.20.x+ (pin latest at implementation time via Maven Central) | Spin up real Postgres and real LocalStack containers in integration tests | Every service's `@SpringBootTest` integration test suite; also usable for the cross-service saga E2E tests |
| JUnit 5 + Mockito (`spring-boot-starter-test`) | ships with Boot 3.5.x BOM | Unit tests with mocked collaborators | Service/domain-logic layer unit tests (credit-limit approval rule, saga state transitions) |
| Spring Cloud Contract | ships with Spring Cloud 2025.0.x | Producer-driven contract tests + generated WireMock stubs between services | order-service ↔ inventory-service and order-service ↔ notification-service contracts; keeps contract tests inside the existing Spring/Java toolchain instead of introducing a separate polyglot tool |
| Awaitility | 4.2.x+ | Polling/async assertions for saga completion in integration/E2E tests | Any test that waits for an SQS-driven side effect (e.g., "eventually inventory is reserved and order status becomes CONFIRMED") |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Docker Compose V2 | Local multi-container orchestration | Use the `docker compose` CLI plugin (not the deprecated Python `docker-compose`); one `docker-compose.yml` at repo root wiring all services + Postgres (one instance, multiple schemas or one instance per service — your call) + LocalStack |
| GitHub Actions | CI/CD | `actions/setup-java@v4` with `distribution: temurin`, `java-version: 21`; Maven dependency cache via `actions/setup-java`'s built-in cache option; `mvn verify` per module runs unit + Testcontainers integration tests since `ubuntu-latest` runners ship Docker preinstalled |
| Maven (multi-module reactor or independent poms) | Build tool | A multi-module Maven reactor (parent pom + one module per service) keeps shared dependency versions (Spring Boot BOM, Testcontainers BOM) DRY across five services — recommended over five fully independent poms for a project this size |
| Postman/Insomnia or `.http` files | Manual API exploration | Optional but useful alongside the Swagger UI per service for demoing the saga flow end-to-end |

## Installation

# Parent pom dependency management (excerpt) — pin BOMs, not individual versions, wherever possible

# spring-boot-starter-parent 3.5.x

# spring-cloud-dependencies 2025.0.x (import scope)

# testcontainers-bom 1.20.x (import scope)

# awssdk-bom 2.44.x (import scope)

# Per-service starters (order-service example)

#   spring-boot-starter-web

#   spring-boot-starter-security

#   spring-boot-starter-oauth2-resource-server

#   spring-boot-starter-data-jpa

#   spring-cloud-aws-starter-sqs

#   resilience4j-spring-boot3

#   springdoc-openapi-starter-webmvc-ui

#   flyway-core, flyway-database-postgresql

#   org.postgresql:postgresql (runtime)

#   org.projectlombok:lombok

#   org.mapstruct:mapstruct + mapstruct-processor

#   org.projectlombok:lombok-mapstruct-binding (annotation processor path)

# Test dependencies (every service)

#   spring-boot-starter-test

#   org.testcontainers:junit-jupiter

#   org.testcontainers:postgresql

#   org.testcontainers:localstack   (order/inventory/notification services)

#   org.awaitility:awaitility

#   spring-cloud-starter-contract-verifier (producer side, order/inventory)

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|--------------------------|
| Java 21 | Java 25 | If the explicit goal shifts from "match today's Pleno job market" to "showcase the newest JVM features" — Java 25 has the un-pinned virtual threads and longer support window, but is a riskier choice for a portfolio meant to look like production-grade, current-industry work |
| Spring Boot 3.5.x | Spring Boot 4.0.x | Once you've shipped the milestone once on 3.5 and want a follow-up "upgrade" milestone that specifically demonstrates migration skill (Jackson 3, Jakarta EE 11) — genuinely a good second-portfolio-piece, not a good first foundation |
| Spring Cloud Gateway Server WebMVC | Classic reactive Spring Cloud Gateway (WebFlux) | If you specifically want to demonstrate reactive programming skills (Project Reactor, `Mono`/`Flux`) as a differentiator for the target role — otherwise it adds a second programming model to teach/debug for no domain benefit |
| Orchestration living inside order-service (lightweight, code-based) | A dedicated orchestration engine (Camunda, Temporal, AWS Step Functions) | If the roadmap wants to demonstrate workflow-engine experience specifically — but for a 3-service saga this is significant overengineering; interviewers will see through unnecessary infrastructure faster than they will value it |
| Spring Cloud Contract | Pact | If the target job market/employer explicitly values polyglot consumer-driven contract testing, or the project ever needs a non-Java consumer (e.g., a frontend team). For an all-Java portfolio, Pact's central broker and consumer-driven flow are more setup than the payoff justifies |
| Flyway | Liquibase | If the roadmap wants to demonstrate database-agnostic migrations or rollback-heavy release engineering — not needed when every service owns a single Postgres schema |
| Spring Security's built-in JWT (Nimbus-based) | jjwt (`io.jsonwebtoken`) | If you want a lower-level, more "from scratch" teaching moment for how JWTs are signed/parsed, at the cost of also needing a second, separate library on the validating side |
| Single shared Postgres instance, multiple databases/schemas (one per service) in docker-compose | One Postgres container per service | If you want to more strictly demonstrate "database per service" isolation as a microservices principle — costs more RAM/containers locally for a marginal realism gain in a portfolio context |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|--------------|
| Netflix Hystrix | In maintenance mode since 2019, removed from Spring Boot 3.x dependency management entirely — will not even compile cleanly against Boot 3.5 without extra work | Resilience4j |
| `WebSecurityConfigurerAdapter` | Deprecated in Spring Security 5.7, fully removed in 6.0 (ships with Boot 3.x) — code samples using it will not compile | `SecurityFilterChain` `@Bean` with the `HttpSecurity` DSL |
| Legacy standalone `docker-compose` (Python v1 binary) | End-of-life, no longer receives updates, subtly different flag behavior from Compose V2 | `docker compose` (V2 CLI plugin, bundled with current Docker Desktop/Engine) |
| An anonymous/no-signup LocalStack image assumption | Since LocalStack 2026.03.0, the single published Docker image requires `LOCALSTACK_AUTH_TOKEN` to even start — a docker-compose file written without this will fail outright for every future contributor/reviewer, not just you | Register a free LocalStack "Hobby" account, store the token as a GitHub Actions secret and a local `.env` (git-ignored), and pass `LOCALSTACK_AUTH_TOKEN` into the container |
| A dedicated orchestration engine (Camunda/Temporal/Step Functions) for a 3-participant saga | Massive infrastructure and learning-curve overhead relative to the actual coordination complexity (order → inventory → notification); dilutes the portfolio's focus away from the Java/Spring domain logic the target role cares about | A lightweight in-process saga: order-service owns the state machine, sends SQS "commands," consumes SQS "reply/event" queues, and persists saga state as part of the Order aggregate |
| Kafka | Not requested by the project constraints (SQS via LocalStack is explicit), and adds a heavier, stateful broker (partitions, consumer groups, Zookeeper/KRaft) that is disproportionate to a 3-service saga — would also contradict the "SQS via LocalStack" constraint | Amazon SQS via LocalStack, exactly as scoped |
| A full microservices "framework" like Netflix OSS Eureka/Ribbon/Zuul (legacy Spring Cloud Netflix stack) | Netflix OSS components (Eureka, Zuul, Ribbon) are in maintenance/end-of-life status and were superseded years ago by Spring Cloud Gateway + Spring Cloud LoadBalancer | Spring Cloud Gateway Server WebMVC + Spring Cloud LoadBalancer (or plain static config, since this project's services are fixed and known at compose time — service discovery like Eureka is arguably itself optional/overengineering here; a static gateway route config per service is enough for 5 known services) |

## Stack Patterns by Variant

- Use Spring Cloud Gateway Server WebMVC with statically configured routes (one route per downstream service) in `application.yml`.
- Because service discovery (Eureka/Consul) is unnecessary complexity when the set of services is fixed and known — five services, known ports, docker-compose networking. Add discovery only if the roadmap later wants to demonstrate that specific skill.
- Swap Spring Cloud Gateway Server WebMVC for the classic reactive Spring Cloud Gateway, and consider WebFlux for the gateway layer only (keep the domain services on Spring MVC/JDBC — mixing reactive domain logic with JPA/Postgres is a well-known pitfall, see PITFALLS.md).
- Because reactive programming is a genuine differentiator some Pleno/Senior postings ask about, but forcing it into every service multiplies debugging complexity for no clear demo value.
- Run one Postgres container per SQL-backed service in docker-compose (4 containers) instead of one shared instance with 4 schemas.
- Because it's a stronger, more literal illustration of the microservices data-ownership principle for an interviewer skimming the docker-compose file — at the cost of local resource usage, which is a non-issue on a modern dev laptop.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|------------------|-------|
| Spring Boot 3.5.x | Spring Cloud 2025.0.x ("Northfields") | Confirmed compatibility per Spring's own release announcement; do not mix Spring Cloud 2023.0.x/2024.0.x trains with Boot 3.5 |
| Spring Boot 3.5.x | Spring Cloud AWS 3.3.x–3.4.x | 3.4.x is reported as the last Spring Cloud AWS line compatible with Boot 3.5.x; always check awspring.io's compatibility matrix before bumping either dependency |
| Spring Boot 3.5.x | Java 17 minimum, Java 21 recommended | Boot 3.x's floor is Java 17; Java 21 unlocks virtual threads used by Spring Cloud Gateway Server WebMVC |
| Lombok 1.18.34+ | MapStruct 1.6.3 + lombok-mapstruct-binding 0.2.0 | Annotation processor order matters: lombok → mapstruct-processor → lombok-mapstruct-binding, in that order in the `pom.xml` annotation processor paths |
| Testcontainers 1.20.x+ | Docker Engine (any recent version with Compose V2) | Requires a working Docker daemon on the CI runner; `ubuntu-latest` GitHub Actions runners ship this out of the box |
| LocalStack image ≥ 2026.03.0 | Requires `LOCALSTACK_AUTH_TOKEN` env var | Older pinned image tags predating March 2026 may still run without a token but are increasingly stale/unsupported; do not rely on this as a long-term workaround |

## Sources

- websearch: "Spring Boot 3.5 latest stable version 2025 Java 17 21 LTS requirement" — confidence MEDIUM (cross-referenced Spring docs system-requirements, HeroDevs EOL tracker, Spring Boot 4.0 announcement)
- websearch: "Spring Cloud Gateway vs Spring Cloud Gateway Server MVC 2025" — confidence MEDIUM (Spring official docs + Codevup migration guide)
- websearch: "Testcontainers Java latest version LocalStack module SQS DynamoDB PostgreSQL" — confidence LOW (exact patch version not confirmed; testcontainers.com + GitHub releases referenced, pin exact version at implementation time)
- websearch: "LocalStack docker-compose SQS DynamoDB S3 setup 2025" — confidence MEDIUM
- websearch: "LocalStack auth token required 2026 free tier community edition" — confidence MEDIUM (LocalStack's own blog posts: "2026 Upcoming Pricing Changes," "LocalStack for AWS 2026.03.0 Release," "The Road Ahead for LocalStack" — treat as time-sensitive, re-verify against localstack.cloud/pricing before implementation)
- websearch: "Spring Cloud AWS 3.x SQS Spring Boot 3 integration latest version" + "Spring Cloud AWS 3.4 compatible Spring Boot 3.5" — confidence MEDIUM (awspring.io docs + GitHub discussions)
- websearch: "Resilience4j Spring Boot 3 latest version 2025 circuit breaker retry" — confidence MEDIUM
- websearch: "saga pattern orchestration vs choreography Spring Boot microservices SQS example" — confidence MEDIUM (microservices.io canonical pattern reference + multiple 2025/2026 tutorials)
- websearch: "Spring Security 6 OAuth2 resource server JWT Spring Boot 3 best practice 2025" — confidence MEDIUM (Spring Security official docs + Baeldung)
- websearch: "Spring Cloud Contract vs Pact 2025" — confidence MEDIUM
- websearch: "springdoc-openapi latest version 2025 Spring Boot 3" — confidence MEDIUM (springdoc.org official site)
- websearch: "MapStruct Lombok latest version 2025 Spring Boot 3 compatibility" — confidence MEDIUM
- websearch: "Flyway vs Liquibase 2025 Spring Boot" — confidence MEDIUM (Baeldung + Bytebase comparison)
- websearch: "GitHub Actions Java Maven Spring Boot multi-module microservices CI/CD 2025" — confidence MEDIUM
- websearch: "Java 17 vs 21 vs 25 LTS 2025 virtual threads" — confidence MEDIUM
- websearch: "AWS SDK v2 DynamoDB Enhanced Client Spring Boot latest version" — confidence LOW (exact patch version time-sensitive, cited 2.44.x as of Feb 2026 snapshot; re-verify at implementation time)

<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->

## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->

## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->

## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:

- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->

## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
