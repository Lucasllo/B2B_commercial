---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "09"
subsystem: inventory-saga
tags: [sqs, dlq, spring-retry, recover, shipstock, inventory-service, review-fix]
status: complete

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: listener do inventory com escopo de Correlation-ID (07-04) e serialização de sessão (07-08)
provides:
  - InvalidShipStockException — ShipStock inválido tratado como anomalia técnica (ERROR + relança + DLQ)
  - "@Retryable(recover = ...) em shipAll e releaseAll, com sobrecargas por tipo de exceção"
  - WARN "ShipStock anomalo" realmente emitido e novo WARN "ReleaseStock anomalo"
affects: [07-10, 07-11]

plan_head_before: a7f5aa1610753bb931df5df3307b976c5bf4aa50
actuals:
  tokens: 45000
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - Subtipo de exceção de validação para separar "descartar" (D-67) de "anomalia técnica -> DLQ" (D-107)
    - "@Retryable(recover = nome) + @Recover sobrecarregados por tipo de exceção"

key-files:
  created:
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/InvalidShipStockException.java
    - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/ReservationCommandListenerTest.java
  modified:
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java
    - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
    - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java
    - inventory-service/src/test/java/com/orderflow/inventory/ShipStockConsumptionIT.java

key-decisions:
  - "WR01_STRATEGY=dlq — InvalidShipStockException (subclasse de InvalidSagaMessageException) capturada antes do catch genérico no listener, logada em ERROR (orderId sanitizado, sem payload) e relançada; o SQS reentrega até a inventory-commands-dlq (maxReceiveCount 3). ReserveStock/ReleaseStock inválidos seguem descartados com WARN (D-67)."
  - "WR02_RECOVER_NAMES=recoverShipAll,recoverReleaseAll — recover declarado pelo nome em cada @Retryable; os métodos ...InconsistentBook foram renomeados para o nome do par (sobrecarga por tipo: DataAccessException -> ReservationConflictException; IllegalStateException -> WARN + relança). recoverReleaseAll(IllegalStateException) ganhou o WARN 'ReleaseStock anomalo (pedido ...)'."
  - "WR01_IT_VISIBILITY=1s-restored-30s — o IT baixa o VisibilityTimeout da inventory-commands-queue para 1 s só durante o caso da DLQ e restaura 30 s no finally."

requirements-completed: [TEST-01, TEST-02]

duration: ~30min
completed: 2026-10-03
---

# Phase 7 Plan 09: WR-01 e WR-02 do inventory-service Summary

**ShipStock inválido deixa de sumir em silêncio (ERROR + reentrega até a DLQ) e os @Recover de shipAll/releaseAll passam a ser escolhidos pelo nome, emitindo o WARN de anomalia do comando certo.**

## Accomplishments

- **WR-01 (Task 1, tracer):** o parser lança `InvalidShipStockException` no ramo `ShipStock` (mesma mensagem de validação, `orderId` lido de forma tolerante e sanitizado, `?` se ausente). O listener captura o subtipo antes de `InvalidSagaMessageException`, loga `ShipStock invalido enviado para reentrega/DLQ orderId=... fila=...` em ERROR e relança. IT com LocalStack real prova que um `ShipStock` com `quantity` 1.000.001 chega à `inventory-commands-dlq` com o mesmo corpo em até 30 s, sem alterar `quantity_on_hand`/`quantity_reserved` nem as marcas `shipped`/`released` do livro.
- **WR-02 (Task 2):** `@Retryable(recover = "recoverShipAll")` e `recover = "recoverReleaseAll"`. O IT confirma o WARN `ShipStock anomalo (pedido <id>)` nos casos de reserva sem livro e já liberada, e `ReleaseStock anomalo (pedido <id>)` (sem o WARN de ShipStock) para reserva viva sem linha de inventory; a exceção original continua chegando ao chamador.
- Testes: `SagaCommandParserTest` 39 -> 47, novo `ReservationCommandListenerTest` (2), `ShipStockConsumptionIT` 10 -> 12. `./mvnw -B -pl inventory-service verify` verde (62 unitários, 72 de integração).

## Task Commits

1. Task 1 (tracer, WR-01): `9bcd21e` — fix(07-09): ShipStock invalido vai para a DLQ com log ERROR
2. Task 2 (WR-02): `b8a462e` — fix(07-09): @Recover de shipAll e releaseAll escolhidos pelo nome

Tracer feedback gate: verificado de ponta a ponta (parser, listener e IT com DLQ real) antes da Task 2.

## Deviations from Plan

- **TDD RED:** na Task 1 o RED foi falha de compilação (a exceção ainda não existia); na Task 2 os testes e a mudança foram escritos em sequência e validados juntos — o comportamento anterior (WARN de ShipStock nunca emitido) já estava documentado no Javadoc de 06-03, não foi reproduzido em execução separada.
- **Contagem de testes do IT:** o plano cita 11 casos existentes em `ShipStockConsumptionIT`; o arquivo tinha 10, agora tem 12 com os dois novos. Sem impacto.
- **Correção de teste (mecânica):** escapes de barra invertida em testes gerados por heredoc corrigidos na hora (nenhum impacto no código de produção).

None of the deviation rules (1-4) were triggered in production code.

## Known Stubs

None.

## Threat Flags

None — nenhuma superfície nova; T-07-30 a T-07-33 mitigadas (DLQ real testada, log sem payload, `recover` por nome com WARNs distintos).

## Self-Check: PASSED

- Arquivos criados/modificados existem; commits `9bcd21e` e `b8a462e` presentes; `commits: 2` medido via ledger (`a7f5aa1..HEAD`).
