---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
plan: "02"
subsystem: inventory-service
tags: [saga, transactional-outbox, sqs, spring-cloud-aws, spring-retry, tdd, inventory-service]

requires:
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "01"
    provides: "SAGA_MESSAGE_CONTRACT (ReserveStock), filas inventory-commands-queue/order-events-queue com DLQ, init hook LocalStack da saga"
provides:
  - "Consumidor idempotente da inventory-commands-queue: ReservationCommandListener + SagaCommandParser (validação defensiva ASVS V5, 64KB, FAIL_ON_TRAILING_TOKENS)"
  - "InventoryService.reserveAll — reserva multi-item tudo-ou-nada (mesmo par @Retryable/@Transactional de reserve/release), com as três situações do livro stock_reservations: reserva nova, replay idempotente (D-65), e livro inconsistente (IllegalStateException)"
  - "Transactional Outbox duplicado no inventory-service (D-62): outbox_event (V2), OutboxWriter/OutboxRelay/OutboxRelayJob — resolveQueue roteia StockReserved/StockReservationFailed para order-events-queue e já sabe rotear STOCK_ADJUSTED para notification-events-queue (usado a partir de 05-04)"
  - "StockReservedEvent/StockReservationFailedEvent com reasonCode INSUFFICIENT_STOCK/PRODUCT_NOT_STOCKED (RESERVATION_CANCELLED reservado para 05-04)"
affects: [05-03, 05-04, 05-05, 05-06]

actuals:
  tokens: 28048
  tasks: 2
  commits: 4
  plan_head_before: f4eed0948b5185218eb558b2168d5cce7e744570

tech-stack:
  added: []
  patterns:
    - "Outbox duplicado por serviço (D-62), segunda instância: mesma entidade/repositório/relay do order-service, pacote com.orderflow.inventory.saga.outbox — resolveQueue despacha por eventType para duas filas diferentes (order-events-queue vs notification-events-queue)"
    - "reserveAll com o mesmo par @Retryable+@Transactional co-localizado de reserve/release (nunca composto a partir deles — auto-invocação pularia o proxy, 05-RESEARCH.md Pattern 3): avalia todas as linhas do comando antes de escrever qualquer coisa, ordenadas por productId"
    - "SagaCommandParser: parser defensivo dedicado por tipo de mensagem (teto 64KB, FAIL_ON_TRAILING_TOKENS, sanitização de valores externos antes de entrar em mensagem de exceção/log) — mesma disciplina de NotificationService.record (Fase 3), agora extraída para uma classe própria em vez de inline no listener"
    - "Replay idempotente por linha completa do livro (D-65): todas as linhas do reservationId presentes e nenhuma liberada -> reemite resultado sem tocar em inventory/stock_reservations; qualquer combinação parcial -> anomalia técnica"

key-files:
  created:
    - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxEvent.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxEventRepository.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxWriter.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelay.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelayJob.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/SchedulingConfig.java
    - inventory-service/src/main/resources/db/migration/V2__outbox_event.sql
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ReservationLine.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ReserveStockCommand.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ReservationFailureLine.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/StockReservedEvent.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/StockReservationFailedEvent.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/InvalidSagaMessageException.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/ReservationOutcome.java
    - inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java
    - inventory-service/src/test/java/com/orderflow/inventory/IdempotentReservationIT.java
    - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java
    - inventory-service/src/test/java/com/orderflow/inventory/support/SagaQueues.java
  modified:
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryRepository.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservationRepository.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java
    - inventory-service/src/main/resources/application.yml
    - inventory-service/src/test/resources/application-test.yml
    - inventory-service/src/test/java/com/orderflow/inventory/support/LocalStackTestSupport.java
    - inventory-service/src/test/java/com/orderflow/inventory/support/LocalStackProvisioningWaiter.java

