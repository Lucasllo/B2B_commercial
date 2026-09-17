# Pitfalls Research

**Domain:** B2B/wholesale order management, Java/Spring Boot microservices, event-driven saga, portfolio project
**Researched:** 2026-09-16
**Confidence:** MEDIUM (community sources + official LocalStack/AWS issue trackers, cross-checked across multiple independent sources; no HIGH-confidence curated/vendor docs consulted directly for saga-specific advice)

## Critical Pitfalls

### Pitfall 1: Saga rollback treated like a database transaction rollback

**What goes wrong:**
The developer assumes that if the inventory reservation step fails, the system can just "roll back" the order the way a `@Transactional` method rolls back on exception. In a saga, there is no automatic rollback — each prior step already committed its own local transaction. If order-service marks the order CONFIRMED and *then* the async reservation fails, the order is left in an inconsistent state unless order-service explicitly listens for a compensation event and explicitly transitions the order back to CANCELLED (or a new REJECTED_STOCK state) and reverses any side effects already triggered (e.g., a notification already sent, a credit hold already placed).

**Why it happens:**
ACID intuition from single-database CRUD apps doesn't transfer to cross-service flows. It's tempting to write the "happy path" (reserve succeeds → confirm order) and treat the failure path as an afterthought, especially under time pressure on a solo project.

**How to avoid:**
- Model the saga explicitly as a state machine with a compensating transition for every forward transition, not just a try/catch.
- Define the failure event contract (`StockReservationFailedEvent`) with the same rigor as the success contract before writing order-service code — decide up front what order-service does with it (status, notification, any credit-limit hold release).
- Never mark an order CONFIRMED until the reservation success event is received; keep it in an explicit intermediate state (e.g., `AWAITING_STOCK_RESERVATION`) while the saga is in flight.
- Write the compensation path test *before* the happy path test (TDD forces the failure case to be a first-class citizen, not an afterthought).

**Warning signs:**
- Order status diagram has no arrow leading out of a "waiting" state back to CANCELLED/REJECTED.
- No SQS listener/handler exists for a failure or rejection event type from inventory-service — only the success event is consumed.
- Manual test: kill inventory-service or force it to return "insufficient stock" mid-flow, then check if the order is stuck forever in a pending state.

**Phase to address:**
Phase where order-service + inventory-service saga integration is built (the core value flow). This is the single most important correctness property of the entire project — it should be its own phase with explicit UAT for the failure path, not a side effect of the happy-path phase.

---

### Pitfall 2: Dual-write problem between the order's own database and the outbound event

**What goes wrong:**
Order-service writes the order to PostgreSQL, then separately calls `sqsClient.sendMessage(...)` to publish "OrderCreated"/"ReserveStock" as a second, unrelated operation. If the process crashes or the SQS call fails after the DB commit (or the DB commit fails after the SQS send), the two systems diverge silently: an order exists with no reservation ever requested, or a reservation request goes out for an order that doesn't exist.

**Why it happens:**
It's the natural, simplest way to write the code (`save(order); sqs.send(event);`), and it "works" in every manual test because both calls almost always succeed together in a local dev environment with no real network partition.

**How to avoid:**
- Use the **transactional outbox pattern**: within the same PostgreSQL transaction that saves/updates the order, insert a row into an `outbox_events` table. A separate poller/relay (a scheduled job or Debezium-style process — for this project scope, a simple `@Scheduled` poller is sufficient and easier to explain in an interview) reads unsent outbox rows and publishes them to SQS, marking them sent only after a successful publish.
- This guarantees "the event is published if and only if the DB transaction committed," without requiring distributed transactions (2PC), which are explicitly out of scope for microservices with SQS.
- If full outbox implementation is judged too heavy for early phases, at minimum log this as a **known limitation** in an ADR and document why (time-boxing), rather than silently shipping the dual-write and pretending it's safe.

**Warning signs:**
- Code has a DB save call immediately followed by an unguarded message-publish call with no shared transaction boundary or idempotency safety net.
- No `outbox` table or equivalent exists anywhere in order-service's schema.
- No test simulates "SQS publish throws after DB commit succeeded."

