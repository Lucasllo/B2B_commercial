---
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
plan: "04"
subsystem: notification-service
tags: [spring-boot, dynamodb, localstack, sqs, authorization, idor, timeline]
status: complete

requires:
  - phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
    provides: "Lado inventory-service da expedicao (ShipStock) concluido em 06-03; sequencial pela sessao unica do LocalStack Hobby"
  - phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
    provides: "notification-service com listener SQS, NotificationRecord/@DynamoDbBean, rota de historico de produto e init hook da tabela"
provides:
  - "Tabela notification-history com particao generica entityId (produto ou pedido) criada pelo init hook, que recria o key-schema antigo"
  - "Healthcheck do LocalStack no compose so saudavel com a particao entityId"
  - "OrderLifecycleEvent: lado consumidor do NOTIFICATION_EVENT_CONTRACT com os oito tipos ORDER_*"
  - "NotificationService: validacao por tipo, mensagem legivel montada no servidor, lifecycleRank como desempate, historyForOrder com regra SELLER/BUYER"
  - "GET /notifications/orders/{orderId} para SELLER_ADMIN e BUYER da propria empresa, com 404 order_not_found identico"
  - "OrderTimelineIT (6) e OrderTimelineControllerIT (7); NotificationServiceTest com 49 casos"
affects: [06-05, 06-07]

actuals:
  tokens: 19000
  tasks: 3
  commits: 3

plan_head_before: 1516a90b3d304a4573d95c22a9f922c44abb3136

tech-stack:
  added: []
  patterns:
    - "Particao DynamoDB generica (entityId) para entidades diferentes na mesma tabela, com filtro por eventType na leitura"
    - "Mensagem legivel montada no servidor por tipo a partir de campos validados; evento invalido descartado inteiro"
    - "404 unico para outra empresa, inexistente, sem eventos e empresas misturadas (nao revela existencia)"
    - "Desempate de ordenacao por rank de ciclo de vida entre occurredAt e sortKey"

key-files:
  created:
    - notification-service/src/main/java/com/orderflow/notification/history/dto/OrderLifecycleEvent.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationNotFoundException.java
    - notification-service/src/test/java/com/orderflow/notification/OrderTimelineIT.java
    - notification-service/src/test/java/com/orderflow/notification/OrderTimelineControllerIT.java
  modified:
    - localstack-init/ready.d/01-create-notification-resources.sh
    - docker-compose.yml
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationRecord.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationRepository.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationController.java
    - notification-service/src/main/java/com/orderflow/notification/history/dto/NotificationResponse.java
    - notification-service/src/main/java/com/orderflow/notification/config/GlobalExceptionHandler.java
    - notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java
    - notification-service/src/test/java/com/orderflow/notification/NotificationEventFlowIT.java
    - notification-service/src/test/java/com/orderflow/notification/NotificationStoreUnavailableIT.java

key-decisions:
  - "NOTIFICATION_PK=entityId: nome generico da particao; NotificationResponse.entityId substitui o campo de produto (sem consumidores externos)"
  - "PRODUCT_HISTORY_ROUTE=kept: GET /notifications/{productId} inalterado (so SELLER_ADMIN), sem colidir com /notifications/orders/{orderId}"
  - "TIMELINE_ORDER=occurredAt,lifecycle-rank,sortKey nas duas rotas (rank ORDER_CREATED=1, PENDING_APPROVAL=2, APPROVED/REJECTED=3, CONFIRMED/CANCELLED=4, SHIPPED=5, DELIVERED=6, outros=0)"
  - "TIMELINE_READ_RULE: SELLER_ADMIN recebe a lista (vazia sem eventos); BUYER recebe 404 se a lista ORDER_* estiver vazia ou algum registro tiver companyId diferente do JWT; a rota de pedido so devolve eventType ORDER_*"
  - "total da mensagem de ORDER_CREATED sempre com duas casas (setScale(2, HALF_UP)), independente de como o produtor serializou; o rawPayload preserva o valor original"

requirements-completed: [ORD-10]

duration: ~14 min
completed: 2026-10-01

