---
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
plan: "03"
subsystem: inventory-service
tags: [spring-boot, jpa, flyway, saga, sqs, spring-retry, idempotency]
status: complete

requires:
  - phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
    provides: "ShipStockCommand (SAGA_MESSAGE_CONTRACT) publicado pelo /ship e rota ShipStock no OutboxRelay (06-02)"
  - phase: 05-saga-de-reserva-de-estoque
    provides: "livro stock_reservations, reserveAll/releaseAll com @Retryable/@Recover, ReservationCommandListener"
provides:
  - "Migracao V4: stock_reservations.shipped/shipped_at e CHECKs chk_stock_reservations_not_released_and_shipped e chk_stock_reservations_shipped_at"
  - "InventoryService#shipAll: baixa fisica idempotente (quantity_on_hand e quantity_reserved) pela quantidade do livro"
  - "ShipStockCommand + ramo ShipStock no SagaCommandParser e no ReservationCommandListener"
  - "release (REST) e releaseAll (saga) ignoram reservas expedidas"
  - "ShipStockConsumptionIT (10 testes) e 11 casos novos em SagaCommandParserTest"
affects: [06-05, 06-07]

actuals:
  tokens: 10200
  tasks: 2
  commits: 2

plan_head_before: caafcd0d4ee071797ab8ae76a19e349ce278558e

tech-stack:
  added: []
  patterns:
    - "Marca de estado no livro (shipped) como guarda de idempotencia, com CHECK no banco como rede de seguranca"
    - "Quantidade de efeito colateral sempre lida do livro, nunca do corpo do comando"
    - "@Recover para IllegalStateException relancando a causa (licao de reserveAll/releaseAll)"

key-files:
  created:
    - inventory-service/src/main/resources/db/migration/V4__stock_reservation_shipped.sql
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ShipStockCommand.java
    - inventory-service/src/test/java/com/orderflow/inventory/ShipStockConsumptionIT.java
  modified:
    - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservation.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/Inventory.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
    - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java

key-decisions:
  - "SHIP_STOCK_ANOMALY=missing-or-released-throws: reserva expedida de novo e no-op com INFO; reserva inexistente ou liberada lanca IllegalStateException citando orderId/productId/reservationId (reentrega ate a DLQ)"
  - "SHIP_STOCK_ADJUSTED_EVENT=none: a expedicao nao publica STOCK_ADJUSTED (significa ajuste do vendedor); a prova da baixa e GET /inventory/{productId}"
  - "RELEASE_AFTER_SHIP=ignored: ReleaseStock e DELETE REST para reserva expedida sao ignorados (WARN na saga, no-op idempotente no REST), nunca decrementam quantity_reserved de novo"
  - "reserveAll nao muda: ReserveStock reentregue depois da expedicao cai no replay idempotente (so javadoc)"
  - "Spring Retry resolveu recoverReleaseAllInconsistentBook (nao recoverShipAllInconsistentBook) para a IllegalStateException de shipAll, pois as duas tem a mesma assinatura de parametros e retorno void; comportamento identico"

requirements-completed: [ORD-10]

duration: ~45 min
completed: 2026-10-01

coverage:
  - id: D1
    description: "ShipStock na inventory-commands-queue baixa quantity_on_hand e quantity_reserved pela quantidade do livro e marca a reserva como expedida, na mesma transacao"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "ShipStockConsumptionIT#shipStockDecrementsOnHandAndReservedAndMarksTheBookRowShipped"
        status: pass
      - kind: integration
        ref: "ShipStockConsumptionIT#shipStockForTwoProductsDecrementsBothUsingTheBookQuantityNotTheCommandOne"
        status: pass
    human_judgment: false
  - id: D2
    description: "A expedicao nao publica STOCK_ADJUSTED"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "ShipStockConsumptionIT#shipStockPublishesNoStockAdjustedEvent"
        status: pass
    human_judgment: false
  - id: D3
    description: "ShipStock reentregue (mesmo eventId ou outro) nao baixa de novo; ReserveStock reentregue apos a expedicao nao mexe no estoque"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "ShipStockConsumptionIT#redeliveredShipStockWithSameOrNewEventIdDoesNotDecrementAgain"
        status: pass
      - kind: integration
        ref: "ShipStockConsumptionIT#reserveStockRedeliveredAfterShipmentDoesNotChangeStock"
        status: pass
    human_judgment: false
  - id: D4
    description: "Reserva inexistente ou liberada e anomalia tecnica (IllegalStateException com orderId, nunca ExhaustedRetryException) sem alterar estoque"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "ShipStockConsumptionIT#shipAllForReservationWithoutBookRowThrowsIllegalStateNotExhaustedRetryAndChangesNothing"
        status: pass
      - kind: integration
        ref: "ShipStockConsumptionIT#shipAllForReleasedReservationThrowsIllegalStateAndChangesNothing"
        status: pass
    human_judgment: false
  - id: D5
    description: "ReleaseStock e DELETE REST apos a expedicao nao mexem na reserva expedida nem na de outro pedido; o banco recusa released+shipped"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "ShipStockConsumptionIT#releaseStockAfterShipmentIsIgnoredAndDoesNotTouchAnotherOrdersReservation"
        status: pass
      - kind: integration
        ref: "ShipStockConsumptionIT#restDeleteAfterShipmentReturns200KeepsQuantitiesAndDoesNotTouchAnotherOrdersReservation"
        status: pass
      - kind: integration
        ref: "ShipStockConsumptionIT#databaseRefusesARowThatIsBothReleasedAndShipped"
        status: pass
    human_judgment: false
  - id: D6
    description: "ShipStock malformado e descartado pelo parser (tamanho, JSON, items, quantidade, reservationId != orderId, campos ausentes/invalidos) com mensagem sanitizada"
    requirement: "ORD-10"
    verification:
      - kind: unit
        ref: "SagaCommandParserTest#validShipStockParsesIntoCommand (+10 casos shipStock*)"
        status: pass
    human_judgment: false
