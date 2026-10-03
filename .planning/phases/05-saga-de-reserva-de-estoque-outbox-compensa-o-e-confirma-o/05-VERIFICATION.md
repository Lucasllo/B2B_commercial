---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
verified: 2026-09-30T23:00:00Z
status: passed
score: 5/5 must-haves verificados (Success Criteria do ROADMAP) + 4/4 requisitos satisfeitos
covered_files:
  - .planning/REQUIREMENTS.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-01-PLAN.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-01-SUMMARY.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-02-PLAN.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-02-SUMMARY.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-03-PLAN.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-03-SUMMARY.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-04-PLAN.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-04-SUMMARY.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-05-PLAN.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-05-SUMMARY.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-06-PLAN.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-06-SUMMARY.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-REVIEW-FIX.md
  - .planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-REVIEW.md
  - README.md
  - docker-compose.yml
  - e2e-tests/pom.xml
  - e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelay.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
  - inventory-service/src/main/resources/db/migration/V2__outbox_event.sql
  - inventory-service/src/main/resources/db/migration/V3__allow_reservation_tombstones.sql
  - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
  - order-service/src/main/java/com/orderflow/order/saga/ReservationSagaStarter.java
  - order-service/src/main/java/com/orderflow/order/saga/SagaTimeoutJob.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/SagaEventParser.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
  - order-service/src/main/resources/application.yml
  - order-service/src/main/resources/db/migration/V2__order_reservation_saga.sql
  - scripts/smoke-order-saga.sh
covered_digest: "v1:sha256:7f63f2cc91dbf935d02d06244b0b7244345cfe53aa07de092780159183ccd3f1"
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 5/5
  gaps_closed: []
  gaps_remaining: []
  regressions: []
  human_items_resolved_by: "05-UAT.md tests 1-5 (status complete, 43/43 pass, 0 issues)"
---

# Phase 5: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação — Verification Report

**Phase Goal:** Entregar o Core Value do projeto — ao seguir para confirmação, o order-service publica
o comando de reserva pelo padrão Transactional Outbox, o inventory-service reserva o estoque de forma
idempotente e devolve o resultado, e o pedido termina sempre em CONFIRMED ou CANCELLED, nunca preso
num estado intermediário.

**Verified:** 2026-09-30
**Status:** passed
**Re-verification:** Sim — o relatório de 2026-09-29 (human_needed) ficou obsoleto porque os commits de
code-review fix f48e759 (WR-01), 3b7eb3c (WR-02) e d9082b2 (WR-04) tocaram arquivos cobertos. Esta
rodada reconfere os must-haves contra o HEAD, reconcilia o item humano pendente com o `05-UAT.md` e
atualiza `covered_files`/`covered_digest`.

## Re-verificação dos commits de code-review fix

| Commit | Arquivo | O que mudou | Risco de regressão nos must-haves | Conclusão |
|---|---|---|---|---|
| f48e759 (WR-01) | `inventory-service/.../stock/InventoryService.java` | Novo `@Recover recoverReleaseAllInconsistentBook(IllegalStateException, UUID, String, List)` que apenas relança a exceção original. Lido no HEAD (linhas ~429–450): o `@Recover` anterior `recoverReleaseAll(DataAccessException…)` continua intacto; o novo só intercepta `IllegalStateException` (anomalia "reserva viva sem linha de inventory", nunca retentável). Não altera os ramos de sucesso/falha/replay/lápide de `releaseAll` nem `reserveAll`. | Nenhum: só muda qual exceção chega ao chamador no caminho de anomalia técnica (antes `ExhaustedRetryException`). Idempotência (SC4) e tudo-ou-nada (SC2/3) intactos. | Mantém SC2, SC3, SC4 |
| 3b7eb3c (WR-02) | `order-service/.../saga/messaging/SagaEventParser.java` (+4 testes em `SagaEventParserTest`) | `requireIntAtLeast` → `requireIntInRange` (piso e teto checados em `long` antes do cast; teto 1.000.000 para `quantity`/`requested`, `Integer.MAX_VALUE` para `available`); `productId` repetido em `items` rejeitado com `InvalidSagaMessageException`. | Baixo: só endurece validação de mensagem malformada na fronteira de confiança; quantidades legítimas (E2E/ITs usam valores pequenos) não são afetadas. Testes novos presentes: `stockReservedWithDuplicateProductIdIsRejected`, `…QuantityAboveCeilingIsRejected`, `failureWithRequestedAboveCeilingIsRejected`, `failureWithAvailableAboveIntRangeIsRejected`. | Mantém SC2, SC3 |
| d9082b2 (WR-04) | `order-service/src/main/resources/application.yml` | `spring.task.scheduling.pool-size: 2` (comentário explicando: `OutboxRelayJob` e `SagaTimeoutJob` deixam de serializar um ao outro). | Nenhum negativo; fortalece a garantia "pedido nunca preso" (SC3) sob SQS lento. | Reforça SC3 |

