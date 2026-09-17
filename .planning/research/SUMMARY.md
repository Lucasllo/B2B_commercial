# Project Research Summary

**Project:** OrderFlow
**Domain:** B2B wholesale order management platform (portfolio project for Pleno Java Developer role)
**Researched:** 2026-09-16
**Confidence:** MEDIUM (web-search-derived across multiple independent sources; version numbers should be re-verified at implementation time)

## Executive Summary

OrderFlow is a single-seller, multi-buyer B2B order management platform designed to demonstrate event-driven microservices architecture via a saga pattern — the explicit Core Value of the portfolio project. The recommended approach is a 6-service Java/Spring Boot system (auth, catalog, inventory, order, notification, API Gateway) running locally via docker-compose with PostgreSQL for transactional data, DynamoDB (via LocalStack) for notification history, and SQS (via LocalStack) for async orchestration. The architecture must deliver a complete order flow (create → credit-limit conditional approval → inventory reservation → confirmation → shipment) end-to-end using transactional outbox and compensating transactions, not just happy-path saga logic.

Three critical architectural risks emerged from research: (1) saga rollbacks are not automatic database rollbacks — compensation logic must be explicit, (2) the dual-write problem between database and event publish must be solved via transactional outbox pattern to guarantee consistency, and (3) SQS at-least-once delivery requires idempotent consumers to prevent duplicate stock reservations or notifications. The roadmap must sequence phases to de-risk the saga mechanism early (build auth, catalog, inventory, and a simple notification proof-of-concept before attempting order-service saga orchestration), and explicitly budget testing/UAT for failure paths (not just happy path).

The technology stack is well-established (Java 21, Spring Boot 3.5.x, Spring Cloud 2025.0.x) and carries MEDIUM confidence — primarily validated by cross-checking web sources and official Spring documentation. Feature research identified 13 must-have features for v1 (table stakes: auth, catalog, inventory, order creation, credit-limit approval, saga reservation, full status lifecycle, notification history) and 7 cheap differentiators (idempotency, optimistic concurrency, OpenAPI docs, correlation IDs, DLQ handling, contract tests, observability). LocalStack authentication token requirement (since March 2026) is a critical infrastructure gotcha that must be designed into docker-compose and CI from day one.

## Key Findings

### Recommended Stack

Java 21 (LTS, Temurin distribution) with Spring Boot 3.5.x is the current enterprise baseline as of 2026 and matches the job market this portfolio targets — it ships virtual threads as a modern talking point without forcing adoption of bleeding-edge Java 25. Spring Cloud 2025.0.x ("Northfields") is the compatible release train; mixing Spring Cloud versions with Boot versions is one of the most common and hardest-to-debug microservices setup mistakes. The stack deliberately avoids Spring Boot 4.0 (released Nov 2025), which forces Jackson 3 and Jakarta EE 11 migrations with no architectural benefit for a portfolio project.

**Core technologies:**
- **Java 21 (LTS)** — matches today's Pleno job market, enables virtual threads without requiring experimental Java 25
- **Spring Boot 3.5.x** — mature, last of the 3.x line; avoids 4.0's breaking changes (Jackson 3, Jakarta EE 11) that distract from domain logic
- **Spring Cloud 2025.0.x (Northfields)** — correct release train for Boot 3.5.x; version mismatches are the #1 microservices setup failure mode
- **Spring Cloud Gateway Server WebMVC** — non-reactive gateway (with virtual threads) for the API Gateway; avoids forcing Project Reactor/reactive programming into a portfolio project where the demo value is order/saga logic, not streaming
- **PostgreSQL 16** — transactional store for auth, catalog, inventory, order services; one instance with multiple databases/schemas (database-per-service principle)
- **DynamoDB (LocalStack-emulated)** — NoSQL store for notification-service history; demonstrates SQL+NoSQL usage side-by-side
- **SQS (LocalStack-emulated)** — async messaging backbone for saga orchestration and event fan-out; consumed via Spring Cloud AWS (SqsTemplate, @SqsListener)
- **Testcontainers + LocalStack module** — integration testing without manual test setup; real Postgres, real DynamoDB, real SQS in test containers
- **Transactional Outbox + Spring Cloud Contract** — core reliability and contract-testing patterns for the saga

**Critical infrastructure constraint:** LocalStack since 2026.03.0 requires a LOCALSTACK_AUTH_TOKEN (free "Hobby" tier) — the Docker image no longer accepts anonymous access. This must be designed into docker-compose.yml and GitHub Actions CI from day one.

### Expected Features

The feature research identified a clear MVP scope already in PROJECT.md's Active list, with two important gaps to make explicit during requirements definition: (1) buyer-company as a first-class entity with credit-limit attributes, and (2) order list/detail views (read-side access, not just write-side order creation).