---

# Phase 6 Plan 03: ShipStock no inventory-service Summary

**Baixa fisica de estoque na expedicao: o inventory-service consome `ShipStock`, decrementa `quantity_on_hand` e `quantity_reserved` pela quantidade do livro `stock_reservations` e marca a reserva como `shipped`, uma unica vez por reserva.**

## Accomplishments

- Migracao V4 com `shipped`/`shipped_at` e as CHECKs `chk_stock_reservations_not_released_and_shipped` e `chk_stock_reservations_shipped_at`; `chk_inventory_not_oversold` continua valido porque as duas colunas caem pelo mesmo valor.
- `Inventory#ship` (nunca mascara com `Math.max`, lanca `IllegalStateException`), `StockReservation#markShipped` e `InventoryService#shipAll` com `@Retryable`/`@Transactional` iguais aos de `releaseAll`, linhas ordenadas por `productId`, quantidade sempre do livro, sem outbox.
- `ShipStockCommand`, ramo no `SagaCommandParser` (reusa `requireItems`/`requireReservationIdMatchingOrderId`) e no `ReservationCommandListener`.
- Guardas `isShipped()` em `release` (REST, no-op idempotente) e `releaseAll` (saga, WARN) — Pitfall 5 / T-06-11.
- `ShipStockConsumptionIT` (10 testes contra Postgres e LocalStack reais) e 11 testes novos em `SagaCommandParserTest` (39 no total).

## Decisoes registradas (Claude's Discretion)

```
SHIP_STOCK_ANOMALY=missing-or-released-throws
SHIP_STOCK_ADJUSTED_EVENT=none
RELEASE_AFTER_SHIP=ignored
```

`SHIP_STOCK_ADJUSTED_EVENT=none` deve virar limitacao/ideia registrada em 06-07: a baixa fisica nao aparece no historico de `STOCK_ADJUSTED`.

### Qual `@Recover` o Spring Retry resolveu

`shipAll` e `releaseAll` tem a mesma lista de parametros (`UUID, String, List<ReservationLine>`) e ambos retornam `void`. Para a `IllegalStateException` lancada por `shipAll`, o Spring Retry resolveu `recoverReleaseAllInconsistentBook`: o WARN de `recoverShipAllInconsistentBook` nunca apareceu nos logs enquanto o `INFO`/`WARN` de outros pontos do servico apareceram. O comportamento e identico (relanca a causa original com `orderId`/`productId`; os testes comprovam `IllegalStateException`, nao `ExhaustedRetryException`). `recoverShipAllInconsistentBook` foi mantido para explicitar a intencao, com a observacao no javadoc.

## Task Commits

1. **Task 1 (tracer): ShipStock baixa on_hand e reserved e marca o livro** - `9a89c0b` (feat)
2. **Task 2: idempotencia pelo livro, anomalias e guardas em release/releaseAll** - `55ba6b2` (feat)

## Deviations from Plan

None - plan executed exactly as written. Observacao de processo: o RED da Task 1 foi confirmado como erro de compilacao (`ShipStockCommand` inexistente); o RED da Task 2 foi confirmado por falha real do `DELETE` REST (503: a CHECK `released AND shipped` barrou a escrita, sem a guarda). Os testes e a implementacao de cada task entraram no mesmo commit, como em 06-02. Um achado util do RED: sem as guardas `isShipped()`, o `ReleaseStock` apos a expedicao nao corrompe o estoque porque a CHECK do banco derruba a transacao, mas vira retentativa ate a DLQ — a guarda transforma isso em no-op limpo.

**Total deviations:** 0.

## Verificacao

- `./mvnw -B -pl inventory-service verify` verde: 43 testes unitarios e 61 ITs, 0 falhas (Docker disponivel, Postgres 16.15 e LocalStack reais via Testcontainers; compose derrubado).
- `./mvnw -B -pl inventory-service verify -Dit.test=ShipStockConsumptionIT,TombstoneReleaseIT,IdempotentReservationIT`: 18 testes, verde.
- Criterios de aceitacao: `shipAll` com `@Retryable` e `@Transactional`; dois `@Recover` novos (`DataAccessException`, `IllegalStateException`); V4 contem `chk_stock_reservations_not_released_and_shipped`; listener chama `inventoryService.shipAll`; `isShipped()` aparece em `shipAll`, `release` e `releaseAll`.

## Known Stubs

None.

## Threat Flags

None - sem superficie nova alem da `inventory-commands-queue` ja coberta pelo threat model (T-06-10 a T-06-13 mitigados).

## Issues Encountered

- Hipotese nao testada: o SQS de producao entrega ate `maxReceiveCount` 3 antes da DLQ; os ITs chamam `shipAll` direto para provar a anomalia em vez de esperar a DLQ.
- Consistencia eventual SHIPPED x baixa (suposicao carregada do plano): se o `ShipStock` for para a DLQ, o pedido segue SHIPPED e nao ha compensacao automatica nesta fase.

## Next Phase Readiness

Pronto para 06-04. Lado order-service e inventory-service da expedicao completos (ORD-10).

## Self-Check: PASSED

- Arquivos criados presentes: V4__stock_reservation_shipped.sql, ShipStockCommand.java, ShipStockConsumptionIT.java.
- Commits `9a89c0b` e `55ba6b2` existem em `git log`.
