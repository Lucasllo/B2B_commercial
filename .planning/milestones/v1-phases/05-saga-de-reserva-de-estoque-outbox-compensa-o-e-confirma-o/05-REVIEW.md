---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
reviewed: 2026-09-29T00:00:00Z
depth: standard
files_reviewed: 112
files_reviewed_list:
  - auth-service/Dockerfile
  - catalog-service/Dockerfile
  - docker-compose.yml
  - docs/API.md
  - docs/VISAO-GERAL.md
  - e2e-tests/pom.xml
  - e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java
  - e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java
  - e2e-tests/src/test/java/com/orderflow/e2e/support/DownstreamStubServer.java
  - e2e-tests/src/test/java/com/orderflow/e2e/support/E2eHttp.java
  - e2e-tests/src/test/java/com/orderflow/e2e/support/E2eInfrastructure.java
  - e2e-tests/src/test/java/com/orderflow/e2e/support/E2eJwt.java
  - e2e-tests/src/test/java/com/orderflow/e2e/support/LocalStackProvisioningWaiter.java
  - e2e-tests/src/test/java/com/orderflow/e2e/support/LocalStackTestSupport.java
  - e2e-tests/src/test/resources/e2e/inventory-service-overrides.yml
  - e2e-tests/src/test/resources/e2e/order-service-overrides.yml
  - gateway/Dockerfile
  - inventory-service/Dockerfile
  - inventory-service/src/main/java/com/orderflow/inventory/config/SchedulingConfig.java
  - inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ReleaseStockCommand.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ReservationFailureLine.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ReservationLine.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/ReserveStockCommand.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/StockReservationFailedEvent.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/dto/StockReservedEvent.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/InvalidSagaMessageException.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxEvent.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxEventRepository.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelay.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelayJob.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxWriter.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryRepository.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/ReservationOutcome.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservation.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservationRepository.java
  - inventory-service/src/main/resources/application.yml
  - inventory-service/src/main/resources/db/migration/V2__outbox_event.sql
  - inventory-service/src/main/resources/db/migration/V3__allow_reservation_tombstones.sql
  - inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/IdempotentReservationIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/saga/outbox/OutboxRelayTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/support/LocalStackProvisioningWaiter.java
  - inventory-service/src/test/java/com/orderflow/inventory/support/LocalStackTestSupport.java
  - inventory-service/src/test/java/com/orderflow/inventory/support/SagaQueues.java
  - inventory-service/src/test/java/com/orderflow/inventory/TombstoneReleaseIT.java
  - inventory-service/src/test/resources/application-test.yml
  - localstack-init/ready.d/02-create-order-saga-resources.sh
  - notification-service/Dockerfile
  - order-service/Dockerfile
  - order-service/pom.xml
  - order-service/src/main/java/com/orderflow/order/config/SchedulingConfig.java
  - order-service/src/main/java/com/orderflow/order/config/SqsMessagingConfig.java
  - order-service/src/main/java/com/orderflow/order/order/CancellationCode.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderResponse.java
  - order-service/src/main/java/com/orderflow/order/order/Order.java
  - order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderRepository.java
  - order-service/src/main/java/com/orderflow/order/order/OrderService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderStatus.java
  - order-service/src/main/java/com/orderflow/order/saga/CancellationReasons.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ReleaseStockCommand.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ReservationFailureLine.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ReservationLine.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/ReserveStockCommand.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/StockReservationFailedEvent.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/dto/StockReservedEvent.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/InvalidSagaMessageException.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/ReservationResultListener.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/SagaEventParser.java
  - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxEvent.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxEventRepository.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelayJob.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxWriter.java
  - order-service/src/main/java/com/orderflow/order/saga/ReservationSagaStarter.java
  - order-service/src/main/java/com/orderflow/order/saga/SagaTimeoutJob.java
  - order-service/src/main/resources/application.yml
  - order-service/src/main/resources/db/migration/V2__order_reservation_saga.sql
  - order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java
  - order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java
  - order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
  - order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java
  - order-service/src/test/java/com/orderflow/order/OrderControllerIT.java
  - order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java
  - order-service/src/test/java/com/orderflow/order/OrderSagaMigrationIT.java
  - order-service/src/test/java/com/orderflow/order/ReservationCommandPublishingIT.java
  - order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java
  - order-service/src/test/java/com/orderflow/order/saga/CancellationReasonsTest.java
  - order-service/src/test/java/com/orderflow/order/saga/messaging/SagaEventParserTest.java
  - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java
  - order-service/src/test/java/com/orderflow/order/saga/SagaTimeoutJobTest.java
  - order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java
  - order-service/src/test/java/com/orderflow/order/support/LocalStackProvisioningWaiter.java
  - order-service/src/test/java/com/orderflow/order/support/LocalStackTestSupport.java
  - order-service/src/test/java/com/orderflow/order/support/OrderSagaQueues.java
  - order-service/src/test/java/com/orderflow/order/support/OrderTestInfrastructure.java
  - order-service/src/test/resources/application-test.yml
  - pom.xml
  - README.md
  - scripts/smoke-order-flow.sh
  - scripts/smoke-order-saga.sh
