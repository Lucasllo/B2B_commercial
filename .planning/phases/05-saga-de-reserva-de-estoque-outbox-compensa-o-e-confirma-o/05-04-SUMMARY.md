---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
plan: "04"
subsystem: order-service+inventory-service
tags: [saga, transactional-outbox, sqs, scheduled-job, tombstone, tdd, order-service, inventory-service]

requires:
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "01"
    provides: "SAGA_MESSAGE_CONTRACT (ReleaseStock), reservation_started_at (SAGA_TIMEOUT_CLOCK), CancellationCode.RESERVATION_TIMEOUT, coluna/índice V2 do order-service"
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "02"
    provides: "reserveAll multi-item, outbox duplicado do inventory-service, OutboxRelay.resolveQueue já roteando STOCK_ADJUSTED"
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "03"
    provides: "OrderSagaService guardado pelo estado, findByIdForUpdate/SAGA_RESULT_LOCK=order-row, ReleaseStockCommand.from"
provides:
  - "Garantia de CÓDIGO do 'nunca preso' (D-63): SagaTimeoutJob cancela pedido em RESERVING além do prazo e grava ReleaseStock de compensação na mesma transação"
  - "Liberação idempotente de reserva existente e lápide (D-66) para ReleaseStock chegando antes do ReserveStock — inclusive produto sem linha de estoque (TOMBSTONE_FK=dropped, V3)"
  - "reserveAll responde RESERVATION_CANCELLED quando encontra lápide — corrida da fila SQS padrão (sem FIFO) resolvida sempre para 'nada reservado'"
  - "STOCK_ADJUSTED do inventory-service sai pelo mesmo Transactional Outbox (D-60) — publicador direto da Fase 3 removido, fecha D-29/D-30"
affects: [05-05, 05-06]

actuals:
  tokens: 27275
  tasks: 3
  commits: 6
  plan_head_before: 78147d1d945040612ec125561fadfc6f5b07a98e

tech-stack:
  added: []
  patterns:
    - "SagaTimeoutJob: bean @Scheduled SEPARADO de OrderSagaService (mesmo motivo de OutboxRelayJob/OutboxRelay) — cada pedido vencido processado na SUA PRÓPRIA transação pelo proxy do outro bean, uma exceção por pedido isolada em log ERROR sem derrubar o ciclo"
    - "StockReservation.tombstone: fábrica estática que reaproveita o construtor + markReleased para criar uma linha já released=true — nenhum campo novo na entidade"
    - "InventoryService.releaseAll: mesmo par @Retryable+@Transactional co-localizado de reserveAll, processando uma linha por vez (não em lote como reserveAll) — tombstone ou devolução de reserva, ordenado por productId"
    - "reserveAll: ordem de checagem invertida em relação a 05-02 — 'alguma linha liberada' (lápide) é verificado ANTES de 'todas as linhas presentes', porque agora uma lápide pode coexistir com livro parcial sob corrida"

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/saga/SagaTimeoutJob.java
    - order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java
    - order-service/src/test/java/com/orderflow/order/saga/SagaTimeoutJobTest.java
    - inventory-service/src/main/resources/db/migration/V3__allow_reservation_tombstones.sql
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ReleaseStockCommand.java
    - inventory-service/src/test/java/com/orderflow/inventory/TombstoneReleaseIT.java
    - inventory-service/src/test/java/com/orderflow/inventory/saga/outbox/OutboxRelayTest.java
  modified:
    - order-service/src/main/java/com/orderflow/order/order/OrderRepository.java
    - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
    - order-service/src/main/java/com/orderflow/order/saga/CancellationReasons.java
    - order-service/src/main/resources/application.yml
    - order-service/src/test/resources/application-test.yml
    - order-service/src/test/java/com/orderflow/order/saga/CancellationReasonsTest.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelay.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservation.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java
    - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java
    - inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java
    - inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java
    - inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java
  deleted:
    - inventory-service/src/main/java/com/orderflow/inventory/stock/StockAdjustmentResult.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/messaging/StockEventPublisher.java
    - inventory-service/src/test/java/com/orderflow/inventory/StockEventPublishFailureIT.java

