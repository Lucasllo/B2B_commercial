---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "06"
subsystem: docs
tags: [adr, madr, documentacao, arquitetura, check-adrs]

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: Código de Correlation-ID (CorrelationContext, filtros, atributo SQS) a que o ADR 0008 liga
provides:
  - 11 ADRs em português (MADR) em docs/adr/ com índice
  - scripts/check-adrs.sh, verificação mecânica de formato, alternativa rejeitada, links, D-xx e índice
  - COVERAGE.md da fase com a declaração do gate de API externa
affects: [07-11]

actuals:
  tokens: 16900
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - ADR em MADR 4.0 traduzido, com Fase de origem, links D-xx e links para o código
    - Script bash de consistência documental, com teste negativo que prova que ele acusa o defeito

key-files:
  created:
    - scripts/check-adrs.sh
    - docs/adr/README.md
    - docs/adr/0001-saga-por-orquestracao-no-order-service.md
    - docs/adr/0002-transactional-outbox-em-vez-de-publicacao-direta.md
    - docs/adr/0003-localstack-em-vez-de-aws-real.md
    - docs/adr/0004-sem-service-discovery-nem-config-server.md
    - docs/adr/0005-jwt-auto-emitido-e-validado-localmente.md
    - docs/adr/0006-dynamodb-para-o-historico-de-notificacoes.md
    - docs/adr/0007-credito-serializado-por-empresa.md
    - docs/adr/0008-correlation-id-proprio-em-vez-de-tracing-distribuido.md
    - docs/adr/0009-gateway-server-webmvc-em-vez-do-reativo.md
    - docs/adr/0010-falha-fechada-sem-resilience4j.md
    - docs/adr/0011-codigo-duplicado-por-servico-em-vez-de-modulo-comum.md
    - .planning/phases/07-endurecimento-observabilidade-e-entrega/COVERAGE.md
  modified: []

key-decisions:
  - "ADR_LIST=0001 saga por orquestração; 0002 Transactional Outbox; 0003 LocalStack; 0004 sem service discovery/config server; 0005 JWT local; 0006 DynamoDB; 0007 crédito serializado; 0008 Correlation-ID próprio; 0009 Gateway WebMVC; 0010 falha fechada sem Resilience4j; 0011 código duplicado por serviço"
  - "ADR_TEMPLATE=MADR-4.0-pt (front matter status/date/decision-makers; seções Contexto e problema, Fatores de decisão, Alternativas consideradas, Decisão, Consequências, Prós e contras das alternativas, Mais informações com Fase de origem)"
  - "API_COVERAGE_GATE=none (a fase não integra API externa nova; ver COVERAGE.md)"

patterns-established:
  - "Todo ADR cita só decisões D-xx que existem como **D-xx:** em algum CONTEXT.md; o script confere"
  - "Alternativa só aparece num ADR se foi discutida nos CONTEXT, RESEARCH, DISCUSSION-LOG ou pesquisa do projeto"

requirements-completed: [INFRA-03]

coverage:
  - id: D1
    description: Os 11 ADRs existem em MADR em português, com status Aceito, fase de origem, alternativa rejeitada e links para D-xx e código, e o índice lista exatamente esses arquivos
    requirement: INFRA-03
    verification:
      - kind: other
        ref: scripts/check-adrs.sh
        status: pass
    human_judgment: false
  - id: D2
    description: Os quatro ADRs obrigatórios do critério 5 (orquestração, Outbox, LocalStack, ausência de service discovery e config server) existem e cada um tem alternativa rejeitada com o motivo
    requirement: INFRA-03
    verification:
      - kind: other
        ref: scripts/check-adrs.sh
        status: pass
    human_judgment: false
  - id: D3
    description: check-adrs.sh acusa, nomeando arquivo e problema, ADR sem alternativa rejeitada, seção, fase de origem, link, D-xx existente ou entrada no índice
    requirement: INFRA-03
    verification:
      - kind: other
        ref: scripts/check-adrs.sh (teste negativo com cópia de ADR sem "rejeitada"; segundo teste com seção removida, link quebrado, D-9999 e arquivo fora do índice)
        status: pass
    human_judgment: false
  - id: D4
    description: A ausência de Resilience4j é consequência declarada no ADR 0010 e o tracing distribuído é alternativa rejeitada no ADR 0008
    requirement: INFRA-03
    verification:
      - kind: other
        ref: docs/adr/0010-falha-fechada-sem-resilience4j.md e docs/adr/0008-correlation-id-proprio-em-vez-de-tracing-distribuido.md
        status: pass
    human_judgment: true

duration: 25min
completed: 2026-10-03
status: complete
plan_head_before: 370bd0448557eba4df1877306e8dc49fbdf1afbb
plan_head_after: 1ebf5a13dfa04b9f6b051aab0260d3a64a7f9b0e
---

# Phase 07 Plan 06: ADRs e verificação mecânica Summary

**Onze ADRs em português no formato MADR contam o porquê das decisões-chave (saga por orquestração, Outbox, LocalStack, sem discovery, JWT local, DynamoDB, crédito serializado, Correlation-ID próprio, Gateway WebMVC, falha fechada sem Resilience4j e código duplicado), e `scripts/check-adrs.sh` quebra quando um ADR perde a alternativa rejeitada, uma seção, a fase de origem, um link ou a entrada no índice.**

## Performance

- **Duration:** 25 min
- **Tasks:** 3
- **Files created:** 14

## Accomplishments