findings:
  critical: 0
  warning: 4
  info: 2
  total: 6
status: issues_found
---

# Phase 5: Code Review Report

**Reviewed:** 2026-09-29
**Depth:** standard
**Files Reviewed:** 112
**Status:** issues_found

## Summary

Revisão da saga de reserva de estoque (Transactional Outbox em `order-service`/`inventory-service`,
consumidores SQS idempotentes, job de timeout, lápide de liberação, módulo `e2e-tests` e scripts de
smoke). A implementação é sólida: as transições de estado do pedido são sempre guardadas por status
(`requireReserving`/`requireApproved`/etc.), a escrita no outbox usa `Propagation.MANDATORY` em todos
os pontos de entrada auditados (nunca há gravação de comando fora da transação de negócio), a
concorrência entre o resultado da reserva e o job de timeout é serializada corretamente por
`findByIdForUpdate`/`PESSIMISTIC_WRITE` sobre a MESMA trava de linha (`SAGA_RESULT_LOCK=order-row`), e
a idempotência do lado do `inventory-service` (livro `stock_reservations` + lápide) resolve
corretamente a corrida da fila SQS padrão (sem FIFO) tanto para `ReserveStock`/`ReleaseStock`
concorrentes quanto para reentrega duplicada. Não encontrei nenhum caso em que um pedido possa ficar
preso, um comando possa ser publicado sem o estado correspondente ter avançado (ou vice-versa), ou uma
reserva possa ser vendida além do estoque disponível.

Os achados abaixo são pontuais: nenhum é um risco de perda de dados ou de segurança explorável de
fora do sistema — todos vivem na fronteira de confiança *interna* entre `order-service` e
`inventory-service` (a fila SQS), ou são inconsistências de robustez/observabilidade que não mudam o
resultado de negócio, mas valem a pena corrigir. Destaque para o WR-01: `releaseAll` reproduz
exatamente a MESMA classe de bug do Spring Retry que foi encontrada e corrigida para `reserveAll`
dentro deste mesmo plano (05-02), só que não recebeu a correção equivalente.

## Warnings

### WR-01: `InventoryService.releaseAll` não tem `@Recover` para `IllegalStateException` — mesmo bug já corrigido em `reserveAll` nesta fase