**Phase to address:**
Phase where order-service is first wired to publish events to inventory-service (start of the saga implementation). This is exactly the kind of "why" decision the developer wants explained before implementing — a good candidate for an ADR: "Outbox pattern vs. direct publish" with the dual-write risk as the rationale.

---

### Pitfall 3: Non-idempotent SQS consumers causing duplicate stock reservations or duplicate notifications

**What goes wrong:**
SQS guarantees at-least-once delivery — the same message can be delivered twice (network retry, visibility timeout expiring before the consumer finishes, consumer crash after processing but before deleting the message). If inventory-service's reservation handler isn't idempotent, a duplicated "ReserveStock" message can decrement inventory twice for the same order, causing incorrect stock levels or a false "insufficient stock" rejection. Similarly, notification-service could log duplicate "order shipped" notifications.

**Why it happens:**
Local testing with LocalStack rarely reproduces duplicate delivery because there's no real network partition or load — the pitfall only shows up under retry/timeout conditions the developer doesn't naturally trigger by hand.

**How to avoid:**
- Every consumer handler should key off a unique idempotency identifier (the order ID + event type, or a message/event UUID generated at publish time) and check-before-act: has this specific event already been applied? A unique constraint in PostgreSQL (e.g., `UNIQUE(order_id, event_type)` on a processed-events table, or a `reservation` row keyed by order ID) turns a duplicate into a harmless no-op / constraint violation caught and ignored, rather than a double-decrement.
- For DynamoDB-based notification history, use a deterministic partition/sort key (e.g., `orderId#eventType`) so a duplicate write is an overwrite, not a duplicate row.
- Explicitly write one test that publishes the same event twice and asserts stock is only decremented once.

**Warning signs:**
- Inventory decrement logic is a plain `stock = stock - quantity` with no check for "have I already applied this specific reservation."
- No unique constraint anywhere ties a reservation to its originating order/event.

**Phase to address:**
Same phase as the saga (order-service ↔ inventory-service reservation). Idempotency should be a stated acceptance criterion of that phase's UAT, not a "nice to have."

---

### Pitfall 4: Race condition on inventory reservation (overselling) under concurrent orders

**What goes wrong:**
Two buyer companies order the last unit(s) of the same product at nearly the same time. If inventory-service does a read-check-then-write ("read stock, if stock >= qty then subtract") without a database-level guard, both orders can read the same stock level before either write commits, and both reservations succeed — overselling.

**Why it happens:**
This is invisible in sequential manual testing (one browser tab, one request at a time) and only manifests under concurrent load, which a solo developer testing manually rarely produces. It's also easy to defer because "credit limit approval" and "saga plumbing" feel like the more visibly demo-able concerns.

**How to avoid:**
- Perform the stock check-and-decrement as a single atomic SQL statement (`UPDATE inventory SET reserved = reserved + :qty WHERE product_id = :id AND (available - reserved) >= :qty`) and check the affected-row count, rather than a separate SELECT followed by UPDATE.
- Alternatively use optimistic locking (a `@Version` column on the inventory entity) and retry on `OptimisticLockException`.
- Write a concurrency test using multiple threads (or a Testcontainers-based test firing parallel requests) hitting the same product's limited stock, asserting the total reserved never exceeds available stock.

**Warning signs:**
- Inventory reservation code reads the entity, checks a field in Java, then saves — two separate steps with a gap.
- No `@Version` field or atomic conditional UPDATE anywhere in the inventory persistence layer.

**Phase to address:**
The inventory-service phase (stock management), verified again when the saga integration phase exercises it under the order flow.

---

### Pitfall 5: Credit-limit approval decision made on stale or non-atomic data

**What goes wrong:**
The order-approval rule ("above credit limit → PENDING_APPROVAL, below → auto-confirm") reads the buyer company's current outstanding balance/credit usage, compares it to the limit, and decides — but if two orders from the same buyer are created concurrently, both can read the same "current usage" before either order is committed, both passing the check when only one should have. Over time, this silently lets a buyer exceed its credit limit despite the business rule apparently being enforced.

**Why it happens:**
Credit-limit logic looks like a simple `if (currentUsage + orderTotal > creditLimit)` check, and it's easy to treat it as a stateless calculation rather than a concurrency-sensitive read-modify-write against the buyer's credit usage.

