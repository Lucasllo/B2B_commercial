---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
plan: "06"
subsystem: docs+testing
tags: [smoke-test, docker-compose, saga, documentation, gateway]

requires:
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "01"
    provides: "Filas inventory-commands-queue/order-events-queue com DLQ; order-service ligado ao LocalStack"
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "03"
    provides: "ORDER_RESPONSE_CONTRACT com cancellationCode/cancellationReason/confirmedAt/cancelledAt"
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "04"
    provides: "Timeout da saga, lápide (tombstone), STOCK_ADJUSTED pelo outbox do inventory-service"
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "05"
    provides: "Saga completa provada de ponta a ponta pelo módulo e2e-tests (Testcontainers)"
provides:
  - "scripts/smoke-order-saga.sh — demonstração da saga completa na stack real (docker compose), pelo Gateway: reserva+confirmação, cancelamento por estoque insuficiente/produto sem estoque, aprovação manual confirmando, STOCK_ADJUSTED pelo outbox"
  - "scripts/smoke-order-flow.sh ajustado ao estado RESERVING (D-54) em vez de APPROVED, com estoque de P1 definido para manter o determinismo do smoke da Fase 4"
  - "README.md, docs/API.md e docs/VISAO-GERAL.md documentando a saga, os estados novos, os códigos de cancelamento e as limitações da Fase 5 para um avaliador externo"
affects: []

actuals:
  tokens: 17176
  tasks: 2
  commits: 2
  plan_head_before: 81db7d135ea265756f90a6dcea222bb43b53dfff

tech-stack:
  added: []
  patterns:
    - "wait_for_order_status: polling de GET /orders/{id} a cada 1s por até 30s, com falha imediata se o pedido chegar a um estado terminal diferente do esperado — mesmo espírito do count_history_entries/loop de espera do smoke-notification-flow.sh, adaptado para o ciclo RESERVING -> CONFIRMED|CANCELLED"

key-files:
  created:
    - scripts/smoke-order-saga.sh
  modified:
    - scripts/smoke-order-flow.sh
    - README.md
    - docs/API.md
    - docs/VISAO-GERAL.md

key-decisions:
  - "OUTBOX_RETENTION=none-this-phase — linhas publicadas do outbox não são apagadas nesta fase; documentado como limitação conhecida em README.md § Limitações conhecidas (Fase 5) (decisão do planejador registrada no PLAN.md)."

patterns-established: []

requirements-completed: [ORD-04, ORD-05, TEST-03]

coverage:
  - id: D1
    description: "scripts/smoke-order-saga.sh demonstra a saga completa na stack real (docker compose up --wait), pelo Gateway: pedido com estoque suficiente reserva e confirma com o estoque refletido no inventory (Success Criteria 2), pedido acima do disponível ou de produto sem estoque cancela com motivo legível sem afetar a reserva confirmada (Success Criteria 3), a aprovação manual do vendedor também confirma via saga (D-48), e o ajuste de estoque do passo 3 chega ao histórico de notificações pelo outbox (D-60)"
    requirement: "ORD-05"
    verification:
      - kind: other
        ref: "bash scripts/smoke-order-saga.sh (execução real contra a stack docker compose, 10/10 passos, linha final `SMOKE OK`)"
        status: pass
    human_judgment: false
  - id: D2
    description: "scripts/smoke-order-flow.sh (Fase 4) ajustado ao estado RESERVING nos passos 5 e 11 (D-54) e ao estoque de P1 definido no passo 3 — continua SMOKE OK sem regressão nos Success Criteria 1 a 4 originais"
    requirement: "TEST-03"
    verification:
      - kind: other
        ref: "bash scripts/smoke-order-flow.sh (execução real contra a stack docker compose, 15/15 passos, linha final `SMOKE OK`)"
        status: pass
    human_judgment: false
  - id: D3
    description: "scripts/smoke-notification-flow.sh (Fase 3) continua passando sem nenhuma alteração de código, mesmo com STOCK_ADJUSTED agora saindo pelo outbox do inventory-service (05-04)"
    requirement: "ORD-04"
    verification:
      - kind: other
        ref: "bash scripts/smoke-notification-flow.sh (execução real contra a stack docker compose, 7/7 passos, linha final `SMOKE OK`)"
        status: pass
    human_judgment: false
  - id: D4
    description: "README.md, docs/API.md e docs/VISAO-GERAL.md explicam a saga (diagrama, outbox, entrega pelo menos uma vez, idempotência, timeout/compensação, lápide, como observar), os estados/códigos de cancelamento novos, e as limitações D-30/'APPROVED não reserva estoque' como resolvidas — legível por um avaliador externo sem abrir o código"
    requirement: "ORD-05"
    verification: []
    human_judgment: true
    rationale: "Clareza do texto para um avaliador externo e legibilidade da Swagger UI (Task 2 <human-check>) exigem julgamento humano, não verificável só por grep — os quatro greps automatizados do plano (Limitações conhecidas (Fase 5), e2e-tests -am verify, smoke-order-saga.sh, RESERVATION_TIMEOUT, order-events-queue) já passaram e estão documentados abaixo."

