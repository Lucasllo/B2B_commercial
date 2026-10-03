---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "04"
subsystem: observability
tags: [correlation-id, sqs, outbox, flyway, e2e, inventory]

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: Contrato SQS correlationId e o lado order da saga (07-02)
provides:
  - Correlation-ID no inventory: filtro HTTP, coluna outbox V5, atributo na volta da saga
  - E2E com order e inventory reais provando o mesmo ID nos dois listeners
affects: [07-09, 07-11]

actuals:
  tokens: 12890
  tasks: 2
  commits: 4

tech-stack:
  added: []
  patterns:
    - CorrelationContext e CorrelationIdFilter duplicados no inventory, sem módulo comum
    - Relay envia send(String, Message) com header correlationId lido da linha do outbox
    - onMessage(String) sem @SqsListener delega com ID nulo para testes unitários

key-files:
  created:
    - inventory-service/src/main/java/com/orderflow/inventory/observability/CorrelationContext.java
    - inventory-service/src/main/java/com/orderflow/inventory/observability/CorrelationIdFilter.java
    - inventory-service/src/main/resources/db/migration/V5__outbox_correlation_id.sql
    - e2e-tests/src/test/java/com/orderflow/e2e/CorrelationIdE2EIT.java
  modified:
    - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelay.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxWriter.java
    - inventory-service/src/test/java/com/orderflow/inventory/support/SagaQueues.java

key-decisions:
  - "E2E_CORRELATION_PROOF=output-capture"
  - "LISTENER_COMPAT_OVERLOAD=onMessage(String) delegates with null"

patterns-established:
  - "E2E_CORRELATION_PROOF=output-capture: o mesmo System.out do JVM de teste, linha com o nome simples do listener e [id]"
  - "LISTENER_COMPAT_OVERLOAD: sobrecarga onMessage(String) sem @SqsListener delega com correlationId nulo"

requirements-completed: [QUAL-02, TEST-01, TEST-02]

coverage:
  - id: D1
    description: O mesmo Correlation-ID de POST /orders chega a CONFIRMED e aparece no log do ReservationCommandListener e do ReservationResultListener, e em inventory.outbox_event.correlation_id do StockReserved
    requirement: QUAL-02
    verification:
      - kind: e2e
        ref: e2e-tests/src/test/java/com/orderflow/e2e/CorrelationIdE2EIT.java#postedCorrelationIdReturnsOnBothListenersAndOnTheStockReservedOutboxRow
        status: pass
    human_judgment: false
  - id: D2
    description: O relay do inventory envia o header correlationId quando a linha tem ID e omite o header quando é nulo, sem perder o isolamento de falha por evento
    requirement: QUAL-02
    verification:
      - kind: unit
        ref: inventory-service/src/test/java/com/orderflow/inventory/saga/outbox/OutboxRelayTest.java#eventWithCorrelationIdSendsItAsMessageHeader
        status: pass
    human_judgment: false
  - id: D3
    description: Comando com, sem e com correlationId inválido é processado; valor inválido não chega ao log; PUT com X-Correlation-Id publica STOCK_ADJUSTED com o mesmo atributo; o filtro HTTP não ecoa o header
    requirement: TEST-01
    verification:
      - kind: integration
        ref: inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java#reserveStockWithInvalidCorrelationIdIsProcessedWithAUuidAndDoesNotLogTheRawValue
        status: pass
      - kind: integration
        ref: inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java#putWithCorrelationIdPublishesStockAdjustedCarryingThatAttribute
        status: pass
      - kind: unit
        ref: inventory-service/src/test/java/com/orderflow/inventory/observability/CorrelationIdFilterTest.java#filterExposesTheHeaderInTheMdcAndDoesNotEchoItOnTheResponse
        status: pass
    human_judgment: false

duration: 19min
completed: 2026-10-03
status: complete
plan_head_before: 4e1e1fd6e659882c8aa286f57e5a7b8e4968a57c
plan_head_after: 0d739f9114a0380a649ec0b87006cb7755ee478f
---

# Phase 07 Plan 04: Correlation-ID no inventory Summary

**O inventory lê o atributo `correlationId` da saga, grava na coluna do outbox e devolve o mesmo ID ao order-service; um E2E com os dois serviços reais prova o ID nos dois listeners.**

## Performance

- **Duration:** 19 min
- **Started:** 2026-10-03T14:03:00Z
- **Completed:** 2026-10-03T14:22:28Z
- **Tasks:** 2
- **Files modified:** 16

## Accomplishments

- `ReservationCommandListener` abre o MDC com o atributo SQS `correlationId` (`required = false`); valor inválido vira UUID e não entra no log.
- Migração `V5` adiciona `outbox_event.correlation_id`; o writer grava o MDC e o relay reenvia o ID como atributo para `order-events-queue` e `notification-events-queue`.
- Filtro HTTP do inventory põe `X-Correlation-Id` no MDC, limpa ao final e não ecoa o header.
- `CorrelationIdE2EIT` leva um pedido a CONFIRMED e encontra o mesmo ID na linha de `ReservationCommandListener`, na de `ReservationResultListener` e na linha `StockReserved` do outbox.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: failing relay and writer tests** - `d05e594` (test)
2. **Task 1 GREEN: saga propagation and E2E** - `6c548b9` (feat)
3. **Task 2 RED: failing HTTP filter test** - `84c6df7` (test)
4. **Task 2 GREEN: filter, command attribute and STOCK_ADJUSTED** - `0d739f9` (feat)

