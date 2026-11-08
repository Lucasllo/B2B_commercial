# Phase 6: Ciclo de Vida Completo - Pattern Map

**Mapped:** 2026-09-30
**Files analyzed:** 24 (novos/modificados)
**Analogs found:** 22 / 24 (todos os analogos sao arquivos git-tracked)

Base de caminhos: `order-service/src/main/java/com/orderflow/order` = `ORD/`; `inventory-service/src/main/java/com/orderflow/inventory` = `INV/`; `notification-service/src/main/java/com/orderflow/notification/history` = `NOT/`.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| `ORD/order/OrderStatus.java` (mod: `canTransitionTo`) | model/enum | transform | ele mesmo (`CREDIT_CONSUMING`, L37) | exact |
| `ORD/order/Order.java` (mod: `ship/deliver`, `assignCarrier`, colunas) | model | CRUD | `Order.confirm/cancel` (L195-213) | exact |
| `ORD/order/carrier/CarrierGateway.java` + `SimulatedCarrierGateway.java` | service (seam) | transform | sem analogo de dominio; ver "No Analog" | partial |
| `ORD/order/OrderShipmentController.java` (ou mod. `OrderDecisionController`) | controller | request-response | `ORD/order/OrderDecisionController.java` | exact |
| `ORD/order/OrderShipmentService.java` | service | CRUD + outbox | `ORD/order/OrderDecisionService.java` + `saga/OrderSagaService.java` | exact |
| `ORD/order/exception/InvalidOrderTransitionException.java` | exception | - | `exception/OrderNotPendingException.java` | exact |
| `ORD/config/GlobalExceptionHandler.java` (mod) | config | request-response | handler `OrderNotPendingException` (L80) | exact |
| `ORD/order/dto/OrderResponse.java` (mod: 6 campos) | DTO | transform | campos `confirmedAt/cancelledAt` | exact |
| `ORD/saga/OrderSagaService.java` (mod: carrier + eventos) | service | event-driven | ele mesmo (L93-122) | exact |
| `ORD/order/OrderCreationService/OrderService/OrderDecisionService`, `saga/SagaTimeoutJob` (mod: emitir evento ORDER_*) | service | outbox | `OrderSagaService.expireReservation` (L137-157) | exact |
| `ORD/saga/messaging/dto/ShipStockCommand.java` | DTO | event-driven | `saga/messaging/dto/ReleaseStockCommand.java` | exact |
| `ORD/order/timeline/OrderTimelineEvent.java` (envelope ORDER_*) | DTO | event-driven | `ReleaseStockCommand` (envelope plano) | role-match |
| `ORD/saga/outbox/OutboxRelay.java` (mod: `resolveQueue`) | service | event-driven | ele mesmo (L331-336) | exact |
| `order-service/.../db/migration/V3__order_fulfillment.sql` | migration | - | `V2__order_reservation_saga.sql` | exact |
| `INV/saga/messaging/dto/ShipStockCommand.java` | DTO | event-driven | `INV/saga/messaging/dto/ReleaseStockCommand` | exact |
| `INV/saga/messaging/SagaCommandParser.java` (mod) | utility | request-response | ele mesmo (`parseReleaseStock`) | exact |
| `INV/saga/messaging/ReservationCommandListener.java` (mod) | listener | event-driven | ele mesmo (L53-64) | exact |
| `INV/stock/InventoryService.java` (mod: `shipAll`) | service | CRUD | `releaseAll` (L400-433) | exact |
| `INV/stock/StockReservation.java` + `Inventory.java` (mod: `shipped`, `ship(qty)`) | model | CRUD | `markReleased` / `Inventory.release` | exact |
| `inventory-service/.../db/migration/V4__reservation_shipped.sql` | migration | - | `V3__allow_reservation_tombstones.sql` | role-match |
| `NOT/NotificationRecord.java`, `NotificationRepository.java`, `localstack-init/ready.d/01-create-notification-resources.sh` (mod: PK generica) | model/repo/config | CRUD | proprios arquivos | exact |
| `NOT/NotificationService.java` (mod: validacao por tipo) | service | event-driven | ele mesmo (L53-117) | exact |
| `NOT/NotificationController.java` (mod: `/orders/{orderId}`) | controller | request-response | `ORD/order/OrderService.getById` (404 BUYER) | role-match |
| `scripts/smoke-order-lifecycle.sh`; `e2e-tests/.../OrderShipmentE2EIT.java` | test | - | `scripts/smoke-order-saga.sh`; `OrderReservationSagaE2EIT.java` | exact |