duration: 30min
completed: 2026-09-30
status: complete
---

# Phase 5 Plan 6: Demonstração da Saga na Stack Real Summary

**`scripts/smoke-order-saga.sh` prova pelo Gateway, na stack `docker compose` real, os três desfechos da saga — reserva e confirmação com o estoque refletido no inventory, cancelamento com motivo legível por falta de estoque ou produto não cadastrado, e confirmação após aprovação manual —, o smoke da Fase 4 passa a esperar `RESERVING` em vez de `APPROVED`, e README/docs explicam a saga completa (outbox, entrega pelo menos uma vez, idempotência, timeout com compensação, lápide) a um avaliador externo.**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-09-30T01:20:29Z (aprox., commit anterior 81db7d1)
- **Completed:** 2026-09-30T01:46:46Z
- **Tasks:** 2
- **Files modified:** 5 (1 criado, 4 modificados)

## Accomplishments

- `scripts/smoke-order-saga.sh` novo (modo `100755` no índice): 10 passos pelo Gateway, com a função
  `wait_for_order_status` (polling de `GET /orders/{id}` a cada 1s por até 30s, falha imediata em
  estado terminal diferente do esperado) — prova reserva+confirmação com `quantityReserved`/
  `quantityAvailable` refletidos no `inventory-service` (Success Criteria 2), cancelamento por
  `INSUFFICIENT_STOCK` com `cancellationReason` citando `disponível 7`/`solicitado 20` e a reserva do
  pedido confirmado intacta, cancelamento por `PRODUCT_NOT_STOCKED`, aprovação manual do vendedor
  também chegando a `CONFIRMED` com o estoque reservado (D-48), e o evento `STOCK_ADJUSTED` do ajuste
  do passo 3 visível no histórico de notificações em segundos (D-60). Executado de ponta a ponta
  contra a stack real: `SMOKE OK` em todos os passos.
- `scripts/smoke-order-flow.sh` (Fase 4) ajustado ao novo contrato: define o estoque de P1 (100
  unidades) logo após criá-lo, e os passos 5/11 passam a esperar `status":"RESERVING"` em vez de
  `APPROVED` (D-54), preservando as asserções de `decidedBy` e o determinismo dos passos seguintes
  (que dependem da exposição de crédito acumulada). Reexecutado contra a stack real: `SMOKE OK` nos
  15 passos, sem regressão.
- `scripts/smoke-notification-flow.sh` (Fase 3) reexecutado sem nenhuma alteração de código: `SMOKE
  OK` nos 7 passos — confirma que `STOCK_ADJUSTED` continua chegando ao histórico mesmo agora saindo
  pelo Transactional Outbox do `inventory-service` (05-04), não mais por publicação direta.
- `README.md`: introdução atualizada para "Fases 1 a 5"; `./mvnw -B -pl e2e-tests -am verify`
  documentado em §Build e testes (com a ressalva de derrubar o compose antes); §Pedidos e §Como a
  aprovação por crédito funciona atualizados para `RESERVING`; nova seção "Saga de reserva de
  estoque (Fase 5)" (diagrama em texto do fluxo completo, motivo do Transactional Outbox, entrega
  pelo menos uma vez com idempotência nos dois lados, lápide contra a corrida da fila sem FIFO,
  timeout com compensação, `CONFIRMED` mantendo a reserva até a expedição); nova seção "Como
  observar a saga" (filas/DLQs via `awslocal`, tabelas `outbox_event`, `GET /orders/{id}`, o smoke
  novo); §Estoque com nota de que as rotas REST de reserva/liberação são ferramenta administrativa,
  não usadas pela saga; itens 1 de "Limitações conhecidas (Fase 3)" e (Fase 4) marcados como
  resolvidos na Fase 5; nova seção "Limitações conhecidas (Fase 5)" com as oito limitações
  registradas no plano (latência do relay, `OUTBOX_RETENTION=none-this-phase`, timeout fixo, DLQ só
  inspecionável à mão, motivo de cancelamento revelando disponibilidade — D-56 deliberado —, filas
  sem política de acesso, lápides sem limpeza, sem cancelamento pelo comprador).
- `docs/API.md`: `POST /orders`/`approve` documentam a resposta `RESERVING` (com os quatro campos
  novos sempre nulos nessa resposta); nova subseção "Estados do pedido e saga de reserva de estoque
  (Fase 5)" com a tabela de transições (o que consome crédito) e a tabela dos quatro códigos de
  cancelamento com exemplo de `cancellationReason`; `GET /orders` documenta `?status=RESERVING|
  CONFIRMED|CANCELLED`.
- `docs/VISAO-GERAL.md`: §Estado atual e §Arquitetura em alto nível atualizados com as duas filas da
  saga e suas DLQs, o outbox duplicado nos dois serviços (D-62), e a orquestração no
  `order-service`; §Stack técnica cita o módulo `e2e-tests`.

## Task Commits

