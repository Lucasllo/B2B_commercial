---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "02"
subsystem: api
tags: [correlation-id, mdc, outbox, sqs, saga, restclient, order-service]

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: Contrato de Correlation-ID do Gateway (header X-Correlation-Id, MDC correlationId)
  - phase: 05-saga-e-outbox
    provides: Outbox transacional e relay SQS do order-service
provides:
  - Correlation-ID do pedido HTTP até o atributo SQS correlationId, de volta na saga e no timeout
  - RestClient de catálogo e auth reenviando X-Correlation-Id a partir do MDC
affects: [07-03, 07-04, 07-05, 07-11]

actuals:
  tokens: 15742
  tasks: 3
  commits: 6

tech-stack:
  added: []
  patterns:
    - CorrelationContext.open/current com escopo MDC restaurado no close
    - Atributo SQS String correlationId via MessageBuilder.setHeader no OutboxRelay
    - Colunas nullable correlation_id em orders e outbox_event (Flyway V4, sem backfill)

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/observability/CorrelationContext.java
    - order-service/src/main/java/com/orderflow/order/observability/CorrelationIdFilter.java
    - order-service/src/main/resources/db/migration/V4__correlation_id.sql
    - order-service/src/test/java/com/orderflow/order/observability/CorrelationIdFilterTest.java
    - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxWriterTest.java
    - order-service/src/test/java/com/orderflow/order/CorrelationIdPropagationIT.java
  modified:
    - order-service/src/main/resources/application.yml
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxEvent.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxWriter.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
    - order-service/src/main/java/com/orderflow/order/order/Order.java
    - order-service/src/main/java/com/orderflow/order/order/OrderService.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/ReservationResultListener.java
    - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
    - order-service/src/main/java/com/orderflow/order/config/ClientConfig.java
    - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java
    - order-service/src/test/java/com/orderflow/order/support/OrderSagaQueues.java
    - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
    - order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java
    - order-service/src/test/java/com/orderflow/order/support/DownstreamStubServer.java
    - order-service/src/test/java/com/orderflow/order/client/DownstreamClientsTest.java

key-decisions:
  - "SQS_CORRELATION_ATTRIBUTE=correlationId"
  - "CORRELATION_CODE_PLACEMENT=duplicated-per-service"
  - "TRANSITION_CORRELATION_SOURCE=request-id"
  - "LEGACY_CORRELATION=null"
  - "ORDER_CORRELATION_SETTER=recordCorrelationId"

patterns-established:
  - "order-service não ecoa X-Correlation-Id na resposta; o Gateway é o dono do header de resposta"
  - "O ID vive no MDC e nas colunas/atributo; o envelope de negócio não ganha campo correlationId"
  - "Timeout sem HTTP reabre o MDC com orders.correlation_id; linha legada nula gera UUID só naquela execução"

requirements-completed: [QUAL-02, TEST-01, TEST-02]

coverage:
  - id: D1
    description: POST /orders com X-Correlation-Id grava o mesmo ID na outbox, no atributo SQS correlationId e no log, sem campo correlationId no corpo e sem ecoar o header na resposta
    requirement: QUAL-02
    verification:
      - kind: unit
        ref: order-service/src/test/java/com/orderflow/order/observability/CorrelationIdFilterTest.java
        status: pass
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/CorrelationIdPropagationIT.java
        status: pass
    human_judgment: false
  - id: D2
    description: StockReserved devolve o atributo ao MDC e aos eventos seguintes; ausência de atributo gera UUID novo e não vaza o ID da mensagem anterior
    requirement: QUAL-02
    verification:
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/CorrelationIdPropagationIT.java
        status: pass
    human_judgment: false
  - id: D3
    description: Timeout de reserva republica ReleaseStock e ORDER_CANCELLED com orders.correlation_id
    requirement: QUAL-02
    verification:
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java#timeoutReusesTheCreationCorrelationIdOnReleaseStockAndOrderCancelled
        status: pass
    human_judgment: false
  - id: D4
    description: RestClient de catálogo e auth envia X-Correlation-Id quando o MDC tem ID e omite o header quando o MDC está vazio
    requirement: TEST-02
    verification:
      - kind: unit
        ref: order-service/src/test/java/com/orderflow/order/client/DownstreamClientsTest.java#clientsSendTheMdcCorrelationIdToCatalogAndAuth
        status: pass
    human_judgment: false

duration: 66min
completed: 2026-10-02
status: complete
plan_head_before: 1a568756f01729ede23e885c192e907a4a8c957a
plan_head_after: b4d8e129cbe239e5b098f642dc8d91f8c5072f39
---

# Phase 07 Plan 02: Correlation-ID no order-service Summary

