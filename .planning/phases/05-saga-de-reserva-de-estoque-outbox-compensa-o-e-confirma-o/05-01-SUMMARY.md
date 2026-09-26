---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
plan: "01"
subsystem: order-service
tags: [saga, transactional-outbox, sqs, spring-cloud-aws, flyway, order-service]

requires:
  - phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
    provides: "OrderService/OrderDecisionService com aprovação automática e manual sob company_credit_lock; OrderStatus com os 8 estados da ORD-10"
provides:
  - "Status RESERVING: toda entrada em aprovação (automática ou manual) grava direto RESERVING + comando ReserveStock no outbox, na mesma transação"
  - "Padrão Transactional Outbox no order-service: entidade outbox_event, repositório com SELECT...FOR UPDATE SKIP LOCKED, escritor (OutboxWriter), relay @Scheduled (OutboxRelay/OutboxRelayJob) que publica no SQS real isolando falha por evento"
  - "Ponto de entrada único da saga (ReservationSagaStarter), chamado pela aprovação automática e pela manual"
  - "Migração V2 do order-service: 9 estados no CHECK, colunas de cancelamento/confirmação, tabela outbox_event, e migração de dados de pedidos APPROVED legados para RESERVING com comando no outbox"
  - "Filas inventory-commands-queue/order-events-queue com suas DLQs, criadas pelo init hook do LocalStack"
  - "order-service ligado ao LocalStack no docker-compose (SPRING_CLOUD_AWS_ENDPOINT, depends_on healthy)"
affects: [05-02, 05-03, 05-04, 05-05, 05-06]

actuals:
  tokens: 28027
  tasks: 2
  commits: 4
  plan_head_before: dda827c0b733e7c5bafa25e84703e5163468be63

tech-stack:
  added: [spring-cloud-aws-starter-sqs (order-service), testcontainers-localstack (order-service, test), awaitility (order-service, test)]
  patterns:
    - "Transactional Outbox duplicado por serviço (D-62): entidade OutboxEvent + repositório com {h-schema} e FOR UPDATE SKIP LOCKED, ordenado por attempts,created_at,id (OUTBOX_RELAY_DEFAULTS) — mesma técnica do CompanyCreditLockRepository, aplicada agora à publicação assíncrona"
    - "Relay em bean separado do @Scheduled trigger (OutboxRelay vs OutboxRelayJob) para não bypassar o proxy transacional por auto-invocação"
    - "Ponto de entrada único da saga (ReservationSagaStarter) com Propagation.MANDATORY, chamado por dois callers (criação automática e decisão manual) na mesma transação que já segura a trava da empresa"

key-files:
  created:
    - order-service/src/main/resources/db/migration/V2__order_reservation_saga.sql
    - order-service/src/main/java/com/orderflow/order/saga/ReservationSagaStarter.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxEvent.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxEventRepository.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxWriter.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
    - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelayJob.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ReserveStockCommand.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ReservationLine.java
    - order-service/src/main/java/com/orderflow/order/config/SqsMessagingConfig.java
    - order-service/src/main/java/com/orderflow/order/config/SchedulingConfig.java
    - localstack-init/ready.d/02-create-order-saga-resources.sh
    - order-service/src/test/java/com/orderflow/order/support/LocalStackTestSupport.java
    - order-service/src/test/java/com/orderflow/order/support/LocalStackProvisioningWaiter.java
    - order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java
    - order-service/src/test/java/com/orderflow/order/OrderSagaMigrationIT.java
    - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java
  modified:
    - order-service/pom.xml
    - order-service/src/main/resources/application.yml
    - order-service/src/test/resources/application-test.yml
    - order-service/src/main/java/com/orderflow/order/order/OrderStatus.java
    - order-service/src/main/java/com/orderflow/order/order/Order.java
    - order-service/src/main/java/com/orderflow/order/order/OrderService.java
    - order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java
    - order-service/src/test/java/com/orderflow/order/support/OrderTestInfrastructure.java
    - order-service/src/test/java/com/orderflow/order/OrderControllerIT.java
    - order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java
    - order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java
    - order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java
    - order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java
    - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
    - docker-compose.yml

