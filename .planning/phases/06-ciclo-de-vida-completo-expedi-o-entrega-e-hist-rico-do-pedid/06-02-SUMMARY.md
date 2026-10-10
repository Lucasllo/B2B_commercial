---
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
plan: "02"
subsystem: order-service
tags: [spring-boot, jpa, saga, transactional-outbox, state-machine, rest, sqs]

requires:
  - phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
    provides: "OrderStatus.transitions(), Order.moveTo, colunas shipped_*/delivered_* e CarrierAssignment (06-01)"
  - phase: 05-saga-de-reserva-de-estoque
    provides: "OutboxWriter/OutboxRelay, findByIdForUpdate, inventory-commands-queue"
provides:
  - "POST /orders/{id}/ship e /deliver (SELLER_ADMIN) via OrderShipmentController/OrderShipmentService"
  - "Order#ship e Order#deliver passando por moveTo"
  - "InvalidOrderTransitionException + handler 409 invalid_order_transition"
  - "ShipStockCommand (SAGA_MESSAGE_CONTRACT) e rota ShipStock no OutboxRelay"
  - "OrderLifecycleTransitionsIT: 36 pares status x acao provados contra a tabela"
affects: [06-03, 06-05, 06-07]

actuals:
  tokens: 12700
  tasks: 2
  commits: 2

plan_head_before: 09069b35cef67ffcdc7dd8f281a0318631a4429b

tech-stack:
  added: []
  patterns:
    - "Acao de ciclo de vida em controller irmao pequeno, sob a trava de linha do pedido"
    - "Comando de estoque gravado no outbox na mesma transacao da mudanca de status"
    - "Teste parametrizado semeando por JDBC cada status e comparando a resposta da API com a tabela de transicoes"

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/order/OrderShipmentController.java
    - order-service/src/main/java/com/orderflow/order/order/OrderShipmentService.java
    - order-service/src/main/java/com/orderflow/order/order/exception/InvalidOrderTransitionException.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ShipStockCommand.java
    - order-service/src/test/java/com/orderflow/order/OrderShipmentIT.java
    - order-service/src/test/java/com/orderflow/order/OrderLifecycleTransitionsIT.java
  modified:
    - order-service/src/main/java/com/orderflow/order/order/Order.java
    - order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
    - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
    - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java

key-decisions:
  - "SHIPMENT_LOCK=order-row (findByIdForUpdate, sem company_credit_lock: SHIPPED e DELIVERED ja consomem credito)"
  - "INVALID_TRANSITION_ERROR=409 {error:invalid_order_transition, message:'Order cannot transition from <ATUAL> to <DESTINO>'}; approve/reject mantem order_not_pending"
  - "SAGA_MESSAGE_CONTRACT ShipStock={eventId,eventType:ShipStock,occurredAt,orderId,reservationId=orderId,items[{productId,quantity}]} sem reason, na inventory-commands-queue"
  - "Controller irmao OrderShipmentController em vez de mais metodos em OrderDecisionController"

requirements-completed: [ORD-10]

