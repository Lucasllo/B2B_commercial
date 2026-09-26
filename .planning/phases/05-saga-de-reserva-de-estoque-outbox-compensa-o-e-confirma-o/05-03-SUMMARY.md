---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
plan: "03"
subsystem: order-service
tags: [saga, transactional-outbox, sqs, spring-cloud-aws, tdd, order-service]

requires:
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "01"
    provides: "SAGA_MESSAGE_CONTRACT (StockReserved/StockReservationFailed/ReleaseStock), colunas de cancelamento/confirmação já criadas na V2, OutboxWriter/OutboxRelay já roteando ReleaseStock"
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "02"
    provides: "inventory-service publicando StockReserved/StockReservationFailed na order-events-queue com reasonCode/failures prontos; lição do poll-timeout do SqsAsyncClient"
provides:
  - "order-service consome o resultado da reserva na order-events-queue e fecha o ciclo order -> inventory -> order: pedido termina sempre em CONFIRMED ou CANCELLED quando o resultado chega (ORD-05)"
  - "Colunas da saga (cancellation_code, cancellation_reason, cancelled_at, confirmed_at) mapeadas na entidade Order e expostas em OrderResponse (novo ORDER_RESPONSE_CONTRACT)"
  - "OrderSagaService: transição guardada pelo estado (sem tabela de mensagens processadas), trava de linha do pedido (findByIdForUpdate/PESSIMISTIC_WRITE) para serializar resultado x timeout (05-04) sobre o mesmo pedido (ORD-06, D-64)"
  - "Compensação de sucesso tardio: StockReserved para pedido já CANCELLED grava ReleaseStock no outbox na mesma transação (reason=LATE_RESERVATION), nunca deixa estoque órfão"
  - "CancellationReasons: mensagem de cancelamento legível montada por modelo fixo no servidor, nunca texto livre da mensagem (proteção contra Information Disclosure)"
affects: [05-04, 05-05, 05-06]

actuals:
  tokens: 23489
  tasks: 2
  commits: 4
  plan_head_before: 8af3e9b0d1b16562e29a9e91fc68d1fda46c83ea

tech-stack:
  added: []
  patterns:
    - "SagaEventParser: parser único que despacha por eventType e devolve Object (StockReservationFailedEvent ou StockReservedEvent) — o chamador distingue por instanceof, sem precisar de uma interface/arquivo dto próprio para o tipo comum"
    - "OrderSagaService guardado pelo estado (D-64): cada método (applyReservationFailed/applyStockReserved) trava a linha do pedido com findByIdForUpdate, checa o status atual e decide idempotentemente — nenhuma tabela de mensagens processadas"
    - "SAGA_RESULT_LOCK=order-row: a saga usa a trava de LINHA DO PEDIDO (findByIdForUpdate), não a company_credit_lock, para serializar resultado da reserva x job de timeout (05-04) sobre o mesmo pedido"
    - "Mesma lição de poll-timeout do 05-02, agora no order-service: o primeiro @SqsListener real de um serviço que compartilha o SqsAsyncClient com um RestClient síncrono de timeout curto precisa de spring.cloud.aws.sqs.listener.poll-timeout=0s + folga nos timeouts do cliente"

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/order/CancellationCode.java
    - order-service/src/main/java/com/orderflow/order/saga/CancellationReasons.java
    - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/InvalidSagaMessageException.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/ReservationResultListener.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/SagaEventParser.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ReleaseStockCommand.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ReservationFailureLine.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/StockReservationFailedEvent.java
    - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/StockReservedEvent.java
    - order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java
    - order-service/src/test/java/com/orderflow/order/saga/CancellationReasonsTest.java
    - order-service/src/test/java/com/orderflow/order/saga/messaging/SagaEventParserTest.java
    - order-service/src/test/java/com/orderflow/order/support/OrderSagaQueues.java
  modified:
    - order-service/src/main/java/com/orderflow/order/config/SqsMessagingConfig.java
    - order-service/src/main/java/com/orderflow/order/order/Order.java
    - order-service/src/main/java/com/orderflow/order/order/OrderRepository.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderResponse.java
    - order-service/src/main/resources/application.yml
    - order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java
    - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java

