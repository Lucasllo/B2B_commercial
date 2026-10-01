---
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
plan: "05"
subsystem: order-service
tags: [spring-boot, outbox, sqs, localstack, timeline, saga]
status: complete

requires:
  - phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
    provides: "06-04: notification-service consome os oito ORDER_* (NOTIFICATION_EVENT_CONTRACT); 06-01/06-02: confirmacao com transportadora, /ship e /deliver"
  - phase: 05-saga-de-reserva-de-estoque
    provides: "OutboxWriter/OutboxRelay e o padrao MANDATORY de ReservationSagaStarter"
provides:
  - "OrderLifecycleEvent: lado produtor do NOTIFICATION_EVENT_CONTRACT (8 tipos, EVENT_TYPES, fabricas por tipo, NON_NULL)"
  - "OrderTimelineEvents: escritor MANDATORY dos oito eventos no outbox, chamado nos sete pontos de transicao"
  - "OutboxRelay roteia os tipos ORDER_* para a notification-events-queue (lista explicita) sem fila nova"
  - "LocalStack dos ITs do order-service com SQS+DynamoDB e hooks 01 e 02; NotificationEventsQueue (suporte de teste)"
affects: [06-06, 06-07]

actuals:
  tokens: 13000
  tasks: 2
  commits: 2

plan_head_before: 4d47cda9101e72a481d75914a783f3be099ab9b1

tech-stack:
  added: []
  patterns:
    - "Evento de linha do tempo gravado no outbox na transacao da transicao (Propagation.MANDATORY), nunca enviado direto ao SQS"
    - "occurredAt tirado da coluna que a propria transicao gravou, nao de Instant.now()"
    - "Roteamento do relay por lista explicita de tipos; tipo desconhecido vira falha por evento"

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/timeline/OrderLifecycleEvent.java
    - order-service/src/main/java/com/orderflow/order/timeline/OrderTimelineEvents.java
    - order-service/src/test/java/com/orderflow/order/support/NotificationEventsQueue.java
    - order-service/src/test/java/com/orderflow/order/OrderTimelinePublishingIT.java
  modified:
    - order-service/src/main/java/com/orderflow/order/order/OrderService.java
    - order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java
    - order-service/src/main/java/com/orderflow/order/order/OrderShipmentService.java
    - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
    - order-service/src/main/resources/application.yml
    - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java
    - order-service/src/test/java/com/orderflow/order/support/LocalStackTestSupport.java
    - order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java
    - order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java
    - order-service/src/test/java/com/orderflow/order/OrderShipmentIT.java

key-decisions:
  - "TIMELINE_OCCURRED_AT=transition-column: occurredAt vem de createdAt/decidedAt/confirmedAt/cancelledAt/shippedAt/deliveredAt, nunca de um Instant.now() a parte; coluna nula lanca IllegalStateException"
  - "TIMELINE_ROUTING=explicit-list: o relay reconhece OrderLifecycleEvent.EVENT_TYPES (nao um prefixo); tipo inesperado segue sendo falha por evento"
  - "TEST_LOCALSTACK_SERVICES=sqs,dynamodb: LocalStack dos ITs do order-service copia os hooks 01 e 02 e espera as tres filas"

requirements-completed: [ORD-10]

duration: ~11 min (build/ITs incluidos)
completed: 2026-10-01