**How to avoid:**
- Treat "credit usage" as a value that's updated atomically alongside order creation (in the same DB transaction, with row-level locking on the buyer/company record — `SELECT ... FOR UPDATE` or optimistic locking) rather than a value computed by summing orders on the fly at check time.
- Decide explicitly (and document in an ADR) whether credit usage is a maintained running total (faster, needs careful update discipline) or computed on demand from order history (simpler, but must scan consistently within a transaction with correct isolation level).
- This is a good candidate to explicitly scope down for the portfolio: document the simplification chosen (e.g., "single-row locking, no distributed credit ledger") and why it's acceptable at this scale.

**Warning signs:**
- Credit check queries all of a buyer's orders and sums totals in application code without a surrounding transaction/lock.
- No test creates two orders from the same buyer concurrently near the credit limit boundary.

**Phase to address:**
The order-approval phase (credit limit check), before saga/inventory integration is layered on top.

---

### Pitfall 6: LocalStack behavior silently diverging from real AWS (false confidence from local tests)

**What goes wrong:**
Tests pass reliably against LocalStack, giving false confidence that the SQS/DynamoDB integration is correct, when LocalStack's emulation differs from real AWS in specific documented ways: DynamoDB consumed-capacity numbers differ, some PartiQL operators behave differently, IAM/permissions aren't fully emulated (so a misconfigured IAM policy would "work" locally but fail in real AWS), SQS dead-letter-queue redrive on a zero visibility timeout only takes effect on the *next* `ReceiveMessage` call rather than immediately, and `ChangeMessageVisibility` has had reported inconsistencies. S3 (if used) requires path-style URLs, unlike real AWS's virtual-hosted style.

**Why it happens:**
LocalStack is explicitly a simulation, not a re-implementation of AWS's internals; some behaviors are approximated. Because the project has no live-AWS deployment target, there's no natural point where these gaps would surface — they only would if the developer later deploys to real AWS or an interviewer asks "would this work on production AWS?"

**How to avoid:**
- Don't rely on IAM policies configured in LocalStack as proof of correct security posture — treat IAM/permission design as a separate documented concern (ADR) validated by reading policy JSON, not by "it worked against LocalStack."
- Design consumer logic around the *documented* SQS semantics (at-least-once delivery, visibility timeout, redrive policy) rather than around whatever LocalStack happens to do in a specific version — write the idempotency and retry logic assuming real AWS semantics, and treat any LocalStack-specific quirk as a workaround confined to test setup, never baked into production code.
- Pin the LocalStack Docker image version in docker-compose and note it in an ADR, since behavior has changed between versions (e.g., DynamoDB table-listing timing changed between 1.2 and 1.4/2.0).
- In the README/ADR, be explicit and honest that this is a portfolio simplification: "AWS integration is demonstrated via LocalStack; behavior parity with real AWS is not guaranteed for all edge cases (see LocalStack limitations)." This preempts an interviewer's "did you know LocalStack doesn't fully replicate X" gotcha — it shows awareness rather than blind trust.

**Warning signs:**
- No documentation anywhere acknowledges LocalStack is an approximation.
- docker-compose.yml doesn't pin a LocalStack image tag (uses `latest`), so behavior can silently shift between runs.

**Phase to address:**
The infrastructure/docker-compose setup phase (when LocalStack is first introduced), and revisited in the AWS integration ADR.

---

### Pitfall 7: Confusing unit-test mocks with real integration confidence

**What goes wrong:**
The project plan calls for JUnit+Mockito (unit), Testcontainers (integration), and contract/E2E tests — but it's common to write extensive Mockito-based tests that assert "service X calls repository Y with argument Z" and consider the service "well tested," while the actual serialization/deserialization of SQS message bodies, the real PostgreSQL schema constraints, or the real DynamoDB item shape are never exercised. Tests pass, but the first time two real services talk to each other (or a real Postgres constraint rejects a row Mockito never modeled), it breaks.