key-decisions:
  - "ORDER_RESPONSE_CONTRACT=id,companyId,status,total,createdBy,createdAt,decidedBy,decidedAt,reason,cancellationCode,cancellationReason,confirmedAt,cancelledAt,items[...] — os quatro campos novos entram depois de reason e antes de items"
  - "SAGA_RESULT_LOCK=order-row — OrderSagaService trava a linha do pedido (findByIdForUpdate, PESSIMISTIC_WRITE), não company_credit_lock; liberar crédito ao cancelar não estoura limite, mas resultado x timeout (05-04) disputam o mesmo pedido e precisam de uma trava comum"
  - "CANCELLATION_REASON_TEMPLATE — texto montado no servidor (CancellationReasons) a partir de modelo fixo por reasonCode, com o sku do snapshot do item (valor seguro já gravado no pedido) e os números do evento; truncado em 500 caracteres com '…'; nunca texto livre vindo da mensagem"
  - "STOCK_RESERVED_ITEMS_CHECK — StockReserved com itens diferentes dos do pedido (produto ou quantidade) é tratado como InvalidSagaMessageException e descartado com log; a fila é uma fronteira de confiança e um sucesso forjado não confirma pedido"
  - "SagaEventParser.parse devolve Object (StockReservationFailedEvent ou StockReservedEvent) em vez de uma interface marcadora própria — evita um arquivo dto extra não previsto no plano; o listener distingue por instanceof"

patterns-established:
  - "OrderSagaService guardado pelo estado, sem tabela de mensagens processadas (D-64) — mesmo espírito do replay idempotente do inventory-service (05-02), mas por trava de linha + checagem de status em vez de livro de reservas"

requirements-completed: [ORD-05, ORD-06]

coverage:
  - id: D1
    description: "StockReservationFailed (INSUFFICIENT_STOCK/PRODUCT_NOT_STOCKED/RESERVATION_CANCELLED) leva o pedido RESERVING a CANCELLED com código, motivo legível montado por modelo fixo, cancelledAt preenchido e decidedBy/decidedAt/reason intactos; crédito liberado"
    requirement: "ORD-05"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#insufficientStockCancelsOrderWithCodeAndReadableReasonAndReleasesCredit"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#productNotStockedCancelsOrderWithItsOwnReasonPrefix"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#reservationCancelledWithNoFailuresUsesFixedReasonText"
        status: pass
    human_judgment: false
  - id: D2
    description: "StockReserved com os itens do pedido confirma o pedido (CONFIRMED, confirmedAt preenchido, campos de cancelamento nulos); pedido CONFIRMED continua consumindo crédito"
    requirement: "ORD-05"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#stockReservedConfirmsOrderAndConsumesCreditSoNextOrderNeedsApproval"
        status: pass
    human_judgment: false
  - id: D3
    description: "Resultado duplicado (falha ou sucesso) é no-op na segunda entrega — cancelledAt/confirmedAt inalterados, guardado pelo estado sem tabela de mensagens processadas"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#duplicateFailureEventIsANoOpTheSecondTime"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#duplicateStockReservedIsANoOpTheSecondTime"
        status: pass
    human_judgment: false
  - id: D4
    description: "StockReserved tardio para pedido já CANCELLED mantém CANCELLED e grava, na mesma transação, um ReleaseStock no outbox (reason=LATE_RESERVATION) que o relay entrega na inventory-commands-queue; falha tardia após CONFIRMED mantém CONFIRMED"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#lateSuccessForCancelledOrderWritesReleaseStockToOutboxAndSendsCommand"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#lateFailureAfterConfirmedStaysConfirmed"
        status: pass
    human_judgment: false
  - id: D5
    description: "StockReserved cujos itens não batem com os do pedido, resultado para pedido inexistente, ou mensagem malformada/eventType desconhecido são descartados com log WARN sem mudar pedido algum; o listener continua processando"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#stockReservedWithMismatchedQuantityIsDiscardedAndOrderStaysReserving"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#unknownOrderMalformedBodyAndUnknownEventTypeAreDiscardedAndProcessingContinues"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/saga/messaging/SagaEventParserTest.java (23 casos)"
        status: pass
    human_judgment: false
  - id: D6
    description: "GET /orders/{id} expõe cancellationCode, cancellationReason, confirmedAt e cancelledAt; POST /orders e approve continuam devolvendo RESERVING com 201/200"
    requirement: "ORD-05"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java (suite completa, sem regressao)"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java#cancelFromReservingRecordsCodeReasonAndCancelledAtWithoutTouchingDecisionFields"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java#confirmFromReservingRecordsConfirmedAtElseThrowsWithoutChangingState"
        status: pass
    human_judgment: false
  - id: D7
    description: "Nenhum cancellationReason contém Exception, 'at com.orderflow' ou 'http://'; a mensagem é montada por modelo fixo (CancellationReasons), nunca texto livre vindo da fila (T-05-14)"
    requirement: "ORD-05"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java#cancellationReasonNeverLeaksStackTraceUrlOrExceptionText"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/saga/CancellationReasonsTest.java (6 casos)"
        status: pass
    human_judgment: false