coverage:
  - id: D1
    description: "Criacao dentro do limite grava ORDER_CREATED, ORDER_APPROVED (SYSTEM) e ReserveStock, nessa ordem; acima do limite grava ORDER_CREATED e ORDER_PENDING_APPROVAL, sem ReserveStock"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderTimelinePublishingIT#withinLimitCreationWritesCreatedApprovedThenReserveStockAndRelayDeliversBothTimelineEvents"
        status: pass
      - kind: integration
        ref: "OrderTimelinePublishingIT#overTheLimitCreationWritesCreatedAndPendingApprovalAndNoReserveStockNorApproved"
        status: pass
    human_judgment: false
  - id: D2
    description: "Envelope: eventId = id da linha, occurredAt = instante gravado pela transicao, nenhum campo nulo serializado, entrega na notification-events-queue real com published_at preenchido e attempts 0"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderTimelinePublishingIT (10 testes)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Aprovacao manual, rejeicao, confirmacao, cancelamento (falha e timeout), expedicao e entrega gravam cada um um evento na transacao da transicao; StockReserved tardio/duplicado e ship recusado nao geram evento"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderTimelinePublishingIT#manualApprovalWritesApprovedWithTheSellerAndReasonThenReserveStock, #rejectionWritesRejectedWithTheReasonAndNeverReserveStock, #stockReservedWritesConfirmedWithCarrierAndTrackingAndADuplicateWritesNothing, #reservationFailureWritesCancelledAndALateStockReservedOnlyAddsReleaseStock, #reservationTimeoutWritesCancelledWithTheTimeoutCode, #shipAndDeliverWriteShippedAndDeliveredWithTheSellerThatActed, #refusedShipWritesNoShippedRow"
        status: pass
    human_judgment: false
  - id: D4
    description: "Jornada completa gera exatamente cinco linhas ORDER_* (CREATED, APPROVED, CONFIRMED, SHIPPED, DELIVERED), todas publicadas, e as cinco chegam a fila"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderTimelinePublishingIT#fullJourneyWritesExactlyFiveTimelineRowsAllPublishedAndDeliveredToTheQueue"
        status: pass
    human_judgment: false
  - id: D5
    description: "Relay: oito tipos ORDER_* para a fila de notificacoes, tres comandos para a de comandos, tipo desconhecido vira falha por evento sem derrubar o lote"
    requirement: "ORD-10"
    verification:
      - kind: unit
        ref: "OutboxRelayTest (6 testes)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Nenhum dual-write: o pacote timeline nao tem cliente SQS; o escritor so chama OutboxWriter em transacao MANDATORY"
    requirement: "ORD-10"
    verification:
      - kind: command
        ref: "grep gate do <verify> (zero ocorrencias de SqsOperations/SqsTemplate/SqsAsyncClient em codigo de timeline/)"
        status: pass
    human_judgment: false
---

# Phase 6 Plan 05: Eventos de linha do tempo no order-service Summary

**O order-service passa a gravar um evento `ORDER_*` no outbox, na mesma transacao de cada transicao persistida do pedido (criacao, decisao automatica ou manual, rejeicao, confirmacao, cancelamento por falha ou timeout, envio e entrega), e o relay os entrega na `notification-events-queue` que o notification-service ja consome.**

## Accomplishments

- `timeline/OrderLifecycleEvent` (record `NON_NULL`, oito constantes, `EVENT_TYPES`, uma fabrica por tipo que so preenche os campos do contrato) e `timeline/OrderTimelineEvents` (oito metodos `@Transactional(MANDATORY)` que so chamam `OutboxWriter#enqueue`; `eventId` = id da linha).
- Pontos de emissao (sete, mais a espera de aprovacao): `OrderService#createWithCreditCheck` (`created` + `approved` ou `pendingApproval`), `OrderDecisionService#approve`/`#reject`, `OrderSagaService#applyStockReserved` (so o ramo que confirma), `#applyReservationFailed` e `#expireReservation` (`cancelled`), `OrderShipmentService#ship`/`#deliver`. A entrada em `RESERVING` nao tem evento proprio (D-78).
- `OutboxRelay` ganhou o quinto parametro `orderflow.messaging.notification-events-queue` e roteia `EVENT_TYPES` para ela; `application.yml` ganhou a chave (mesma fila do hook 01, sem fila nova nem variavel nova no compose).
- Suporte de teste: `LocalStackTestSupport` com SQS+DynamoDB, hooks 01 e 02 e as tres filas; `NotificationEventsQueue` (le/apaga e filtra por `orderId`, prazo de 30 s).

## Decisoes registradas (Claude's Discretion)

```
TIMELINE_OCCURRED_AT=transition-column
TIMELINE_ROUTING=explicit-list
TEST_LOCALSTACK_SERVICES=sqs,dynamodb
```

Lista final de pontos de emissao: criacao (`ORDER_CREATED` + `ORDER_APPROVED` | `ORDER_PENDING_APPROVAL`), aprovacao manual (`ORDER_APPROVED`, `decidedBy` do vendedor), rejeicao (`ORDER_REJECTED`), `StockReserved` que confirma (`ORDER_CONFIRMED` com `carrier`/`trackingCode`), `StockReservationFailed` (`ORDER_CANCELLED`), timeout (`ORDER_CANCELLED` `RESERVATION_TIMEOUT`), `/ship` (`ORDER_SHIPPED`), `/deliver` (`ORDER_DELIVERED`). Nao emitem: `StockReserved` tardio para pedido CANCELLED, duplicata para pedido CONFIRMED, `/ship` recusado.

## Task Commits