**Why it happens:**
Mockito tests are fast and easy to write, and it's satisfying to see high line/branch coverage. Testcontainers tests are slower and require more setup (Docker, LocalStack modules, waiting for containers), so there's a natural gravitational pull toward over-investing in mocks and under-investing in the more valuable but more effortful integration layer — especially attractive to skip late in a solo timeline.

**How to avoid:**
- Explicitly split test intent: Mockito/unit tests verify *business logic in isolation* (credit-limit math, saga state transitions in a state-machine class); Testcontainers/integration tests verify *the actual wire format* — real Postgres constraints, real JSON (de)serialization of the SQS event payload, real DynamoDB item shape via LocalStack.
- For the saga specifically, prioritize one true end-to-end Testcontainers-based test that starts order-service and inventory-service (or at least real Postgres + real LocalStack SQS) and drives a full "create order → reserve stock → confirm" cycle, plus one for the failure/compensation path. This single test catches the class of bug that pure mocking cannot.
- Budget test-writing time so integration/E2E tests for the saga are written in the same phase as the saga code, not deferred to a later "testing phase" that may get cut for time.

**Warning signs:**
- Order-service and inventory-service have never actually exchanged a real SQS message outside of Mockito-mocked `SqsClient` calls.
- No Testcontainers-based test spins up LocalStack and a real queue for the reservation flow.

**Phase to address:**
Testing should be embedded per phase (not deferred to a final "testing phase"), but the saga integration phase specifically needs a Testcontainers-based E2E test as an explicit deliverable/acceptance criterion.

---

### Pitfall 8: Scope creep into a "distributed monolith" that's unfinishable solo

**What goes wrong:**
Six independently deployable services (auth, catalog, inventory, order, notification, gateway) plus SQS, two databases (Postgres + DynamoDB), Testcontainers, contract testing, and CI/CD is already a substantial surface area for one developer. The common failure mode is starting all services in parallel, adding "just one more" cross-cutting concern (distributed tracing, config server, service discovery, circuit breakers, API versioning) before any single vertical slice works end-to-end, and ending up with five half-finished services and no working demo — the worst possible outcome for a portfolio project, since an unfinished ambitious project demonstrates less than a finished modest one.

**Why it happens:**
Each service and pattern is individually "correct" microservices practice and each is genuinely useful for an interview talking point, so it's tempting to add all of them. Without a forcing function (a working E2E demo early), there's no natural signal that scope has outgrown solo capacity until very late.

**How to avoid:**
- Sequence phases so a **thin vertical slice** (one buyer creates one order for one product, no auth yet, hardcoded credit limit, single service or two at most) works end-to-end and is demoable before any additional service or cross-cutting concern is added. This validates the riskiest architectural bet (the saga) first, while it's still cheap to fix.
- Explicitly defer infrastructure sophistication that doesn't serve the stated Core Value: service discovery/config server (docker-compose static config is fine), circuit breakers/resilience4j (nice-to-have, not core — the failing saga path is more important to demonstrate than retry backoff tuning), distributed tracing (valuable for interviews, but a good candidate for a later phase once the core flow is solid, not a blocker).
- Treat the "Out of Scope" list in PROJECT.md as a living document — if a new idea shows up mid-build ("what if I also add a recommendation engine"), the default answer is no unless it strengthens the Core Value (saga-based order flow), and it should be captured for a future milestone rather than pulled into this one.
- Time-box each service's "done" definition tightly: catalog-service and inventory-service, in particular, only need the endpoints order-service actually calls — resist building generic CRUD admin APIs for every conceivable field.

**Warning signs:**
- More than one service is "in progress" at the same time with none fully working.
- No end-to-end demo (even a rough one) exists after the first 2-3 phases.
- Backlog/idea list is growing faster than the Active requirements list is shrinking.

**Phase to address:**
Roadmap-level concern — the roadmap should sequence phases as "walking skeleton first" (minimal auth stub or none → catalog+inventory minimal → order+saga core value → approval/credit-limit → notification/DynamoDB → hardening/CI polish), not "one service fully built at a time" or "all services scaffolded before any works end-to-end."

---

### Pitfall 9: API Gateway and auth treated as trivial, built last, and blocking everything