## Pattern Assignments

### `OrderStatus.canTransitionTo` (D-83)
**Analog:** `ORD/order/OrderStatus.java` L37-38 - conjunto unico, nunca duplicado:
```java
public static final Set<OrderStatus> CREDIT_CONSUMING =
        Set.of(APPROVED, RESERVING, CONFIRMED, SHIPPED, DELIVERED);
```
Copiar: uma `Map<OrderStatus, Set<OrderStatus>>`/`EnumMap` estatica unica; `Order.ship/deliver/confirm/cancel` e o fluxo de decisao consultam a mesma tabela. O teste parametrizado (9x9 pares) compara com a tabela documentada. Transicoes reais: CREATED->PENDING_APPROVAL|RESERVING(via APPROVED logico)|REJECTED, PENDING_APPROVAL->RESERVING|REJECTED, RESERVING->CONFIRMED|CANCELLED, CONFIRMED->SHIPPED, SHIPPED->DELIVERED. Nota: hoje a entrada em RESERVING e gravada direto (D-50), entao `APPROVED` nao e destino real.

### `Order.java` - `ship`/`deliver`/`assignCarrier` (L195-224)
Guarda por estado no proprio agregado, metodos mutadores sem setters:
```java
public void confirm(OffsetDateTime now) {
    requireReserving();
    this.status = OrderStatus.CONFIRMED;
    this.confirmedAt = now;
}
private void requireReserving() {
    if (this.status != OrderStatus.RESERVING) {
        throw new IllegalStateException("Order " + id + " is not RESERVING (status=" + status + ")");
    }
}
```
Copiar: `confirm(now, carrier, trackingCode)`; `ship(by, now)`/`deliver(by, now)` lancam a excecao de dominio 409 (nao `IllegalStateException`, que e erro de programacao) quando `!status.canTransitionTo(...)`. Colunas `@Column(name="confirmed_at")` (L91) como modelo de `shipped_at/shipped_by/delivered_at/delivered_by/carrier/tracking_code`.

### `OrderShipmentController` / `OrderShipmentService` (D-74)
**Analog:** `ORD/order/OrderDecisionController.java`
```java
@RestController
@RequestMapping("/orders")
public class OrderDecisionController {
    @PostMapping("/{orderId}/approve")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public OrderResponse approve(@PathVariable UUID orderId, ..., @AuthenticationPrincipal Jwt jwt) {
        String sellerId = requireSellerId(jwt);
        ...
    }
    private String requireSellerId(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) { throw new AccessDeniedException("sub claim is missing"); }
        return sub;
    }
}
```
Sem `@RequestBody`, sem `Location`; `ship`/`deliver` devolvem `OrderResponse` 200. Service: `@Transactional`, sem trava de empresa (SHIPPED/DELIVERED nao mudam exposicao de credito). Travar a linha como a saga:
```java
Optional<Order> maybeOrder = orderRepository.findByIdForUpdate(orderId);   // OrderSagaService L59
// vazio -> throw new OrderNotFoundException("Order not found")  (OrderDecisionService L181-182)
```
`ship`: `order.ship(sub, now)` + `outboxWriter.enqueue(...)` do `ShipStock` + evento `ORDER_SHIPPED`, tudo na mesma `@Transactional`.

### `ShipStockCommand` (order-service e inventory-service)
**Analog:** `ORD/saga/messaging/dto/ReleaseStockCommand.java` - record plano, `EVENT_TYPE` constante, fabrica `from(Order, eventId, occurredAt)`:
```java
public record ReleaseStockCommand(UUID eventId, String eventType, Instant occurredAt, UUID orderId,
        String reservationId, String reason, List<ReservationLine> items) {
    public static final String EVENT_TYPE = "ReleaseStock";
    public static ReleaseStockCommand from(Order order, UUID eventId, Instant occurredAt, String reason) {
        List<ReservationLine> lines = order.getItems().stream()
                .map(i -> new ReservationLine(i.getProductId(), i.getQuantity())).toList();
        return new ReleaseStockCommand(eventId, EVENT_TYPE, occurredAt, order.getId(), order.getId().toString(), reason, lines);
    }
}
```
Sem `reason`. `reservationId = orderId.toString()`. Gravar com:
```java
outboxWriter.enqueue(command.eventId(), ReleaseStockCommand.EVENT_TYPE, order.getId().toString(), command);
```

