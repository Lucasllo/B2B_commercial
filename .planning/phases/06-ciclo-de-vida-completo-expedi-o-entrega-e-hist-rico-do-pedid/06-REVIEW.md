---
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
reviewed: 2026-10-01T00:00:00Z
depth: standard
files_reviewed: 67
files_reviewed_list:
  - docker-compose.yml
  - docs/API.md
  - docs/VISAO-GERAL.md
  - e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java
  - e2e-tests/src/test/java/com/orderflow/e2e/OrderShipmentE2EIT.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ShipStockCommand.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/Inventory.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservation.java
  - inventory-service/src/main/resources/db/migration/V4__stock_reservation_shipped.sql
  - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/ShipStockConsumptionIT.java
  - localstack-init/ready.d/01-create-notification-resources.sh
  - notification-service/src/main/java/com/orderflow/notification/config/GlobalExceptionHandler.java
  - notification-service/src/main/java/com/orderflow/notification/history/dto/NotificationResponse.java
  - notification-service/src/main/java/com/orderflow/notification/history/dto/OrderLifecycleEvent.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationController.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationNotFoundException.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationRecord.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationRepository.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java
  - notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java
  - notification-service/src/test/java/com/orderflow/notification/NotificationEventFlowIT.java
  - notification-service/src/test/java/com/orderflow/notification/NotificationStoreUnavailableIT.java
  - notification-service/src/test/java/com/orderflow/notification/OrderTimelineControllerIT.java
  - notification-service/src/test/java/com/orderflow/notification/OrderTimelineIT.java
  - order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderResponse.java
  - order-service/src/main/java/com/orderflow/order/order/exception/InvalidOrderTransitionException.java
  - order-service/src/main/java/com/orderflow/order/order/Order.java
  - order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderShipmentController.java
  - order-service/src/main/java/com/orderflow/order/order/OrderShipmentService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderStatus.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ShipStockCommand.java
  - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
  - order-service/src/main/java/com/orderflow/order/shipping/CarrierAssignment.java
  - order-service/src/main/java/com/orderflow/order/shipping/CarrierGateway.java
  - order-service/src/main/java/com/orderflow/order/shipping/SimulatedCarrierGateway.java
  - order-service/src/main/java/com/orderflow/order/shipping/TrackingCodes.java
  - order-service/src/main/java/com/orderflow/order/timeline/OrderLifecycleEvent.java
  - order-service/src/main/java/com/orderflow/order/timeline/OrderTimelineEvents.java
  - order-service/src/main/resources/application.yml
  - order-service/src/main/resources/db/migration/V3__order_fulfillment.sql
  - order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java
  - order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderStatusDiagramConsistencyTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderStatusTransitionsTest.java
  - order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java
  - order-service/src/test/java/com/orderflow/order/OrderLifecycleTransitionsIT.java
  - order-service/src/test/java/com/orderflow/order/OrderSagaMigrationIT.java
  - order-service/src/test/java/com/orderflow/order/OrderShipmentIT.java
  - order-service/src/test/java/com/orderflow/order/OrderTimelinePublishingIT.java
  - order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java
  - order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java
  - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java
  - order-service/src/test/java/com/orderflow/order/shipping/SimulatedCarrierGatewayTest.java
  - order-service/src/test/java/com/orderflow/order/shipping/TrackingCodesTest.java
  - order-service/src/test/java/com/orderflow/order/support/LocalStackTestSupport.java
  - order-service/src/test/java/com/orderflow/order/support/NotificationEventsQueue.java
  - README.md
  - scripts/smoke-order-lifecycle.sh
findings:
  critical: 0
  warning: 3
  info: 7
  total: 10
status: issues_found
---

# Phase 6: Code Review Report

**Reviewed:** 2026-10-01
**Depth:** standard
**Files Reviewed:** 67
**Status:** issues_found

## Summary