key-decisions:
  - "Livro parcialmente preenchido (algum produto do comando com linha em stock_reservations, outro sem, ou alguma linha já liberada) é sempre anomalia técnica — IllegalStateException citando o orderId, nunca reemitida como resultado de negócio; só acontece se alguém usar REST manual com o id de um pedido (D-65, 05-RESEARCH.md Pattern 4 Option A/B não precisou ser escolhida porque nenhuma lápide de produto sem estoque existe ainda — chega em 05-04/D-66)."
  - "[Rule 1 - Bug] spring.cloud.aws.sqs.listener.poll-timeout=0s: o SqsAsyncClientCustomizer existente (apiCallTimeout, WR-03, pensado só para a chamada síncrona de PUT /inventory) é compartilhado pelo cliente inteiro; o long polling padrão do primeiro @SqsListener real deste serviço estourava esse teto a cada ciclo. Desligar o long polling (poll-timeout=0) resolve sem afrouxar o motivo original; apiCallTimeout/apiCallAttemptTimeout também ganharam folga de 3s/1s para 5s/2s para absorver a contenção das dez requisições concorrentes do container do listener."
  - "[Rule 1 - Bug] @Recover recoverReserveAllInconsistentBook(IllegalStateException, ...): Spring Retry intercepta qualquer exceção escapando de um método @Retryable, não só as de retryFor — sem um @Recover cujo tipo bata, a exceção real (a anomalia do livro inconsistente) ficava soterrada por ExhaustedRetryException(\"Cannot locate recovery method\")."
  - "[Rule 1 - Bug] SagaQueues.awaitResultsForOrders(Set<UUID>, int): a versão original (awaitResultsForOrder de um único orderId) apagava qualquer mensagem lida da fila de resultado, mesmo sem casar o orderId — corretо quando só um pedido está em jogo, mas descartaria em silêncio o resultado de um segundo pedido concorrente cujo evento chegasse na mesma janela de leitura, exatamente o cenário do teste de disputa pelas últimas unidades."

requirements-completed: [ORD-06, ORD-05]