### `OutboxRelay.resolveQueue` (D-79)
**Analog:** `ORD/saga/outbox/OutboxRelay.java` L331-336 + construtor L296-303 (`@Value("${orderflow.messaging.inventory-commands-queue}")`):
```java
private String resolveQueue(String eventType) {
    if (ReserveStockCommand.EVENT_TYPE.equals(eventType) || RELEASE_STOCK_EVENT_TYPE.equals(eventType)) {
        return inventoryCommandsQueue;
    }
    throw new IllegalStateException("No queue configured for outbox eventType '" + eventType + "'");
}
```
Acrescentar `ShipStock` -> `inventoryCommandsQueue`; `eventType.startsWith("ORDER_")` -> nova propriedade `orderflow.messaging.notification-events-queue` (+ `application.yml` do order-service, env em `docker-compose.yml`, e `02-create-order-saga-resources.sh`/permissoes se necessario). Preservar `safeQueueNameFor` e a captura por evento. `OutboxWriter` (`Propagation.MANDATORY`, L242) nao muda.

### Pontos de emissao dos eventos ORDER_* (D-78)
**Analog:** `OrderSagaService.expireReservation` L150-156 - mutar o agregado e enfileirar no outbox na mesma transacao:
```java
order.cancel(CancellationCode.RESERVATION_TIMEOUT, CancellationReasons.forTimeout(), now);
UUID eventId = UUID.randomUUID();
ReleaseStockCommand command = ReleaseStockCommand.from(order, eventId, now.toInstant(), ...);
outboxWriter.enqueue(command.eventId(), ReleaseStockCommand.EVENT_TYPE, order.getId().toString(), command);
```
Aplicar em: `OrderService.createWithCreditCheck` (ORDER_CREATED + ORDER_PENDING_APPROVAL ou ORDER_APPROVED), `OrderDecisionService.approve/reject` (L158-173), `OrderSagaService.applyStockReserved` (L106-109: `order.confirm(now, carrier, tracking)` + ORDER_CONFIRMED) e `applyReservationFailed` (L73) e `expireReservation` (ORDER_CANCELLED com `cancellationCode`). Envelope: `eventId, eventType, occurredAt, orderId, companyId` + campos do tipo. Preferir um helper unico `OrderTimelinePublisher` (evita 8 copias do boilerplate). Nota: `OrderSagaService.applyStockReserved` precisa injetar `CarrierGateway`.

### `CarrierGateway` / `SimulatedCarrierGateway`
Sem analogo de dominio; usar o estilo de costura de `ORD/client/CatalogServiceClient.java` (interface/cliente injetado por construtor) mas sem I/O. Determinismo a partir de `orderId` (ex.: `Math.floorMod(orderId.hashCode(), CARRIERS.size())`; S10 = 2 letras derivadas + 9 digitos derivados + `BR`; unicidade: `UNIQUE` em `tracking_code` na V3).

### `V3__order_fulfillment.sql` (order) e `V4__reservation_shipped.sql` (inventory)
**Analog:** `V2__order_reservation_saga.sql` - `ALTER TABLE orders ADD COLUMN ...` com comentario de decisao; CHECK de nome explicito (`chk_orders_...`). Colunas: `carrier VARCHAR`, `tracking_code VARCHAR(13)` (CHECK regex `^[A-Z]{2}[0-9]{9}BR$` + UNIQUE parcial), `shipped_at/shipped_by/delivered_at/delivered_by`. Sem mudar o CHECK de status (ja contem SHIPPED/DELIVERED). Inventory: `ALTER TABLE stock_reservations ADD COLUMN shipped BOOLEAN NOT NULL DEFAULT false, ADD COLUMN shipped_at TIMESTAMPTZ` (ver V3 existente do inventory para o estilo; conferir numeracao: V3 ja existe, entao o novo e V4). `ddl-auto: validate` exige entidade alinhada.