key-decisions:
  - "SAGA_MESSAGE_CONTRACT: envelope plano eventId/eventType/occurredAt + campos do tipo, mesma convenção do StockAdjustedEvent/NOTIFICATION_EVENT_CONTRACT da Fase 3. ReserveStock (order → inventory-commands-queue): eventId, eventType=ReserveStock, occurredAt, orderId, reservationId (=orderId.toString(), RESERVATION_ID_SCOPE=scope-per-product), items=[{productId, quantity}] na ordem de lineNumber."
  - "SAGA_TIMEOUT_CLOCK=reservation_started_at — coluna própria gravada na entrada em RESERVING; o job de timeout (05-04) conta a partir dela, não de decided_at, para que um pedido APPROVED legado migrado pela V2 não seja cancelado por timeout no primeiro ciclo."
  - "OUTBOX_RELAY_DEFAULTS: relay-interval 1000ms em produção / 200ms em teste, batch-size 20, ordem attempts,created_at,id — ordenar primeiro por attempts impede que eventos que falham sempre ocupem o lote inteiro."
  - "[Deviation Rule 1] OrderApprovalIT tinha uma asserção de status APPROVED para um pedido criado automaticamente dentro do limite (via POST /orders) rotulada no plano como 'aprovação manual' por engano de contagem de linha — corrigida para RESERVING, já que o código de Task 1 transiciona toda aprovação automática para RESERVING antes de montar a resposta."

requirements-completed: [ORD-04]

coverage:
  - id: D1
    description: "Aprovação automática dentro do limite grava RESERVING + ReserveStock no outbox na mesma transação, e o relay publica na fila real do LocalStack sem atributo JavaType"
    requirement: "ORD-04"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java#withinLimitOrderEntersReservingWithOutboxRowAndCommandReachesTheRealQueue"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java#twoItemOrderPublishesItemsInLineOrder"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java#reservingOrderConsumesCreditAndBlocksASecondOrderOverTheLimit"
        status: pass
    human_judgment: false
  - id: D2
    description: "Migração V2 move pedidos APPROVED legados para RESERVING e insere um comando ReserveStock por pedido no outbox, itens na ordem de line_number"
    requirement: "ORD-04"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderSagaMigrationIT.java#applyingV2OverAV1BaseMovesEveryApprovedOrderToReservingAndInsertsOneReserveStockEventPerOrder"
        status: pass
    human_judgment: false
  - id: D3
    description: "Aprovação manual do vendedor entra no mesmo ponto de entrada da saga (RESERVING + outbox), sob a mesma trava; rejeição nunca inicia a saga; dez aprovações simultâneas geram exatamente uma linha ReserveStock"
    requirement: "ORD-04"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java#sellerAdminApprovesPendingOrderAndDecisionIsRecordedAndConsumesCredit"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java#tenSimultaneousApprovalsOfTheSamePendingOrderYieldExactlyOneWinner"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java#approvalAndRejectionFiredTogetherOnTheSamePendingOrderNeverBothSucceed"
        status: pass
    human_judgment: false
  - id: D4
    description: "O relay isola falha de envio por evento — um evento com erro de envio não impede a publicação dos demais do lote, e um eventType desconhecido é registrado como falha sem envio"
    requirement: "ORD-04"
    verification:
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java#oneFailingSendDoesNotPreventTheOtherTwoEventsOfTheSameBatchFromBeingPublished"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java#unknownEventTypeIsRecordedAsFailureWithoutSendingAnything"
        status: pass
    human_judgment: false
  - id: D5
    description: "Filas inventory-commands-queue e order-events-queue existem com RedrivePolicy apontando para suas DLQs e maxReceiveCount 3, criadas só pelo init hook do LocalStack"
    requirement: "ORD-04"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java#sagaQueuesExistWithDeadLetterQueueAndMaxReceiveCountThree"
        status: pass
    human_judgment: false
  - id: D6
    description: "docker-compose.yml liga o order-service ao LocalStack saudável (healthcheck confere as duas filas novas) — configuração local, sem teste automatizado no módulo Java"
    verification:
      - kind: other
        ref: "POSTGRES_DB=x POSTGRES_USER=x POSTGRES_PASSWORD=x LOCALSTACK_AUTH_TOKEN=x docker compose config --quiet"
        status: pass
    human_judgment: true
    rationale: "docker compose config valida sintaxe, não o comportamento real de subida da stack completa — confirmação humana/smoke real fica para a demonstração da fase, não coberta por teste Java neste plano"

duration: 33min
completed: 2026-09-26
status: complete
---

# Phase 5 Plan 1: Saga de Reserva — Outbox no order-service Summary

**Aprovação automática e manual do pedido gravam direto RESERVING + comando `ReserveStock` na tabela `outbox_event`, na mesma transação da trava de crédito, e um relay `@Scheduled` publica na fila real `inventory-commands-queue` do LocalStack — nenhum pedido avançado sem comando, nenhum comando sem pedido avançado.**

## Performance

- **Duration:** ~33 min
- **Started:** 2026-09-26T10:30:00-03:00 (aprox., commit anterior dda827c)
- **Completed:** 2026-09-26T11:03:34-03:00
- **Tasks:** 2
- **Files modified:** 32 (17 criados, 15 modificados)

## Accomplishments

