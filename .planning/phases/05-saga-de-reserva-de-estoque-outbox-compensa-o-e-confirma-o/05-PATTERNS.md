# Phase 5: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação - Pattern Map

**Mapped:** 2026-09-25
**Files analyzed:** ~28 new/modified files (order-service, inventory-service, shared infra)
**Analogs found:** 24 / 28

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `order-service/.../saga/outbox/OutboxEvent.java` | model | event-driven | `inventory-service/.../stock/dto/StockAdjustedEvent.java` (envelope shape) + new table pattern | role-match |
| `order-service/.../saga/outbox/OutboxEventRepository.java` | model/repository | batch/CRUD | `order-service/.../credit/CompanyCreditLockRepository.java` | exact (native `{h-schema}` lock query) |
| `order-service/.../saga/outbox/OutboxRelay.java` | service | batch/event-driven | `inventory-service/.../stock/messaging/StockEventPublisher.java` (publish call) + `CompanyCreditLocker` (native-query + `@Transactional` shape) | role-match (composed) |
| `inventory-service/.../saga/outbox/OutboxEvent.java` | model | event-driven | same as order-service's `OutboxEvent` (duplicated per D-62) | exact (duplicate) |
| `inventory-service/.../saga/outbox/OutboxEventRepository.java` | model/repository | batch/CRUD | `order-service/.../credit/CompanyCreditLockRepository.java` | exact |
| `inventory-service/.../saga/outbox/OutboxRelay.java` | service | batch/event-driven | order-service's own `OutboxRelay` (duplicated per D-62) | exact (duplicate) |
| `order-service/.../saga/messaging/dto/ReserveStockCommand.java` | model (DTO) | event-driven | `inventory-service/.../stock/dto/StockAdjustedEvent.java` | role-match |
| `order-service/.../saga/messaging/dto/ReleaseStockCommand.java` | model (DTO) | event-driven | `inventory-service/.../stock/dto/StockAdjustedEvent.java` | role-match |
| `order-service/.../saga/messaging/dto/StockReservedEvent.java` / `StockReservationFailedEvent.java` | model (DTO) | event-driven | `inventory-service/.../stock/dto/StockAdjustedEvent.java` | role-match |
| `order-service/.../saga/messaging/ReservationResultListener.java` | controller (listener) | event-driven | `notification-service/.../history/messaging/NotificationEventListener.java` | exact (SqsListener shape) |
| `inventory-service/.../saga/messaging/ReservationCommandListener.java` | controller (listener) | event-driven | `notification-service/.../history/messaging/NotificationEventListener.java` | exact |
| `order-service/.../saga/SagaTimeoutJob.java` | service | batch/event-driven | `OutboxRelay` (`@Scheduled` + `@Transactional` shape) | role-match |
| `order-service/.../config/SqsMessagingConfig.java` (NEW) | config | request-response | `inventory-service/.../config/SqsMessagingConfig.java` | exact |
| `order-service/.../order/OrderStatus.java` (MODIFIED) | model (enum) | CRUD | itself (existing file) | exact |
| `order-service/.../order/Order.java` (MODIFIED — add `confirm`/`cancel`/RESERVING transitions) | model (entity) | CRUD | itself — mirror `approveManually`/`reject` guard style | exact |
| `order-service/.../order/OrderDecisionService.java` (MODIFIED) | service | CRUD | itself + `CompanyCreditLocker.acquire` pattern for lock scope | exact |
| `order-service/.../order/OrderCreationService.java` (MODIFIED) | service | CRUD | itself | exact |
| `order-service/.../order/dto/OrderResponse.java` (MODIFIED) | model (DTO) | request-response | itself | exact |
| `inventory-service/.../stock/InventoryService.java` (MODIFIED — add `reserveAll`) | service | CRUD | itself (`reserve`/`release` methods) | exact (same class, new co-located method) |
| `inventory-service/.../stock/StockReservationRepository.java` (MODIFIED — tombstone queries) | model/repository | CRUD | itself | exact |
| `inventory-service/.../stock/InventoryController.java` (MODIFIED — outbox write instead of `StockEventPublisher`) | controller | request-response | itself | exact |
| `order-service/src/main/resources/db/migration/V2__*.sql` | migration | batch | `order-service/.../V1__init_order_schema.sql` | exact |
| `inventory-service/src/main/resources/db/migration/V2__*.sql` | migration | batch | `inventory-service/.../V1__init_inventory_schema.sql` | exact |
| `localstack-init/ready.d/02-create-order-saga-resources.sh` | config | batch | `localstack-init/ready.d/01-create-notification-resources.sh` | exact |
| `docker-compose.yml` (MODIFIED) | config | — | existing inventory-service/notification-service blocks | exact |
| `order-service/pom.xml` (MODIFIED) | config | — | `inventory-service/pom.xml` | exact |
| `e2e-tests/pom.xml` (NEW module) | config | — | `order-service/pom.xml`/`inventory-service/pom.xml` (failsafe binding) | role-match |
| `e2e-tests/src/test/java/.../OrderReservationSagaE2EIT.java` | test | event-driven/request-response | order-service's `DownstreamStubServer`-based ITs (Phase 4) | role-match |
| `inventory-service/.../stock/messaging/StockEventPublisher.java` (DELETE) | service | event-driven | — | n/a (removal, Pitfall 4) |