coverage:
  - id: D1
    description: "Estoque insuficiente produz StockReservationFailed com reasonCode INSUFFICIENT_STOCK e failures [{productId, requested, available}]; nada é reservado (quantityReserved 0, nenhuma linha em stock_reservations)"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java#insufficientStockProducesFailureWithReasonAndDetailAndReservesNothing"
        status: pass
    human_judgment: false
  - id: D2
    description: "Produto sem linha de estoque produz PRODUCT_NOT_STOCKED com available 0; a mensagem é consumida uma única vez (sem reentrega)"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java#productWithoutStockLineProducesProductNotStockedAndMessageIsConsumedOnce"
        status: pass
    human_judgment: false
  - id: D3
    description: "Multi-item com um produto insuficiente ou sem linha de estoque falha tudo (tudo-ou-nada) — o produto que estava ok não é reservado"
    requirement: "ORD-05"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java#twoItemsOnlyOneInsufficientFailsListingOnlyThatProduct"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java#oneProductMissingAndOtherInsufficientFailsAsProductNotStockedWithBothFailures"
        status: pass
    human_judgment: false
  - id: D4
    description: "Mensagem malformada (JSON inválido, eventType desconhecido) é descartada com log WARN sanitizado; o consumo continua para a próxima mensagem válida"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java#malformedMessagesAreDiscardedWithWarnLogAndConsumptionStaysAlive"
        status: pass
      - kind: unit
        ref: "inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java (22 casos)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Com estoque suficiente, reserva todos os itens e responde StockReserved; quantityOnHand não muda, quantityReserved reflete exatamente o pedido, uma linha por produto em stock_reservations"
    requirement: "ORD-05"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java#sufficientStockReservesEverythingAndRespondsStockReserved"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java#twoItemsWithStockReservesBothWithOneRowEach"
        status: pass
    human_judgment: false
  - id: D6
    description: "Reentrega do mesmo ReserveStock decrementa a disponibilidade uma única vez e reemite um StockReserved por entrega, com eventId distintos (Success Criteria 4)"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/IdempotentReservationIT.java#resendingTheSameCommandReservesOnceAndReemitsStockReservedWithDistinctEventIds"
        status: pass
    human_judgment: false
  - id: D7
    description: "Uma falha anterior não deixa rastro no livro — o mesmo comando reenviado depois de o vendedor ajustar o estoque é reavaliado e pode ter sucesso"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/IdempotentReservationIT.java#previousFailureLeavesNoTraceAndIsReevaluatedAfterStockIsAdjusted"
        status: pass
    human_judgment: false
  - id: D8
    description: "Dois pedidos disputando as últimas unidades ao mesmo tempo terminam com exatamente um StockReserved e um StockReservationFailed; quantityReserved nunca passa de quantityOnHand"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/IdempotentReservationIT.java#concurrentOrdersDisputingTheLastUnitsNeverSellBeyondStock"
        status: pass
    human_judgment: false
  - id: D9
    description: "Livro parcialmente preenchido (anomalia técnica) lança IllegalStateException sem gravar nada no outbox — vai para reentrega e DLQ, nunca para um resultado de negócio"
    requirement: "ORD-06"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/IdempotentReservationIT.java#inconsistentBookThrowsIllegalStateExceptionWithoutTouchingTheOutbox"
        status: pass
    human_judgment: false
  - id: D10
    description: "Outbox do inventory-service (V2, FOR UPDATE SKIP LOCKED, Propagation.MANDATORY) é o único caminho de publicação do resultado da reserva; nenhum código de produção fora do relay e do publicador legado de STOCK_ADJUSTED fala com o SQS diretamente"
    verification:
      - kind: other
        ref: "test \"$(grep -rl 'import io.awspring.cloud.sqs.operations\\.' inventory-service/src/main/java | wc -l)\" -eq 2"
        status: pass
    human_judgment: false

duration: 89min
completed: 2026-09-26
status: complete
---

# Phase 5 Plan 2: Consumo Idempotente do ReserveStock no Inventory-Service Summary

**Inventory-service consome `ReserveStock` da `inventory-commands-queue`, reserva todos os itens do pedido numa única transação tudo-ou-nada, e devolve `StockReserved`/`StockReservationFailed` pelo seu próprio Transactional Outbox — com replay idempotente por republicação e proteção real contra sobrevenda sob disputa concorrente.**

## Performance

- **Duration:** ~89 min (inclui uma interrupção real de ~25 min por bloqueio de guarda de branch protegida — ver "Issues Encountered")
- **Started:** 2026-09-26T11:08:00-03:00 (aprox., logo após o commit final de 05-01)
- **Completed:** 2026-09-26T12:20:30-03:00
- **Tasks:** 2
- **Files modified:** 28 (19 criados, 9 modificados)

## Accomplishments

- `ReservationCommandListener` + `SagaCommandParser` consomem `ReserveStock` com validação defensiva completa (teto de 64 KB, `FAIL_ON_TRAILING_TOKENS`, todas as regras de campo do contrato, mensagens sempre sanitizadas) — mensagem malformada é descartada com log e nunca derruba o consumo (D-67, ASVS V5).
- `InventoryService.reserveAll` reserva multi-item tudo-ou-nada com o mesmo par `@Retryable`+`@Transactional` de `reserve`/`release`, nunca compondo a partir deles (auto-invocação, 05-RESEARCH.md Pattern 3) — falha de negócio grava `StockReservationFailedEvent` com `reasonCode`/`failures` por produto sem tocar em `inventory` nem `stock_reservations` (D-55, D-56, D-58).
- Transactional Outbox duplicado no inventory-service (D-60, D-62): `outbox_event` (V2), `OutboxWriter`/`OutboxRelay`/`OutboxRelayJob`, com o relay já preparado para rotear `STOCK_ADJUSTED` para `notification-events-queue` a partir de 05-04.
- Replay idempotente (D-65, Success Criteria 4 do ROADMAP): republicar o mesmo comando decrementa a disponibilidade uma única vez e reemite `StockReserved` com `eventId` novo a cada entrega; uma falha anterior não deixa rastro e é reavaliada contra o estoque atual.
- Disputa concorrente pelas últimas unidades prova, contra Postgres e LocalStack reais, que `@Version` + `chk_inventory_not_oversold` + retry protegem a invariante mesmo com dois pedidos diferentes processados em paralelo pelo container do listener.
- Livro parcialmente preenchido (anomalia técnica, só possível via REST manual) lança `IllegalStateException` e vai para reentrega/DLQ, nunca é confundido com falha de negócio.