- Status `RESERVING` incluído na ORD-10 (migração V2), com `reservation_started_at` como relógio próprio do timeout futuro (05-04) — `APPROVED` vira passo lógico da decisão, nunca estado persistido de pedido novo (D-50).
- Padrão Transactional Outbox implementado no order-service: `outbox_event` (V2), `OutboxEventRepository` com `SELECT ... FOR UPDATE SKIP LOCKED` (`{h-schema}`), `OutboxWriter` (grava na transação de negócio) e `OutboxRelay`/`OutboxRelayJob` (`@Scheduled`, publica no SQS isolando falha por evento).
- `ReservationSagaStarter` é o ponto de entrada único da saga — chamado tanto por `OrderService.createWithCreditCheck` (aprovação automática, Task 1) quanto por `OrderDecisionService.approve` (aprovação manual, Task 2), sempre na mesma transação que já segura `company_credit_lock`.
- Migração de dados D-51: pedidos `APPROVED` legados de uma base da Fase 4 migram para `RESERVING` com um comando `ReserveStock` no outbox por pedido, provado isoladamente por `OrderSagaMigrationIT` (JUnit puro, sem Spring, aplica a V1 e depois a V2).
- Filas `inventory-commands-queue`/`order-events-queue` com suas DLQs (`maxReceiveCount` 3) criadas pelo novo init hook do LocalStack; `docker-compose.yml` liga o `order-service` ao `localstack` saudável.

## Task Commits

Cada task seguiu o ciclo RED → GREEN (TDD):

1. **Task 1 RED** — `e60a8a7` (test): testes com falha intencional (3/5 casos do tracer falham no assert de status RESERVING) + todo o scaffolding de produção necessário para compilar, sem o wiring em `OrderService`.
2. **Task 1 GREEN** — `239123c` (feat): `OrderService.createWithCreditCheck` chama `sagaStarter.start` após aprovação automática — suíte inteira (53 ITs + 15 unitários) verde.
3. **Task 2 RED** — `55c2a05` (test): testes de aprovação manual com falha intencional (2 casos falham no assert de status/contagem de outbox) + `OrderSagaMigrationIT`/`OutboxRelayTest` (já verdes, comportamento da Task 1) + compose atualizado.
4. **Task 2 GREEN** — `eeea85b` (feat): `OrderDecisionService.approve` chama `sagaStarter.start` após aprovação manual — suíte inteira (54 ITs + 19 unitários) verde.

**Plan metadata:** commit deste SUMMARY (a seguir).

## Files Created/Modified

- `order-service/src/main/resources/db/migration/V2__order_reservation_saga.sql` — 9 estados no CHECK, colunas da saga, tabela `outbox_event`, migração de dados D-51
- `order-service/src/main/java/com/orderflow/order/saga/ReservationSagaStarter.java` — ponto de entrada único da saga
- `order-service/src/main/java/com/orderflow/order/saga/outbox/*.java` — entidade, repositório, escritor, relay e job do outbox
- `order-service/src/main/java/com/orderflow/order/saga/messaging/dto/*.java` — `ReserveStockCommand`/`ReservationLine`
- `order-service/src/main/java/com/orderflow/order/config/{SqsMessagingConfig,SchedulingConfig}.java` — configuração SQS e `@EnableScheduling`
- `localstack-init/ready.d/02-create-order-saga-resources.sh` — filas da saga com DLQ (modo `100755`)
- `order-service/src/main/java/com/orderflow/order/order/{OrderStatus,Order,OrderService,OrderDecisionService}.java` — `RESERVING`, `startReservation`, wiring da saga nos dois caminhos de aprovação
- `docker-compose.yml` — `order-service` depende do `localstack` saudável, healthcheck confere as filas novas
- Testes: `ReservationCommandPublishingIT`, `OrderSagaMigrationIT`, `OutboxRelayTest`, suporte LocalStack copiado do inventory-service, e ajuste mecânico de `OrderControllerIT`/`CreditLockAndExposureIT`/`CreditLimitBoundaryConcurrencyIT`/`OrderApprovalIT`/`OrderDecisionConcurrencyIT`/`OrderDomainTest`

## Decisions Made