Reviewed the Phase 6 additions across order-service (simulated carrier, S10 tracking code, ship/deliver, timeline events through the outbox), inventory-service (ShipStock consumption, V4 ledger columns), notification-service (entityId partition, order timeline endpoint) and the compose/LocalStack/smoke tooling.

The core mechanics hold up under adversarial tracing:

- **State transitions**: `Order.moveTo` is the only status assignment and checks the single `OrderStatus` table before touching any field. `ship` and `deliver` take the order-row lock (`findByIdForUpdate`), and the loser of a concurrent `ship` sees `SHIPPED` and gets a 409. `OrderShipmentIT` covers that race.
- **Transactional boundaries**: every timeline event and the `ShipStock` command are written through `OutboxWriter`/`OrderTimelineEvents` with `Propagation.MANDATORY`, in the same transaction as the status change. A rolled-back redelivery of `StockReserved` or `StockReservationFailed` cannot leave a duplicate or phantom event.
- **Idempotency**: `shipAll` takes the quantity from the ledger, never from the command. Its `shipped` guard plus the `@Version` on `Inventory` keep two concurrent deliveries of the same `ShipStock` from double-decrementing. The V4 `CHECK (NOT (released AND shipped))` is a sound last line of defence.
- **Authorization / IDOR**: ship and deliver are `SELLER_ADMIN` only and take the actor from the JWT `sub`. The BUYER timeline returns the same 404 for foreign, missing and empty orders, and fails closed when any stored event carries a different `companyId`. `@PathVariable UUID` blocks arbitrary partition-key input.
- **Migrations**: V3 runs the backfill before the CHECK constraints, and V4 adds a constant-default column, so neither is a rewrite-lock hazard.
- **S10 check digit** implementation matches the UPU algorithm (remainder 0 gives 5, remainder 1 gives 0).

No blockers found. The findings below are one latent data-loss path, two maintainability or consistency hazards, and several robustness and test-quality items.

## Warnings

### WR-01: A rejected `ShipStock` is silently acked and the stock is never decremented (limits duplicated in three places)

**File:** `inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java:49-54`, `inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java:39-42`
**Issue:** Any `InvalidSagaMessageException` is logged at WARN and the listener returns normally, so SQS deletes the message. For `ReserveStock` that is acceptable: the order then times out and is cancelled. For `ShipStock` it is not. By the time the command exists, the order is already persisted as `SHIPPED` (D-75: no intermediate state, no reply). If the parser rejects the command for any reason (item count above 50, quantity above 1,000,000, an unknown or renamed field, a payload over 64 KB), the stock decrement is lost for good. There is no DLQ entry, no retry and no signal to the order, whereas a ledger anomaly (`IllegalStateException`) at least reaches the DLQ.

Today the limits happen to agree: `CreateOrderRequest.MAX_ITEMS_PER_ORDER=50`, `OrderItemRequest.MAX_QUANTITY_PER_ITEM=1_000_000`, and the same literals in `SagaCommandParser`. But they are copy-pasted constants in three modules with no test tying them together, so raising an order limit later will silently turn large shipped orders into permanent stock drift.

**Fix:** Do not swallow an invalid `ShipStock`. Rethrow so it follows the same redelivery-to-DLQ path as the other `ShipStock` anomalies:
```java
} catch (InvalidSagaMessageException e) {
    if (isShipStock(payload)) { throw e; }   // post-SHIPPED command must never be dropped silently
    log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
    return;
}
```
Also add a contract test that asserts `SagaCommandParser`'s caps are at least `CreateOrderRequest.MAX_ITEMS_PER_ORDER` and `OrderItemRequest.MAX_QUANTITY_PER_ITEM`. At minimum, log at ERROR with `orderId` for a rejected `ShipStock`.

### WR-02: Ambiguous `@Recover` methods for `shipAll` and `releaseAll`; one handler is dead and the chosen one is undefined