coverage:
  - id: D1
    description: "POST /orders/{id}/ship leva CONFIRMED a SHIPPED com shippedAt/shippedBy (sub do JWT), mantendo transportadora e rastreio"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderShipmentIT#shipMovesAConfirmedOrderToShippedRecordingWhoAndWhenAndKeepingCarrierAndTracking"
        status: pass
    human_judgment: false
  - id: D2
    description: "ShipStock gravado no outbox na mesma transacao, entregue pelo relay na inventory-commands-queue com reservationId=orderId, itens do pedido e sem reason"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderShipmentIT#shipWritesExactlyOneShipStockToTheOutboxAndTheRelayDeliversItToTheInventoryCommandsQueue"
        status: pass
      - kind: unit
        ref: "OutboxRelayTest#pendingShipStockEventIsSentToTheInventoryCommandsQueueAndMarkedPublished"
        status: pass
    human_judgment: false
  - id: D3
    description: "Duas expedicoes simultaneas: uma vence, a outra recebe InvalidOrderTransitionException, exatamente um ShipStock"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderShipmentIT#twoConcurrentShipCallsOnTheSameOrderLetExactlyOneWinAndWriteExactlyOneShipStock"
        status: pass
    human_judgment: false
  - id: D4
    description: "POST /orders/{id}/deliver leva SHIPPED a DELIVERED sem gravar nada no outbox e sem nova mensagem de estoque; DELIVERED e SHIPPED seguem consumindo credito"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderShipmentIT#deliverMovesAShippedOrderToDeliveredWithoutAnyFurtherStockCommandAndStillConsumesCredit"
        status: pass
      - kind: integration
        ref: "OrderShipmentIT#aShippedOrderKeepsConsumingCreditSoANewOrderOverTheLimitWaitsForApproval"
        status: pass
    human_judgment: false
  - id: D5
    description: "Cada um dos 9 status x 4 acoes responde 200 so na origem da aresta da acao e 409 nos demais casos sem alterar o pedido; CREATED->DELIVERED e CANCELLED imutavel sao 409"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderLifecycleTransitionsIT#theApiAnswers200OnlyOnTheSourceOfTheActionsEdgeAnd409EverywhereElseWithoutChangingTheOrder (36 execucoes)"
        status: pass
      - kind: integration
        ref: "OrderLifecycleTransitionsIT#skippingStagesAndTouchingACancelledOrderAreBothConflicts"
        status: pass
    human_judgment: false
  - id: D6
    description: "404 order_not_found, 403 forbidden (BUYER), 401 sem token e 400 invalid_parameter para id nao UUID em ship e deliver"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderLifecycleTransitionsIT#shipAndDeliverOnAnUnknownOrderAreA404OrderNotFound, #aBuyerCannotShipOrDeliverAndAnonymousCallersAreUnauthorized, #anOrderIdThatIsNotAUuidIsA400InvalidParameter"
        status: pass
    human_judgment: false

duration: 22min
completed: 2026-10-01
status: complete
---

# Phase 6 Plan 02: Expedicao e entrega do pedido pelo vendedor Summary

**POST /orders/{id}/ship e /deliver (SELLER_ADMIN) levam o pedido CONFIRMED -> SHIPPED -> DELIVERED sob a trava de linha do pedido, o ship grava o comando ShipStock no outbox na mesma transacao, e um teste parametrizado prova 200/409 de todos os 36 pares status x acao contra OrderStatus.transitions().**

## Performance

- **Duration:** 22 min
- **Completed:** 2026-10-01T00:20:00Z
- **Tasks:** 2
- **Files modified:** 11 (6 criados, 5 alterados; +982 / -6 linhas)

## Accomplishments

- **Ship (tracer):** `OrderShipmentService#ship` e `@Transactional`, busca com `findByIdForUpdate`, chama `Order#ship` (que passa por `moveTo`) e grava um `ShipStockCommand` via `OutboxWriter` na mesma transacao. O relay entrega a mensagem na `inventory-commands-queue`; o pedido nao espera resposta e nao ha estado intermediario (D-75).
- **Deliver:** mesmo molde, sem escrita no outbox. `shipped*` ficam intactos; `deliveredBy`/`deliveredAt` vem do `sub` do JWT e de um instante UTC truncado a microssegundos.
- **Erro 409 novo:** `InvalidOrderTransitionException(from, to)` com mensagem so de nomes de enum, mapeada para `invalid_order_transition`. `approve`/`reject` seguem com `order_not_pending` (contrato da Fase 4 preservado).
- **Prova API x tabela:** `OrderLifecycleTransitionsIT` semeia por JDBC cada um dos 9 status (respeitando as CHECKs de V2/V3) e chama as 4 acoes (36 execucoes parametrizadas). Afirma tambem que as 4 arestas das acoes existem em `OrderStatus.transitions()`.
- **Seguranca/idempotencia:** `@PreAuthorize("hasRole('SELLER_ADMIN')")` nos dois endpoints, `requireSellerId` recusa `sub` ausente, concorrencia provada (uma vencedora, um `ShipStock`).