coverage:
  - id: D1
    description: "Evento ORDER_* valido na notification-events-queue vira item da tabela com particao entityId = orderId, sort key eventType#eventId e companyId, consultavel em GET /notifications/orders/{orderId} em ate 15 s"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderTimelineIT#orderCreatedPublishedToQueueAppearsInOrderTimelineWithinFifteenSeconds"
        status: pass
      - kind: integration
        ref: "OrderTimelineIT#storedItemCarriesCompanyIdAndDeterministicSortKey"
        status: pass
    human_judgment: false
  - id: D2
    description: "Os oito tipos ORDER_* validados por tipo com mensagem legivel exata; tipo desconhecido, campo ausente, UUID invalido, trackingCode/cancellationCode fora do padrao e texto acima do teto descartados sem gravar"
    requirement: "ORD-10"
    verification:
      - kind: unit
        ref: "NotificationServiceTest#everyOrderEventTypeProducesItsExactReadableMessage (9 casos)"
        status: pass
      - kind: unit
        ref: "NotificationServiceTest#invalidOrderEventsAreDiscardedWhole (11 casos)"
        status: pass
      - kind: integration
        ref: "OrderTimelineIT#invalidTrackingCodeIsDiscardedAndListenerStaysAlive"
        status: pass
    human_judgment: false
  - id: D3
    description: "Linha do tempo em ordem de ciclo de vida mesmo com eventos de mesmo occurredAt fora de ordem"
    requirement: "ORD-10"
    verification:
      - kind: unit
        ref: "NotificationServiceTest#historyForOrderBreaksSameInstantTiesByLifecycleRankNotBySortKey"
        status: pass
      - kind: integration
        ref: "OrderTimelineIT#fullJourneyPublishedShuffledComesBackInLifecycleOrderEvenWithSameInstant"
        status: pass
      - kind: integration
        ref: "OrderTimelineIT#sadPathCreatedPendingRejectedKeepsOrderAndRejectionReason"
        status: pass
    human_judgment: false
  - id: D4
    description: "Reentregar o mesmo evento grava um unico registro (chave deterministica, putItem sem condicao)"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderTimelineIT#sameConfirmedEventPublishedTwiceYieldsASingleTimelineEntry"
        status: pass
    human_judgment: false
  - id: D5
    description: "Autorizacao da linha do tempo (IDOR): SELLER_ADMIN ve qualquer pedido, BUYER so o da propria empresa, 404 identico para outra empresa/inexistente/sem eventos/empresas misturadas, 401, 403 sem company_id, 400 para nao-UUID, sem vazamento nos erros"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "OrderTimelineControllerIT (7 testes)"
        status: pass
      - kind: unit
        ref: "NotificationServiceTest#buyerGetsNotFoundForAnotherCompanyEmptyOrMixedCompanies"
        status: pass
    human_judgment: false
  - id: D6
    description: "Historico de produto da Fase 3 inalterado (GET /notifications/{productId} so SELLER_ADMIN, STOCK_ADJUSTED, mesma ordenacao) com o campo entityId"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "NotificationEventFlowIT, NotificationControllerIT, NotificationDeliveryIT, NotificationStoreUnavailableIT, OpenApiDocsIT (suite completa)"
        status: pass
      - kind: integration
        ref: "OrderTimelineControllerIT#buyerStillCannotReadTheProductHistoryRoute"
        status: pass
    human_judgment: false
  - id: D7
    description: "Init hook recria a tabela com o key-schema antigo num LocalStack que nao reiniciou, e o healthcheck do compose so fica saudavel com a particao entityId"
    requirement: "ORD-10"
    verification:
      - kind: command
        ref: "bash -n 01-create-notification-resources.sh; docker compose config --quiet; grep -c 'grep -q entityId' docker-compose.yml"
        status: pass
    human_judgment: true
    rationale: "A criacao com entityId e exercitada pelos ITs (o hook real roda no LocalStack do Testcontainers), mas o ramo de recriacao do key-schema antigo e o healthcheck do compose so foram validados por sintaxe/config; nenhum teste automatizado sobe um LocalStack com a tabela antiga nem a stack do compose."
---

# Phase 6 Plan 04: Linha do tempo do pedido no notification-service Summary