- Os quatro ADRs obrigatórios do critério 5 (0001-0004) existem com alternativa rejeitada e motivo, e foram o tracer: o script e o índice nasceram junto com eles.
- Cada ADR tem a linha `Fase de origem`, links relativos para os `CONTEXT.md` com os D-xx e para o código que implementa a decisão; todos os links e todos os D-xx citados resolvem.
- ADR 0010 declara a ausência de Resilience4j como consequência (falha fechada 503 da Fase 4, backlog nos Deferred Ideas do 07-CONTEXT); ADR 0008 registra Micrometer Tracing/OpenTelemetry como rejeitado por enquanto (D-98) e logs JSON como fora de escopo.
- ADR 0008 cita a extensão barata de usar `orders.correlation_id` em `/approve`, `/ship` e `/deliver` (`TRANSITION_CORRELATION_SOURCE=request-id`).
- `scripts/check-adrs.sh` (modo 100755) confere front matter, sete seções, `rejeitada`, `Fase de origem:`, links, D-xx contra `**D-xx:**` dos CONTEXT.md, índice, padrão de token e a presença dos quatro temas obrigatórios; imprime `ADR CHECK FALHOU: <arquivo>: <problema>` e termina com `ADR CHECK OK 11 ADRs`.

## Task Commits

1. **Task 1 (tracer): ADRs 0001-0004, índice, script e COVERAGE.md** - `1ba55f3` (docs)
2. **Task 2: ADRs 0005-0008** - `221f094` (docs)
3. **Task 3: ADRs 0009-0011 e índice completo** - `1ebf5a1` (docs)

## Decisions Made

- `ADR_LIST`, `ADR_TEMPLATE` e `API_COVERAGE_GATE` conforme o frontmatter acima.
- Os ADRs 0002, 0004, 0007 e 0008 citavam por link ADRs ainda inexistentes durante o tracer; esses trechos ficaram como texto até a Task 3 e viraram links quando 0009-0011 foram escritos, para o `ADR CHECK OK 4 ADRs` e o `OK 8 ADRs` passarem em cada ponto.
- `date: 2026-10-01` em todos os ADRs, como o plano manda (data do CONTEXT da fase).

## TDD Gate Compliance

Plano de documentação e script; não é `type: tdd` e nenhuma tarefa tem `tdd="true"`. O teste negativo do tracer (ADR sem `rejeitada`) foi executado e imprimiu `1`.

## Deviations from Plan

### Ajustes de fidelidade às fontes (regra "nada inventado", D-110)

**1. [Rule 1 - Fidelidade] Alternativas da tabela do plano que não foram discutidas em nenhum artefato foram omitidas**
- **ADR 0007:** a tabela do plano lista "isolamento SERIALIZABLE" como alternativa rejeitada, mas nenhum CONTEXT, RESEARCH, DISCUSSION-LOG ou PITFALLS da Fase 4 discute essa opção. Em vez dela entrou a alternativa de `SELECT ... FOR UPDATE` direto em `orders`, que o `04-RESEARCH.md` discute de fato.
- **ADR 0005:** "Keycloak" não aparece em nenhum artefato; o ADR cita "provedor de identidade externo" (CLAUDE.md), "Spring Authorization Server completo" (01-RESEARCH) e jjwt (CLAUDE.md), além da introspecção por chamada excluída pela D-03.
- **ADR 0002:** a retenção do outbox e as lápides estão ligadas ao AR-05-02 e ao README (`OUTBOX_RETENTION=none-this-phase`); o AR-05-02 em si trata das lápides, e o ADR diz isso.
- **ADR 0003:** o plano cita "Pitfall 5 LocalStack"; no `PITFALLS.md` o LocalStack é o Pitfall 6 e os testes só com mocks são o Pitfall 7, e o ADR usa os números reais, sem numerar o Pitfall 6 explicitamente.
- **ADR 0011:** o plano fala em "~40 linhas duplicadas"; o `CorrelationContext` de cada serviço tem 55-57 linhas, então o ADR diz "algumas dezenas de linhas por serviço".
- **ADR 0008:** o smoke `scripts/smoke-correlation-id.sh` pertence ao plano 07-11 e ainda não existe; o ADR o descreve como previsto na D-99 e entregue no 07-11, sem link.

**Total deviations:** 1 categoria (fidelidade às fontes), nenhuma mudança de escopo.

## Known Stubs

None.

## Threat Flags

None.

## Issues Encountered

- Autocrlf do repositório converte LF para CRLF ao tocar os `.md`; o script lê os ADRs com `tr -d '\r'` e funciona nos dois casos. O `.sh` não foi afetado (modo 100755 e LF preservados).

## Verification

- `bash scripts/check-adrs.sh` → `ADR CHECK OK 11 ADRs` (exit 0); com 4 e 8 ADRs nos pontos intermediários também passou.
- Teste negativo do plano: cópia de 0001 com `rejeitada` trocado por `descartada` → 1 linha `9999-negativo.md: sem alternativa rejeitada`; arquivo removido em seguida.
- Segundo teste negativo (ADR com seção `Decisão` renomeada, link quebrado, D-9999 e fora do índice) → as quatro mensagens esperadas; arquivo removido.
- `git ls-files -s scripts/check-adrs.sh` → `100755`.
- `grep -c Resilience4j docs/adr/0010-*.md` → 7; `grep -c rejeitada docs/adr/0008-*.md` → 2.
- `COVERAGE.md` da fase tem uma linha `No external API integration: ...`.

## Self-Check: PASSED

- FOUND: scripts/check-adrs.sh
- FOUND: docs/adr/README.md
- FOUND: docs/adr/0001-saga-por-orquestracao-no-order-service.md ... docs/adr/0011-codigo-duplicado-por-servico-em-vez-de-modulo-comum.md (11 ADRs)
- FOUND: .planning/phases/07-endurecimento-observabilidade-e-entrega/COVERAGE.md
- FOUND: 1ba55f3
- FOUND: 221f094
- FOUND: 1ebf5a1