1. **Task 1 (tracer): criacao grava ORDER_CREATED e a decisao no outbox; relay entrega na notification-events-queue** - `f9561c2` (feat)
2. **Task 2: aprovacao manual, rejeicao, confirmacao, cancelamento, envio e entrega viram eventos** - `63526c9` (feat)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug de teste] `OrderShipmentIT` contava linhas ORDER_* como "comando de estoque"**
- **Found during:** Task 1 (ao revisar os asserts de contagem sem filtro do outbox)
- **Issue:** `deliverMovesAShippedOrder...` afirma que `/deliver` nao grava nada no outbox comparando a contagem total apos `/ship` e apos `/deliver`; com `ORDER_DELIVERED` a contagem muda por desenho (D-78). A mesma classe de falha do Pitfall 2, em um IT que o plano nao listou.
- **Fix:** o helper `outboxRowCount(orderId)` passou a contar so `ReserveStock`/`ReleaseStock`/`ShipStock` (o teste continua provando "nenhum comando de estoque novo no deliver").
- **Files modified:** `order-service/src/test/java/com/orderflow/order/OrderShipmentIT.java`
- **Commit:** `f9561c2`

**Total deviations:** 1 auto-fixed (1 ajuste de teste). **Impact:** nenhum no escopo de producao.

Observacoes de processo: a implementacao do contrato (`OrderLifecycleEvent`/`OrderTimelineEvents`) foi escrita antes dos testes da Task 1 (os testes foram escritos logo em seguida, antes de ligar o `OrderService`, e passaram na primeira execucao); o RED da Task 1 NAO foi observado como falha. Na Task 2 os testes vieram antes da implementacao e o RED foi confirmado (7 de 10 falhando antes do cabeamento). Testes e implementacao de cada task entraram no mesmo commit, como em 06-02 a 06-04.

## Verificacao

- Docker disponivel; nenhuma stack do compose de pe. `./mvnw -B -pl order-service verify` verde contra Postgres e LocalStack reais (Testcontainers): 165 testes unitarios e 131 ITs, 0 falhas (`OrderTimelinePublishingIT` 10, `OrderShipmentIT` 7, `ReservationResultListenerIT` 13, `SagaTimeoutIT` 5, `ReservationCommandPublishingIT` 5, `OrderApprovalIT` 2, `OrderLifecycleTransitionsIT` 41, demais anteriores verdes).
- Gate de grep: zero ocorrencias de `SqsOperations|SqsTemplate|SqsAsyncClient` em codigo (fora de comentarios) do pacote `timeline`.
- Criterios de aceitacao: `Propagation.MANDATORY` nos oito metodos; `orderTimelineEvents.confirmed` 1x e `.cancelled` 2x em `OrderSagaService`; `approved`/`rejected` em `OrderDecisionService`; `shipped`/`delivered` em `OrderShipmentService`; `LocalStackTestSupport` referencia `01-create-notification-resources.sh` e `notification-events-queue`.
- Tracer gate: `<verify>` do tracer reexecutado e verde antes da expansao.

## Known Stubs

None.

## Threat Flags

None - superficie ja coberta por T-06-19 a T-06-22 (mitigados: MANDATORY + gate de grep, NON_NULL, roteamento por lista explicita com teste, decisor vindo das colunas gravadas do JWT).

## Issues Encountered

- Nao verificado ponta a ponta com o notification-service real: o order-service foi provado entregando na fila real de notificacoes; o consumo dessas mensagens pelo notification-service (06-04) so e exercitado junto no E2E/smoke de 06-06/06-07.
- Limitacao documentada (suposicao do plano): o outbox cresce de 5 a 7 linhas por pedido sem retencao (`OUTBOX_RETENTION=none-this-phase`).
- Ordenacao dos testes por `created_at` do outbox assume instantes distintos (microssegundos) entre linhas gravadas em sequencia na mesma transacao; nao houve empate nas execucoes.

## Next Phase Readiness

Pronto para 06-06: o order-service publica a jornada completa e o notification-service a guarda; falta o fluxo E2E ponta a ponta e a documentacao/smoke (06-06, 06-07).

## Self-Check: PASSED

- Arquivos criados presentes: OrderLifecycleEvent.java, OrderTimelineEvents.java, NotificationEventsQueue.java, OrderTimelinePublishingIT.java.
- Commits `f9561c2` e `63526c9` existem em `git log`; `git rev-list --count 4d47cda..HEAD` = 2 no momento da escrita.