duration: 34min
completed: 2026-09-26
status: complete
---

# Phase 5 Plan 3: Order-Service Consome o Resultado da Reserva — CONFIRMED, CANCELLED e Compensação Tardia Summary

**`ReservationResultListener` consome `StockReserved`/`StockReservationFailed` da `order-events-queue` e o `OrderSagaService`, guardado pelo estado (trava de linha do pedido), leva RESERVING a CANCELLED (com código e motivo legível) ou CONFIRMED — compensando com `ReleaseStock` pelo outbox um sucesso que chega tarde para um pedido já cancelado, fechando o ciclo order → inventory → order.**

## Performance

- **Duration:** ~34 min (commits) — sessão real mais longa por causa da leitura extensa de contexto (05-01/05-02, código existente do inventory-service para espelhar o parser/listener)
- **Started:** 2026-09-26T12:24:40-03:00 (aprox., commit anterior 8af3e9b)
- **Completed:** 2026-09-26T12:58:07-03:00
- **Tasks:** 2
- **Files modified:** 21 (14 criados, 7 modificados)

## Accomplishments

- `CancellationCode` (enum) + quatro colunas mapeadas em `Order` (`cancellationCode`, `cancellationReason`, `cancelledAt`, `confirmedAt`) + guarda `requireReserving()` — `Order#cancel`/`Order#confirm` só transicionam a partir de `RESERVING`, erro de programação a partir de qualquer outro estado (D-53).
- `OrderResponse` com o novo `ORDER_RESPONSE_CONTRACT` (quatro campos novos depois de `reason`, antes de `items`).
- `SagaEventParser`/`ReservationResultListener` consomem a `order-events-queue` com a mesma disciplina defensiva do `SagaCommandParser`/`ReservationCommandListener` do inventory-service (teto de 64 KB, `FAIL_ON_TRAILING_TOKENS`, validação estrita de campo, valores sanitizados em log — D-67, ASVS V5).
- `OrderSagaService`, guardado pelo estado (D-64): `applyReservationFailed` cancela com mensagem legível montada por `CancellationReasons` (modelo fixo, T-05-14); `applyStockReserved` confirma, ou — se o pedido já está `CANCELLED` — grava um `ReleaseStock` no outbox na MESMA transação (`STOCK_RESERVED_ITEMS_CHECK` valida que os itens do evento batem com os do pedido antes de aplicar qualquer transição).
- Trava de linha do pedido (`OrderRepository#findByIdForUpdate`, `PESSIMISTIC_WRITE`, `SAGA_RESULT_LOCK=order-row`) serializa o resultado da reserva contra o futuro job de timeout (`05-04`) sobre o mesmo pedido, sem depender de `company_credit_lock`.
- Caminho de falha escrito e commitado (RED+GREEN) antes do caminho feliz em ambas as tasks (D-68, Success Criteria 3) — confirmado pelo histórico do git (primeiro commit de `ReservationResultListenerIT.java` sem nenhuma asserção de `CONFIRMED`).

## Task Commits