key-decisions:
  - "TOMBSTONE_FK=dropped — V3 remove a FK stock_reservations.product_id → inventory(product_id); product_id passa a ser referência opaca (D-15) para que a lápide exista também para produto sem linha de estoque (D-58). Rejeitada a alternativa de criar linha de inventory zerada (mudaria o 404 de GET /inventory/{id} para produto nunca estocado, D-18)."
  - "REST_RESERVATION_ENDPOINTS=kept — POST/DELETE /inventory/{productId}/reservations continuam como ferramenta administrativa de SELLER_ADMIN; order e inventory só falam por SQS na saga, então a 'identidade de serviço' prevista no javadoc antigo do controller deixou de ser necessária. DELETE REST mantém D-14 (liberar inexistente é no-op, sem lápide) — a lápide só nasce no ReleaseStock da fila."
  - "SAGA_TIMEOUT_DEFAULTS=reservation-timeout 2m, timeout-check-interval 10000ms, timeout-batch-size 50 em produção; 10m/500ms em teste — prazo longo no teste evita que o job cancele pedidos de outras classes *IT que compartilham o mesmo contexto Spring em cache; o teste de timeout recua reservation_started_at do próprio pedido via JDBC."
  - "[Rule 1 - Bug] StockReservationConcurrencyIT usava um PostgreSQLContainer SEPARADO do de AbstractIntegrationTest. Por não estender essa base (precisa de socket HTTP real para concorrência, D-22), o Spring cria um ApplicationContext próprio, mantido em cache/vivo em segundo plano — o ReservationCommandListener DESSE contexto continuava consumindo a mesma inventory-commands-queue (compartilhada via LocalStackTestSupport) depois que as tasks da classe terminavam, gravando o efeito no banco ERRADO quando 'vencia a corrida' contra o listener certo. Corrigido reaproveitando o mesmo container Postgres de AbstractIntegrationTest (campo package-private acessível no mesmo pacote) em vez de um segundo container — elimina o split-brain sem tocar no motivo original da classe evitar MockMvc."

patterns-established:
  - "Corrida da fila SQS padrão (sem FIFO) resolvida por lápide + reexecução @Retryable, nunca por ordenação de mensagem — mesmo espírito de reserveAll (05-02) aplicado à composição release+reserve"

requirements-completed: [ORD-04, ORD-05, ORD-06]