## Task Commits

Cada task seguiu o ciclo RED → GREEN (TDD):

1. **Task 1 RED** — `235fd8b` (test): `ReservationCommandConsumptionIT` (5 casos de falha) + `SagaCommandParserTest` (22 casos) + todo o scaffolding de produção necessário para compilar, com a chamada `inventoryService.reserveAll(...)` comentada em `ReservationCommandListener`. RED confirmado manualmente rodando `./mvnw -B -pl inventory-service verify -Dit.test=ReservationCommandConsumptionIT`: os 5 testes de integração falharam no assert esperado (resultado nunca chega em 15s), não em erro de compilação/infra.
2. **Task 1 GREEN** — `60319e2` (feat): wiring restaurada — 5/5 `ReservationCommandConsumptionIT` + 22/22 `SagaCommandParserTest` + `./mvnw -B -pl inventory-service verify` inteiro verde (42 testes). Inclui a correção do `poll-timeout`/timeouts do `SqsAsyncClient` (deviation abaixo).
3. **Task 2 RED** — `0ba4d94` (test): `IdempotentReservationIT` (4 casos) + caminho feliz acrescentado a `ReservationCommandConsumptionIT` + correção de `SagaQueues.awaitResultsForOrders`. RED confirmado manualmente com o ramo de replay e o `@Recover` de `IllegalStateException` removidos de `reserveAll`: `resendingTheSameCommand...` falhou no timeout esperado, `inconsistentBookThrows...` falhou com o tipo de exceção errado (achado que expôs o bug do `@Recover`, corrigido no GREEN); os outros dois casos já passavam (exercitam a Task 1).
4. **Task 2 GREEN** — `9ffc8af` (feat): replay idempotente + `@Recover` corrigido — 11/11 testes novos verdes e `./mvnw -B -pl inventory-service verify` inteiro verde (48 testes).

**Plan metadata:** commit deste SUMMARY (a seguir).

## Files Created/Modified

- `inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/*.java` — outbox duplicado do order-service (D-62)
- `inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/*.java` — DTOs do contrato, `SagaCommandParser`, `ReservationCommandListener`
- `inventory-service/src/main/java/com/orderflow/inventory/stock/{InventoryService,InventoryRepository,StockReservationRepository,ReservationOutcome}.java` — `reserveAll` tudo-ou-nada + replay + livro inconsistente
- `inventory-service/src/main/java/com/orderflow/inventory/config/{SqsMessagingConfig,SchedulingConfig}.java` — `setPayloadTypeMapper`, `poll-timeout` fix, `@EnableScheduling`
- `inventory-service/src/main/resources/{application.yml,db/migration/V2__outbox_event.sql}` — configuração da saga e migração do outbox
- Testes: `ReservationCommandConsumptionIT`, `IdempotentReservationIT`, `SagaCommandParserTest`, `support/SagaQueues`, ajuste de `LocalStackTestSupport`/`LocalStackProvisioningWaiter` para as 3 filas

## Decisions Made