1. **Task 1: Tracer — pelo Gateway, na stack real, um pedido chega a CONFIRMED com o estoque reservado e outro termina CANCELLED com motivo** — `916ebbc` (feat): `scripts/smoke-order-saga.sh` novo + `scripts/smoke-order-flow.sh` ajustado; suíte de três smokes executada contra a stack real (`docker compose up -d --build --wait` → três smokes → `docker compose down`), todos `SMOKE OK`.
2. **Task 2: README e docs explicam a saga, os estados novos e as limitações da fase a quem avalia o projeto** — `9957ed3` (docs): `README.md`, `docs/API.md`, `docs/VISAO-GERAL.md` atualizados; os cinco greps automatizados do plano confirmados.

**Plan metadata:** commit deste SUMMARY (a seguir).

## Files Created/Modified

- `scripts/smoke-order-saga.sh` — smoke novo da saga na stack real (10 passos, modo `100755`)
- `scripts/smoke-order-flow.sh` — estoque de P1 + `RESERVING` nos passos 5/11
- `README.md` — introdução, §Build e testes, §Pedidos, §Como a aprovação por crédito funciona,
  §Estoque, "Saga de reserva de estoque (Fase 5)", "Como observar a saga", limitações das Fases 3/4
  atualizadas, "Limitações conhecidas (Fase 5)" nova
- `docs/API.md` — `POST /orders`/`approve`, "Estados do pedido e saga de reserva de estoque (Fase
  5)", `GET /orders` com `?status=`
- `docs/VISAO-GERAL.md` — Estado atual, diagrama de arquitetura, Stack técnica

## Decisions Made

- `OUTBOX_RETENTION=none-this-phase` registrado (frontmatter e README § Limitações conhecidas (Fase
  5)) — exigido pelo `<output>` do plano.
- Legado da Fase 4: antes de subir a stack para este plano, `SELECT status, cancellation_code,
  count(*) FROM "order".orders GROUP BY 1, 2` no volume `postgres-data` mostrou **zero** pedidos em
  `APPROVED` — apenas `CANCELLED`/`PRODUCT_NOT_STOCKED` (6) e `REJECTED` (3), remanescentes de
  execuções anteriores dos smokes/plans 05-01 a 05-05 sobre o mesmo volume. A migração V2 (05-01,
  D-51) já havia movido qualquer pedido `APPROVED` legado para `RESERVING` + comando no outbox em
  execuções anteriores da stack; nenhum pedido órfão foi encontrado nesta verificação.

## Deviations from Plan

None - plan executado exatamente como escrito.

## Issues Encountered

- Nota administrativa (sem impacto no plano): o sentinela do protocolo de commit (`gsd-plan-head-before-05-06`) não foi criado antes do primeiro commit da Task 1. Reconstruído após o fato a partir do `git status`/log da conversa (HEAD real antes da Task 1 era `81db7d1`, o mesmo commit relatado no início da sessão) — `actuals.commits`/`plan_head_before` no frontmatter refletem essa base correta, confirmada por `git rev-list --count 81db7d1..HEAD` = 2 no momento da escrita deste SUMMARY.

## User Setup Required

None - nenhuma configuração de serviço externo nova (`LOCALSTACK_AUTH_TOKEN`/`POSTGRES_PASSWORD` já
exigidos desde a Fase 1).

## Next Phase Readiness

- Core Value do projeto (ROADMAP) demonstrado de ponta a ponta na stack real, pelo Gateway: pedido →
  aprovação (automática/manual) → reserva de estoque assíncrona via saga → `CONFIRMED`/`CANCELLED`,
  nunca preso num estado intermediário.
- Documentação da Fase 5 fecha D-30 (dual-write) e "APPROVED não reserva estoque" (limitação da Fase
  4), sem prometer entrega exatamente uma vez ou ordem garantida (T-05-26, checagem humana pendente
  no UAT consolidado de fim de fase).
- Stack deixada **down** ao final (`docker compose down`), liberando a sessão única do LocalStack
  Hobby para qualquer suíte `./mvnw verify`/`e2e-tests` subsequente.
- Nenhum bloqueio conhecido para o encerramento da Fase 5 ou para a Fase 6 (transportadora,
  SHIPPED/DELIVERED, baixa de `quantity_on_hand`).

---
*Phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o*
*Completed: 2026-09-30*

## Self-Check: PASSED

- `scripts/smoke-order-saga.sh` confirmado em disco (`[ -f ]`) e no índice com modo `100755`
  (`git ls-files -s`).
- Os 2 commits do plano confirmados em `git log --oneline --all`: `916ebbc`, `9957ed3`.
- `bash scripts/smoke-order-saga.sh`, `bash scripts/smoke-order-flow.sh` e
  `bash scripts/smoke-notification-flow.sh` reexecutados contra `docker compose up -d --build
  --wait` real, todos terminando em `SMOKE OK`; `docker compose down` confirmado com exit 0 ao
  final — nenhum container da stack restou de pé.
- Os cinco greps automatizados do plano reconfirmados: `Limitações conhecidas (Fase 5)` (README,
  count 1), `e2e-tests -am verify` (README, count 1), `smoke-order-saga.sh` (README, count 1),
  `RESERVATION_TIMEOUT` (docs/API.md, count 2), `order-events-queue` (docs/VISAO-GERAL.md, count 6).