## Registros exigidos pelo plano

- `SAGA_MESSAGE_CONTRACT` (comando novo, consumido por 06-03): `{"eventId":"<uuid>","eventType":"ShipStock","occurredAt":"<instant ISO-8601>","orderId":"<uuid>","reservationId":"<orderId em texto>","items":[{"productId":"<uuid>","quantity":<int>}]}` — sem `reason`, itens na ordem de `lineNumber`, na `inventory-commands-queue`.
- `SHIPMENT_LOCK=order-row` (`findByIdForUpdate`; sem `company_credit_lock` porque SHIPPED/DELIVERED ja estao em `CREDIT_CONSUMING` e nao mudam a exposicao).
- `INVALID_TRANSITION_ERROR=409 {"error":"invalid_order_transition","message":"Order cannot transition from <ATUAL> to <DESTINO>"}`.

## Task Commits

1. **Task 1 (tracer): POST /orders/{id}/ship com ShipStock no outbox** - `89af10b` (feat)
2. **Task 2: POST /orders/{id}/deliver e matriz status x acao** - `69b5f19` (feat)

## Verification

Docker disponivel; compose derrubado (`docker compose ps -q` vazio); Testcontainers + LocalStack reais.

- `./mvnw -B -pl order-service test -Dtest=OrderDomainTest,OutboxRelayTest` - 23 testes, verde (antes do commit da Task 1).
- Tracer gate: `./mvnw -B -pl order-service verify -Dit.test=OrderShipmentIT` - 5 testes, verde, antes de expandir.
- `./mvnw -B -pl order-service verify -Dit.test=OrderLifecycleTransitionsIT,OrderShipmentIT` - 48 testes, verde (41 + 7).
- `./mvnw -B -pl order-service verify` completo - 163 unitarios + 121 ITs, BUILD SUCCESS.
- Criterios de aceite: `OrderShipmentService.ship` e `@Transactional`, usa `findByIdForUpdate` e `outboxWriter.enqueue(..., ShipStockCommand.EVENT_TYPE, ...)`; `deliver` nao chama `outboxWriter`; controller sem `@RequestBody`; `OutboxRelay.resolveQueue` referencia `ShipStockCommand.EVENT_TYPE`; `OrderLifecycleTransitionsIT` referencia `OrderStatus.transitions()`.

## Deviations from Plan

**1. [Process] Ciclo RED/GREEN sem execucao de RED separada nem commit de RED**
- Os testes foram escritos antes da implementacao, mas nao rodei a suite para registrar a falha antes de implementar, e cada tarefa gerou um unico commit `feat` (mesmo desvio ja registrado em 06-01). Sem evidencia formal via `check tdd-red-evidence`.

**2. [Process] Testes de deliver adiados para o commit da Task 2**
- Para manter os commits atomicos por tarefa, o codigo e os testes de `deliver` (OrderDomainTest, OrderShipmentIT, `Order#deliver`, endpoint) foram removidos da arvore antes do commit da Task 1 e reintroduzidos no da Task 2. No commit da Task 1 o laco "ship de qualquer outro status" pula DELIVERED (nao ha como construi-lo ainda); na Task 2 ele cobre todos os 8 outros status.

**Total deviations:** 2 (ambas de processo). **Impact:** nenhum no escopo funcional.

## Known Stubs

None.

## Threat Flags

None alem do modelo de ameacas do plano (T-06-05 a T-06-09 mitigados e cobertos por teste).

## Issues Encountered

None.

## Next Phase Readiness

06-03 pode consumir `ShipStock` na `inventory-commands-queue` seguindo o contrato registrado acima. `/deliver` ainda nao grava evento de linha do tempo (entra em 06-05).

## Self-Check: PASSED

- Arquivos criados verificados em disco (controller, service, excecao, DTO, dois ITs).
- Commits `89af10b` e `69b5f19` presentes em `git log`; `git rev-list --count 09069b3..HEAD` = 2 no momento da escrita.