Cada task seguiu o ciclo RED → GREEN (TDD):

1. **Task 1 RED** — `1c3b499` (test): 6 casos de falha em `ReservationResultListenerIT` (INSUFFICIENT_STOCK, PRODUCT_NOT_STOCKED, RESERVATION_CANCELLED, crédito liberado, duplicata no-op, pedido inexistente/mensagem malformada) + `SagaEventParserTest`/`CancellationReasonsTest` (já verdes) + scaffolding de produção com o wiring do listener comentado de propósito.
2. **Task 1 GREEN** — `8f2cbb4` (feat): wiring restaurado (`ReservationResultListener` chama `OrderSagaService.applyReservationFailed`) — 6/6 `ReservationResultListenerIT` + suíte inteira do módulo verde (60 testes). Inclui a correção de `poll-timeout`/timeouts do `SqsAsyncClient` (deviation abaixo).
3. **Task 2 RED** — `a061eee` (test): 5 casos do caminho feliz (`StockReserved` confirma e consome crédito, duplicata, sucesso tardio para pedido cancelado grava `ReleaseStock`, falha tardia após `CONFIRMED`, itens divergentes descartados) + `SagaEventParserTest`/`OrderDomainTest` novos (já verdes) + scaffolding com o despacho de `StockReservedEvent` comentado de propósito.
4. **Task 2 GREEN** — `506811b` (feat): despacho restaurado (`ReservationResultListener` chama `OrderSagaService.applyStockReserved`) — 11/11 `ReservationResultListenerIT` + suíte inteira do módulo verde (65 testes).

**Plan metadata:** commit deste SUMMARY (a seguir).

## Files Created/Modified

- `order-service/src/main/java/com/orderflow/order/order/CancellationCode.java` — enum dos quatro códigos do CHECK da V2
- `order-service/src/main/java/com/orderflow/order/order/{Order,OrderRepository,dto/OrderResponse}.java` — colunas/guardas da saga, `findByIdForUpdate`, contrato REST novo
- `order-service/src/main/java/com/orderflow/order/saga/{OrderSagaService,CancellationReasons}.java` — serviço transacional guardado pelo estado + modelo de mensagem legível
- `order-service/src/main/java/com/orderflow/order/saga/messaging/*.java` — `SagaEventParser`, `InvalidSagaMessageException`, `ReservationResultListener`, DTOs (`StockReservationFailedEvent`, `StockReservedEvent`, `ReservationFailureLine`, `ReleaseStockCommand`)
- `order-service/src/main/java/com/orderflow/order/config/SqsMessagingConfig.java` + `application.yml` — `order-events-queue`, `poll-timeout=0s`, folga de timeouts
- Testes: `ReservationResultListenerIT` (11 casos), `SagaEventParserTest` (23 casos), `CancellationReasonsTest` (6 casos), `support/OrderSagaQueues`, ajustes em `OrderCreationServiceTest`/`OrderDomainTest`

## Decisions Made

Ver `key-decisions` do frontmatter — `ORDER_RESPONSE_CONTRACT`, `SAGA_RESULT_LOCK=order-row`, `CANCELLATION_REASON_TEMPLATE` e `STOCK_RESERVED_ITEMS_CHECK` (exigidos pelo `<output>` do plano), mais a decisão técnica de `SagaEventParser.parse` devolver `Object` em vez de uma interface marcadora própria.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `spring.cloud.aws.sqs.listener.poll-timeout=0s` + folga nos timeouts do `SqsAsyncClient` (mesma lição de 05-02)**
- **Found during:** Task 1, primeira execução real de `ReservationResultListenerIT` contra LocalStack (GREEN)
- **Issue:** `SqsMessagingConfig`'s `SqsAsyncClientCustomizer` (D-41, `apiCallTimeout=3s`/`apiCallAttemptTimeout=1s`, pensado só para o `RestClient` síncrono não prender a thread HTTP) é compartilhado pelo `SqsAsyncClient` inteiro. Com `ReservationResultListener` sendo o primeiro `@SqsListener` real do order-service, o long polling padrão do SQS (até 20s) estourava o teto de 1s por tentativa a cada ciclo — exatamente o bug já documentado em `05-02-SUMMARY.md` para o inventory-service, agora reproduzido aqui.
- **Fix:** `spring.cloud.aws.sqs.listener.poll-timeout: 0s` (application.yml) desliga o long polling; `apiCallTimeout`/`apiCallAttemptTimeout` de 3s/1s para 5s/2s (`SqsMessagingConfig`).
- **Files modified:** `order-service/src/main/resources/application.yml`, `order-service/src/main/java/com/orderflow/order/config/SqsMessagingConfig.java`
- **Verification:** `ReservationResultListenerIT` 6/6 (depois 11/11) verde, `./mvnw -B -pl order-service verify` inteiro verde (60, depois 65 testes).
- **Committed in:** `8f2cbb4` (Task 1 GREEN)