coverage:
  - id: D1
    description: "Pedido preso em RESERVING além do prazo é cancelado pelo SagaTimeoutJob com RESERVATION_TIMEOUT e, na mesma transação, um ReleaseStock com os itens do pedido chega à inventory-commands-queue"
    requirement: "ORD-05"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java#orderStuckPastDeadlineIsCancelledByTimeoutAndReleaseStockReachesInventoryQueue"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/saga/SagaTimeoutJobTest.java (2 casos)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Pedido dentro do prazo não é tocado pelo job; resultado de falha tardio para pedido já cancelado por timeout é no-op; StockReserved tardio gera um SEGUNDO ReleaseStock (LATE_RESERVATION) e o pedido continua CANCELLED; crédito liberado por timeout permite novo pedido"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java#orderWithinDeadlineIsUntouchedByTheJobAfterSeveralCycles"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java#failureResultAfterTimeoutStaysCancelledWithTheSameCancelledAt"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java#lateSuccessAfterTimeoutStaysCancelledAndWritesASecondReleaseStock"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java#creditReleasedByTimeoutAllowsANewOrderToFitTheLimit"
        status: pass
    human_judgment: false
  - id: D3
    description: "ReleaseStock de uma reserva existente devolve a quantidade reservada e marca a linha liberada; repetir o ReleaseStock não muda nada (idempotente)"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/TombstoneReleaseIT.java#releaseAfterReserveReturnsQuantityAndMarksRowReleasedThenIsIdempotentOnRepeat"
        status: pass
    human_judgment: false
  - id: D4
    description: "ReleaseStock chegando ANTES do ReserveStock grava lápides para todos os produtos do comando — inclusive produto sem linha de estoque; o ReserveStock posterior encontra a lápide e responde StockReservationFailed com reasonCode RESERVATION_CANCELLED e failures vazio, sem reservar nada"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/TombstoneReleaseIT.java#releaseBeforeReserveWritesTombstonesEvenForProductWithoutStockLineAndLaterReserveIsCancelled"
        status: pass
      - kind: unit
        ref: "inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java (6 casos novos de ReleaseStock)"
        status: pass
    human_judgment: false
  - id: D5
    description: "ReleaseStock e ReserveStock do mesmo pedido chegando juntos terminam sempre com quantity_reserved inalterado e nenhuma reserva viva, em qualquer ordem de processamento (5 rodadas)"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/TombstoneReleaseIT.java#releaseAndReserveSentTogetherAlwaysEndWithUnchangedQuantityAndNoLiveReservation"
        status: pass
    human_judgment: false
  - id: D6
    description: "PUT /inventory/{productId} grava STOCK_ADJUSTED no outbox na mesma transação (published_at preenchido pelo relay, eventId da mensagem igual ao id da linha); PUT recusado (409) não grava linha nova; testes de contrato pré-existentes (6 campos, sem JavaType) continuam passando"
    requirement: "ORD-04"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java#adjustingStockTwicePublishesOneContractMessagePerAdjustmentWithCorrectPreviousQuantity"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java#rejectedPutReservationAndReleaseDoNotPublishAnyEventBeyondTheOriginalAdjustment"
        status: pass
    human_judgment: false
  - id: D7
    description: "Único arquivo de produção do inventory-service que importa a API de envio SQS é o relay do outbox; publicador direto legado e seu teste de falha não existem mais"
    requirement: "ORD-04"
    verification:
      - kind: unit
        ref: "inventory-service/src/test/java/com/orderflow/inventory/saga/outbox/OutboxRelayTest.java (4 casos)"
        status: pass
      - kind: other
        ref: "test \"$(grep -rl 'import io.awspring.cloud.sqs.operations\\.' inventory-service/src/main/java | wc -l)\" -eq 1"
        status: pass
      - kind: other
        ref: "test ! -e .../StockEventPublisher.java && test ! -e .../StockAdjustmentResult.java && test ! -e .../StockEventPublishFailureIT.java"
        status: pass
    human_judgment: false

duration: ~110min
completed: 2026-09-29
status: complete
---

# Phase 5 Plan 4: Timeout da Saga, Lápide de Liberação e STOCK_ADJUSTED pelo Outbox Summary

**`SagaTimeoutJob` fecha a garantia "nunca preso" cancelando pedidos vencidos e compensando com `ReleaseStock`; o inventory-service devolve reservas existentes e grava lápides (D-66) que resolvem a corrida da fila SQS padrão contra `ReserveStock`; e `STOCK_ADJUSTED` passa a sair pelo mesmo Transactional Outbox, eliminando de vez a publicação direta da Fase 3 (D-60).**

## Performance

- **Duration:** ~110 min (inclui uma investigação real de contenção intermitente na suite completa do inventory-service — ver "Deviations")
- **Tasks:** 3
- **Files modified:** 27 (7 criados, 17 modificados, 3 removidos)

## Accomplishments