`git diff --stat d9082b2 HEAD -- . ':!.planning'` está **vazio**: nenhuma alteração de código de produção ou teste posterior ao último fix — os commits seguintes (UAT, SECURITY, VALIDATION) são só documentação.

## Goal Achievement

### Observable Truths (Success Criteria 1–5 do ROADMAP)

| # | Truth (ROADMAP) | Status | Evidência |
|---|---|---|---|
| 1 | Evento de reserva gravado numa tabela outbox na MESMA transação da mudança de status, publicado no SQS só depois do commit — nunca pedido avançado sem evento nem evento sem pedido | ✓ VERIFIED | Reverificado no HEAD: `ReservationSagaStarter.start` é `@Transactional(propagation = Propagation.MANDATORY)` (linha 30) e é chamado por `OrderService` (linha 76, aprovação automática) e `OrderDecisionService` (linha 58, aprovação manual) — único ponto de entrada. Gate de grep: `OutboxRelay.java` é o ÚNICO arquivo de `src/main` que importa `io.awspring.cloud.sqs.operations.*` em cada um dos dois serviços. Testes: `ReservationCommandPublishingIT`, `OrderSagaMigrationIT`, `OutboxRelayTest`, `StockAdjustedEventPublishingIT` (05-01/05-04 SUMMARY); 05-UAT testes 6–10. |
| 2 | Com estoque suficiente, o pedido percorre reserva → CONFIRMED automaticamente, e a quantidade reservada aparece refletida no inventory-service | ✓ VERIFIED | `reserveAll` + `OrderSagaService.applyStockReserved`; `OrderReservationSagaE2EIT` (5 casos, sem nenhum mock — grep por `mockito/MockBean/MockitoBean` em `e2e-tests/src` vazio); smoke na stack real observado em 05-UAT teste 3 (POST → RESERVING → CONFIRMED com `confirmedAt`). |
| 3 | Com estoque insuficiente, evento de falha leva o pedido a CANCELLED com motivo registrado; falha escrita antes do sucesso; pedido nunca fica travado | ✓ VERIFIED | `applyReservationFailed`/`CancellationReasons` (`cancellationCode`, `cancellationReason`); `SagaTimeoutJob` cancela por `RESERVATION_TIMEOUT` e compensa com `ReleaseStock`; `SagaTimeoutIT` (5 testes) verde no log pós-fix; pool de 2 threads (WR-04) evita que o relay atrase o job de timeout. 05-UAT teste 3: pedido acima do disponível termina CANCELLED com `INSUFFICIENT_STOCK` e motivo legível. TDD "falha antes do sucesso" (D-68) registrado nos SUMMARYs/git. |
| 4 | Reentregar o mesmo comando de reserva duas vezes decrementa o estoque uma única vez — idempotência comprovada por teste que republica o evento | ✓ VERIFIED | `IdempotentReservationIT` (inventory) e `OrderReservationSagaE2EIT#republishingSameReserveStockAfterConfirmedDoesNotReserveAgain` (republica direto na fila real). `releaseAll`/`reserveAll` intactos após WR-01. |
| 5 | Teste E2E com Testcontainers (PostgreSQL + LocalStack reais) percorre criar → reservar → confirmar e falha → cancelar, executável por um único comando | ✓ VERIFIED | Módulo `e2e-tests` (`./mvnw -B -pl e2e-tests -am verify`): `E2eContextsSmokeIT` 7 + `OrderReservationSagaE2EIT` 5 = 12 testes, 0 falhas no log pós-fix. |

**Score:** 5/5 truths verificadas (0 presente-mas-comportamento-não-exercitado).

### Evidência de execução pós-fix (não re-executada pelo verificador, por instrução)

`$HOME/orderflow-verify-05.log` (modificado 2026-09-30 19:35, posterior aos commits 19:03–19:05 dos fixes): `./mvnw -B verify` → **BUILD SUCCESS**, 8 módulos; ocorrências por módulo no log: inventory-service 32 unit + 51 IT, order-service 58 unit + 70 IT, e2e-tests 12; `Failures: 0, Errors: 0, Skipped: 0` em todas as linhas de resumo. Como o diff de código desde d9082b2 é vazio, o log vale para o HEAD. Não havia razão específica para duvidar dele, portanto a suíte não foi re-executada (nem o `docker compose` foi tocado).

### Requisitos da Fase (ORD-04, ORD-05, ORD-06, TEST-03)

| Requisito | Descrição | Status | Evidência |
|---|---|---|---|
| ORD-04 | Order-service publica evento de reserva via SQS pelo padrão Transactional Outbox | ✓ SATISFIED | SC1; `ReservationSagaStarter`/`OutboxRelay` (order) + outbox do inventory para `STOCK_ADJUSTED` |
| ORD-05 | Order-service consome o resultado e transiciona para CONFIRMED/CANCELLED | ✓ SATISFIED | SC2/SC3; `OrderSagaService`, `SagaTimeoutJob`, `SagaEventParser` endurecido (WR-02) |
| ORD-06 | Consumidores SQS processam cada evento de forma idempotente | ✓ SATISFIED | SC4; `reserveAll`/`releaseAll` + livro `stock_reservations` + lápide; guarda por estado no order-service |
| TEST-03 | E2E/contrato verifica o fluxo completo da saga | ✓ SATISFIED | SC5; `e2e-tests` |