---

**Total deviations:** 1 auto-fixed (Rule 1 - bug, mesma classe de correção já documentada em `05-02-SUMMARY.md`)
**Impact on plan:** Necessário para o consumidor funcionar corretamente contra LocalStack real — nenhum scope creep, nenhuma funcionalidade nova além do especificado no plano.

## Issues Encountered

- Mesma limitação do `gsd_run check tdd-red-evidence` já documentada em `05-01-SUMMARY.md`/`05-02-SUMMARY.md`: a ferramenta espera saída TAP e não interpreta Surefire/Failsafe — RED foi capturado manualmente (comentando a wiring de produção no listener, rodando o `<verify>` da task, confirmando falha pelo motivo certo — status nunca transiciona —, depois restaurando) e documentado nas mensagens de commit de teste.

## User Setup Required

None - nenhuma configuração de serviço externo nova (o `LOCALSTACK_AUTH_TOKEN` já era exigido desde a Fase 1; as filas da saga já foram criadas em `05-01`).

## Next Phase Readiness

- Ciclo order → inventory → order fechado para o caminho "resultado chega": pedido termina sempre em CONFIRMED ou CANCELLED (ORD-05/ORD-06 entregues do lado do order-service).
- `05-04` (timeout de reserva) pode reaproveitar `OrderRepository#findByIdForUpdate` (mesma trava `SAGA_RESULT_LOCK=order-row`) e `CancellationCode.RESERVATION_TIMEOUT`/`ReleaseStockCommand.RESERVATION_TIMEOUT` (já existentes, só não emitidos ainda) para cobrir o caso "resultado nunca chega".
- `ReleaseStockCommand` já publicado corretamente na `inventory-commands-queue` pelo relay existente (`OutboxRelay.resolveQueue`, sem alteração necessária) — o inventory-service ainda precisa implementar o consumidor de `ReleaseStock` (lápide e livro, D-65/D-66), que é escopo de `05-04`.
- Nenhum bloqueio conhecido para `05-04`.

---
*Phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o*
*Completed: 2026-09-26*

## Self-Check: PASSED

- Todos os arquivos-chave criados confirmados em disco (`[ -f ]`): `CancellationCode`, `CancellationReasons`, `OrderSagaService`, `SagaEventParser`, `ReservationResultListener`, `ReleaseStockCommand`, `StockReservedEvent`, `ReservationResultListenerIT`, `CancellationReasonsTest`, `SagaEventParserTest`, `OrderSagaQueues`, este SUMMARY.
- Todos os 4 commits do plano confirmados em `git log --oneline --all`: `1c3b499`, `8f2cbb4`, `a061eee`, `506811b`.
- Todos os casos de `<acceptance_criteria>` das duas tasks reverificados: campos de `OrderResponse` na ordem exigida; `OrderSagaService.applyReservationFailed`/`applyStockReserved` `@Transactional` usando `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`); `ReservationResultListener` captura só `InvalidSagaMessageException`; primeiro commit de `ReservationResultListenerIT.java` sem `"CONFIRMED"` (`grep -c` = 0); métodos de falha acima dos de `CONFIRMED` no arquivo.
- `./mvnw -B -pl order-service clean verify` re-executado após o commit final: 65 testes (19 unitários relevantes + suíte completa de integração contra Postgres/LocalStack reais), todos verdes.