**What goes wrong:**
Because auth-service and the API Gateway are "infrastructure," they're sometimes deferred to the end ("I'll add security once the business logic works") or, conversely, over-built first with full OAuth2/JWT refresh-token rotation before any business flow exists. Either extreme costs solo time: deferring auth means retrofitting role checks (BUYER vs SELLER_ADMIN) into every existing endpoint later, which touches every service; over-building it first means spending scarce early time on the least differentiating part of the portfolio (auth is table-stakes, not what will impress a Java Pleno interviewer) before the actual saga/architecture story exists.

**Why it happens:**
Auth feels foundational, so it's psychologically tempting to either "get it out of the way first" (over-investing) or "hack around it for now" (under-investing) — both are scope-management failures.

**How to avoid:**
- Build a minimal JWT auth (issue token with role claim, validate signature + role in a shared filter/gateway) early enough that every other service can assume "the caller is already authenticated with a known role" from day one, but resist adding refresh tokens, password reset flows, OAuth2/social login, or fine-grained permissions beyond BUYER/SELLER_ADMIN — those add no architectural value to the saga story and are effort sinks.
- Centralize role/JWT validation at the API Gateway where possible so individual services don't each reimplement token parsing — this is also a legitimate, explainable architectural decision for interviews ("why did you put auth validation at the gateway").

**Warning signs:**
- Endpoints exist with no role check at all past the midpoint of the project.
- Time spent on auth exceeds time spent on the saga/order-flow code.