- `SagaTimeoutJob` (`@Scheduled`) cancela pedidos presos em `RESERVING` além de `orderflow.saga.reservation-timeout` com `CancellationCode.RESERVATION_TIMEOUT` e grava, na MESMA transação (`OrderSagaService#expireReservation`, sob `findByIdForUpdate`), um `ReleaseStock` de compensação no outbox — cada pedido processado isoladamente, uma exceção nunca trava os demais do lote (D-63).
- `V3__allow_reservation_tombstones.sql` remove a FK `stock_reservations.product_id → inventory(product_id)` (`TOMBSTONE_FK=dropped`) para permitir lápide de produto sem linha de estoque; `StockReservation.tombstone` grava a linha já liberada.
- `InventoryService.releaseAll` devolve reserva existente (idempotente) ou grava lápide quando não há reserva ainda; `reserveAll` passa a responder `RESERVATION_CANCELLED` (sem tocar no estoque) sempre que encontra qualquer linha liberada para o `reservationId` — a corrida da fila SQS padrão entre `ReleaseStock` e `ReserveStock` termina sempre em "nada reservado", em qualquer ordem, comprovado em 5 rodadas concorrentes.
- `InventoryService.setStock` grava `STOCK_ADJUSTED` no `outboxWriter` na mesma transação do ajuste; `InventoryController` não injeta mais nenhum publicador; `StockEventPublisher`/`StockAdjustmentResult`/`StockEventPublishFailureIT` (publicação direta da Fase 3, D-29) foram removidos — fecha a limitação de dual-write D-29/D-30.
- `SagaCommandParser` passa a devolver `Object` (`ReserveStockCommand` ou `ReleaseStockCommand`) despachado por `instanceof` no `ReservationCommandListener`, mesma técnica do `SagaEventParser` do order-service (05-03).

## Task Commits

Cada task seguiu o ciclo RED → GREEN (TDD):

1. **Task 1 RED** — `68fac2b` (test): `SagaTimeoutIT` (5 casos) + `SagaTimeoutJobTest` (2 casos) + `CancellationReasonsTest.forTimeout` + todo o scaffolding de produção (`findExpiredReservationIds`, `expireReservation`, `forTimeout()`, `application.yml`/`application-test.yml`) já implementado, com o corpo de `SagaTimeoutJob.run()` temporariamente vazio. RED confirmado: 4/5 `SagaTimeoutIT` falharam pelo motivo certo (pedido nunca cancelado), o 5º (`orderWithinDeadlineIsUntouched...`) passou corretamente (job inerte não deveria mesmo tocar o pedido).
2. **Task 1 GREEN** — `270c84d` (feat): corpo de `run()` restaurado — 5/5 `SagaTimeoutIT`, 2/2 `SagaTimeoutJobTest`, suíte inteira do order-service verde (70 testes).
3. **Task 2 RED** — `8e4e6e6` (test): `TombstoneReleaseIT` (4 casos) + `SagaCommandParserTest` (6 casos novos) + toda a produção (`V3`, `ReleaseStockCommand`, `StockReservation#tombstone`, `InventoryService#releaseAll`/`recoverReleaseAll`, ramo `RESERVATION_CANCELLED` de `reserveAll`) já implementada, com o despacho de `ReleaseStock` no `ReservationCommandListener` comentado. RED confirmado: 3/4 `TombstoneReleaseIT` falharam pelo motivo certo (estoque nunca devolvido/lápide nunca gravada); o teste do `DELETE` REST (não depende de SQS) passou.
4. **Task 2 GREEN** — `721ea38` (feat): despacho restaurado — 4/4 `TombstoneReleaseIT`, suíte inteira verde (51 testes, após a Task 3 remover `StockEventPublishFailureIT`).
5. **Task 3 RED** — `63da4f5` (test): `StockAdjustedEventPublishingIT` com asserções de linha do outbox + `OutboxRelayTest` do inventory (cópia do order-service) + `InventoryController`/`InventoryService`/`SqsMessagingConfig`/`AbstractIntegrationTest` já atualizados e `StockEventPublisher`/`StockAdjustmentResult`/`StockEventPublishFailureIT` já removidos, com a gravação no outbox de `setStock` temporariamente comentada. RED confirmado: 2/4 `StockAdjustedEventPublishingIT` falharam pelo motivo certo (nenhum evento publicado); os 2 testes que nunca esperavam publicação (400/403) passaram. Este commit também traz o achado e a correção do `StockReservationConcurrencyIT` (ver Deviations).
6. **Task 3 GREEN** — `5b17ad5` (feat): gravação no outbox restaurada — `./mvnw -B -pl inventory-service verify` inteiro verde (51 testes).

