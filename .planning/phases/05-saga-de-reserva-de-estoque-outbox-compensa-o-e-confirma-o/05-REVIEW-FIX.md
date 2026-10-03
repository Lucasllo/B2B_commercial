---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
fixed_at: 2026-09-30T00:00:00Z
review_path: .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 3
skipped: 1
status: partial
---

# Phase 5: Code Review Fix Report

**Fixed at:** 2026-09-30
**Source review:** .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4 (fix_scope = critical_warning; IN-01 e IN-02 fora de escopo)
- Fixed: 3
- Skipped: 1

**Verification environment:** as correções foram feitas e verificadas num worktree isolado (`.claude/worktrees/rf-05-...`), já removido; os commits foram avançados por fast-forward para `master`. Docker não estava disponível, então nenhum teste de integração (Testcontainers) foi executado. A verificação foi: compilação Maven (`./mvnw -pl inventory-service -am compile`), testes unitários do `SagaEventParserTest` (27 testes, 0 falhas) e parse do YAML com snakeyaml.

## Fixed Issues

### WR-01: `InventoryService.releaseAll` sem `@Recover` para `IllegalStateException`

**Files modified:** `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java`
**Commit:** f48e759
**Applied fix:** adicionado `recoverReleaseAllInconsistentBook(IllegalStateException, UUID, String, List<ReservationLine>)` que relança a exceção original, espelhando o `@Recover` já existente em `reserveAll`. Assim a mensagem com `productId`/`reservationId`/`orderId` não fica soterrada por `ExhaustedRetryException`. Status: fixed, requires human verification (comportamento do Spring Retry só é exercitável em IT; não há IT cobrindo este caminho e Docker estava indisponível).

### WR-02: `SagaEventParser` valida com menos rigor que `SagaCommandParser`

**Files modified:** `order-service/src/main/java/com/orderflow/order/saga/messaging/SagaEventParser.java`, `order-service/src/test/java/com/orderflow/order/saga/messaging/SagaEventParserTest.java`
**Commit:** 3b7eb3c
**Applied fix:** `requireIntAtLeast` virou `requireIntInRange` (piso e teto checados em `long` antes do cast para `int`, rejeitando também números que não cabem em `long`). `quantity` e `requested` usam teto de 1.000.000 (igual ao `SagaCommandParser`); `available` usa `Integer.MAX_VALUE`, pois reflete o saldo em estoque, que o inventory-service permite até esse valor. `requireReservationLines` agora rejeita `productId` repetido com `InvalidSagaMessageException`. Quatro testes unitários novos cobrem os casos. Status: fixed, requires human verification (lógica de validação; escolha do teto de `available` diverge levemente da sugestão do review, que propunha o mesmo teto do comando).

### WR-04: `OutboxRelayJob` e `SagaTimeoutJob` competem por um pool `@Scheduled` de 1 thread

**Files modified:** `order-service/src/main/resources/application.yml`
**Commit:** d9082b2
**Applied fix:** adicionado `spring.task.scheduling.pool-size: 2` com comentário explicando o motivo. YAML validado por parse com snakeyaml.

## Skipped Issues

### WR-03: `poll-timeout: 0s` desliga o long polling no `SqsAsyncClient` compartilhado

**File:** `order-service/src/main/resources/application.yml:75`, `inventory-service/src/main/resources/application.yml:65`, `*/config/SqsMessagingConfig.java`
**Reason:** skipped: a correção exige uma mudança estrutural nos dois serviços (um `SqsAsyncClient` dedicado ao container do listener, com `SqsMessageListenerContainerFactory` customizada, e manutenção do cliente de timeout curto para o relay). Definir um segundo bean `SqsAsyncClient` faz a autoconfiguração do Spring Cloud AWS recuar (`@ConditionalOnMissingBean`) e exigiria reconstruir manualmente endpoint, região e credenciais do LocalStack. Sem Docker para rodar os ITs de saga (`ReservationResultListenerIT`, `ReservationCommandConsumptionIT`, E2E), não há como verificar que o listener continua consumindo, e uma regressão aqui quebraria o fluxo central da saga. O review também a formula como "considerar". O impacto real (custo de `ReceiveMessage` e latência em short polling) só existe em SQS de verdade, não no LocalStack que o projeto usa. Recomendado tratar como item de backlog, com os ITs disponíveis.
**Original issue:** o ajuste `poll-timeout=0s` resolve o conflito com `apiCallAttemptTimeout` curto, mas desliga o long polling para todos os `@SqsListener` da saga, pois o mesmo `SqsAsyncClient` atende o `RestClient`/relay e os listeners.

---

_Fixed: 2026-09-30_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