**O notification-service passa a guardar e servir a jornada do pedido: tabela `notification-history` com particao generica `entityId`, oito eventos `ORDER_*` validados por tipo com mensagem legivel montada no servidor, ordenacao por ciclo de vida e `GET /notifications/orders/{orderId}` para o vendedor e para o comprador da propria empresa (404 identico para o resto).**

## Accomplishments

- Particao `entityId` no init hook (com recriacao do key-schema antigo, Pitfall 11) e no healthcheck do LocalStack; `NotificationRecord.getEntityId()` como `@DynamoDbPartitionKey` e novo atributo `companyId` (nulo nos itens de produto); `findByEntityId`.
- `OrderLifecycleEvent` (DTO do contrato, `TYPES` na ordem do ciclo) e `NotificationService#record` despachando por `eventType`: `STOCK_ADJUSTED` segue o caminho da Fase 3; os oito `ORDER_*` vao para `recordOrderEvent` com obrigatorios por tipo, tetos 64/500, padroes de `trackingCode` e `cancellationCode`, `total >= 0`. Evento invalido e descartado inteiro (`InvalidNotificationEventException`), nunca gravado parcialmente.
- Mensagens exatas por tipo (aprovacao automatica reconhecida por `decidedBy = SYSTEM`; sufixo de motivo so na aprovacao manual).
- `lifecycleRank` entre `occurredAt` e `sortKey` nas duas rotas: `ORDER_CREATED` antes de `ORDER_APPROVED` mesmo no mesmo instante (a sort key sozinha poria APPROVED antes).
- `historyForOrder(orderId, callerCompanyId, sellerView)` e `GET /notifications/orders/{orderId}` com `hasAnyRole('SELLER_ADMIN','BUYER')`; `company_id` so do JWT (`requireCompanyId` no molde do order-service); `NotificationNotFoundException` -> 404 `order_not_found` no `GlobalExceptionHandler`.

## Decisoes registradas (Claude's Discretion)

```
NOTIFICATION_EVENT_CONTRACT={"eventId":"<uuid>","eventType":"ORDER_CONFIRMED","occurredAt":"2026-09-30T12:00:00.123456Z","orderId":"<uuid>","companyId":"<uuid>","carrier":"Expresso Cerrado","trackingCode":"AB123456789BR"}
  (envelope plano, campos nulos omitidos; obrigatorios por tipo: ORDER_CREATED createdBy<=64+total>=0;
   ORDER_PENDING_APPROVAL nenhum; ORDER_APPROVED decidedBy<=64, reason opcional<=500;
   ORDER_REJECTED decidedBy<=64+reason<=500; ORDER_CONFIRMED carrier<=64+trackingCode ^[A-Z]{2}[0-9]{9}BR$;
   ORDER_CANCELLED cancellationCode ^[A-Z_]{1,40}$+cancellationReason<=500; ORDER_SHIPPED shippedBy<=64;
   ORDER_DELIVERED deliveredBy<=64; decidedBy=SYSTEM na aprovacao automatica)
NOTIFICATION_PK=entityId
PRODUCT_HISTORY_ROUTE=kept
TIMELINE_ORDER=occurredAt,lifecycle-rank,sortKey
TIMELINE_READ_RULE=SELLER_ADMIN lista (vazia sem eventos); BUYER 404 se vazia ou algum companyId != JWT; rota de pedido so devolve ORDER_*
```

06-05 (order-service, produtor) deve publicar exatamente esse envelope; o `total` pode ir com qualquer escala (a mensagem normaliza para duas casas).

## Task Commits