- `SAGA_MESSAGE_CONTRACT`, `SAGA_TIMEOUT_CLOCK=reservation_started_at` e `OUTBOX_RELAY_DEFAULTS` registrados em `key-decisions` do frontmatter (linhas exigidas pelo `<output>` do plano).
- `CANCELLATION_CODE_CHECK` já criado na V2 (colunas `cancellation_code`/`cancellation_reason`/`cancelled_at`/`confirmed_at`), mapeamento na entidade fica para `05-03` (`ddl-auto: validate` aceita coluna não mapeada).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `OrderApprovalIT` — assert de status corrigido de APPROVED para RESERVING num pedido auto-aprovado**
- **Found during:** Task 1 (ajuste mecânico dos testes existentes)
- **Issue:** O plano listou a asserção de `autoApprovedResult` (pedido criado via `POST /orders`, decidido automaticamente como `SYSTEM`) entre as "asserções de aprovação manual... não tocar" de `OrderApprovalIT`, mas essa asserção é sobre um pedido que passa pelo MESMO código de aprovação automática mudado na Task 1 — deixá-la em APPROVED quebraria a suíte, já que o código agora transiciona automaticamente para RESERVING antes de montar a resposta.
- **Fix:** Asserção alterada para `RESERVING` (comentário explicando o motivo), mantendo intactas as três asserções genuinamente de aprovação manual (linhas originais 68, 84, 117).
- **Files modified:** `order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java`
- **Verification:** `./mvnw -B -pl order-service verify` inteiro verde, incluindo `OrderApprovalIT`.
- **Committed in:** `e60a8a7` (Task 1 RED commit — parte do ajuste mecânico)

**2. [Rule 3 - Bloqueio] Correção de escape de aspas no init hook do LocalStack**
- **Found during:** Task 1 (primeira tentativa do `ReservationCommandPublishingIT`, RedrivePolicy)
- **Issue:** `printf '{\"deadLetterTargetArn\":...}'` dentro de aspas simples não produz o escape esperado em bash — o `\"` chega literal, gerando um `--attributes` JSON malformado, e a fila principal nascia sem `RedrivePolicy`, causando `QueueDoesNotExistException`/timeout de provisionamento no teste.
- **Fix:** Construção do JSON interno sem escape, seguida de um `sed 's/"/\\"/g'` explícito para escapar as aspas antes de montar o atributo `--attributes`.
- **Files modified:** `localstack-init/ready.d/02-create-order-saga-resources.sh`
- **Verification:** `ReservationCommandPublishingIT` (RedrivePolicy/DLQ) e o full `verify` passam contra o LocalStack real.
- **Committed in:** `e60a8a7` (Task 1 RED commit)

---

**Total deviations:** 2 auto-fixed (1 bug de teste, 1 bloqueio de script)
**Impact on plan:** Ambos necessários para a suíte compilar/passar corretamente contra a implementação real; nenhum scope creep — nenhuma funcionalidade nova além do especificado.

## Issues Encountered

- A ferramenta `gsd_run check tdd-red-evidence` espera saída no formato TAP (Node test runner) e não interpreta a saída do Maven Surefire/Failsafe — não foi possível usá-la para validar o RED automaticamente. RED foi capturado manualmente e documentado nas mensagens de commit de teste: para a Task 1, `ReservationCommandPublishingIT` com o wiring do `OrderService` temporariamente removido reportou 3 de 5 testes falhando no assert `"$.status" expected:<RESERVING> but was:<APPROVED>`; para a Task 2, `OrderApprovalIT`/`OrderDecisionConcurrencyIT` com o wiring do `OrderDecisionService` removido reportaram falhas equivalentes (status e contagem de outbox) — ambos GREEN confirmados depois de restaurar o wiring e rodar `./mvnw -B -pl order-service verify` inteiro.

## User Setup Required

None - nenhuma configuração de serviço externo nova (o `LOCALSTACK_AUTH_TOKEN` já era exigido desde a Fase 1).

## Next Phase Readiness

- Order-service pronto para `05-02` (outbox espelhado no inventory-service e o `@SqsListener` que consome `ReserveStock`): o contrato `ReserveStockCommand`/fila `inventory-commands-queue` já está publicando na stack real.
- `SAGA_TIMEOUT_CLOCK=reservation_started_at` já gravado — `05-04` (job de timeout) pode contar a partir dele sem migração adicional.
- Colunas de cancelamento/confirmação já existem na V2 (não mapeadas ainda) — `05-03` só precisa mapear na entidade `Order`, sem nova migração.
- Nenhum bloqueio conhecido para as próximas fatias do fluxo order → inventory → order.

---
*Phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o*
*Completed: 2026-09-26*

## Self-Check: PASSED

- Todos os arquivos-chave criados confirmados em disco (`[ -f ]`): migração V2, `ReservationSagaStarter`, `OutboxRelay`, init hook da saga, `ReservationCommandPublishingIT`, `OrderSagaMigrationIT`, `OutboxRelayTest`, este SUMMARY.
- Todos os 5 commits do plano confirmados em `git log --oneline --all`: `e60a8a7`, `239123c`, `55c2a05`, `eeea85b`, `acc9821`.
- `./mvnw -B -pl order-service clean verify` re-executado após o commit final: 19 testes unitários + 54 testes de integração, todos verdes.