Ver `key-decisions` do frontmatter — nenhuma decisão nova de arquitetura além das já registradas em `05-CONTEXT.md`; três correções de bug (Rule 1) documentadas abaixo como deviations.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `spring.cloud.aws.sqs.listener.poll-timeout=0s` + folga nos timeouts do `SqsAsyncClient`**
- **Found during:** Task 1, primeira execução real de `ReservationCommandConsumptionIT` contra LocalStack
- **Issue:** `SqsMessagingConfig`'s `SqsAsyncClientCustomizer` (Fase 2/3, `apiCallTimeout=3s`/`apiCallAttemptTimeout=1s`, pensado só para a chamada síncrona de `PUT /inventory` não prender a thread HTTP, WR-03) é compartilhado pelo `SqsAsyncClient` inteiro. Com `ReservationCommandListener` sendo o primeiro `@SqsListener` real deste serviço, o long polling padrão do SQS (até 20s) estourava o teto de 1s por tentativa a cada ciclo de recebimento — o listener errava constantemente e disputava conexões do pool Netty com outras chamadas (ex.: `sendCommand`/`getQueueUrl` do próprio teste).
- **Fix:** `spring.cloud.aws.sqs.listener.poll-timeout: 0s` desliga o long polling (cabe folgado no teto existente); `apiCallTimeout`/`apiCallAttemptTimeout` ganharam folga de 3s/1s para 5s/2s.
- **Files modified:** `inventory-service/src/main/resources/application.yml`, `inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java`
- **Verification:** `ReservationCommandConsumptionIT` 5/5 verde, `./mvnw -B -pl inventory-service verify` inteiro verde (inclui `StockEventPublishFailureIT`, que não depende do valor exato do timeout).
- **Committed in:** `60319e2` (Task 1 GREEN)

**2. [Rule 1 - Bug] `@Recover recoverReserveAllInconsistentBook(IllegalStateException, ...)`**
- **Found during:** Task 2, escrita do teste do livro inconsistente (`IdempotentReservationIT`)
- **Issue:** Spring Retry intercepta QUALQUER exceção escapando de um método `@Retryable`, não só as listadas em `retryFor` — sem um `@Recover` cujo tipo de parâmetro corresponda, a exceção real (`IllegalStateException` da anomalia do livro) ficava soterrada por `ExhaustedRetryException("Cannot locate recovery method")`, escondendo o motivo real do chamador.
- **Fix:** Novo método `recoverReserveAllInconsistentBook(IllegalStateException ex, ...)` que apenas relança a exceção original.
- **Files modified:** `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java`
- **Verification:** `IdempotentReservationIT#inconsistentBookThrowsIllegalStateExceptionWithoutTouchingTheOutbox` verde.
- **Committed in:** `9ffc8af` (Task 2 GREEN)

**3. [Rule 1 - Bug] `SagaQueues.awaitResultsForOrders(Set<UUID>, int)`**
- **Found during:** Task 2, teste de disputa concorrente (`concurrentOrdersDisputingTheLastUnitsNeverSellBeyondStock`)
- **Issue:** `drainOnce` apagava toda mensagem lida da fila de resultado, mesmo sem casar o `orderId` procurado — correto quando só um pedido está em jogo por vez, mas descartava em silêncio o resultado de um SEGUNDO pedido concorrente cujo evento chegasse na mesma janela de leitura (exatamente o cenário do teste: dois `orderId` diferentes, resultados podendo chegar juntos).
- **Fix:** Novo método `awaitResultsForOrders(Set<UUID>, int)` que casa QUALQUER `orderId` do conjunto na mesma rodada de dreno; `awaitResultsForOrder` passou a delegar para ele com um conjunto de um elemento.
- **Files modified:** `inventory-service/src/test/java/com/orderflow/inventory/support/SagaQueues.java`, `inventory-service/src/test/java/com/orderflow/inventory/IdempotentReservationIT.java`
- **Verification:** `concurrentOrdersDisputingTheLastUnitsNeverSellBeyondStock` verde (antes falhava deterministicamente, não por flakiness).
- **Committed in:** `0ba4d94` (Task 2 RED, já que a correção é infraestrutura de teste necessária para o teste compilar/fazer sentido) e mantida em `9ffc8af`.