### `InventoryService.shipAll` (D-75)
**Analog:** `INV/stock/InventoryService.releaseAll` L395-433 (Retryable + Transactional + linhas ordenadas por productId + `@Recover`):
```java
@Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
        maxAttempts = RETRY_MAX_ATTEMPTS, backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
@Transactional
public void releaseAll(UUID orderId, String reservationId, List<ReservationLine> lines) {
    List<ReservationLine> sortedLines = lines.stream().sorted(Comparator.comparing(ReservationLine::productId)).toList();
    for (ReservationLine line : sortedLines) {
        var existing = stockReservationRepository.findByProductIdAndReservationId(line.productId(), reservationId);
        if (existing.isPresent()) {
            StockReservation reservation = existing.get();
            if (reservation.isReleased()) { continue; }
            Inventory inventory = inventoryRepository.findByProductId(line.productId())
                    .orElseThrow(() -> new IllegalStateException("Inventory ausente ..."));
            inventory.release(reservation.getQuantity());
            reservation.markReleased(OffsetDateTime.now());
            inventoryRepository.saveAndFlush(inventory);
            stockReservationRepository.saveAndFlush(reservation);
        } else { ...tombstone... }
    }
}
@Recover public void recoverReleaseAll(DataAccessException ex, UUID orderId, String reservationId, List<ReservationLine> lines) { throw new ReservationConflictException(); }
@Recover public void recoverReleaseAllInconsistentBook(IllegalStateException ex, ...) { throw ex; }
```
Adaptar: `shipAll` - ja `shipped` = `continue` (no-op idempotente); reserva ausente ou `released` = `IllegalStateException` (anomalia -> DLQ, recomendacao do CONTEXT); senao `inventory.ship(qty)` (`quantityOnHand -= qty; quantityReserved -= qty`, espelhando `Inventory.release` L86) e `reservation.markShipped(now)`. Incluir os dois `@Recover` (sem eles o Spring Retry mascara a excecao, ver javadoc L436-444). Respeitar a regra estrutural: nao chamar `reserve/release/reserveAll` de dentro do bean.

### `SagaCommandParser` + `ReservationCommandListener` (inventory)
**Analog:** o proprio arquivo. `parse()` despacha por `eventType` (L78-87); acrescentar `ShipStockCommand.EVENT_TYPE -> parseShipStock` reutilizando `requireUuid/requireInstant/requireReservationIdMatchingOrderId/requireItems` (sem `reason`). Listener (L53-64): novo `if (command instanceof ShipStockCommand s) inventoryService.shipAll(s.orderId(), s.reservationId(), s.items());`. Falha de parse = `InvalidSagaMessageException` -> descarte com WARN (nao relancar).

### `NotificationRecord` / `NotificationRepository` / init hook (D-80)
**Analog:** proprios. Trocar `productId` -> `entityId` (nome generico) em: `@DynamoDbPartitionKey getProductId()` (NotificationRecord), `findByProductId` (Repository L~43), `AttributeName=productId` (2 vezes no `01-create-notification-resources.sh`), `NotificationService.record`/`history`, `NotificationResponse.from`, testes da Fase 3 e healthcheck do LocalStack no `docker-compose.yml`. Registros de pedido guardam `companyId` (novo atributo no bean).

### `NotificationService` (D-82)
**Analog:** `NOT/NotificationService.record` L53-117 - manter: limite 64KB, `FAIL_ON_TRAILING_TOKENS`, raiz objeto, `sanitizeForLog`, `putItem` sem condicao, sort key `eventType + "#" + eventId`. Trocar o `treeToValue(tree, StockAdjustedEvent.class)` fixo + `validate` por um switch em `tree.get("eventType")`: `STOCK_ADJUSTED` (caminho atual) ou `ORDER_*` (novo DTO `OrderTimelineEvent`; validar `orderId`, `companyId`, `eventId`, `occurredAt` + campos por tipo) e montar mensagem legivel por tipo (`"Pedido confirmado - transportadora %s, rastreio %s"`). Tipo desconhecido: `throw new InvalidNotificationEventException("Tipo de evento nao suportado: " + sanitizeForLog(eventType))`.

### `NotificationController` - `GET /notifications/orders/{orderId}` (D-81)
**Analog (controller):** `NOT/NotificationController` (`@PreAuthorize("hasRole('SELLER_ADMIN')")`, `history(UUID)`); **analog (regra BUYER 404):** `ORD/order/OrderService.getById` L82-90 e `OrderController.requireCompanyId` L84-93:
```java
if (!sellerView && !order.getCompanyId().equals(callerCompanyId)) {
    throw new OrderNotFoundException("Order not found");   // 404, nao revela existencia (D-47)
}
```
Mudar `@PreAuthorize` para `hasAnyRole('SELLER_ADMIN','BUYER')` no novo metodo; ler `company_id` do JWT (`jwt.getClaimAsString("company_id")`, `AccessDeniedException` se ausente). Timeline vazia para BUYER ou `companyId` divergente = 404 (criar `NotificationNotFoundException` + handler). Decidir se renomeia `GET /{productId}` para `/products/{productId}` (afeta smoke da Fase 3 e docs; o gateway precisa rotear `/notifications/**`).