**File:** `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java:395-433`
**Issue:** `releaseAll` é anotado com `@Retryable(retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})` e lança `IllegalStateException` na linha 413 quando encontra uma reserva viva no livro sem a linha de `inventory` correspondente ("Inventory ausente para produto com reserva viva..."). O único `@Recover` declarado (linhas 429-433) casa apenas `DataAccessException`. O próprio código documenta, no javadoc de `recoverReserveAllInconsistentBook` (linhas 344-368, para `reserveAll`), que o aspecto de reexecução do Spring Retry intercepta QUALQUER exceção que escape de um método `@Retryable` assim que exista pelo menos um `@Recover` — não só as exceções listadas em `retryFor` — e que, sem um `@Recover` cujo tipo corresponda, a exceção real fica **soterrada por `ExhaustedRetryException("Cannot locate recovery method")`**. Essa correção foi aplicada a `reserveAll` (achada durante `IdempotentReservationIT`) mas não foi replicada para `releaseAll`, que tem exatamente o mesmo formato de anotações e o mesmo tipo de exceção técnica interna. O efeito prático: se a anomalia "reserva viva sem linha de estoque" ocorrer (o próprio javadoc do método já a trata como "nunca deveria acontecer", mas o código a antecipa com uma exceção dedicada), o chamador (`ReservationCommandListener`) e os logs recebem `ExhaustedRetryException: Cannot locate recovery method` em vez da mensagem original que cita `productId`/`reservationId`/`orderId` — dificultando o diagnóstico exatamente no caso em que ele mais importa.
**Fix:**
```java
@Recover
public void recoverReleaseAllInconsistentBook(IllegalStateException ex, UUID orderId, String reservationId,
                                               List<ReservationLine> lines) {
    throw ex;
}
```

### WR-02: `SagaEventParser` (order-service) valida os campos de entrada com menos rigor que seu par `SagaCommandParser` (inventory-service) na mesma fronteira de confiança

**File:** `order-service/src/main/java/com/orderflow/order/saga/messaging/SagaEventParser.java:82-97,145-156`
**Issue:** O javadoc da classe afirma "mesma disciplina de `SagaCommandParser`... todas as regras de campo do contrato", mas há duas lacunas concretas:
1. `requireIntAtLeast` (linhas 145-156) só valida um **piso** (`value < min`) para `quantity`/`requested`/`available` — nunca um teto. `SagaCommandParser.requireQuantity` (inventory-service), em contraste, rejeita explicitamente qualquer valor acima de `MAX_QUANTITY_PER_ITEM = 1_000_000` **antes** de fazer o cast para `int`. Aqui, um valor JSON como `9999999999999` (um `long` válido, mas fora da faixa de `int`) passa na checagem de mínimo e é silenciosamente truncado por `(int) value` — o valor resultante pode ser qualquer inteiro, inclusive negativo. Para `ReservationFailureLine.requested`/`available`, esse valor entra sem mais validação em `CancellationReasons.describeFailures` e aparece no `cancellationReason` retornado ao cliente pela API (`GET /orders/{id}`).
2. `requireReservationLines` (linhas 82-97) não verifica `productId` duplicado dentro de `items`, ao contrário de `SagaCommandParser.requireItems` (que mantém um `Set<UUID> seenProductIds` e rejeita repetição). Um `StockReserved` com `productId` repetido em `items` faz `OrderSagaService.itemsMatchOrder` (`Collectors.toMap(ReservationLine::productId, ReservationLine::quantity)`) lançar `IllegalStateException: Duplicate key ...` — uma exceção **não** capturada por `ReservationResultListener` (que só trata `InvalidSagaMessageException`), fugindo do tratamento "mensagem malformada é descartada com log WARN" que o design pretende para qualquer entrada inválida vinda da fila.
**Fix:** Espelhar as duas checagens de `SagaCommandParser`: adicionar um teto (`MAX_QUANTITY_PER_ITEM`/valor equivalente) em `requireIntAtLeast` antes do cast para `int`, e rejeitar `productId` repetido em `requireReservationLines` com `InvalidSagaMessageException`, do mesmo jeito que `SagaCommandParser.requireItems` já faz.

### WR-03: `poll-timeout: 0s` desliga o long polling no `SqsAsyncClient` inteiro, compartilhado pelos listeners da saga nos dois serviços