---

**Total deviations:** 3 auto-fixed (todos Rule 1 - bug)
**Impact on plan:** Os três achados são correções necessárias para o consumidor funcionar corretamente sob concorrência real e para o comportamento de anomalia técnica ser observável pelo chamador — nenhum scope creep, nenhuma funcionalidade nova além do especificado no plano.

## Issues Encountered

- **Bloqueio de guarda de branch protegida (não é um bug de código):** ao tentar commitar a Task 1 (RED), a asserção de segurança pré-commit do executor (`gsd_run query git.base-branch --is-protected master` → `true`) impediu o commit porque `.planning/config.json` não tinha `git.allow_default_branch_commits` configurado, apesar de este projeto inteiro (Fases 1-4 e o plano 05-01) já commitar direto em `master` por design (`branching_strategy: "none"`, sem outra branch). Reportei o bloqueio ao orquestrador em vez de contornar a guarda unilateralmente; o usuário optou por definir `git.allow_default_branch_commits: true` (commit `f4eed09`, feito pelo orquestrador, não por este executor), e a execução foi retomada exatamente do ponto onde parou — nenhum trabalho foi perdido, mas a pausa consumiu tempo de execução real, refletido na duração acima.
- Mesma limitação do `gsd_run check tdd-red-evidence` já documentada em `05-01-SUMMARY.md`: a ferramenta espera saída TAP e não interpreta Surefire/Failsafe — RED foi capturado manualmente (comentando a wiring de produção, rodando o teste, confirmando falha pelo motivo certo, depois restaurando) e documentado nas mensagens de commit de teste.

## User Setup Required

None - nenhuma configuração de serviço externo nova (o `LOCALSTACK_AUTH_TOKEN` já era exigido desde a Fase 1; as filas da saga já foram criadas em `05-01`).

## Next Phase Readiness

- `order-service` (05-03) já tem tudo que precisa consumir: `order-events-queue` recebe `StockReserved`/`StockReservationFailed` no mesmo `SAGA_MESSAGE_CONTRACT` publicado por `05-01`, com `reasonCode`/`failures` prontos para virar `cancellation_code`/`cancellation_reason` (D-53, D-56).
- Replay idempotente (D-65) e livro inconsistente já provados isoladamente no lado do estoque — `05-03` só precisa aplicar a guarda de estado (`orders.status == RESERVING`, D-64) do próprio lado, sem depender de nenhuma mudança adicional aqui.
- `OutboxRelay.resolveQueue` já sabe rotear `STOCK_ADJUSTED` para `notification-events-queue` — `05-04` só precisa fazer `InventoryController`/`InventoryService.setStock` gravar no outbox em vez de chamar `StockEventPublisher` (que continua existindo, sem alteração, até lá).
- Nenhum bloqueio conhecido para `05-03`.

---
*Phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o*
*Completed: 2026-09-26*

## Self-Check: PASSED

- Todos os arquivos-chave criados confirmados em disco (`[ -f ]`): `OutboxEvent.java`, `SagaCommandParser.java`, `ReservationCommandListener.java`, `V2__outbox_event.sql`, `ReservationCommandConsumptionIT.java`, `IdempotentReservationIT.java`, `SagaCommandParserTest.java`, `SagaQueues.java`, este SUMMARY.
- Todos os 4 commits do plano confirmados em `git log --oneline --all`: `235fd8b`, `60319e2`, `0ba4d94`, `9ffc8af`.
- `./mvnw -B -pl inventory-service clean verify` re-executado após o commit final da Task 2: 48 testes, todos verdes.
