# Architecture Research

**Domain:** B2B wholesale order management — Java/Spring Boot microservices, event-driven saga, portfolio project
**Researched:** 2026-09-16
**Confidence:** MEDIUM-HIGH (core patterns are well-established Spring/microservices practice, cross-checked against current web sources; some specifics — e.g. exact Spring Cloud AWS SQS starter API — should be re-verified against official docs at implementation time)

## Standard Architecture

### System Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              CLIENT (Postman/Swagger/curl)                │
└───────────────────────────────────┬────────────────────────────────────--┘
                                     │ HTTPS/REST + JWT
                                     ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    API GATEWAY (Spring Cloud Gateway)                     │
│         single entry point · routes by path prefix · JWT validation       │
└───┬───────────┬────────────────┬────────────────┬─────────────┬─────────-┘
    │ REST       │ REST           │ REST            │ REST         │ REST
    ▼            ▼                ▼                 ▼              │
┌────────┐  ┌──────────┐   ┌──────────────┐  ┌─────────────┐       │
│  auth  │  │ catalog  │   │  inventory   │  │    order    │◄──────┘
│service │  │ service  │   │   service    │  │   service   │
│(Postgres│ │(Postgres) │   │ (Postgres)   │  │ (Postgres)  │
└────────┘  └──────────┘   └──────┬───────┘  └──────┬──────┘
                                   │  async events (SQS via LocalStack)
                                   │◄────────────────┤
                                   │   reserve/release stock commands+events
                                   │                 │
                                   │                 ▼ order status events
                                   │          ┌──────────────────┐
                                   └─────────►│ notification-svc │
                                              │  (DynamoDB via    │
                                              │   LocalStack)      │
                                              └──────────────────┘

Cross-cutting: all services validate JWT issued by auth-service (shared secret/
public key, no runtime call to auth-service per request). Sync calls (REST) are
solid arrows above; async calls (SQS messages) are the inventory/notification
paths.
```

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|------------------------|
| API Gateway | Single entry point, path-based routing, JWT validation at the edge, request logging | Spring Cloud Gateway, `RouteLocator` or `application.yml` route definitions pointing at docker-compose service DNS names |
| auth-service | User/company accounts, roles (BUYER, SELLER_ADMIN), JWT issuance and refresh | Spring Boot + Spring Security + Postgres; issues signed JWT (HS256/RS256) that other services verify statelessly |
| catalog-service | Product catalog, pricing, product metadata owned by the seller | Spring Boot + Postgres; simple CRUD + query endpoints, read-heavy |
| inventory-service | Stock levels per product, reservation/release, source of truth for "can we fulfill this order" | Spring Boot + Postgres; exposes reservation as an idempotent operation triggered by SQS messages from order-service, publishes success/failure events back |
| order-service | Order lifecycle state machine, saga orchestration, credit-limit check, shipping/carrier attributes | Spring Boot + Postgres; owns the saga orchestrator logic (it is the coordinator), publishes commands to inventory-service and consumes result events |
| notification-service | Durable notification history, read model for "what happened to this order" | Spring Boot + DynamoDB (via LocalStack) + SQS consumer; purely reactive, consumes order/inventory lifecycle events, no REST writes from other services |
| LocalStack | Emulates AWS SQS + DynamoDB (+ S3 if needed) locally | Docker container in docker-compose, all services point AWS SDK endpoints at it |
| Postgres (x4) | Transactional stores for auth, catalog, inventory, order | One Postgres instance/container per service (or one container with multiple databases) — database-per-service is the point being demonstrated |

## Recommended Project Structure

```
orderflow/
├── docker-compose.yml            # orchestrates all services + postgres + localstack
├── api-gateway/                  # Spring Cloud Gateway app
│   └── src/main/java/.../gateway/
├── auth-service/
│   └── src/main/java/.../auth/
│       ├── controller/           # REST endpoints (login, register, refresh)
│       ├── service/               # business logic (JWT issuance, password hashing)
│       ├── repository/            # Spring Data JPA repos
│       ├── domain/ (or model/)    # JPA entities: User, Role, Company
│       ├── security/              # JWT filter, security config
│       └── config/
├── catalog-service/
│   └── src/main/java/.../catalog/  # same layered structure: controller/service/repository/domain
├── inventory-service/
│   └── src/main/java/.../inventory/
│       ├── controller/ service/ repository/ domain/
│       ├── messaging/              # SQS listener (reserve/release commands), SQS publisher (result events)
│       └── config/                 # AWS SDK / Spring Cloud AWS config
├── order-service/
│   └── src/main/java/.../order/
│       ├── controller/ service/ repository/ domain/
│       ├── saga/                   # OrderSagaOrchestrator, saga state, compensation logic
│       ├── messaging/              # publishes reservation commands, consumes inventory result events
│       └── config/
├── notification-service/
│   └── src/main/java/.../notification/
│       ├── messaging/              # SQS listener for order/inventory lifecycle events
│       ├── repository/             # DynamoDB repository (Spring Data DynamoDB or AWS SDK directly)
│       └── domain/                 # NotificationRecord
├── common/ (optional shared library, use sparingly)
│   └── events/                     # shared event DTOs (OrderCreatedEvent, StockReservedEvent, etc.) — a JAR published to a local repo or a git submodule
└── .planning/, .github/workflows/, docs/adr/
```

### Structure Rationale

- **Per-service Maven module, not a single monorepo module:** each service is independently buildable/deployable/testable — the whole point of demonstrating microservices, not a modular monolith. Each has its own `pom.xml`, own Spring Boot app, own Dockerfile.
- **`common/events` shared library — use with restriction:** sharing event DTOs avoids duplicating class definitions across producer/consumer, but it is also the most common way portfolio microservices projects accidentally reintroduce coupling (a shared library becomes a hidden shared codebase). Keep this module to *pure data* (event payload records/DTOs + maybe a marker interface), never business logic. Alternative if you want to demonstrate looser coupling: duplicate the event schema per service and treat the message payload as a contract (JSON schema or an OpenAPI/AsyncAPI-style doc), skipping the shared JAR entirely — this is more work but is the "purer" microservices answer, and it is fine to note this as an explicit tradeoff in an ADR either way.
- **`saga/` folder inside order-service:** since orchestration is chosen (see Pattern 1 below), the orchestrator lives inside order-service rather than a standalone service — no need for a separate "saga-orchestrator" microservice at this scale.
- **Layered internals (controller/service/repository/domain) per service:** standard Spring Boot convention, keeps each service itself simple and idiomatic — the architectural interest is at the service-boundary level, not inside each service.

## Architectural Patterns

### Pattern 1: Saga Orchestration (not choreography)

**What:** order-service acts as the saga coordinator. On order confirmation it explicitly sends a command ("reserve stock for order X") to inventory-service via SQS, then reacts to the result event (`StockReserved` or `StockReservationFailed`) to either advance the order to CONFIRMED or roll it back to CANCELLED.

**When to use:** Recommended here because the saga is small (2 real participants: order + inventory; notification is a pure listener, not a saga participant) and because orchestration is dramatically easier to reason about, test, and — critically for a portfolio piece — **explain in an interview**. Choreography (services independently reacting to each other's events with no coordinator) scales better across many teams/services but produces an implicit process that's hard to trace end-to-end; with 2 participants, that cost buys nothing here.

**Trade-offs:** Orchestration adds a small amount of coordination logic and a state machine inside order-service (this is desirable — it *is* the thing the project exists to demonstrate). The downside is a mild coupling: order-service needs to know about inventory-service's command/event shape. That's acceptable and is normal for orchestrated sagas.

**Example (conceptual flow, not final code):**
```java
// order-service: OrderSagaOrchestrator
// 1. Order created, credit check passes → order set to CONFIRMED-pending
// 2. Publish command to SQS "inventory-reserve-queue"
sqsTemplate.send("inventory-reserve-queue",
    new ReserveStockCommand(orderId, orderLines));

// 3. Order stays in a transient state (e.g. RESERVING_STOCK) until a result event arrives

// inventory-service: SQS @SqsListener consumes ReserveStockCommand,
// attempts reservation, publishes StockReserved or StockReservationFailed
// back to "order-events-queue"

// order-service: @SqsListener on "order-events-queue"
@SqsListener("order-events-queue")
void onInventoryResult(InventoryResultEvent event) {
    if (event instanceof StockReserved r) {
        orderService.confirm(r.orderId());
    } else if (event instanceof StockReservationFailed f) {
        orderService.cancel(f.orderId(), f.reason()); // compensating action
    }
}
```

### Pattern 2: Transactional Outbox (recommended, especially for order-service and inventory-service)

**What:** Instead of writing to Postgres and then calling `sqsTemplate.send(...)` as two separate operations (which can fail independently — the classic dual-write problem), the service writes the business change **and** an outbox row in the same DB transaction. A separate poller (a simple `@Scheduled` method reading unsent outbox rows) or a CDC tool publishes the event afterward.

**When to use:** Any place a DB write and an event publish must be atomic — order-service publishing `ReserveStockCommand` after confirming an order, and inventory-service publishing `StockReserved`/`StockReservationFailed` after updating stock. This is the single most important reliability pattern for this project's saga to actually be correct rather than "correct on the happy path."

**Trade-offs:** Adds an outbox table + a lightweight polling publisher per service (Debezium/CDC is overkill for this scale — a `@Scheduled` poller every 1-2 seconds is entirely sufficient and much simpler to explain/build). Consumers must be idempotent regardless (at-least-once delivery), which the project should demonstrate anyway (e.g. dedupe on `orderId` + event type, or a processed-events table).

**Example:**
```java
@Transactional
public void confirmOrderAndRequestReservation(Order order) {
    order.setStatus(OrderStatus.RESERVING_STOCK);
    orderRepository.save(order);
    outboxRepository.save(new OutboxEvent(
        "ReserveStockCommand", toJson(new ReserveStockCommand(order.getId(), order.getLines()))
    ));
    // both writes commit atomically or neither does
}

@Scheduled(fixedDelay = 2000)
public void publishPendingOutboxEvents() {
    outboxRepository.findUnpublished().forEach(evt -> {
        sqsTemplate.send(resolveQueue(evt.getType()), evt.getPayload());
        outboxRepository.markPublished(evt.getId());
    });
}
```

### Pattern 3: Stateless JWT validation at every service (no runtime call to auth-service)

**What:** auth-service issues a signed JWT containing user id, company id, and role. Every other service (and the gateway) verifies the signature and reads claims locally — no service calls auth-service on every request.

**When to use:** Always, for this kind of system. It avoids making auth-service a single point of failure/latency for every downstream call and is the standard microservices auth approach.

**Trade-offs:** Requires distributing the signing key (symmetric) or public key (asymmetric, preferred) to every service via config/env var. Token revocation before expiry is hard (acceptable to skip for a portfolio project — short expiry + refresh token is enough to mention as a known limitation).

## Data Flow

### Order Confirmation + Inventory Reservation Saga (the core flow)

```
BUYER                order-service              inventory-service         notification-service
  │                        │                            │                         │
  │ POST /orders           │                            │                         │
  ├───────────────────────►│                            │                         │
  │                        │ status=CREATED             │                         │
  │                        │ credit check                │                         │
  │                        │──┐                          │                         │
  │                        │  │ over limit? → PENDING_APPROVAL (stop here,         │
  │                        │◄─┘ wait for SELLER_ADMIN approve/reject)              │
  │                        │                            │                         │
  │                        │ (approved, or under limit) │                         │
  │                        │ status=RESERVING_STOCK      │                         │
  │                        │ [outbox: ReserveStockCommand]                        │
  │                        │───── SQS: reserve-queue ──►│                         │
  │                        │                            │ check stock             │
  │                        │                            │ reserve or fail         │
  │                        │                            │ [outbox: result event]  │
  │                        │◄──── SQS: order-events ─────┤                        │
  │                        │                            │───SQS: notif-events────►│
  │  status=CONFIRMED  or  │ if reserved → CONFIRMED     │                         │ writes to
  │  status=CANCELLED      │ if failed   → CANCELLED     │                         │ DynamoDB
  │◄── (poll/GET order) ───┤ (compensating: no charge    │                         │ (notification
  │                        │  since payment is mocked/   │                         │  history record)
  │                        │  not yet applied at this    │                         │
  │                        │  stage)                     │                         │
```

Later lifecycle steps (SHIPPED, DELIVERED) are simpler: order-service updates status directly (shipping/carrier are attributes on the order, not a separate service per the confirmed decomposition) and publishes a lifecycle event that notification-service consumes to log history. No saga/compensation needed for these — they are forward-only status transitions.

### Key Data Flows

1. **Synchronous command path (buyer/seller-admin → system):** All writes that need an immediate response (create order, approve/reject, login, catalog CRUD, stock adjustment) go through the API Gateway as REST calls to the owning service. These are simple, fast request/response — no saga involved.
2. **Asynchronous saga path (order confirmation ⇄ inventory reservation):** order-service and inventory-service exchange commands/events over SQS, never a direct REST call between them for this flow. This is the one relationship in the system that must be async, because it is the one place a partial failure (stock unavailable) must trigger a rollback of another service's data.
3. **Fan-out notification path (any service → notification-service):** order-service (and optionally inventory-service) publish lifecycle events to an SQS queue that notification-service alone consumes. notification-service is a pure sink — nothing calls it synchronously, nothing calls out of it. This is intentionally the simplest component to build and a good "first" async integration to prove the SQS/LocalStack plumbing before building the harder saga.
4. **Auth propagation:** JWT issued once by auth-service, then carried as a bearer token through the Gateway to every other service, verified locally everywhere. No service-to-service auth calls.

## Scaling Considerations

This is a portfolio project, not a production system under load — scaling is not the point being demonstrated. Still, worth noting for completeness:

| Scale | Architecture Adjustments |
|-------|--------------------------|
| Portfolio/demo (docker-compose, 1 instance each) | Current design is appropriate as-is. No changes needed. |
| If asked "how would this scale" in an interview | Add a real service registry (Consul) + multiple instances behind the Gateway's load balancer; move Postgres to managed instances with read replicas for catalog (read-heavy); move outbox polling to CDC (Debezium) to reduce publish latency and DB polling load at higher event volume. |
| Hypothetical high scale (not to be built) | Split order-service's saga orchestrator into its own lightweight coordination service if multiple sagas per order type emerge; consider Kafka over SQS if ordering guarantees and replay become important. |

### Scaling Priorities

1. **First bottleneck (if this went to production):** the outbox poller's fixed-delay scan interval and single-instance polling — fine for demo volume, would need CDC or multiple poller partitions at real scale.
2. **Second bottleneck:** synchronous credit-limit check and order creation path if catalog/inventory checks are added inline — would push toward caching catalog reads.

## Anti-Patterns

### Anti-Pattern 1: Direct REST call from order-service to inventory-service for reservation

**What people do:** Call `inventoryClient.reserve(orderId, items)` synchronously via REST/Feign because it's simpler to code than messaging.

**Why it's wrong:** It defeats the entire purpose of this project (demonstrating a saga) and creates tight temporal coupling — if inventory-service is briefly unavailable, order creation fails outright rather than being handled as an eventual-consistency rollback. It also removes the interesting failure-handling logic (compensating transaction) that is the "Core Value" of this project per the project brief.

**Do this instead:** Keep the reservation request/response as SQS command + event as described above, even though it is more code than a REST call.

### Anti-Pattern 2: Adding Eureka + Config Server "because real microservices projects have them"

**What people do:** Bolt on Netflix Eureka service discovery and Spring Cloud Config Server by default, since many tutorials include them.

**Why it's wrong:** Eureka is in maintenance mode and solves a problem (dynamic service location across an elastic fleet) that docker-compose's built-in DNS already solves for this scale (services address each other by compose service name, e.g. `http://inventory-service:8080`). Config Server solves a problem (centralized runtime-refreshable config across many services/environments) that 6 services with env-var-driven config don't have. Adding both increases operational surface (2 more services to run, document, and explain) without adding to the demonstrated skill set that matters for the target role — and if asked about it in an interview, "I evaluated it and it wasn't justified at this scale" is a stronger answer than "I added it because tutorials do."

**Do this instead:** Use docker-compose service DNS names for inter-service addresses, and environment variables (with Spring `application.yml` profiles for local/docker/test) for config. Document this as a deliberate ADR decision — it demonstrates judgment, which is exactly what a "Pleno" (mid-level) interview is testing for. If there's spare time near the end of the project, adding Eureka + Gateway discovery client as a bonus phase is reasonable, but it should not gate the core saga/architecture work.

### Anti-Pattern 3: Sharing a mutable domain model / JPA entity across services via a common library

**What people do:** Put `Order`, `Product`, or `User` JPA entities in a shared module so every service can reuse them.

**Why it's wrong:** Recreates the "distributed monolith" — services become coupled to a shared schema/class definition, defeating database-per-service and independent deployability.

**Do this instead:** Each service owns its own persistence model. Share only pure event/message DTOs (see Structure Rationale above), and even then keep them minimal — a data-only record, not a JPA entity.

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| AWS SQS (via LocalStack) | Spring Cloud AWS (`spring-cloud-aws-starter-sqs`) `@SqsListener` for consumers, `SqsTemplate`/`SqsAsyncClient` for producers | Point `spring.cloud.aws.sqs.endpoint` at the LocalStack container's compose DNS name (e.g. `http://localstack:4566`) with dummy credentials/region; identical code path works whether run via plain docker-compose or Testcontainers in integration tests |
| AWS DynamoDB (via LocalStack) | AWS SDK v2 `DynamoDbClient` or a lightweight repository wrapper (Spring Data DynamoDB has rough edges — many teams just use the SDK client directly with a thin repository class) | Only notification-service touches DynamoDB; keep the table schema (single-table, partition key = orderId or notificationId) simple since this is meant to contrast with the relational services, not showcase advanced DynamoDB modeling |
| Carrier/shipping (simulated) | Mock — a fixed/randomized in-process "assign a tracking code" call inside order-service, no real HTTP call | Per project scope, this must not be a real external dependency; a simple `CarrierMockService` component inside order-service is sufficient and still demonstrates the seam where a real integration would go |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| Gateway ↔ all services | Sync REST, path-based routing | Gateway also validates JWT signature before forwarding (fail fast at the edge) |
| auth-service ↔ others | No runtime calls — JWT is self-contained and verified locally by each service | Shared signing key/public key distributed via env var/secret |
| order-service ↔ catalog-service | Sync REST (read product/pricing when building an order) | Read-only, simple client call; no saga needed since catalog data is immutable-ish at order time |
| order-service ↔ inventory-service | Async, SQS commands + result events (the saga) | The one relationship requiring compensation logic |
| order-service / inventory-service → notification-service | Async, SQS fan-out, one-directional | notification-service never talks back to any other service |
| order-service internal: saga orchestrator ↔ order state machine | In-process, same service | Orchestrator mutates order status via the same transactional boundary as the outbox write |

## Build Order (incremental, docker-compose-based, each phase demonstrably working end-to-end)

The guiding constraint: every phase should leave the system in a state where something can be shown running via `docker-compose up` and exercised with a REST call — never a phase that only produces code with nothing observable.

1. **Phase 0 — Skeleton & infra:** docker-compose file wiring up Postgres (one or more instances/databases) + LocalStack, plus a bare API Gateway that returns 200 on a health route. Demonstrates: the whole stack boots together. No business logic yet.
2. **Phase 1 — auth-service (through the Gateway):** Register/login/JWT issuance, roles BUYER/SELLER_ADMIN, Postgres persistence. Route it through the Gateway. Demonstrates: a real request flows Client → Gateway → Service → DB → JWT back. This unlocks every later phase, since every other service needs a valid JWT to test realistically — build it first, not last.
3. **Phase 2 — catalog-service:** Product CRUD, secured by the JWT from Phase 1 (SELLER_ADMIN writes, any authenticated role reads). Demonstrates: a second service behind the same Gateway, protected by the shared auth mechanism — proves the JWT-everywhere pattern generalizes.
4. **Phase 3 — inventory-service (REST only, no messaging yet):** Stock levels CRUD/query, seller sets stock, synchronous "check availability" endpoint. Deliberately build this without SQS first — get the service's own domain logic and Postgres persistence solid before adding async complexity. Demonstrates: a third bounded context, still simple REST/DB.
5. **Phase 4 — notification-service + first async wiring (the "easy" async proof):** Stand up notification-service, DynamoDB via LocalStack, and one real SQS producer/consumer pair — e.g. catalog-service or inventory-service publishes a trivial event ("ProductCreated" or "StockAdjusted") and notification-service records it. This is deliberately the *simplest possible* async integration (one-directional fan-out, no saga, no compensation) — it proves the SQS + LocalStack + DynamoDB plumbing works before the harder saga is attempted. If this phase is shaky, debugging it in isolation is far easier than debugging it as part of the order saga.
6. **Phase 5 — order-service core (CREATE → credit-limit branch → APPROVED/REJECTED), still no saga:** Order creation, credit-limit check against buyer's company, PENDING_APPROVAL/APPROVED/REJECTED transitions, SELLER_ADMIN approve/reject endpoint. Order calls catalog-service synchronously to price/validate lines. No inventory interaction yet — reservation is stubbed or skipped. Demonstrates: the order lifecycle state machine and the sync catalog integration, decoupled from the hardest part (the saga), so this phase is verifiable on its own.
7. **Phase 6 — the saga: order-service ⇄ inventory-service reservation (the core value):** Wire in the transactional outbox in both services, the SQS command/event exchange, the CONFIRMED/CANCELLED outcome, and the compensating rollback path when stock is unavailable. This is the hardest and most important phase — deliberately placed after Phases 1-5 have already de-risked auth, catalog, inventory's own logic, and one working SQS integration, so the only new variable introduced here is the saga/compensation logic itself. Demonstrates: the project's stated Core Value, end-to-end.
8. **Phase 7 — shipping/carrier attributes + SHIPPED/DELIVERED + notification history for the full order lifecycle:** Carrier mock assignment, remaining forward-only status transitions, and wiring order lifecycle events into notification-service (reusing the Phase 4 plumbing). Demonstrates: the complete order lifecycle from CREATED to DELIVERED, fully visible in notification history.
9. **Phase 8 — hardening / cross-cutting concerns:** idempotency on SQS consumers, retry/DLQ behavior, CI/CD pipeline (GitHub Actions building/testing each service), Testcontainers-based integration tests, ADRs written retroactively/refined. Can be interleaved earlier in small increments, but a dedicated pass ensures nothing was skipped.

**Where the API Gateway fits:** build it early (Phase 0/1) as a thin pass-through, not late. Introducing it after several services already exist means retrofitting routes and JWT validation into an already-complex system, and it's low effort to stand up on day one with a single route. Add routes incrementally as each service comes online (Phase 1 adds `/auth/**`, Phase 2 adds `/catalog/**`, etc.) rather than building the whole gateway config in one shot.

**Where service discovery/Config Server fit:** neither is required for any of the above phases — docker-compose DNS + env vars carry the whole build order. If included at all, treat as an optional bonus phase after Phase 8, explicitly framed as "here's how this would evolve toward a more dynamic/cloud-native setup," not as core scope.

## Sources

- [Netflix Eureka in 2025: Is It Time to Say Goodbye?](https://pravesh-sharma.medium.com/netflix-eureka-in-2025-is-it-time-to-say-goodbye-20901d15a287) — MEDIUM confidence (web, cross-checked against known Spring Cloud release-train history)
- [Spring Cloud Netflix to Modern Alternatives: Migration Guide (2026)](https://ankurm.com/spring-cloud-netflix-migration-guide/) — MEDIUM confidence
- [Hiding Services & Runtime Discovery with Spring Cloud Gateway (spring.io)](https://spring.io/blog/2019/07/01/hiding-services-runtime-discovery-with-spring-cloud-gateway/) — MEDIUM-HIGH confidence (official Spring blog)
- [A Practical Guide to the Saga Pattern in Spring Boot Microservices (HackerNoon)](https://hackernoon.com/a-practical-guide-to-the-saga-pattern-in-spring-boot-microservices) — MEDIUM confidence
- [Orchestration Saga Pattern With Spring Boot (Vinsguru)](https://blog.vinsguru.com/orchestration-saga-pattern-with-spring-boot/) — MEDIUM confidence
- [How to Implement the Saga Pattern with SQS and SNS (OneUptime)](https://oneuptime.com/blog/post/2026-02-12-implement-saga-pattern-sqs-sns/view) — MEDIUM confidence
- [Building Reliable Microservices with the Transactional Outbox Pattern in Spring Boot](https://medium.com/@syedharismasood4/building-reliable-microservices-with-the-transactional-outbox-pattern-in-spring-boot-952d96f8b534) — MEDIUM confidence
- [Transactional Outbox Pattern: Guaranteeing Consistency Between PostgreSQL and Kafka with Debezium](https://floriancourouge.com/en/blog/transactional-outbox-pattern-debezium-kafka-postgres) — MEDIUM confidence
- [Centralized Configuration with Spring Cloud Config Server (Thomas Vitale)](https://www.thomasvitale.com/spring-cloud-config-basics/) — MEDIUM-HIGH confidence (recognized Spring community author)
- [Building Cloud-Ready Apps Locally: Spring Boot, AWS, and LocalStack in Action (JetBrains Blog, 2025)](https://blog.jetbrains.com/idea/2025/05/building-cloud-ready-apps-locally-spring-boot-aws-and-localstack-in-action/) — MEDIUM-HIGH confidence
- [Testing AWS service integrations using LocalStack (Testcontainers official guide)](https://testcontainers.com/guides/testing-aws-service-integrations-using-localstack/) — HIGH confidence (official Testcontainers documentation)
- Project context: `.planning/PROJECT.md` (OrderFlow decomposition and constraints, already decided)

---
*Architecture research for: B2B wholesale order management, Java/Spring Boot microservices portfolio project*
*Researched: 2026-09-16*
