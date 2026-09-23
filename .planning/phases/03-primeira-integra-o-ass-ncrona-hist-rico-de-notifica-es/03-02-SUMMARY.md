---
phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
plan: "02"
subsystem: inventory
tags: [spring-cloud-aws, sqs, localstack, testcontainers, spring-retry, mockmvc, awaitility]

# Dependency graph
requires:
  - phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
    provides: "NOTIFICATION_EVENT_CONTRACT (03-01), fila notification-events-queue e init hook provisionados pelo LocalStack"
provides:
  - "inventory-service publica STOCK_ADJUSTED na fila notification-events-queue depois do commit de PUT /inventory/{productId} — lado produtor de NOTF-01 fechado"
  - "StockAdjustmentResult (previousQuantityOnHand capturado dentro da transacao/reexecucao) — tipo interno reaproveitavel por qualquer publicador futuro do inventory-service"
  - "Padrao de publicacao pos-commit no controller, nunca dentro do metodo @Transactional/@Retryable — referencia para a saga da Fase 5"
affects: [03-03-compose-gateway-docs, 05-saga-reserva-estoque]

# Actuals (#2632)
actuals:
  tokens: 11637
  tasks: 2
  commits: 2

plan_head_before: 26b150e880db27e6d8740a6eb1e51d2ad148741f

# Tech tracking
tech-stack:
  added:
    - "io.awspring.cloud:spring-cloud-aws-starter-sqs em inventory-service (producer; ja gerenciado pelo BOM 3.4.2 importado no pom raiz pelo plano 03-01)"
    - "org.testcontainers:localstack, org.awaitility:awaitility em inventory-service (escopo teste)"
  patterns:
    - "Publicacao do evento fora do metodo @Transactional/@Retryable: InventoryController publica depois que inventoryService.setStock(...) ja retornou, quando o proxy Spring ja commitou — nunca de dentro de InventoryService, que continua sem conhecer nada de mensageria"
    - "Tipo de retorno interno (StockAdjustmentResult) para levar dado nao-publico (quantidade anterior) do service ao controller sem poluir o contrato REST (StockResponse)"
    - "MessagingMessageConverter com doNotSendPayloadTypeHeader() do lado do produtor — espelha o payloadTypeMapper nulo do consumidor (03-01), nenhum lado depende do outro para nao vazar nome de classe interna"
    - "Falha de publicacao capturada e logada (ERROR com eventId/productId/fila), nunca relancada e nunca re-tentada — dual-write aceito como limitacao documentada (D-30), sem outbox parcial disfarcado de retry"
    - "LocalStackTestSupport/LocalStackProvisioningWaiter copiados do notification-service (03-01) para o pacote de teste do inventory-service, com a mesma imagem e o mesmo init hook real, mas esperando so pela fila (sem cliente DynamoDB no classpath deste modulo)"

key-files:
  created:
    - inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/StockAdjustmentResult.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockAdjustedEvent.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/messaging/StockEventPublisher.java
    - inventory-service/src/test/java/com/orderflow/inventory/support/LocalStackTestSupport.java
    - inventory-service/src/test/java/com/orderflow/inventory/support/LocalStackProvisioningWaiter.java
    - inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java
    - inventory-service/src/test/java/com/orderflow/inventory/StockEventPublishFailureIT.java
  modified:
    - inventory-service/pom.xml
    - inventory-service/src/main/resources/application.yml
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java
    - inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java
    - inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java

key-decisions:
  - "Corpo JSON observado por StockAdjustedEventPublishingIT (uuids trocados por <uuid>): {\"eventId\":\"<uuid>\",\"eventType\":\"STOCK_ADJUSTED\",\"productId\":\"<uuid>\",\"previousQuantityOnHand\":0,\"newQuantityOnHand\":7,\"occurredAt\":\"2026-09-23T02:26:12.244646700Z\"} — bate campo a campo com NOTIFICATION_EVENT_CONTRACT de 03-01-SUMMARY.md; nenhuma divergencia de nome/ordem de campo encontrada"
  - "Falha de publicacao mantem o 200 e registra ERROR (Assumption A4 de 03-RESEARCH.md confirmada como interpretacao correta de D-30): a transacao do ajuste ja foi commitada quando StockEventPublisher roda, entao falhar a requisicao diria ao vendedor que o ajuste nao aconteceu quando aconteceu"