### `InvalidOrderTransitionException` + handler (D-74)
**Analog:** `OrderNotPendingException` (RuntimeException com mensagem fixa) e `GlobalExceptionHandler` L80-84:
```java
@ExceptionHandler(OrderNotPendingException.class)
public ResponseEntity<Map<String, Object>> handleNotPending(OrderNotPendingException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("order_not_pending", "Order is not pending approval"));
}
```
Novo handler 409 com `errorBody("invalid_order_transition", "Order cannot go from X to Y")` (incluir estados atual/destino na mensagem). 404 ja coberto por `handleOrderNotFound` (L73).

### Testes e smoke
- `scripts/smoke-order-lifecycle.sh`: copiar cabecalho de `scripts/smoke-order-saga.sh` (`set -euo pipefail`, `MSYS_NO_PATHCONV=1`, `fail()`, `strip_cr`, `exec_gateway`, `do_request` preenchendo `REQ_STATUS`/`REQ_BODY`, L1-40) e o fluxo login -> cria pedido -> espera CONFIRMED.
- `e2e-tests/.../OrderShipmentE2EIT.java`: copiar de `OrderReservationSagaE2EIT` (helpers `registerProduct`, `registerCreditLimit`, `putStock`, `createOrder`, `awaitOrderStatus`, `SAGA_TIMEOUT=30s`, `POLL_INTERVAL=500ms`, Awaitility, `E2eHttp`, `E2eJwt`); cenario CONFIRMED -> `POST /orders/{id}/ship` -> Awaitility em `GET stock` ate `quantityOnHand` e `quantityReserved` baixarem. Notification nao entra (D-86).
- Teste de timeline: `notification-service` `NotificationEventFlowIT`/`NotificationControllerIT` (Testcontainers LocalStack, `AbstractIntegrationTest`, `TestJwt`).

## Shared Patterns

### Outbox atomico (unico caminho SQS)
**Source:** `ORD/saga/outbox/OutboxWriter.java` (L242, `Propagation.MANDATORY`). **Apply to:** `/ship`, `/deliver`, toda transicao que emite ORDER_* ou `ShipStock`. Nunca chamar `SqsOperations` fora de `OutboxRelay`.

### Trava de linha do pedido
**Source:** `OrderSagaService` L59 (`orderRepository.findByIdForUpdate`, `SAGA_RESULT_LOCK`). **Apply to:** `/ship`, `/deliver`, saga de confirmacao.

### Idempotencia por estado / livro
Order-service: estado atual diferente do esperado = log + return (OrderSagaService L65-69, L121). Inventory: livro `stock_reservations` (`shipped` flag). Notification: chave deterministica + `putItem`.

### Parsing defensivo de mensagens SQS
**Source:** `INV/saga/messaging/SagaCommandParser` e `NOT/NotificationService` (teto 64KB, sanitizeForLog, `FAIL_ON_TRAILING_TOKENS`). **Apply to:** novo comando `ShipStock` e eventos ORDER_*.

### Formato de erro e autorizacao
`errorBody(code, message)` em `GlobalExceptionHandler`; `@PreAuthorize("hasRole('SELLER_ADMIN')")`; sub/company_id lidos do JWT, nunca do corpo.

### Testes de timestamps
`OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)` para tudo que vai ao TIMESTAMPTZ (OrderSagaService L107).

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `SimulatedCarrierGateway` (algoritmo S10 deterministico) | service | transform | Nao ha gateway/mock interno; usar apenas o estilo de injecao por construtor de `CatalogServiceClient` |
| Diagramas Mermaid (`README.md`, `docs/VISAO-GERAL.md`) | docs | - | Verificar se ha Mermaid existente em `docs/`; nao mapeado |

## Metadata

**Analog search scope:** order-service, inventory-service, notification-service (main), localstack-init, scripts, e2e-tests
**Pattern extraction date:** 2026-09-30