**File:** `order-service/src/main/resources/application.yml:75`, `inventory-service/src/main/resources/application.yml:65`, `order-service/.../config/SqsMessagingConfig.java`, `inventory-service/.../config/SqsMessagingConfig.java`
**Issue:** A correção documentada (`poll-timeout=0s` + folga de timeout) resolve o conflito real entre o long polling padrão do SQS (até 20s) e o `apiCallAttemptTimeout` curto pensado só para a chamada síncrona do `RestClient`/`PUT /inventory`. Só que a correção desliga o long polling para o `SqsAsyncClient` **inteiro**, incluindo os `@SqsListener` da saga (`ReservationCommandListener`/`ReservationResultListener`), que passam a fazer *short polling* contínuo. Em SQS real (fora do LocalStack), isso: (a) multiplica o número de chamadas `ReceiveMessage` (cada uma cobrada), e (b) short polling consulta só um subconjunto aleatório dos servidores que armazenam a fila — a AWS documenta que a resposta pode vir vazia mesmo com mensagens disponíveis, aumentando a latência de entrega sob baixo tráfego. A causa raiz (um único cliente/timeout servindo dois casos de uso com necessidades opostas: `RestClient` síncrono de timeout curto vs. listener de long polling) continua sem uma separação própria.
**Fix:** Considerar um `SqsAsyncClient`/`SqsAsyncClientCustomizer` dedicado para o container do listener (com `apiCallAttemptTimeout` folgado o bastante para acomodar o long polling de até 20s) e manter o timeout curto original apenas no cliente usado pelo `RestClient` síncrono/relay do outbox.

### WR-04: `OutboxRelayJob` e `SagaTimeoutJob` (order-service) competem pelo mesmo pool `@Scheduled` de tamanho padrão (1 thread)

**File:** `order-service/src/main/java/com/orderflow/order/config/SchedulingConfig.java`, `order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelayJob.java`, `order-service/src/main/java/com/orderflow/order/saga/SagaTimeoutJob.java`
**Issue:** `@EnableScheduling` sem `spring.task.scheduling.pool-size` configurado usa o `TaskScheduler` padrão do Spring Boot, que roda com **uma única thread** para todos os beans `@Scheduled` do contexto. No `order-service`, isso inclui `OutboxRelayJob` (a cada 1s em produção, lote de 20) e `SagaTimeoutJob` (a cada 10s, lote de 50) — os dois disputam a mesma thread. Se um lote do relay demorar (SQS lento, `apiCallAttemptTimeout` de 2s por evento com falha, até 20 eventos no lote), o ciclo do `SagaTimeoutJob` correspondente pode atrasar além do `timeout-check-interval` configurado, atrasando a garantia "nunca preso" exatamente sob a condição (SQS degradado) em que ela mais importa. Não compromete a correção (o job eventualmente roda), mas enfraquece a previsibilidade do SLA de timeout documentado.
**Fix:** Configurar `spring.task.scheduling.pool-size` (ex.: 2) para que os dois jobs `@Scheduled` do serviço não serializem entre si.

## Info

### IN-01: Javadoc duplicado (copy-paste) acima de `recoverReserveAllInconsistentBook`

**File:** `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java:344-363`
**Issue:** O mesmo bloco de javadoc aparece duas vezes seguidas, palavra por palavra, antes do método `recoverReserveAllInconsistentBook` (linhas 344-353 e 354-363) — artefato de copiar/colar sem remover a versão anterior.
**Fix:** Remover um dos dois blocos idênticos.

### IN-02: Contexto Spring "zumbi" de `StockReservationConcurrencyIT` continua consumindo a fila real após o fim da classe

**File:** `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java:1-76`
**Issue:** A correção documentada no SUMMARY (reaproveitar o mesmo container Postgres de `AbstractIntegrationTest`) elimina o sintoma observado (split-brain de banco), mas a causa raiz — um `ApplicationContext` próprio que o cache de contexto do Spring mantém vivo depois que a classe termina, com seu próprio `ReservationCommandListener` ainda consumindo `inventory-commands-queue` — continua presente. Qualquer suíte futura que dependa de qual listener processa uma mensagem específica (não só de qual banco recebe o efeito) pode voltar a ficar intermitente pelo mesmo motivo.
**Fix:** Considerar isolar o `ApplicationContext` desta classe (ex.: `@DirtiesContext` no fechamento, ou uma fila dedicada só para este teste) para eliminar o listener remanescente, não apenas neutralizar seu efeito colateral observável.

---

_Reviewed: 2026-09-29_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