patterns-established:
  - "Pattern: publicar evento de dominio a partir do controller, depois que a chamada ao servico transacional ja retornou, nunca de dentro do metodo @Transactional/@Retryable — evita o caso mais obvio de publicar um evento de um commit que ainda pode falhar, sem construir outbox"
  - "Pattern: tipo de retorno interno (record) para levar dado auxiliar do service ao controller sem expor esse campo no DTO de resposta HTTP publico"

requirements-completed: [NOTF-01]

coverage:
  - id: D1
    description: "PUT /inventory/{productId} bem-sucedido publica exatamente um evento STOCK_ADJUSTED na fila notification-events-queue depois do commit, com o corpo JSON de seis campos batendo com NOTIFICATION_EVENT_CONTRACT, previousQuantityOnHand correto (0 na criacao, valor anterior lido dentro da mesma transacao/reexecucao em seguida), eventId novo por ajuste, occurredAt ISO-8601, sem o atributo JavaType"
    requirement: "NOTF-01"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java#adjustingStockTwicePublishesOneContractMessagePerAdjustmentWithCorrectPreviousQuantity"
        status: pass
    human_judgment: false
  - id: D2
    description: "PUT recusado (400 quantidade negativa, 403 papel BUYER, 409 stock_below_reserved), reservar e liberar estoque nao publicam nenhum evento de ajuste (D-28)"
    requirement: "NOTF-01"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java#rejectedPutReservationAndReleaseDoNotPublishAnyEventBeyondTheOriginalAdjustment"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java#putWithNegativeQuantityReturns400AndDoesNotPublish"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java#putWithBuyerTokenReturns403AndDoesNotPublish"
        status: pass
    human_judgment: false
  - id: D3
    description: "Falha de publicacao no SQS (fila inexistente, queue-not-found-strategy=fail) nao derruba o PUT — resposta continua 200, ajuste continua gravado no PostgreSQL, e uma linha ERROR identifica o evento perdido com eventId/productId/fila (D-30)"
    requirement: "NOTF-01"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockEventPublishFailureIT.java#publishFailureDoesNotFailThePutKeepsTheAdjustmentAndLogsErrorWithProductId"
        status: pass
    human_judgment: false
  - id: D4
    description: "Suites de integracao ja existentes do inventory-service (Fase 2) continuam verdes com a assinatura interna alterada de setStock/recoverSetStock e o LocalStack novo na base de teste"
    verification:
      - kind: automated
        ref: "./mvnw -B -pl inventory-service verify (36/36 testes, BUILD SUCCESS)"
        status: pass
    human_judgment: false

duration: 20min
completed: 2026-09-22
status: complete
---

# Phase 3 Plan 2: Inventory-service publisher — stock adjustment publishes STOCK_ADJUSTED after commit Summary

**O ajuste de estoque existente da Fase 2 (`PUT /inventory/{productId}`) publica, depois do commit, um evento `STOCK_ADJUSTED` na fila SQS real que o `notification-service` consome — sem outbox nesta fase (D-29), sem chamada síncrona entre os dois serviços, e sem que uma falha de publicação derrube a resposta HTTP já commitada.**

## Performance

- **Duration:** ~20 min (Task 1 tracer incluiu a maior parte do tempo — subida do container LocalStack real; Task 2 rodou sem incidentes além do RED esperado)
- **Tasks:** 2 (`type="tracer"` e `type="auto"`, ambas `tdd="true"`)
- **Files modified:** 14 (8 criados, 6 modificados)

## NOTIFICATION_EVENT_CONTRACT — corpo observado

Corpo JSON real recebido pelo teste (UUIDs trocados por `<uuid>`):

```json
{"eventId":"<uuid>","eventType":"STOCK_ADJUSTED","productId":"<uuid>","previousQuantityOnHand":0,"newQuantityOnHand":7,"occurredAt":"2026-09-23T02:26:12.244646700Z"}
```