**Cross-referência:** os `requirements:` das seis PLANs são `[ORD-04]`, `[ORD-06, ORD-05]`, `[ORD-05, ORD-06]`, `[ORD-05, ORD-06, ORD-04]`, `[TEST-03, ORD-05]`, `[ORD-04, ORD-05, TEST-03]` — união exata {ORD-04, ORD-05, ORD-06, TEST-03}. `REQUIREMENTS.md` mapeia exatamente esses quatro IDs à "Phase 5" (linhas 119–121 e 135, todos `Complete`; corpo `[x]` nas linhas 34–36 e 62). **Nenhum requisito órfão, nenhum ID sem contabilização.**

### Required Artifacts / Key Links

| Artifact / Link | Status | Detalhes |
|---|---|---|
| `ReservationSagaStarter.java` | ✓ VERIFIED | `Propagation.MANDATORY`; chamado em `OrderService:76` e `OrderDecisionService:58` |
| `order-service/.../outbox/OutboxRelay.java`, `inventory-service/.../outbox/OutboxRelay.java` | ✓ VERIFIED | Únicos importadores do envio SQS em cada módulo |
| `OrderSagaService.java`, `SagaTimeoutJob.java`, `SagaEventParser.java` | ✓ VERIFIED / WIRED | Substantivos; parser endurecido e coberto por 4 testes novos |
| `InventoryService.java` | ✓ VERIFIED | `reserveAll`/`releaseAll` com `@Retryable` + `@Recover` (DataAccessException e IllegalStateException) |
| `ReservationCommandListener.java` | ✓ WIRED | Despacha para `reserveAll`/`releaseAll` |
| Migrations order V2, inventory V2/V3 | ✓ VERIFIED | Existem; migrações rodaram no cold start (05-UAT teste 1) |
| `application.yml` (order-service) | ✓ VERIFIED | `spring.task.scheduling.pool-size: 2` |
| `e2e-tests/pom.xml`, `OrderReservationSagaE2EIT.java`, `scripts/smoke-order-saga.sh` | ✓ VERIFIED | Existem; smoke executado no UAT teste 3 |

### Data-Flow Trace (Level 4)

Fluxo real comprovado ponta a ponta por E2E sem dublês e pelo smoke na stack real (HTTP → outbox → SQS LocalStack → inventory Postgres → evento de volta → status do pedido). Nenhum valor terminando em literal/mock.

### Anti-Patterns Found

`grep -rnE "TBD|FIXME|XXX"` em `inventory-service/src/main`, `order-service/src/main`, `e2e-tests/src` e `scripts` → nenhum resultado. Gate de debt marker não acionado.

`05-REVIEW.md`: 0 críticos, 4 avisos, 2 informativos. WR-01, WR-02, WR-04 corrigidos (verificados acima). **WR-03** (`poll-timeout: 0s` desliga long polling) foi deliberadamente adiado como backlog em `05-REVIEW-FIX.md`: impacto só de custo/latência em SQS real (não em LocalStack), não é must-have do ROADMAP nem das PLANs e não afeta correção do fluxo — ℹ️/⚠️ informativo, **não bloqueante**. IN-01/IN-02 fora de escopo do fix, não bloqueantes.

### Segurança e validação (artefatos complementares)

- `05-SECURITY.md`: status `verified`, `threats_open: 0` (28/28 fechadas, 3 riscos aceitos registrados).
- `05-VALIDATION.md`: `validated`, `nyquist_compliant: true`.
- `05-UAT.md`: `complete`, 43/43 pass, 0 issues.

### Human Verification

O único item humano do relatório anterior (leitura de clareza do README/`docs/API.md`/`docs/VISAO-GERAL.md` + observação da saga pelo Swagger/smoke) foi executado e **aprovado** em `05-UAT.md`: teste 3 (saga observável pelo Swagger e pelo smoke; CONFIRMED com `confirmedAt` e CANCELLED com `INSUFFICIENT_STOCK`) e teste 4 (documentação legível para um avaliador) — ambos `result: pass`. Também aprovados: teste 1 (cold start, migrações Flyway, Gateway UP), teste 2 (LocalStack saudável com as quatro filas) e teste 5 (suíte completa verde após os fixes). Não há mais itens humanos pendentes.

### Gaps Summary

Nenhum gap. Os cinco Success Criteria do ROADMAP e os quatro requisitos (ORD-04, ORD-05, ORD-06, TEST-03) continuam válidos no HEAD depois dos fixes WR-01/WR-02/WR-04; nenhum código foi alterado após o último fix; a suíte completa pós-fix (346 testes) passou; o checkpoint humano foi fechado pelo UAT. A única pendência residual é o item de backlog WR-03 (custo/latência de polling em SQS real), fora do escopo do Goal.

---

_Verified: 2026-09-30_
_Verifier: Claude (gsd-verifier)_
