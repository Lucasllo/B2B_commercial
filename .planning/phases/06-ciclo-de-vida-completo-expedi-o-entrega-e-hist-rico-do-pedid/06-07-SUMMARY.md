---
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
plan: "07"
subsystem: docs
tags: [documentation, mermaid, state-diagram, doc-code-consistency, api-reference]

requires:
  - phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
    provides: OrderStatus.transitions() (06-01), ship/deliver (06-02), ShipStock (06-03), linha do tempo (06-04/06-05), smoke e E2E (06-06)
provides:
  - OrderStatusDiagramConsistencyTest (diagramas stateDiagram-v2 da documentacao x OrderStatus.transitions())
  - Diagrama Mermaid de estados do pedido no README e na visao geral, com tabela de gatilhos
  - Referencia da API da Fase 6 (ship, deliver, linha do tempo, campos novos, eventos ORDER_*, ShipStock)
  - Limitacoes conhecidas (Fase 6) no README
affects: []

actuals:
  tokens: 16000
  tasks: 3
  commits: 3

plan_head_before: ca188a7dfdda316551997d0bd65668f6ac0736a1

tech-stack:
  added: []
  patterns:
    - "Teste que le a documentacao Markdown a partir do diretorio do modulo e a confronta com a tabela unica do codigo"
    - "Comparador estatico recebendo texto, com casos de controle que provam aresta a mais, a menos, estado inexistente e linha malformada"

key-files:
  created:
    - order-service/src/test/java/com/orderflow/order/order/OrderStatusDiagramConsistencyTest.java
  modified:
    - README.md
    - docs/VISAO-GERAL.md
    - docs/API.md

key-decisions:
  - "DOCS_CHECKED_BY_TEST=README.md,docs/VISAO-GERAL.md (um teste parametrizado por documento; cada um exige ao menos um bloco mermaid stateDiagram-v2)"
  - "DIAGRAM_EQUALS_TABLE_PLUS_API_EQUALS_TABLE: 'corresponde exatamente' e provado pela soma de dois testes (diagrama x tabela aqui, API x tabela no OrderLifecycleTransitionsIT de 06-02), nao por um teste unico"
  - "Linha de aresta fora do formato 'A --> B[: rotulo]' (por exemplo A --> B --> C) quebra o teste em vez de ser ignorada"

requirements-completed: [ORD-07, ORD-10]

coverage:
  - id: D1
    description: "README tem o diagrama stateDiagram-v2 do ciclo de vida com exatamente as 9 arestas de OrderStatus.transitions(), rotuladas com o gatilho"
    requirement: "ORD-10"
    verification:
      - kind: unit
        ref: "OrderStatusDiagramConsistencyTest#everyStateDiagramInTheDocumentMatchesTheTransitionTableExactly[../README.md]"
        status: pass
    human_judgment: false
  - id: D2
    description: "Visao geral tem o mesmo diagrama e a tabela transicao x gatilho x endpoint/mensagem x evento da linha do tempo"
    requirement: "ORD-10"
    verification:
      - kind: unit
        ref: "OrderStatusDiagramConsistencyTest#everyStateDiagramInTheDocumentMatchesTheTransitionTableExactly[../docs/VISAO-GERAL.md]"
        status: pass
    human_judgment: false
  - id: D3
    description: "O comparador acusa aresta a mais, aresta a menos, estado inexistente e linha malformada"
    requirement: "ORD-10"
    verification:
      - kind: unit
        ref: "OrderStatusDiagramConsistencyTest#anExtraEdgeIsReported, #aMissingEdgeIsReported, #aNonexistentStateIsReported, #aMalformedEdgeLineIsReportedInsteadOfSilentlyIgnored"
        status: pass
    human_judgment: false
  - id: D4
    description: "README e docs/API.md documentam ship, deliver, a rota da linha do tempo, os seis campos novos, entityId, ShipStock, os oito eventos ORDER_*, o smoke e as limitacoes da Fase 6; transportadora apresentada como simulada com a costura CarrierGateway"
    requirement: "ORD-07"
    verification:
      - kind: other
        ref: "grep -c de invalid_order_transition, /api/notifications/orders/, ORDER_DELIVERED, ShipStock, trackingCode, smoke-order-lifecycle.sh, 'Limitacoes conhecidas (Fase 6)', CarrierGateway > 0"
        status: pass
    human_judgment: true
    rationale: "Renderizacao do Mermaid no GitHub e a experiencia de seguir um pedido pelo Swagger sao visuais; os testes provam o conteudo, nao a legibilidade (checkpoint de UAT de fim de fase)"

duration: ~40min
completed: 2026-10-01
status: complete
---

# Phase 6 Plan 07: Documentacao do ciclo de vida com diagrama conferido por teste Summary

**Diagrama Mermaid `stateDiagram-v2` do ciclo de vida do pedido no README e na visao geral, travado por `OrderStatusDiagramConsistencyTest` contra as 9 arestas de `OrderStatus.transitions()`, mais README e `docs/API.md` atualizados com expedicao, entrega, linha do tempo, contratos novos e as limitacoes conhecidas da Fase 6.**