**O order-service carrega o Correlation-ID do filtro HTTP até a coluna da outbox, o atributo SQS `correlationId`, a volta da saga, o timeout e as chamadas RestClient a catálogo e auth.**

## Performance

- **Duration:** 66 min
- **Started:** 2026-10-02T02:49:00Z
- **Completed:** 2026-10-02T03:55:00Z
- **Tasks:** 3
- **Files modified:** 21

## Accomplishments

- `CorrelationIdFilter` abre o escopo MDC com `X-Correlation-Id`, loga `MÉTODO caminho -> status` (exceto `/actuator`) e não escreve o header na resposta.
- `OutboxWriter` grava `CorrelationContext.current()` na mesma transação; `OutboxRelay` publica o atributo String `correlationId` e loga dentro desse escopo. ID nulo não vira atributo.
- `ReservationResultListener` lê o atributo com `required = false`. Confirmação, cancelamento e timeout logam dentro do escopo; o timeout reutiliza `orders.correlation_id`.
- O interceptor do `ClientConfig` copia o MDC para `X-Correlation-Id` nas chamadas de catálogo e auth.

## Task Commits

1. **Task 1 RED: testes do filtro, da outbox e do IT de propagação** - `b4baba3` (test)
2. **Task 1 GREEN: filtro, V4, writer e relay** - `d722757` (feat)
3. **Task 2 RED: testes do caminho de volta da saga e do timeout** - `9d55591` (test)
4. **Task 2 GREEN: listener, saga e recordCorrelationId** - `761f20f` (feat)
5. **Task 3 RED: testes do header de saída** - `94f198a` (test)
6. **Task 3 GREEN: interceptor RestClient** - `b4d8e12` (feat)

## Decisions Made

- O atributo SQS se chama `correlationId` (String), o mesmo nome da chave MDC.
- O código de correlação fica duplicado por serviço; o order-service não importa o filtro do Gateway.
- Transições HTTP (`/approve`, `/ship`, `/deliver`) usam o ID do request corrente.
- Linha legada com `correlation_id` nulo permanece nula no pedido; a execução sem ID gera UUID só para o log e a outbox daquela vez.
- O ID de criação entra por `Order.recordCorrelationId` e não é substituído depois.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
| ---- | --- | ----- | -------- |
| 1    | b4baba3 | d722757 | — |
| 2    | 9d55591 | 761f20f | — |
| 3    | 94f198a | b4d8e12 | — |

Cada RED falhou na asserção nomeada (`CorrelationIdFilterTest`, `recordCorrelationIdStoresTheCreationIdAndIgnoresALaterValue`, `clientsSendTheMdcCorrelationIdToCatalogAndAuth`) e `gsd-tools check tdd-red-evidence` devolveu `RED_EVIDENCE_OK` antes do GREEN. Não houve commit de refactor.

## Files Created/Modified

- `CorrelationContext` e `CorrelationIdFilter` no order-service, mais `logging.pattern.correlation`.
- Flyway `V4__correlation_id.sql`: `orders.correlation_id` e `outbox_event.correlation_id`, VARCHAR(64), nullable, sem default.
- `OutboxEvent`, `OutboxWriter`, `OutboxRelay` (único import de `io.awspring.cloud.sqs.operations`).
- `Order.recordCorrelationId`, `OrderService.createWithCreditCheck`, `ReservationResultListener`, `OrderSagaService.expireReservation`.
- Interceptor em `ClientConfig.buildRestClient`.
- Testes unitários e ITs listados em `key-files`.

## Deviations from Plan

None - plan executed exactly as written.

## Verification

- Tracer da Task 1 reexecutado após o feat: `./mvnw -B -pl order-service verify -Dit.test=CorrelationIdPropagationIT,ReservationCommandPublishingIT,OrderSagaMigrationIT` — BUILD SUCCESS.
- Task 2: unitários mais `CorrelationIdPropagationIT`, `ReservationResultListenerIT` e `SagaTimeoutIT` — BUILD SUCCESS.
- Task 3: `DownstreamClientsTest` (4 testes) e `./mvnw -B -pl order-service verify` — Failsafe 139 testes, 0 falhas, BUILD SUCCESS.

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: order-service/src/main/java/com/orderflow/order/observability/CorrelationContext.java
- FOUND: order-service/src/main/java/com/orderflow/order/observability/CorrelationIdFilter.java
- FOUND: order-service/src/main/resources/db/migration/V4__correlation_id.sql
- FOUND: order-service/src/test/java/com/orderflow/order/CorrelationIdPropagationIT.java
- FOUND: b4baba3
- FOUND: d722757
- FOUND: 9d55591
- FOUND: 761f20f
- FOUND: 94f198a
- FOUND: b4d8e12