**File:** `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java:449-469` and `535-564`
**Issue:** `recoverReleaseAll`, `recoverShipAll`, `recoverReleaseAllInconsistentBook` and `recoverShipAllInconsistentBook` have identical parameter lists and `void` returns. Spring Retry cannot tell `shipAll` and `releaseAll` apart, so which `@Recover` runs for `shipAll` is an implementation detail. The Javadoc at lines 551-557 admits that `shipAll` actually resolves to `recoverReleaseAllInconsistentBook`, so the `log.warn("ShipStock anomalo ...")` in `recoverShipAllInconsistentBook` never fires. The ShipStock anomaly path therefore has no ERROR/WARN log of its own at the point of failure. It is also fragile: if someone later adds compensation or different logging to a `releaseAll` recover, `shipAll` silently inherits it. The same applies to `recoverShipAll` and `recoverReleaseAll` for `DataAccessException`.
**Fix:** Make the two operations distinguishable (a different return type or an extra marker parameter), or drop the dead `@Recover` and log the anomaly where it is thrown. Spring Retry would then resolve by parameter type. For example, make `shipAll` return a small result record (shipped lines), or log inside `shipAll` before throwing.
```java
throw new IllegalStateException(msg);   // preceded by: log.warn("ShipStock anomalo (pedido {}): {}", orderId, msg);
```
Then delete `recoverShipAllInconsistentBook`.

### WR-03: BUYER timeline can be starved of events by one rejected event, and a rejected event is invisible

**File:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java:132-160` and `313-317`
**Issue:** Two behaviours compound:
1. Any validation failure in `orderMessage` (for example a `shippedBy` or `decidedBy` above 64, a `reason` above 500, or a malformed `trackingCode`) throws `InvalidNotificationEventException`, the listener drops the message, and that transition is permanently absent from the timeline.
2. The BUYER branch requires `!orderRecords.isEmpty()`. If the first event, `ORDER_CREATED`, is the one dropped (for example, a `createdBy` JWT `sub` longer than 64), later events are stored and the buyer still gets 404, while the seller sees a partial timeline.

The caps are enforced at the consumer but not at the producer: `shipped_by`, `decided_by` and `created_by` are `VARCHAR(64)` in the DB, so the producer has the same bound for `created_by`, `shipped_by` and `delivered_by`. The only real divergence is `carrier`, whose cap is enforced by `CarrierAssignment`. That makes the practical risk low today, but the failure mode (silent permanent gap, 404 for the owner) is hard to diagnose.
**Fix:** Log the dropped event at WARN with `orderId` and `eventType`, sanitised. In `historyForOrder`, consider returning an empty 200 list for a BUYER only when no foreign-company record exists, accepting that this distinguishes "no events" from "not yours". The simpler fix is to keep the 404 and add a metric or log so operators can see dropped lifecycle events.

## Info

### IN-01: `OutboxRelay` carries a stale comment and a duplicated literal for `ReleaseStock`

**File:** `order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java:35-36`
**Issue:** The comment says "o DTO `ReleaseStockCommand` ainda não existe nesta task (chega em 05-04)", but `ReleaseStockCommand` exists and is imported by `OrderSagaService` (`ReleaseStockCommand.EVENT_TYPE`). The relay keeps a private duplicate string literal `"ReleaseStock"` while using `ReserveStockCommand.EVENT_TYPE` and `ShipStockCommand.EVENT_TYPE` for the others.
**Fix:** Use `ReleaseStockCommand.EVENT_TYPE` and delete the constant and its comment.

### IN-02: Timeline ordering depends on wall-clock `occurredAt` plus a static rank

**File:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java:329-352`, `order-service/src/main/java/com/orderflow/order/timeline/OrderLifecycleEvent.java:31-36`
**Issue:** `occurredAt` is the transition column (a wall-clock instant taken under the row lock), and the only tie-break is the `lifecycleRank` constant. With a single order-service instance and a monotonic clock this is correct. With several replicas or NTP step-backs, `shippedAt` could sort before `confirmedAt`, and rank only breaks exact ties. The design accepts this, but it is not stated as a limitation in the README list.
**Fix:** Document it as a known limitation. If it matters later, add a per-order monotonically increasing `sequence` to the event.