## Files Created/Modified

- `inventory-service/.../observability/CorrelationContext.java` - MDC com validação `[A-Za-z0-9-]{1,64}`, cópia do order-service.
- `inventory-service/.../observability/CorrelationIdFilter.java` - filtro HTTP, sem eco do header.
- `inventory-service/.../db/migration/V5__outbox_correlation_id.sql` - coluna nullable, sem backfill.
- `inventory-service/.../saga/outbox/OutboxEvent.java` - campo `correlationId` e `pending(..., String)`.
- `inventory-service/.../saga/outbox/OutboxWriter.java` - grava `CorrelationContext.current()`.
- `inventory-service/.../saga/outbox/OutboxRelay.java` - `send(String, Message)` com o header e log INFO sem payload.
- `inventory-service/.../saga/messaging/ReservationCommandListener.java` - `@Header` e sobrecarga `onMessage(String)`.
- `inventory-service/src/main/resources/application.yml` - `logging.pattern.correlation`.
- `e2e-tests/.../E2eHttp.java` - `postJson` com headers extras.
- `e2e-tests/.../CorrelationIdE2EIT.java` - prova do critério 2 pela captura de log.
- `inventory-service/.../support/SagaQueues.java` - envio e leitura do atributo.
- Testes: `OutboxRelayTest`, `OutboxWriterTest`, `CorrelationIdFilterTest`, `ReservationCommandConsumptionIT`, `StockAdjustedEventPublishingIT`.

## Decisions Made

- `E2E_CORRELATION_PROOF=output-capture` — as duas aplicações escrevem no mesmo `System.out`; o teste exige o ID na mesma linha que `ReservationCommandListener` e `ReservationResultListener`.
- `LISTENER_COMPAT_OVERLOAD=onMessage(String) delegates with null` — a sobrecarga não tem `@SqsListener` e só existe para chamadas de teste sem o atributo.

## Deviations from Plan

### Auto-fixed Issues

None.

### TDD sequencing

- `OutboxWriterTest` está na Task 2 do plano e foi escrito no RED da Task 1, antes do writer passar a gravar o MDC. Sem isso o teste nasceria verde.
- O RED da Task 1 inclui o campo `correlationId` e a cópia de `CorrelationContext` para o teste compilar e falhar na asserção (o relay ainda enviava `String`).
- O RED da Task 2 inclui um filtro que ainda não abria o MDC, para a asserção `abc-123` falhar de propósito. O commit seguinte substituiu esse corpo pelo filtro real.

**Total deviations:** 0 auto-fixed. Sequência TDD ajustada para a evidência de falha ser uma asserção, não erro de compilação.
**Impact on plan:** Nenhum comportamento fora do plano. `E2E_CORRELATION_PROOF` e `LISTENER_COMPAT_OVERLOAD` seguem as decisões do planejador.

## TDD Gate Compliance

| Gate | Commit | Result |
|------|--------|--------|
| RED task 1 | `d05e594` | `eventWithCorrelationIdSendsItAsMessageHeader` falhou (publicado 0; `send` ainda era `String`) — `RED_EVIDENCE_OK` |
| GREEN task 1 | `6c548b9` | `OutboxRelayTest` + `OutboxWriterTest` verdes; `CorrelationIdE2EIT` 1 teste, 0 falhas |
| RED task 2 | `84c6df7` | `filterExposesTheHeaderInTheMdcAndDoesNotEchoItOnTheResponse` falhou (`abc-123` vs null) — `RED_EVIDENCE_OK` |
| GREEN task 2 | `0d739f9` | `./mvnw -B -pl inventory-service verify` — Failsafe 65 testes, 0 falhas |

## Issues Encountered

- `JAVA_HOME` não vinha no shell do executor; a sessão apontou para `C:\Program Files\Java\jdk-21.0.10`.
- `LOCALSTACK_AUTH_TOKEN` não estava no ambiente do processo e estava no `.env` (valor não impresso). Os testes de LocalStack usaram essa variável exportada na sessão.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- O elo inventory do QUAL-02 está fechado. `07-09` pode editar `ReservationCommandListener` (WR-01) em cima do método com `@Header`.
- O notification-service continua fora do E2E (D-86); o smoke de 07-11 cobre essa ponta.

## Self-Check: PASSED

- FOUND: inventory-service/src/main/resources/db/migration/V5__outbox_correlation_id.sql
- FOUND: e2e-tests/src/test/java/com/orderflow/e2e/CorrelationIdE2EIT.java
- FOUND: d05e594
- FOUND: 6c548b9
- FOUND: 84c6df7
- FOUND: 0d739f9

## Known Stubs

None.