**Plan metadata:** commit deste SUMMARY (a seguir).

## Files Created/Modified

- `order-service/.../saga/SagaTimeoutJob.java` — job `@Scheduled`, bean separado de `OrderSagaService`
- `order-service/.../order/OrderRepository.java` — `findExpiredReservationIds`
- `order-service/.../saga/{OrderSagaService,CancellationReasons}.java` — `expireReservation`, `forTimeout()`
- `order-service/.../application.yml`/`application-test.yml` — `orderflow.saga.*` (`SAGA_TIMEOUT_DEFAULTS`)
- `inventory-service/.../db/migration/V3__allow_reservation_tombstones.sql` — `DROP CONSTRAINT` (`TOMBSTONE_FK=dropped`)
- `inventory-service/.../saga/messaging/{SagaCommandParser,ReservationCommandListener}.java` + `dto/ReleaseStockCommand.java` — parser/despacho de `ReleaseStock`
- `inventory-service/.../stock/{InventoryService,StockReservation,InventoryController}.java` — `releaseAll`, `tombstone`, ramo `RESERVATION_CANCELLED`, `setStock` pelo outbox
- `inventory-service/.../saga/outbox/OutboxRelay.java` + `config/SqsMessagingConfig.java` — javadoc atualizado (único publicador SQS)
- Testes: `TombstoneReleaseIT`, `OutboxRelayTest` (novo), `SagaCommandParserTest`/`StockAdjustedEventPublishingIT`/`AbstractIntegrationTest` ajustados
- **Removidos:** `stock/StockAdjustmentResult.java`, `stock/messaging/StockEventPublisher.java`, `StockEventPublishFailureIT.java`
- `inventory-service/.../StockReservationConcurrencyIT.java` — reaproveita o Postgres de `AbstractIntegrationTest` (deviation)

## Decisions Made

Ver `key-decisions` do frontmatter — `TOMBSTONE_FK=dropped`, `REST_RESERVATION_ENDPOINTS=kept` e `SAGA_TIMEOUT_DEFAULTS` (exigidas pelo `<output>` do plano), mais o achado/correção do `StockReservationConcurrencyIT`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `StockReservationConcurrencyIT` usava um `PostgreSQLContainer` separado, criando um listener "zumbi" que gravava no banco errado**
- **Found during:** Task 3, ao rodar `./mvnw -B -pl inventory-service verify` (exigido pelo `<verify>` da própria Task 3) — `TombstoneReleaseIT` falhava de forma intermitente só na suíte completa, nunca isolada.
- **Issue:** `StockReservationConcurrencyIT` não estende `AbstractIntegrationTest` (precisa de socket HTTP real para concorrência, D-22) e por isso declarava seu PRÓPRIO `@ServiceConnection PostgreSQLContainer`. O Spring Test cria um `ApplicationContext` próprio para essa assinatura de configuração (sem `@AutoConfigureMockMvc`) e o mantém em cache — vivo em segundo plano — depois que as 3 tasks da classe terminam. O `ReservationCommandListener` DESSE contexto continuava consumindo a `inventory-commands-queue` compartilhada (via `LocalStackTestSupport`, container LocalStack singleton) competindo com o listener do contexto correto. Quando o listener errado "vencia a corrida" para uma mensagem de `TombstoneReleaseIT`, o efeito era gravado no banco Postgres SEPARADO daquela classe — as asserções via JDBC do teste (apontando para o Postgres de `AbstractIntegrationTest`) nunca viam o efeito, e o resultado publicado na fila podia inclusive ser `StockReservationFailed`/`PRODUCT_NOT_STOCKED` (produto nunca cadastrado NAQUELE banco).
- **Fix:** `StockReservationConcurrencyIT` passou a reaproveitar `AbstractIntegrationTest.postgres` (campo package-private, mesmo pacote) em vez de instanciar um segundo container — elimina o split-brain sem alterar o motivo original da classe evitar `MockMvc`.
- **Files modified:** `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java`
- **Verification:** `./mvnw -B -pl inventory-service verify` reexecutado 3 vezes consecutivas após a correção, 51/51 testes verdes em todas, `TombstoneReleaseIT` estável em ~8s (antes: falhava por volta de 45-97s de tentativa).
- **Committed in:** `63da4f5` (Task 3 RED) e `5b17ad5` (Task 3 GREEN)