### IN-03: `toSortedResponses` throws NPE on any record without `occurredAt`

**File:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java:350-352`
**Issue:** `Comparator.comparing(NotificationRecord::getOccurredAt)` is not null-safe. Records written by `record()` always have it, but the table is shared and its key schema is recreated by the init hook. One hand-seeded or legacy item without `occurredAt` turns the whole timeline (and the product history) into a 500.
**Fix:** `Comparator.comparing(NotificationRecord::getOccurredAt, Comparator.nullsFirst(Comparator.naturalOrder()))`.

### IN-04: `GET /notifications/{productId}` is not filtered to `STOCK_ADJUSTED`

**File:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java:288-290`
**Issue:** `history(productId)` returns everything in the partition, while `historyForOrder` filters to `ORDER_*`. The partition is now shared by two entity kinds. Passing an order UUID to the product route returns the order timeline to a SELLER_ADMIN (who may read it anyway), but the symmetry the Javadoc promises ("um id de produto consultado por esta rota nao mostra STOCK_ADJUSTED") is only implemented on one side.
**Fix:** Filter `history` to `eventType == STOCK_ADJUSTED` for symmetry.

### IN-05: Unconditional `putItem` rewrites `recordedAt` on every redelivery

**File:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java:156`, `NotificationRepository.java:34-36`
**Issue:** Redelivery is idempotent by key, but `recordedAt = Instant.now()` is replaced each time, so the field means "last recorded" rather than "first recorded". `occurredAt` is unaffected, so correctness holds.
**Fix:** Either document the meaning or use a conditional put (`attribute_not_exists`) and treat the conditional failure as success.

### IN-06: Negative assertions in tests rely on fixed sleeps, and one E2E assertion cannot detect an async regression

**File:** `e2e-tests/src/test/java/com/orderflow/e2e/OrderShipmentE2EIT.java:101,119-125`, `inventory-service/src/test/java/com/orderflow/inventory/ShipStockConsumptionIT.java:234,284`, `order-service/src/test/java/com/orderflow/order/OrderTimelinePublishingIT.java:332`
**Issue:** "Does not decrement again" is proved with `Thread.sleep(3000)`, which is slow and can pass vacuously on a slow runner. `deliveringShippedOrderDoesNotTouchStock` reads the stock immediately after `deliver` returns 200, so a regression where `deliver` published a late stock command would not be seen (the check runs before any asynchronous effect could land).
**Fix:** After the action, poll with Awaitility's `during(...)`/`atMost` for the invariant to hold for a window, or wait for the relay to drain the outbox before asserting. For the deliver test, wait until the outbox has no pending rows (or until `republish` settle) before reading the stock.

### IN-07: Hard-coded demo credentials and an unresolved LocalStack image tag in committed tooling

**File:** `scripts/smoke-order-lifecycle.sh:22-23`, `docker-compose.yml:20`, `order-service/src/test/java/com/orderflow/order/support/LocalStackTestSupport.java:35`
**Issue:** The smoke script embeds the seeded admin password (`ChangeMe!123`). It also passes passwords on a `curl -d` command line inside the container (visible to `ps` there). That is acceptable for a local demo stack but should be labelled as demo-only. Separately, the image tag `localstack/localstack:2026.08.3` is duplicated in compose and in the test support, so the two can drift (the comment promises they match).
**Fix:** Source the seller credentials from the same `.env` the stack uses, with a visible "demo only" note. Read the LocalStack tag from one place (an env var or a shared properties file).

---

_Reviewed: 2026-10-01_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