## Pattern Assignments

### `order-service/.../saga/outbox/OutboxEvent.java` and `inventory-service/.../saga/outbox/OutboxEvent.java` (model, event-driven)

**Analog:** `inventory-service/.../stock/dto/StockAdjustedEvent.java` (envelope shape convention) — read for `eventType`/`eventId`/`occurredAt` naming only; the entity itself has no existing analog since this is the first outbox table in the repo. Use the shape given in RESEARCH.md Code Examples verbatim as the starting point:

```java
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private String eventType;       // "ReserveStock", "StockReserved", "STOCK_ADJUSTED", "ReleaseStock"
    @Column(nullable = false, columnDefinition = "text")
    private String payload;         // JSON — serialized once at write time
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private OffsetDateTime publishedAt; // null until the relay sends it
}
```
Per D-62, this class and its repository/relay are **duplicated** verbatim (with package-appropriate imports) in both services — no shared module.

---

### `order-service/.../saga/outbox/OutboxEventRepository.java` (and inventory-service's copy)

**Analog:** `order-service/.../credit/CompanyCreditLockRepository.java` (lines 1-33) — exact analog for the native `{h-schema}` marker + locking-query convention.

**Imports pattern:**
```java
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

**Core native-query pattern to copy** (from RESEARCH.md, generalized directly from `CompanyCreditLockRepository`'s `{h-schema}` technique):
```java
@Query(value = "SELECT * FROM {h-schema}outbox_event "
        + "WHERE published_at IS NULL "
        + "ORDER BY created_at ASC "
        + "LIMIT :batchSize "
        + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
List<OutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);
```
Note: `CompanyCreditLockRepository` uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` + JPQL for its single-row lock; the outbox repo instead needs `FOR UPDATE SKIP LOCKED` which JPA's `@Lock` annotation cannot express — hence the native query, same `{h-schema}` marker convention, different locking clause. This is why `{h-schema}outbox_event` (not JPQL) is correct here.

---

### `order-service/.../saga/outbox/OutboxRelay.java` (and inventory-service's copy)

**Analog:** `order-service/.../credit/CompanyCreditLocker.java` (transactional wrapper convention) composed with `inventory-service/.../stock/messaging/StockEventPublisher.java` (SQS send call shape).

**Transaction/scheduling pattern** (from RESEARCH.md Pattern 1, to copy):
```java
@Scheduled(fixedDelayString = "${orderflow.outbox.relay-interval:1000}")
@Transactional
public void relay() {
    List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(batchSize);
    for (OutboxEvent event : batch) {
        sqsTemplate.send(to -> to.queue(resolveQueue(event.getEventType())).payload(event.getPayload()));
        event.markPublished(Instant.now());
    }
}
```

**SQS send call convention** (from `StockEventPublisher.publishStockAdjusted`, lines 44-56) — note the important divergence: `StockEventPublisher` deliberately catches and logs `RuntimeException` on send failure because it runs *after* commit with no compensating mechanism (D-29/D-30 dual-write). `OutboxRelay` must **NOT** copy that catch-and-swallow behavior: it runs the send *inside* the same transaction as `markPublished`, so a send failure should propagate (rolling back `markPublished`, leaving the row unpublished for the next tick) rather than being logged and ignored. Copy the `sqsTemplate.send(to -> to.queue(...).payload(...))` call shape only, not the try/catch.

**Constructor injection convention** (all analogs): plain constructor, no field `@Autowired`, e.g. `StockEventPublisher`'s constructor with `@Value("${...}")` for queue name — reuse this exact style for injecting `SqsTemplate` and any per-event-type queue name resolution.

---

### `order-service/.../saga/messaging/ReservationResultListener.java` and `inventory-service/.../saga/messaging/ReservationCommandListener.java`