## Performance

- **Duration:** ~40 min (inclui tres execucoes do teste de consistencia pelo Maven)
- **Completed:** 2026-10-01
- **Tasks:** 3
- **Files:** 4 (1 criado, 3 alterados; +746 / -25 linhas)

## Accomplishments

- **Teste de consistencia (D-83):** `OrderStatusDiagramConsistencyTest` le `README.md` e `docs/VISAO-GERAL.md` a partir do diretorio do modulo (`../README.md`, `../docs/VISAO-GERAL.md`), extrai todo bloco ` ```mermaid ` que contem `stateDiagram-v2`, exige pelo menos um por documento e compara o conjunto de arestas `A --> B` (ignorando as de `[*]`) com `OrderStatus.transitions()`; a falha lista as arestas a mais e a menos, estados inexistentes e linhas de aresta fora do formato. Sete casos de controle sobre texto (sem arquivo) provam que o comparador tem dentes: diagrama gerado da propria tabela passa; `CANCELLED --> SHIPPED` a mais, `SHIPPED --> DELIVERED` a menos, `DELIVERED --> RETURNED` (estado inexistente) e `A --> B --> C` malformado sao acusados; marcadores `[*]` e rotulos sao aceitos; so blocos mermaid com `stateDiagram-v2` sao extraidos. 9 testes, 0 falhas.
- **README (D-84):** secao "Ciclo de vida do pedido (Fase 6)" com o diagrama (9 arestas rotuladas, nenhum rotulo com dois `:`), nota de que `CREATED` e `APPROVED` nunca sao observados em repouso (D-45, D-50), `CANCELLED` so pela saga (D-77), `SHIPPED`/`DELIVERED` consomem credito (D-37), transportadora e rastreio simulados com a costura `CarrierGateway` (D-71), expedicao com `ShipStock` pelo outbox e baixa de `quantityOnHand`/`quantityReserved`, linha do tempo e o smoke `bash scripts/smoke-order-lifecycle.sh` (`docker compose up -d --build --wait` antes, `docker compose down` depois). Endpoints `ship`, `deliver` e `GET /api/notifications/orders/{orderId}` (regra SELLER/BUYER, 404 `order_not_found`), os seis campos novos do detalhe do pedido, `entityId` no historico, comando do E2E da expedicao e "Limitacoes conhecidas (Fase 6)".
- **Visao geral (D-84):** "Estado atual" e tabela de servicos atualizados; secao "Ciclo de vida do pedido (Fase 6)" com o mesmo diagrama (rotulos mais longos) e a tabela de 9 transicoes + criacao com gatilho, endpoint ou mensagem e evento da linha do tempo ("sem evento proprio" para `APPROVED` -> `RESERVING`); paragrafos sobre `CarrierGateway` (simulado, nomes ficticios, sem rede) e sobre o caminho outbox -> relay -> `notification-events-queue` -> DynamoDB.
- **`docs/API.md`:** tres secoes novas (`ship`, `deliver`, `GET /notifications/orders/{orderId}`), tabela dos oito eventos `ORDER_*` com campos e exemplos de `message`, `409 invalid_order_transition` com o corpo exato, `404 order_not_found` do notification-service, seis campos novos em todos os exemplos de `OrderResponse` (e um exemplo `CONFIRMED` completo), `entityId` no historico por produto, estados `SHIPPED`/`DELIVERED`, lista de transportadoras e `ShipStock` (campos, fila, idempotencia pelo livro, sem resposta, sem `STOCK_ADJUSTED`).

## Documentos conferidos pelo teste

| Documento | Diagramas `stateDiagram-v2` | Arestas |
|---|---|---|
| `README.md` | 1 | 9 (== `OrderStatus.transitions()`) |
| `docs/VISAO-GERAL.md` | 1 | 9 (== `OrderStatus.transitions()`) |

`docs/API.md` descreve os estados em tabela e aponta para o diagrama do README; nao tem bloco Mermaid proprio e nao e lido pelo teste.

## Limitacoes conhecidas da Fase 6 (registradas no README)

1. Unicidade do rastreio probabilistica, sem `UNIQUE` (violacao dentro da transacao do `CONFIRMED` viraria laco de reentrega).
2. Rastreio de pedidos legados sem o digito verificador S10 (backfill da V3).
3. Transportadora simulada (nomes ficticios, sem rede); costura `CarrierGateway`.
4. A expedicao nao publica `STOCK_ADJUSTED`; a prova da baixa e `GET /api/inventory/{productId}`.
5. Sem cancelamento manual depois de `CONFIRMED` (backlog).
6. Baixa de estoque e linha do tempo eventualmente consistentes; `ShipStock` anomalo para na DLQ sem compensacao automatica.
7. Outbox sem retencao, 5 a 7 linhas por pedido.
8. `notification-service` fora do E2E em JVM; a juncao e provada pelo smoke na stack real.
9. Controle de acesso as filas e da infraestrutura (IAM em AWS real).
10. O ramo de recriacao da tabela antiga no hook do LocalStack nao e exercitado (LocalStack com `PERSISTENCE=0`); `docker compose down` resolve num ambiente com tabela antiga.

## Task Commits

1. **Task 1 (tracer): diagrama no README e teste de consistencia** - `26d0fac` (test)
2. **Task 2: visao geral detalhada, README com endpoints/smoke/limitacoes e teste cobrindo os dois documentos** - `45ea0c7` (docs)
3. **Task 3: referencia da API da Fase 6** - `d508554` (docs)

## Verification

- `./mvnw -B -pl order-service test -Dtest=OrderStatusDiagramConsistencyTest` - 9 testes, 0 falhas, BUILD SUCCESS (README e visao geral conferidos).
- RED da Task 1 observado: antes do diagrama, o teste falhou nos dois documentos ("precisa conter ao menos um bloco mermaid"), com os sete casos de controle verdes.
- Tracer gate: `<verify>` do tracer reexecutado de ponta a ponta antes da expansao, verde.
- Greps (todos > 0): `stateDiagram-v2` em README e visao geral; `Limitações conhecidas (Fase 6)`, `smoke-order-lifecycle.sh`, `/api/notifications/orders/`, `/api/orders/{orderId}/ship`, `/api/orders/{orderId}/deliver`, `invalid_order_transition` no README; `CarrierGateway` na visao geral; `invalid_order_transition`, `/api/notifications/orders/{orderId}`, `ORDER_DELIVERED`, `ShipStock`, `trackingCode` em `docs/API.md`.
- Bloco Mermaid do README: exatamente 9 linhas `ESTADO --> ESTADO` sem `[*]` e nenhuma linha com dois `:`; tabela da visao geral com 9 linhas de transicao.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug de documentacao] README afirmava os seis campos novos tambem na listagem**
- **Found during:** Task 3 (ao conferir `OrderSummaryResponse`)
- **Issue:** O texto escrito na Task 2 dizia que `GET /api/orders` tambem traz `carrier`/`trackingCode`/etc.; o resumo da listagem (`OrderSummaryResponse`) nao os inclui.
- **Fix:** README e `docs/API.md` passaram a dizer que so o detalhe (`POST /orders`, `GET /orders/{orderId}`, `approve`, `reject`, `ship`, `deliver`) tem os campos e que a listagem continua sem itens e sem campos de saga/expedicao.
- **Files modified:** `README.md`, `docs/API.md`
- **Commit:** `d508554`

**2. [Process] Execucao de RED em arquivo de teste diferente do final**
- O primeiro RED rodou com os dois documentos registrados (README e visao geral) e foi corrigido para so o README na Task 1 (a visao geral so ganha o diagrama na Task 2), conforme o plano; o teste e o diagrama da Task 1 entraram no mesmo commit.

**Total deviations:** 1 auto-fixed (documentacao), 1 de processo. **Impact:** nenhum no escopo.

## Known Stubs

None. Nenhum valor fixo ou vazio flui para a documentacao; os exemplos usam ids truncados com reticencias e nenhum valor do `.env` foi copiado (T-06-26).

## Threat Flags

None. Plano so de documentacao e de um teste; T-06-25 mitigado pelo `OrderStatusDiagramConsistencyTest` (diagrama) somado ao `OrderLifecycleTransitionsIT` (API), e T-06-26 respeitado (exemplos ficticios).

## Issues Encountered

None.

## Flagged Assumptions

- **ORD-10:** "corresponde exatamente ao comportamento real da API" e provado pela soma de dois testes (diagrama x tabela aqui; API x tabela no `OrderLifecycleTransitionsIT`, 06-02). Nao existe um teste unico que leia o diagrama e chame a API.
- **ORD-07:** a documentacao apresenta transportadora e rastreio como simulados e a costura `CarrierGateway` como ponto de extensao; nenhuma integracao real e prometida.

## Pendente para o UAT de fim de fase

Conferencia humana (nao automatizavel): abrir o README renderizado no GitHub (ou visualizador com Mermaid) e confirmar que o diagrama renderiza sem erro de sintaxe e com rotulos legiveis; seguir um pedido pelo Swagger UI do order-service e do notification-service com a stack no ar.

## Next Phase Readiness

Ultimo plano da Fase 6: fase pronta para verificacao (`/gsd-verify-work`). A Fase 7 (endurecimento) herda as limitacoes registradas no README (circuit breaker, DLQ da `notification-events-queue`, retencao do outbox).

## Self-Check: PASSED

- `OrderStatusDiagramConsistencyTest.java` existe em disco; `README.md`, `docs/VISAO-GERAL.md` e `docs/API.md` alterados.
- Commits `26d0fac`, `45ea0c7` e `d508554` presentes em `git log`.