**Must-have (v1, table stakes for credible B2B system):**
- Role-based JWT auth (BUYER, SELLER_ADMIN)
- Seller-managed product catalog with prices
- Real inventory tracking with reservation-on-order
- Credit-limit-based order approval
- Full order status lifecycle
- Saga: order-service reserves stock in inventory-service via SQS, with compensation/cancellation on reservation failure
- Notification history (DynamoDB via SQS)
- Order list/detail views (buyer sees own orders; seller sees all)

**Should-have (v1.x, cheap differentiators):**
- Idempotency keys on SQS consumers
- Optimistic concurrency on inventory reservation
- OpenAPI/Swagger documentation per service
- Correlation-ID propagation across service logs
- Dead-letter queue handling for failed events
- Contract tests between services (Spring Cloud Contract)

### Architecture Approach

The architecture is a 6-service decomposition (auth, catalog, inventory, order, notification, API Gateway) coordinated via **saga orchestration** (not choreography). The order-service owns the saga state machine — when an order is ready to confirm, it publishes a command ("reserve this stock") to SQS, waits for a result event (success or failure) from inventory-service, and either advances to CONFIRMED or rolls back to CANCELLED. Orchestration is deliberately chosen because with only 2 saga participants, it is dramatically easier to reason about, test, and explain in an interview.

The **transactional outbox** is the single most important reliability pattern: whenever order-service or inventory-service writes a business change to PostgreSQL, they write to an outbox_events table in the same transaction. A separate scheduled poller publishes these events to SQS. This solves the dual-write problem where save(order); sqs.send(event) can diverge. JWT validation is stateless at every service (no runtime auth-service calls).

**Major components:**
1. **API Gateway** — single entry point, path-based routing, JWT signature validation at the edge
2. **auth-service** — user/company accounts, JWT issuance with roles (BUYER, SELLER_ADMIN)
3. **catalog-service** — seller-managed product catalog, prices, read-heavy
4. **inventory-service** — stock levels, atomic reservation, consumes SQS commands, publishes events
5. **order-service** — order lifecycle state machine, saga orchestrator, credit-limit approval
6. **notification-service** — pure sink for order/inventory lifecycle events, audit history

### Critical Pitfalls

1. **Saga rollback is not automatic** — If order-service marks an order CONFIRMED and then the async inventory reservation fails, the order is stuck unless order-service explicitly listens for a compensation event and transitions to CANCELLED. Model the saga as an explicit state machine with compensating transitions. Avoidance: Write failure-path tests (TDD) before happy-path tests.

2. **Dual-write problem** — If order-service writes save(order) then sqs.send(event) as two unguarded calls, a crash between them causes divergence. Use the transactional outbox pattern: save order and outbox row in same transaction; separate poller publishes to SQS.

3. **Non-idempotent SQS consumers** — SQS guarantees at-least-once delivery. Same message can arrive twice. Inventory-service's reservation handler must key off order ID + event type and check-before-acting. UNIQUE constraint on (order_id, event_type) makes duplicates harmless no-ops.

4. **Race condition on inventory reservation (overselling)** — Two orders for last unit(s) placed simultaneously can both pass stock check. Use atomic SQL (UPDATE ... WHERE available - reserved >= qty) or optimistic locking (@Version).

5. **LocalStack behavior divergence from real AWS** — DynamoDB operators behave differently, SQS DLQ timing differs. Design around documented AWS semantics, not LocalStack's specific behavior. Pin LocalStack image version; document known gaps in ADR.

## Implications for Roadmap

Suggested phase structure based on research:

### Phase 0: Skeleton + Infrastructure
Validates docker-compose, LocalStack auth token, basic health check. Delivers docker-compose.yml with services stubbed, Postgres + LocalStack running.

### Phase 1: Authentication (Core Dependency)
Unlocks all downstream phases. Register/login, JWT issuance with BUYER/SELLER_ADMIN roles, Gateway JWT validation.

### Phase 2: Catalog Service
Proves JWT propagation generalizes. Product CRUD, pricing, query endpoint.

### Phase 3: Inventory Service (REST only, no messaging)
De-risks saga by proving stock management works separately. Stock CRUD, check availability, atomic SQL for concurrency safety.

### Phase 4: Notification Service + First Async (Simple SQS Fan-Out)
Validates SQS + LocalStack + DynamoDB end-to-end with minimal complexity before attempting saga. One-directional fan-out, no compensation.

### Phase 5: Order Service Core (Order Creation → Credit Approval, No Saga Yet)
Credit-limit branching, approval/rejection, status transitions. Decoupled from inventory integration to prove approval logic before saga complexity.

### Phase 6: Saga Orchestration (Order-Service ↔ Inventory-Service) — CORE VALUE
Transactional outbox, SQS command/event exchange, CONFIRMED/CANCELLED outcomes, compensating rollback. The stated Core Value of the portfolio.

### Phase 7: Shipping Lifecycle + Full Notification Coverage
Remaining status transitions (SHIPPED, DELIVERED), full order lifecycle events to notification-service.

### Phase 8: Cross-Cutting Hardening + CI/CD
Idempotency improvements, DLQ behavior, GitHub Actions pipeline, Testcontainers integration tests, ADRs.