Bate campo a campo com `NOTIFICATION_EVENT_CONTRACT=eventId:UUID,eventType:String,productId:UUID,previousQuantityOnHand:Integer,newQuantityOnHand:Integer,occurredAt:Instant(ISO-8601)` registrado em `03-01-SUMMARY.md` — nenhuma divergência de nome, ordem ou tipo de campo. `previousQuantityOnHand`/`newQuantityOnHand` chegam como `int` (não `Integer`) do lado do produtor, mas isso é indiferente para o JSON — ambos serializam como número.

## Accomplishments

- `StockEventPublisher` (`inventory-service/stock/messaging`) publica `StockAdjustedEvent` na fila `notification-events-queue` via `SqsTemplate.send`, chamado pelo `InventoryController` depois que `inventoryService.setStock(...)` já retornou — nunca de dentro do método `@Transactional`/`@Retryable`, que continua sem qualquer import de mensageria
- `InventoryService.setStock`/`recoverSetStock` agora devolvem `StockAdjustmentResult` (record interno com `StockResponse` + `previousQuantityOnHand`), capturando a quantidade anterior dentro da mesma transação/tentativa de reexecução — nunca por uma leitura separada de fora, que sob concorrência poderia capturar um valor já desatualizado
- `SqsMessagingConfig` desliga o atributo `JavaType` do lado do produtor (`doNotSendPayloadTypeHeader()`), espelhando a defesa já existente do lado consumidor (03-01)
- Falha de publicação é capturada e registrada em nível ERROR (com `eventType`, `eventId`, `productId` e nome da fila), nunca relançada — o `PUT` continua devolvendo 200 e o ajuste continua gravado, mesmo quando a fila é inexistente
- Nenhum evento é publicado por `PUT` recusado (400/403/409), por reserva ou por liberação de estoque (D-28) — só o ajuste bem-sucedido gera evento
- Base de teste do `inventory-service` ganhou um `LocalStackContainer` real (copiado do `notification-service`, esperando só pela fila), ao lado do Postgres já existente — as 27 suites herdadas da Fase 2 continuam verdes sem nenhuma outra alteração
- 5 novos testes de integração contra LocalStack real (36/36 no total do módulo, `BUILD SUCCESS`)

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — o vendedor ajusta o estoque e o evento de contrato cai na fila real, depois do commit** — `996c1d5` (feat) — RED (compilação falha sem `StockEventPublisher`/`StockAdjustedEvent`/config novos) → GREEN (1/1 teste novo, depois `./mvnw -pl inventory-service verify` 32/32 completo)
2. **Task 2: O que não pode virar evento, e o que acontece quando o evento se perde** — `ad4cd9e` (feat) — RED (`StockEventPublishFailureIT` falhava com 500 antes do try/catch — `MessagingOperationFailedException` não capturada) → GREEN (5/5 novos, 36/36 no total do módulo)

**Plan metadata:** commit de documentação final a ser criado logo após este SUMMARY.

## Files Created/Modified

- `inventory-service/pom.xml` — adiciona `spring-cloud-aws-starter-sqs` (produção), `testcontainers:localstack` e `awaitility` (teste)
- `inventory-service/src/main/resources/application.yml` — `spring.cloud.aws.*` (region/credentials/endpoint/`queue-not-found-strategy: fail`), `orderflow.messaging.notification-events-queue`
- `inventory-service/.../config/SqsMessagingConfig.java` — conversor sem atributo de tipo
- `inventory-service/.../stock/StockAdjustmentResult.java` — tipo interno service→controller
- `inventory-service/.../stock/dto/StockAdjustedEvent.java` — contrato JSON do lado produtor
- `inventory-service/.../stock/messaging/StockEventPublisher.java` — publicação pós-commit, falha capturada e logada
- `inventory-service/.../stock/InventoryService.java` — `setStock`/`recoverSetStock` devolvem `StockAdjustmentResult`; captura da quantidade anterior dentro da transação/reexecução
- `inventory-service/.../stock/InventoryController.java` — `setStock` publica depois de `inventoryService.setStock(...)` retornar
- `inventory-service/src/test/.../support/{LocalStackTestSupport,LocalStackProvisioningWaiter}.java` — LocalStack real, copiado do `notification-service`, esperando só pela fila
- `inventory-service/src/test/.../AbstractIntegrationTest.java`, `StockReservationConcurrencyIT.java` — `@DynamicPropertySource` registrando propriedades do LocalStack
- `inventory-service/src/test/.../{StockAdjustedEventPublishingIT,StockEventPublishFailureIT}.java` — 5 testes novos contra LocalStack real