**Analog:** `notification-service/.../history/messaging/NotificationEventListener.java` (full file, 47 lines) — exact match for role and data flow.

**Imports pattern (lines 1-9):**
```java
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
```

**Core listener pattern (lines 24-46):**
```java
@Component
public class NotificationEventListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);
    private final NotificationService notificationService;
    private final String queueName;

    public NotificationEventListener(NotificationService notificationService,
                                      @Value("${orderflow.notifications.queue-name}") String queueName) {
        this.notificationService = notificationService;
        this.queueName = queueName;
    }

    @SqsListener("${orderflow.notifications.queue-name}")
    public void onMessage(String payload) {
        try {
            notificationService.record(payload);
        } catch (InvalidNotificationEventException e) {
            log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
        }
    }
}
```

**Error handling divergence to apply (per D-67 and Security Domain V5):** copy the `String payload` + service-layer parsing + discard-on-malformed-input shape exactly, but the *business-failure* branch differs from `NotificationEventListener`'s pure-sink case: a business failure in `ReservationCommandListener` (e.g. `INSUFFICIENT_STOCK`) must still be treated as **successful consumption** (message deleted, D-67) — call the service, catch the business exception, and inside that catch block write the failure outbox event (not just log-and-drop like the notification sink). Only a technical exception (e.g. `DataAccessException` from a DB outage) should propagate uncaught, letting SQS redeliver toward the DLQ (Security Domain / Pitfall table, "Poison-pill SQS message").

---

### `order-service/.../config/SqsMessagingConfig.java` (NEW file for order-service)

**Analog:** `inventory-service/.../config/SqsMessagingConfig.java` (full file, 47 lines) — exact match, order-service currently has no SQS config at all.

**Full pattern to copy (adjust package only):**
```java
@Configuration
public class SqsMessagingConfig {

    @Bean
    public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
        SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
        converter.doNotSendPayloadTypeHeader();
        return converter;
    }

    @Bean
    public SqsAsyncClientCustomizer sqsAsyncClientTimeoutCustomizer() {
        return builder -> builder.overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofSeconds(3))
                .apiCallAttemptTimeout(Duration.ofSeconds(1)));
    }
}
```
Both beans (`doNotSendPayloadTypeHeader` and the timeout customizer) are needed identically in order-service since it now both produces (relay) and consumes (`ReservationResultListener`) SQS messages, exactly like inventory-service does.

---

### `order-service/.../order/OrderStatus.java` (MODIFIED)

**Analog:** itself (existing file, 29 lines) — this is a targeted edit, not a new-analog situation.

**Current pattern (lines 12-28) to extend:**
```java
public enum OrderStatus {
    CREATED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    CONFIRMED,
    CANCELLED,
    SHIPPED,
    DELIVERED;

    public static final Set<OrderStatus> CREDIT_CONSUMING = Set.of(APPROVED, CONFIRMED, SHIPPED, DELIVERED);
}
```
Per D-49/D-52 and RESEARCH.md's Code Examples section: insert `RESERVING` after `APPROVED` (or wherever the CHECK constraint ordering dictates — keep enum declaration order matching the V2 CHECK constraint's enumerated order, mirroring how V1 already keeps the two in lockstep) and add it to `CREDIT_CONSUMING`:
```java
CREATE_D..., APPROVED, RESERVING, REJECTED, CONFIRMED, CANCELLED, SHIPPED, DELIVERED;
public static final Set<OrderStatus> CREDIT_CONSUMING = Set.of(APPROVED, RESERVING, CONFIRMED, SHIPPED, DELIVERED);
```
Preserve the existing javadoc convention of citing the CHECK constraint file and explaining which decisions justify each status set — this repo consistently documents *why* a status is/isn't in a set (see current javadoc lines 22-27).

---

### `order-service/.../order/Order.java` (MODIFIED — add RESERVING transition, confirm/cancel)

**Analog:** itself — copy the existing guard-method style exactly (`approveManually`, `reject`, lines 117-142).

**Guard pattern to replicate for new `confirm(...)`/`cancel(...)`/new-RESERVING-entry methods:**
```java
public void approveManually(String decidedBy, String reason, OffsetDateTime now) {
    requirePendingApproval();
    this.status = OrderStatus.APPROVED;
    this.decidedBy = decidedBy;
    this.decidedAt = now;
    this.reason = blankToNull(reason);
}

private void requirePendingApproval() {
    if (this.status != OrderStatus.PENDING_APPROVAL) {
        throw new OrderNotPendingException();
    }
}
```
Apply the identical shape: a `requireReserving()` private guard (mirroring `requireCreated()`/`requirePendingApproval()`) that throws a dedicated exception (new `OrderNotReservingException`, following the `OrderNotPendingException` naming/exception-per-guard convention in `order-service/.../order/exception/`), and separate columns per D-53 (`cancellation_code`, `cancellation_reason`, `cancelled_at`, `confirmed_at`) set only inside these new guarded methods — never touching `decidedBy/decidedAt/reason`, exactly as D-53 requires and exactly how `reject()` vs `approveManually()` already keep their own fields cleanly separated without cross-writes.