**Phase to address:**
An early "walking skeleton" phase — minimal JWT + role check wired through the gateway — before the order/saga phases, but scoped tightly (see prevention).

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems (relevant to this project's scale/purpose).

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|-----------------|------------------|
| Direct DB-save + SQS-publish without outbox | Faster to implement, fewer moving parts | Silent data/event divergence on partial failure (Pitfall 2) | Only in an early throwaway spike; must be replaced (or explicitly documented as a known limitation via ADR) before the saga phase is considered "done" |
| Synchronous REST call from order-service to inventory-service instead of async SQS for reservation | Simpler to reason about, no eventual consistency | Contradicts the explicit architectural goal of demonstrating saga/event-driven orchestration (Core Value); tight coupling | Never for the reservation flow itself — this is the whole point of the portfolio. Acceptable for truly synchronous reads (e.g., catalog price lookup) |
| Skipping optimistic locking / atomic UPDATE on inventory stock | Simpler code, fewer edge cases to think about | Overselling under concurrency (Pitfall 4), invisible until a race-condition test or real concurrent load exposes it | Never — this is cheap to do correctly from the start and expensive to retrofit once inventory logic is used everywhere |
| Testing only with Mockito, skipping Testcontainers for the saga | Faster test suite, less Docker setup pain | False confidence; real serialization/schema bugs surface only in production-like conditions (Pitfall 7) | Acceptable for peripheral CRUD endpoints (e.g., catalog product listing); never acceptable for the order↔inventory saga path |
| Using `latest` tag for LocalStack/Postgres images in docker-compose | Always "up to date," less version bookkeeping | Non-reproducible builds; behavior can silently shift between LocalStack versions (Pitfall 6) | Never for a project meant to demonstrate reliable CI/CD — always pin versions |
| Hardcoding credit limit / buyer data via seed script instead of an admin UI | Saves UI-building time | None significant — this is a legitimate, permanent simplification for a portfolio, not debt | Always acceptable; document as an intentional scope decision |

## Integration Gotchas

Common mistakes when connecting to external services relevant to this stack.

| Integration | Common Mistake | Correct Approach |
|-------------|-----------------|-------------------|
| LocalStack SQS | Assuming DLQ redrive and visibility-timeout behavior is instant/identical to real AWS | Design consumers around documented AWS semantics (at-least-once, eventual redrive), not LocalStack's exact local timing; verify redrive behavior explicitly rather than assuming |
| LocalStack DynamoDB | Assuming PartiQL queries and consumed-capacity metrics behave exactly like real AWS | Avoid relying on capacity metrics or advanced PartiQL operators (e.g., `NOT begins_with`) in ways the app logic depends on; stick to well-supported basic operations (`PutItem`, `GetItem`, `Query` with simple key conditions) |
| Testcontainers + LocalStack module | Spinning up a fresh LocalStack container per test class/method, making the suite very slow, or sharing one poorly-isolated container across all tests causing state leakage between tests | Use a shared, reused Testcontainers instance (singleton container pattern) per test run for speed, but ensure each test clears/creates its own queues, tables, and data to avoid cross-test pollution |
| GitHub Actions + Testcontainers/Docker | Assuming Docker-in-Docker "just works" without checking runner Docker socket access, or not caching layers, making CI very slow | GitHub Actions' standard runners include a Docker daemon by default (Docker-from-Docker via the mounted socket) — verify this early in the CI phase with a trivial Testcontainers job, don't discover Docker access issues after building all the tests |
| Inter-service auth propagation | Each service re-validating and re-parsing the JWT independently, with subtly different logic per service | Validate/parse the JWT once at the gateway (or a shared library module) and pass validated claims (buyer ID, role) downstream via headers/context, keeping validation logic in one place |

## Performance Traps

Patterns that work at small scale but fail as usage grows (mostly theoretical for a portfolio's actual traffic, but worth knowing and mentioning in interviews).

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|-----------------|
| Computing credit usage by summing all historical orders per request | Fine with a handful of demo orders | Maintain a running credit-usage total updated transactionally, or add an index/aggregate table | Once order history per buyer grows into the hundreds/thousands — irrelevant for a demo, but worth naming as a known scaling limitation in the README |
| Polling-based outbox relay with a short fixed interval | Fine for a few orders/minute in a demo | Acceptable at this scale; note that at higher throughput a CDC-based relay (e.g., Debezium) would replace polling | Effectively never at portfolio-demo scale; mention as a "next step" in docs, don't build it |
| Single shared LocalStack/Postgres container reused across all Testcontainers test classes without transaction rollback/cleanup between tests | Test suite slows down and becomes flaky as more integration tests are added | Wrap DB tests in transactions that roll back, or truncate/reset relevant tables between tests | Becomes noticeable once the test suite grows past a few dozen integration tests |

## Security Mistakes

Domain-specific security issues beyond general web security, relevant to B2B multi-tenant-like buyer data.

| Mistake | Risk | Prevention |
|---------|------|------------|
| Not scoping buyer-company data access (a BUYER user from Company A can query/view Company B's orders by guessing an order ID) | Cross-tenant data leakage — a realistic and interview-relevant B2B concern | Every order/catalog query for a BUYER role must be filtered by the authenticated user's company ID at the query level, not just hidden in the UI; write a test asserting Company A cannot fetch Company B's order by ID |
| Trusting the JWT role/company claims without validating signature and expiry in every service that reads them directly | Forged tokens or stale/role-elevated tokens accepted | Centralize JWT signature/expiry validation (ideally at the gateway) using a well-tested library (Spring Security + `jjwt` or Spring's OAuth2 resource server support), never hand-roll parsing |
| Storing the SELLER_ADMIN approval action without an audit trail (who approved/rejected, when) | No accountability trail for a business-critical action (credit/approval decisions) | Persist approver ID + timestamp + decision reason on the order/approval record — this is also a good, cheap interview talking point about auditability |
| LocalStack credentials/test AWS keys accidentally resembling real-looking secrets committed to git | Low real risk (LocalStack keys are fake), but sets a bad habit and can trigger false-positive secret-scanning noise, or worse, real credentials get mixed into the same `.env` file by habit | Keep LocalStack's dummy credentials clearly fake (e.g., `test`/`test`) and separate from any file structure that could later hold real AWS credentials; never commit `.env` files with real keys, add `.env` to `.gitignore` from the start |

## UX Pitfalls

This project is API/architecture-focused (portfolio for backend role), so UX is secondary, but worth naming for the API "UX" (DX) that recruiters/interviewers will experience directly.

| Pitfall | User Impact | Better Approach |
|---------|-------------|-------------------|
| Order status field exposed as opaque internal enum values with no documented meaning | Whoever demos/reviews the API (interviewer) can't understand the flow without reading source code | Document the full order status state machine (diagram) in the README/ADR, and return clear status values in API responses |
| No seed/demo data script | Reviewer has to manually create buyers, products, and credit limits before seeing anything work, which is friction that reduces the chance a busy interviewer actually runs it | Provide a one-command seed script (or Flyway/Liquibase seed migration) that populates realistic demo data (a seller, a few buyer companies with different credit limits, a small catalog) so `docker-compose up` + one script produces a demoable state immediately |
| No clear "how to see the saga in action" walkthrough | The most important architectural feature (the saga) is invisible unless someone knows to watch logs/queues during a specific request | README should include a concrete step-by-step ("create this order via curl/Postman, watch these three service logs / this DynamoDB table update") that makes the saga observable |

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Saga reservation flow:** Often missing the failure/compensation path entirely — verify by forcing an "insufficient stock" response and confirming the order transitions to CANCELLED/REJECTED, not stuck in an intermediate state
- [ ] **Idempotent consumers:** Often missing duplicate-delivery handling — verify by manually re-publishing the same event twice and checking stock/notification state doesn't double-apply
- [ ] **Credit-limit approval:** Often missing concurrency safety — verify with a test that fires two near-simultaneous orders from the same buyer near the credit limit boundary
- [ ] **Cross-tenant data isolation:** Often missing company-scoped filtering — verify a BUYER from Company A cannot fetch Company B's order/catalog data by direct ID
- [ ] **CI pipeline:** Often "green" only because integration tests are skipped/mocked in CI — verify Testcontainers-based tests actually run in GitHub Actions (not just locally) and that LocalStack-backed tests execute in the pipeline, not just Postgres ones
- [ ] **docker-compose full-stack startup:** Often only tested with services started one at a time during development — verify a genuine `docker-compose up` from a clean state (no pre-existing volumes) brings up all services healthy and the demo flow works, since startup-order/health-check dependencies are easy to miss
- [ ] **ADRs:** Often written after the fact as documentation theater — verify each ADR actually explains a "why," including at least one rejected alternative, not just a description of what was built

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|-----------------|------------------|
| Dual-write divergence discovered late (Pitfall 2) | MEDIUM | Introduce the outbox table and relay retroactively; backfill by reconciling existing orders/events manually (acceptable one-time script for a portfolio project, not production-grade but honest to note) |
| Overselling discovered late (Pitfall 4) | LOW | Convert the check-then-write to an atomic conditional UPDATE or add optimistic locking; add the missing concurrency test; no data migration needed since it's a logic fix |
| Discovered late that services were built as a "distributed monolith" (Pitfall 8) | HIGH | Do not attempt to fully re-architect under time pressure; instead, cut scope aggressively to get *one* vertical slice fully working end-to-end and demoable, explicitly documenting the rest as "next steps" in the README rather than leaving them half-broken |
| Missing cross-tenant isolation discovered late (Pitfall 5 security variant) | MEDIUM | Add company-ID filtering at the repository/query layer across affected endpoints; add regression tests per endpoint; this is a mechanical but service-wide fix, budget real time for it |
| LocalStack version drift breaks CI (Pitfall 6) | LOW | Pin the image version in docker-compose/CI config, document the pinned version and why in an ADR |

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|-------------------|----------------|
| Saga rollback treated as automatic (P1) | Order-service ↔ inventory-service saga phase | Forced-failure UAT: insufficient-stock scenario ends in a terminal CANCELLED/REJECTED state, never stuck |
| Dual-write between DB and event publish (P2) | Same saga phase (event publishing introduced) | Test/inspection confirms an event is only ever missing if the DB transaction also didn't commit (outbox table exists and is drained) |
| Non-idempotent consumers (P3) | Same saga phase | Test republishes an identical event twice; stock/notification state changes only once |
| Overselling race condition (P4) | Inventory-service phase (stock management), re-verified in saga phase | Concurrency test with parallel requests against limited stock never exceeds available quantity |
| Credit-limit race condition (P5) | Order-approval / credit-limit phase | Concurrency test with two near-simultaneous orders from the same buyer at the credit boundary |
| LocalStack/AWS behavior drift (P6) | Infrastructure/docker-compose phase (LocalStack introduced) | docker-compose pins LocalStack version; ADR documents known parity gaps |
| Mocks mistaken for integration confidence (P7) | Every phase touching cross-service or cross-store behavior; explicit E2E test required in the saga phase | At least one Testcontainers-based E2E test exists for the full create→reserve→confirm flow and for the failure path |
| Scope creep / distributed monolith (P8) | Roadmap structure itself | After each phase, a working `docker-compose up` demo exists showing incremental, real progress — never multiple simultaneously half-built services |
| Auth over/under-built (P9) | Early walking-skeleton phase | Every subsequent endpoint added already assumes role-checked JWT context from day one; no retrofitting needed |

## Sources

- [A Practical Guide to the Saga Pattern in Spring Boot Microservices — HackerNoon](https://hackernoon.com/a-practical-guide-to-the-saga-pattern-in-spring-boot-microservices)
- [Saga Pattern in Microservices — Baeldung on Computer Science](https://www.baeldung.com/cs/saga-pattern-microservices)
- [Microservices.io — Pattern: Saga](https://microservices.io/patterns/data/saga.html)
- [Building a Reliable Rollback System with SAGA, Event Sourcing and Outbox Patterns — Medium](https://medium.com/@mehhmetoz/building-a-reliable-rollback-system-with-saga-event-sourcing-and-outbox-patterns-0477e713b010)
- [Dual Writes — The Unknown Cause of Data Inconsistencies — Thorben Janssen](https://thorben-janssen.com/dual-writes/)
- [Data Consistency in Microservices: Challenges, Strategies, and Examples — Medium](https://medium.com/@mohanakrishnakavali/data-consistency-in-microservices-challenges-strategies-and-examples-ecd926cd0839)
- [Challenges and solutions for distributed data management — Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/architect-microservice-container-applications/distributed-data-management)
- [LocalStack DynamoDB behavior discrepancy across versions — GitHub Issue #8192](https://github.com/localstack/localstack/issues/8192)
- [DynamoDB ConsumedCapacity discrepancy — GitHub Issue #13090](https://github.com/localstack/localstack/issues/13090)
- [DynamoDB PartiQL NOT begins_with bug — GitHub Issue #11241](https://github.com/localstack/localstack/issues/11241)
- [SQS messages only moved to DLQ on subsequent ReceiveMessage — GitHub Issue #8234](https://github.com/localstack/localstack/issues/8234)
- [SQS ChangeMessageVisibility not working — GitHub Issue #9598](https://github.com/localstack/localstack/issues/9598)
- [SQS MessageRetentionPeriod has no effect — GitHub Issue #3525](https://github.com/localstack/localstack/issues/3525)
- [Amazon SQS At-Least-Once Delivery Explained](https://awsi.click/en/articles/sqs-at-least-once-delivery-internals/)
- [SQS Idempotent Consumer: Process Each Message Once — ADHDecode](https://adhdecode.com/articles/sqs/sqs-idempotent-consumer-pattern/)
- [Testing Spring Boot Microservices with Testcontainers — Medium](https://medium.com/but-it-works-on-my-machine/testing-spring-boot-microservices-with-testcontainers-bd8dc289d581)
- [Testing the Integration Layer of Your Spring Boot Application with Testcontainers and MockServer — Igor Venturelli](https://igventurelli.io/testing-the-integration-layer-of-your-spring-boot-application-with-testcontainers-and-mockserver/)
- [Running Testcontainers Tests Using GitHub Actions and Testcontainers Cloud — Docker Blog](https://www.docker.com/blog/running-testcontainers-tests-using-github-actions/)
- [Stop Over-Engineering: Why Your Side Project Doesn't Need Microservices, Docker, and Kubernetes — DEV Community](https://dev.to/hizba_31d77c41803163b8ff0/stop-over-engineering-why-your-side-project-doesnt-need-microservices-docker-and-kubernetes-3ac8)
- [Why I stopped using Microservices — Robin Wieruch](https://www.robinwieruch.de/microservices-tradeoffs/)
- Confidence tier obtained via project confidence-classification tooling for the `websearch` provider (cross-checked across independent sources): MEDIUM.

---
*Pitfalls research for: B2B/wholesale order management, Java/Spring Boot microservices (OrderFlow)*
*Researched: 2026-09-16*