## Decisions Made

- **Corpo JSON observado e falha-mantém-200** — ver seções dedicadas acima; registradas para o plano `03-03` (compose/gateway/docs) e para a Fase 5 (outbox) usarem o formato e o comportamento observados, não os presumidos.
- Nenhuma decisão de Claude's Discretion nova além das já resolvidas em `03-01-PLAN.md`/`03-CONTEXT.md` — este plano seguiu D-27 a D-33 exatamente como travadas.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. O `.env` com `LOCALSTACK_AUTH_TOKEN` (resolvido subindo os diretórios pais a partir do módulo de teste) já estava disponível na raiz do repositório principal, herdado da Fase 1/plano `03-01` — nenhuma configuração nova foi necessária.

## User Setup Required

None - nenhuma configuração nova além da já registrada na Fase 1/plano `03-01` (`LOCALSTACK_AUTH_TOKEN`).

## Threat Flags

Nenhuma superfície nova além das já registradas no `<threat_model>` do plano (T-03-11 a T-03-16, T-03-SC) — todas com disposição `mitigate`/`accept` implementadas e cobertas por teste:
- T-03-11 (evento de commit que nunca aconteceu) — mitigada: publicação só depois do retorno bem-sucedido de `setStock`, provada por `rejectedPutReservationAndReleaseDoNotPublishAnyEventBeyondTheOriginalAdjustment`
- T-03-12 (evento perdido em falha do SQS) — aceita por decisão do usuário (D-29/D-30), log ERROR provado por `StockEventPublishFailureIT`
- T-03-13 (nome de classe interna vazando) — mitigada: `doNotSendPayloadTypeHeader()`, provada pela ausência de `SqsHeaders.SQS_DEFAULT_TYPE_HEADER`
- T-03-14 (indisponibilidade do SQS derrubando o ajuste) — mitigada: falha capturada, PUT continua 200
- T-03-15 (credencial AWS real/chamada acidental) — mitigada: credenciais literais `test`, endpoint sempre sobrescrito
- T-03-16 (token impresso em log) — mitigada: mesma resolução de token do `notification-service`, nunca imprime o valor
- T-03-SC (cadeia de suprimentos) — aceita: nenhuma dependência nova além das já auditadas em `03-RESEARCH.md`

## Known Stubs

Nenhum. O fluxo ajuste-de-estoque → evento-na-fila-real está implementado e testado de ponta a ponta contra LocalStack real, sem dado mock ou placeholder.

## Next Phase Readiness

- O plano `03-03` (compose, rota do Gateway, script de smoke, documentação) pode agora demonstrar o fluxo completo `PUT /inventory/{productId}` → `notification-events-queue` → `GET /notifications/{productId}`, usando o corpo JSON observado acima como referência.
- A Fase 5 (saga de reserva de estoque) reaproveita o padrão de publicação pós-commit e o tipo `StockAdjustmentResult` como referência de como levar dado interno do service ao controller sem poluir o contrato REST — mas precisa substituir o envio direto por Transactional Outbox quando a saga exigir atomicidade real entre DB e evento (D-29).
- Nenhum bloqueio conhecido.

---
*Phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es*
*Completed: 2026-09-22*

## Self-Check: PASSED

All key files and commit hashes verified present:
- `inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java`,
  `StockAdjustmentResult.java`, `StockAdjustedEvent.java`, `StockEventPublisher.java`,
  `LocalStackTestSupport.java`, `LocalStackProvisioningWaiter.java`,
  `StockAdjustedEventPublishingIT.java`, `StockEventPublishFailureIT.java` — all found.
- Commits `996c1d5`, `ad4cd9e` — all found in `git log --oneline --all`.
- `./mvnw -B -pl inventory-service verify` — BUILD SUCCESS, 36/36 tests.