---

### `inventory-service/.../stock/InventoryService.java` (MODIFIED — add `reserveAll`)

**Analog:** itself — `reserve()` (lines 127-159) and `release()` (lines 165-188) are the direct structural analog for the new `reserveAll` method; `RetryConfig` (full file) is the analog for why the annotation ordering matters.

**Imports (already present, lines 1-15):**
```java
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Transactional;
```

**Core pattern to replicate exactly (retry constants + annotation shape, lines 49-52, 127-131):**
```java
private static final int RETRY_MAX_ATTEMPTS = 10;
private static final long RETRY_DELAY_MS = 20;
private static final double RETRY_MULTIPLIER = 2.0;
private static final long RETRY_MAX_DELAY_MS = 200;

@Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
        maxAttempts = RETRY_MAX_ATTEMPTS,
        backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
@Transactional
public ReservationOutcome reserveAll(String reservationId, List<ReservationLine> lines) {
    // direct repository calls only — never inventoryService.reserve(...) in a loop (Pitfall 1)
}

@Recover
public ReservationOutcome recoverReserveAll(DataAccessException ex, String reservationId, List<ReservationLine> lines) {
    throw new ReservationConflictException();
}
```

**Critical constraint (must be stated in the plan, not just implied):** `reserveAll` must be a **new method on this same bean**, never composed by calling the existing public `reserve()` in a loop — see class-level javadoc (lines 20-27) which already documents this exact self-invocation hazard for `reserve`/`release`. `RetryConfig`'s `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)` already covers any new `@Retryable` method on this bean — no config change needed, just correct co-location of `@Retryable` + `@Transactional` on `reserveAll` itself.

**Idempotent-replay-then-write pattern to extend (mirrors `reserve()` lines 132-153):**
```java
var existingReservation = stockReservationRepository.findByProductIdAndReservationId(productId, reservationId);
if (existingReservation.isPresent()) {
    return StockResponse.from(inventory); // idempotent replay, D-11
}
```
`reserveAll` needs this check per-line before any write, plus the new tombstone check (D-66) — no existing analog for tombstone lookup; this is new logic layered onto the existing idempotent-replay shape.

---

### `inventory-service/.../stock/InventoryController.java` (MODIFIED — outbox instead of `StockEventPublisher`)

**Analog:** itself, plus the deleted `StockEventPublisher.publishStockAdjusted` (lines 44-56) as the "what NOT to keep" reference (Pitfall 4). Per D-60, the outbox row for `STOCK_ADJUSTED` must be written **inside** `InventoryService.setStock`'s existing `@Transactional` boundary (same method, one more repository save), not called by the controller after return as `StockEventPublisher` currently is. This is an architecture correction, not a copy — flag explicitly in the plan that `StockEventPublisher` is deleted entirely, not deprecated in place.

---

### Migrations: `order-service/.../V2__*.sql` and `inventory-service/.../V2__*.sql`

**Analog:** `order-service/.../V1__init_order_schema.sql` (CHECK constraint convention) and `inventory-service/.../V1__init_inventory_schema.sql` (full file, 39 lines, FK/CHECK/index conventions).

**Table/constraint conventions to copy from `V1__init_inventory_schema.sql`:**
```sql
CREATE TABLE stock_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES inventory(product_id),
    reservation_id VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    released BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ,
    CONSTRAINT uq_stock_reservations_product_reservation UNIQUE (product_id, reservation_id)
);
CREATE INDEX idx_stock_reservations_product_id ON stock_reservations(product_id);
```
Apply same style (explicit `DEFAULT gen_random_uuid()`, `TIMESTAMPTZ NOT NULL DEFAULT now()`, named `CONSTRAINT`/index prefixes `chk_`/`uq_`/`idx_`) to the new `outbox_event` table in both services, and to widening the `orders` status CHECK constraint (same convention as `V1__init_order_schema.sql` lines 17-19, not shown in full above but referenced in RESEARCH.md line 441 — read that file directly when implementing to preserve exact CHECK syntax).