---

**Total deviations:** 1 auto-fixed (Rule 1 - bug de infraestrutura de teste, pré-existente, exposto pelas novas asserções via JDBC desta task)
**Impact on plan:** Correção necessária para a suíte completa do inventory-service ser deterministicamente verde (exigido pelo `<verify>` da Task 3); nenhum scope creep — nenhuma funcionalidade nova além do especificado no plano.

## Issues Encountered

- Mesma limitação do `gsd_run check tdd-red-evidence` já documentada em `05-01`/`05-02`/`05-03-SUMMARY.md`: a ferramenta espera saída TAP e não interpreta Surefire/Failsafe — RED foi capturado manualmente (comentando a wiring de produção crítica de cada task, rodando o `<verify>` correspondente, confirmando falha pelo motivo certo, depois restaurando) e documentado nas mensagens de commit de teste.
- Investigação de flakiness intermitente na Task 3 (ver Deviations acima) consumiu tempo real de execução significativo antes da causa raiz ser isolada.

## User Setup Required

None - nenhuma configuração de serviço externo nova (`LOCALSTACK_AUTH_TOKEN` já exigido desde a Fase 1; filas da saga já criadas em `05-01`).

## Next Phase Readiness

- Goal do ROADMAP ("nunca preso num estado intermediário") cumprido por código: timeout + compensação (D-63), corrida da fila padrão resolvida por lápide sem FIFO (D-66).
- Restrição de projeto "Transactional Outbox em order-service e inventory-service" cumprida por inteiro — nenhum publicador direto resta em nenhum dos dois serviços (D-60, D-29/D-30 fechadas).
- `05-05`/`05-06` podem assumir que todo evento do inventory-service (resultado de reserva e ajuste de estoque) sai pelo mesmo outbox, e que o ciclo order↔inventory nunca deixa um pedido preso, mesmo sem resposta do estoque.
- Nenhum bloqueio conhecido para `05-05`.

---
*Phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o*
*Completed: 2026-09-29*

## Self-Check: PASSED

- Todos os arquivos-chave criados confirmados em disco (`[ -f ]`): `SagaTimeoutJob.java`, `SagaTimeoutIT.java`, `SagaTimeoutJobTest.java`, `V3__allow_reservation_tombstones.sql`, `ReleaseStockCommand.java` (inventory), `TombstoneReleaseIT.java`, `OutboxRelayTest.java` (inventory), este SUMMARY.
- Todos os 6 commits do plano confirmados em `git log --oneline --all`: `68fac2b`, `270c84d`, `8e4e6e6`, `721ea38`, `63da4f5`, `5b17ad5`.
- Arquivos removidos confirmados ausentes (`[ ! -e ]`): `StockAdjustmentResult.java`, `stock/messaging/StockEventPublisher.java`, `StockEventPublishFailureIT.java`.
- Gate de grep reverificado: `grep -rl 'import io.awspring.cloud.sqs.operations\.' inventory-service/src/main/java` devolve só `OutboxRelay.java` (count 1); `grep -vE '^\s*(\*|//)' InventoryService.java | grep -cE '(^|[^.A-Za-z])release\('` devolve 1.
- `./mvnw -B -pl order-service clean verify` re-executado: 70 testes verdes. `./mvnw -B -pl inventory-service clean verify` re-executado 3x consecutivas: 51 testes verdes em todas (sem flakiness residual).