### Phase Ordering Rationale

Auth first (Phase 1) because every downstream service needs JWT validation. Catalog + Inventory as simple CRUD before saga (Phases 2-3) to de-risk domain logic. Notification as simple fan-out before saga (Phase 4) to validate SQS/LocalStack/DynamoDB plumbing. Order-service core before saga (Phase 5) to separate approval logic from saga complexity. Saga as dedicated phase (Phase 6) after all de-risking. Remaining lifecycle (Phase 7) completes features. Hardening (Phase 8) polishes and validates.

### Research Flags

**Phases needing deeper research during planning:**
- **Phase 6 (Saga Orchestration):** Requires in-depth review of Spring Cloud AWS SqsTemplate API, transactional outbox polling strategies, compensating transaction semantics. Cross-check Spring Cloud Contract for producer-side contract tests.
- **Phase 4 (Notification Service):** DynamoDB table design (partition key strategy) and Spring Data DynamoDB vs. raw AWS SDK trade-offs should be validated against current (September 2026) library maturity.

**Phases with standard patterns:**
- **Phase 1 (Authentication):** Spring Security JWT is well-documented.
- **Phase 2-3 (Catalog, Inventory):** Standard Spring Boot CRUD + JPA patterns.
- **Phase 5 (Order Service Core):** Order state machine and ACID credit checks are standard patterns.
- **Phase 7-8 (Lifecycle, Hardening):** Mostly composition of existing patterns.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| **Stack** | MEDIUM | Java 21, Spring Boot 3.5.x, Spring Cloud 2025.0.x validated against current (Sep 2026) Spring release announcements and multiple independent web sources. Version numbers are time-sensitive. LocalStack auth token requirement confirmed via LocalStack official blog. |
| **Features** | MEDIUM | B2B feature set cross-checked across vendor platforms (Orderwerks, OrderEase, Kibo Commerce, Oro Inc., Adobe Commerce B2B). Two gaps identified (buyer-company entity, order list views) but not contradicting existing scope. |
| **Architecture** | MEDIUM-HIGH | Saga orchestration, transactional outbox, microservices decomposition patterns are well-established. Spring Cloud AWS integration carries MEDIUM confidence. Testcontainers patterns are HIGH confidence (official documentation). |
| **Pitfalls** | MEDIUM | Nine pitfalls identified via HackerNoon, Baeldung, microservices.io, LocalStack/AWS GitHub issue trackers. Saga-specific pitfalls confirmed by multiple sources. LocalStack behavior gaps verified via GitHub issues. |

**Overall confidence:** MEDIUM

### Gaps to Address

1. **Spring Cloud AWS SQS API specifics** — Validate exact method signatures and behavior of SqsTemplate and @SqsListener against official awspring.io docs during Phase 6 planning.

2. **DynamoDB table design for notification history** — Deep-dive into query patterns for "list all notifications for a buyer company" and pagination strategy during Phase 4 planning.

3. **LocalStack image version behavior stability** — Pin specific image version during Phase 0 infrastructure setup; verify against latest LocalStack release notes.

4. **GitHub Actions Docker socket access** — Verify standard runners provide Docker-in-Docker via socket during Phase 8 (CI/CD) with a trivial Testcontainers test.

5. **Spring Cloud Contract vs. Pact trade-off** — Review current Spring Cloud Contract docs during Phase 6 to confirm recommendation still holds.

## Sources

### Primary Research Files (This Project)
- .planning/research/STACK.md — Technology stack research, version requirements, rationale, alternatives
- .planning/research/FEATURES.md — Feature landscape, dependency analysis, MVP definition
- .planning/research/ARCHITECTURE.md — System design, component responsibilities, patterns, build order, data flows
- .planning/research/PITFALLS.md — 9 critical pitfalls, technical debt patterns, integration gotchas
- .planning/PROJECT.md — Project scope, decisions, constraints

### Secondary Sources (Web Search, MEDIUM Confidence)
- Spring Framework / Spring Boot official documentation (spring.io)
- Spring Cloud release announcements and compatibility matrix
- Amazon AWS SDK for Java docs (awspring.io)
- Testcontainers official guide
- HackerNoon, Baeldung, microservices.io — saga pattern, outbox pattern articles
- LocalStack GitHub issues — behavior gaps, version history
- Vendor platform surveys (Orderwerks, OrderEase, Mintsoft, Kibo Commerce, Oro Inc., Adobe Commerce)
- OWASP / security references

### Tertiary Sources (LOW Confidence, Requires Verification)
- AWS SDK v2 DynamoDB Enhanced Client API — version 2.44.x may shift; verify at implementation time
- Specific LocalStack image behavior (version 2026.03.0 forward) — re-check against latest release notes before Phase 0

---
*Research synthesis completed: 2026-09-16*
*Synthesized by: Research Synthesizer Agent*
*Ready for roadmap creation: yes*