**Data-backfill convention (D-51):** no existing analog for a data migration in this repo (both V1 files are schema-only) — this is genuinely new; write it as plain SQL `UPDATE`/`INSERT` statements inside the V2 migration file itself (Flyway runs it before app startup), following the comment-density convention already used in both V1 files (explaining *why*, e.g. lines 1-8 of `V1__init_inventory_schema.sql`).

---

### `localstack-init/ready.d/02-create-order-saga-resources.sh`

**Analog:** `localstack-init/ready.d/01-create-notification-resources.sh` (full file, 34 lines) — exact match.

**Full pattern to copy (queue creation + region/credential convention):**
```bash
#!/bin/bash
set -euo pipefail

REGION="us-east-1"
QUEUE_NAME="notification-events-queue"

awslocal --region "$REGION" sqs create-queue --queue-name "$QUEUE_NAME"
```
Extend for two queues + two DLQs (D-61: `inventory-commands-queue`, `order-events-queue`, each with its own DLQ) — the DLQ + redrive-policy creation has no existing analog in this repo (Phase 3 created no DLQ); this is the project's first DLQ, so `RedrivePolicy`/`maxReceiveCount` wiring must be written fresh via `awslocal sqs set-queue-attributes`, following the same `set -euo pipefail` + region/credential header convention as the existing script.

## Shared Patterns

### Outbox relay transaction/locking discipline
**Source:** `order-service/.../credit/CompanyCreditLockRepository.java` (native `{h-schema}` query) + RESEARCH.md Pattern 1
**Apply to:** Both services' `OutboxEventRepository`/`OutboxRelay`
```java
@Query(value = "SELECT * FROM {h-schema}outbox_event WHERE published_at IS NULL "
        + "ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
List<OutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);
```

### Retry-wraps-transaction on the same method (never composed by self-invocation)
**Source:** `inventory-service/.../stock/InventoryService.java` (class javadoc lines 20-27) + `inventory-service/.../config/RetryConfig.java` (full file)
**Apply to:** `InventoryService.reserveAll` — the single most important non-obvious constraint in this phase (RESEARCH.md Pattern 3). Never call the existing public `reserve()`/`release()` in a loop from a new method on the same bean or from within an outer `@Transactional`.

### SqsListener consume-and-emit-failure-event (never swallow business failures silently, never catch technical errors)
**Source:** `notification-service/.../history/messaging/NotificationEventListener.java` (full file)
**Apply to:** `ReservationCommandListener`, `ReservationResultListener` — copy the constructor/`@SqsListener` shape; diverge on the catch block per D-67 (business failure → still delete message, but write an outbox failure event instead of just logging).

### SQS producer/consumer config (payload-type header suppression + client timeout)
**Source:** `inventory-service/.../config/SqsMessagingConfig.java` (full file)
**Apply to:** New `order-service/.../config/SqsMessagingConfig.java` — copy verbatim.

### Constructor injection, no field `@Autowired`
**Source:** every analog above (`CompanyCreditLocker`, `InventoryService`, `NotificationEventListener`, `StockEventPublisher`)
**Apply to:** All new services/listeners/relays in this phase.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| Tombstone lookup/insert logic (D-66) in `StockReservationRepository`/`InventoryService.reserveAll` | model/service | CRUD | First use of tombstone semantics in this repo; RESEARCH.md Pattern 4 provides two design options but no existing code to copy from — planner must pick Option A/B explicitly |
| DLQ + redrive-policy provisioning in `localstack-init` scripts | config | batch | Project's first DLQ (Phase 3 created none); no existing `awslocal sqs set-queue-attributes --attributes RedrivePolicy=...` call anywhere in the repo to copy |
| Data-backfill migration (`UPDATE orders SET status='RESERVING' ...` + outbox insert per legacy row, D-51) | migration | batch | Both existing V1 migrations are schema-only; no prior data-backfill migration exists in this repo |
| `e2e-tests` Maven module scaffold (dual Spring Boot context in one JVM) | test | request-response/event-driven | No prior multi-module dual-context test harness exists; RESEARCH.md flags this as a Wave-0 spike, not a copyable pattern — closest available reference is `DownstreamStubServer`/`AbstractIntegrationTest` from Phase 4's order-service tests, useful for stub conventions only, not for the dual-context bootstrap itself |

## Metadata

**Analog search scope:** `order-service/src/main/java`, `inventory-service/src/main/java`, `notification-service/src/main/java`, `localstack-init/ready.d`, Flyway migrations in `order-service`/`inventory-service`
**Files scanned:** ~35 (full source tree listing via `git ls-files`, ~10 read in full)
**Pattern extraction date:** 2026-09-25