1. **Task 1 (tracer): ORDER_CREATED na linha do tempo sobre particao generica entityId** - `ec61534` (feat)
2. **Task 2: oito tipos ORDER_* validados por tipo, mensagens e desempate por ciclo de vida** - `f26ee81` (feat)
3. **Task 3: leitura SELLER/BUYER com 404 identico, hook recria tabela antiga, healthcheck entityId** - `da4214f` (feat)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `total` de ORDER_CREATED perdia a escala na mensagem**
- **Found during:** Task 1 (teste `orderCreatedProducesRecordPartitionedByOrderIdWithCompanyAndMessage`)
- **Issue:** `40.00` lido para a arvore `JsonNode` virava `double`, e a mensagem saia `Pedido criado — total 40.0`, divergindo do texto exigido (`total 40.00`).
- **Fix:** o leitor estrito passou a usar `USE_BIG_DECIMAL_FOR_FLOATS` e a mensagem formata `total` com `setScale(2, RoundingMode.HALF_UP).toPlainString()` (valor monetario, independente da escala do produtor). O `rawPayload` preserva o valor original.
- **Files modified:** `NotificationService.java`
- **Commit:** `ec61534`

**Total deviations:** 1 auto-fixed (1 bug). **Impact:** nenhum no escopo; o plano dizia "total em texto simples" e o exemplo ja era `40.00`.

Observacao de processo: os testes de cada task foram escritos antes e o RED confirmado (erro de compilacao na Task 1, falhas reais na Task 2); testes e implementacao de cada task entraram no mesmo commit, como em 06-02 e 06-03. Os testes unitarios da regra BUYER (`historyForOrder` com tres argumentos) foram adicionados alem do exigido, na Task 3.

## Verificacao

- `./mvnw -B -pl notification-service verify` verde contra LocalStack real via Testcontainers (Docker disponivel; nenhuma stack do compose de pe): 51 testes unitarios e 32 ITs, 0 falhas (`OrderTimelineIT` 6, `OrderTimelineControllerIT` 7, `NotificationControllerIT` 6, `NotificationDeliveryIT` 4, `NotificationEventFlowIT` 4, `OpenApiDocsIT` 4, `NotificationStoreUnavailableIT` 1).
- Criterios de aceitacao: `AttributeName=entityId` aparece 2 vezes no hook; `table-not-exists` presente; `bash -n` no hook sem erro; `docker compose config --quiet` valido e `grep -q entityId` no healthcheck; nenhuma ocorrencia de `findByProductId`/`getProductId` fora de comentarios; `/orders/{orderId}` com `hasAnyRole('SELLER_ADMIN','BUYER')` e `/{productId}` com `hasRole('SELLER_ADMIN')`; handler 404 `order_not_found`; `lifecycleRank` em `NotificationService`; mensagem de confirmacao afirmada literalmente em `NotificationServiceTest`.
- Tracer gate: `<verify>` do tracer reexecutado e verde antes da expansao.

## Known Stubs

None.

## Threat Flags

None - superficie nova (rota `/notifications/orders/{orderId}`, mais um tipo de evento na fila) ja coberta por T-06-14 a T-06-18, todos mitigados.

## Issues Encountered

- O ramo de recriacao do key-schema antigo do hook e o healthcheck novo do compose NAO foram exercitados de ponta a ponta: nenhum teste sobe um LocalStack com a tabela antiga nem a stack do compose; so sintaxe (`bash -n`), `docker compose config` e a criacao com `entityId` via os ITs. O smoke com a stack real fica para 06-07.
- Suposicoes carregadas do plano (nao testadas aqui): SELLER_ADMIN consultando pedido inexistente recebe 200 `[]`; a linha do tempo e eventualmente consistente (relay do outbox + fila).
- Limitacao documentada (T-06-16): o controle de acesso a fila e da infraestrutura (IAM em AWS real); no LocalStack um evento forjado e tratado pela regra de `companyId` divergente, que vira 404 para qualquer BUYER.

## Next Phase Readiness

Pronto para 06-05: o consumidor aceita os oito tipos no formato do NOTIFICATION_EVENT_CONTRACT; falta o order-service publicar esses eventos (producer) e os endpoints de enviar/entregar ja existentes (06-02) gerarem `ORDER_SHIPPED`/`ORDER_DELIVERED`.

## Self-Check: PASSED

- Arquivos criados presentes: OrderLifecycleEvent.java, NotificationNotFoundException.java, OrderTimelineIT.java, OrderTimelineControllerIT.java.
- Commits `ec61534`, `f26ee81` e `da4214f` existem em `git log`; `git rev-list --count 1516a90..HEAD` = 3 no momento da escrita.
