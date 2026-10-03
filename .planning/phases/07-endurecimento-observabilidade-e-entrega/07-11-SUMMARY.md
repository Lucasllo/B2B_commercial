---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "11"
subsystem: infra
tags: [github-actions, ci, smoke, correlation-id, readme, docs, localstack, d-99, d-100, d-101, d-102, d-103, d-104, d-111]
status: complete

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: Correlation-ID em todos os serviços (07-01..07-04), Swagger única (07-05), ADRs e check-adrs.sh (07-06), matriz de cobertura e check-coverage-matrix.sh (07-10)
provides:
  - scripts/smoke-correlation-id.sh — prova na stack real o mesmo Correlation-ID nos logs de gateway, order, inventory, notification, catalog e auth
  - .github/workflows/ci.yml — pipeline com jobs guardas, sem-localstack, com-localstack (max-parallel 1) e e2e
  - scripts/check-no-skipped-tests.sh e scripts/ci-summary.sh
  - README com a seção "Para avaliadores" e "Limitações conhecidas (Fase 7)"; docs/API.md e docs/VISAO-GERAL.md apontando Swagger, ADRs e o header X-Correlation-Id
  - estudos/29-github-actions-ci.md
affects: [fim da fase 07 — UAT humano do CI e do Try it out]

plan_head_before: 646c5816b1cb0540fee3283652a51c2df85b2159
actuals:
  tokens: 13000
  tasks: 3
  commits: 3

tech-stack:
  added: [GitHub Actions (actions/checkout v7, actions/setup-java v6, actions/upload-artifact v7)]
  patterns:
    - "CI com jobs com LocalStack em sequência (max-parallel 1) e falha ::error:: explícita sem o secret, nunca teste pulado"
    - "Guarda por grep contra @Disabled/@EnabledIf/Assumptions (inclusive qualificados) com prova negativa"
    - "Resumo de relatórios Surefire/Failsafe em markdown por shell puro, sempre exit 0"

key-files:
  created:
    - scripts/smoke-correlation-id.sh
    - scripts/check-no-skipped-tests.sh
    - scripts/ci-summary.sh
    - .github/workflows/ci.yml
    - estudos/29-github-actions-ci.md
  modified:
    - pom.xml
    - estudos/README.md
    - README.md
    - docs/API.md
    - docs/VISAO-GERAL.md

key-decisions:
  - "CI_REPORT=upload-artifact+shell-summary"
  - "CI_RUNNER=ubuntu-24.04"
  - "CI_LOCALSTACK_DEDUP=push-ou-PR-de-fork (e o job e2e herda o mesmo if)"
  - "CI_E2E=install-then-verify"
  - "CI_ACTION_PINS=actions/checkout@v7, actions/setup-java@v6, actions/upload-artifact@v7 (majors mais novos conferidos com git ls-remote --tags: v7, v6, v7)"
  - "TESTCONTAINERS_VERSION_SOURCE=spring-boot-bom — propriedade testcontainers.version e import do testcontainers-bom removidos; dependency:tree mostra org.testcontainers 1.21.4 em todos os 6 módulos com Testcontainers"
  - "SMOKE_SERVICES=gateway order-service inventory-service notification-service catalog-service auth-service"

requirements-completed: [INFRA-02, QUAL-01, QUAL-02]

duration: 95min
completed: 2026-10-03
---

# Phase 7 Plan 11: Smoke de Correlation-ID, CI e README para avaliadores Summary

**O smoke `smoke-correlation-id.sh` prova na stack real o mesmo ID em seis logs (com troca de ID inválido por UUID), o `ci.yml` builda e testa cada serviço com LocalStack em sequência e falha alto sem o secret, e o README ganha a seção "Para avaliadores".**

## Performance

- **Duration:** ~95 min (13 min só de `docker compose up --build --wait` na primeira subida)
- **Tasks:** 3 (1 tracer + 2 auto)
- **Files modified:** 10

## Accomplishments

- **Tracer (Task 1):** `scripts/smoke-correlation-id.sh` cria empresa, comprador, produto e estoque, abre um pedido com `X-Correlation-Id: smoke-cid-<epoch>-<random>`, confere que a resposta do Gateway traz exatamente um header com o mesmo valor, espera `CONFIRMED` e `ORDER_CONFIRMED` na linha do tempo e acha `[<id>]` nos logs dos 6 serviços. O caminho do ID inválido (`bad value!`) devolve um UUID novo e o valor cru não aparece em nenhum log. Rodou verde na stack real na primeira execução (`SMOKE OK correlation-id smoke-cid-1791046349-12652`); `smoke-order-lifecycle.sh` também verde (sem regressão com as mudanças da fase). Stack derrubada (`docker compose ps -q` vazio).
- **CI (Task 2):** `.github/workflows/ci.yml` com `guardas`, `sem-localstack` (auth/catalog/gateway em paralelo), `com-localstack` (inventory/order/notification com `max-parallel: 1`) e `e2e` (`install -DskipTests` e depois `-pl e2e-tests verify`), todos em `ubuntu-24.04`, Temurin 21, cache Maven, artifacts e resumo por módulo com `if: always()`. Passo `::error::` quando o secret falta. `actionlint 1.7.7` sem erros. `check-no-skipped-tests.sh` com prova negativa (forma qualificada `@org.junit.jupiter.api.Disabled` e outras 4 formas acusadas). `ci-summary.sh` testado sobre os relatórios reais do gateway (Surefire 14, Failsafe 10) e sobre um XML sintético com falha. `pom.xml` sem a versão morta do Testcontainers.
- **Entrega (Task 3):** `## Para avaliadores` no topo do README (7 subseções), UI única `http://localhost:8080/swagger-ui.html`, `## Limitações conhecidas (Fase 7)`; `docs/API.md` documenta `X-Correlation-Id` e aponta a Swagger do Gateway e os ADRs; `docs/VISAO-GERAL.md` ganhou a seção de observabilidade. `check-adrs.sh` (11 ADRs) e `check-coverage-matrix.sh` (72 regras) seguem verdes.

## Task Commits

1. **Task 1: smoke de Correlation-ID** — `8f07561`
2. **Task 2: pipeline de CI, guardas, resumo, estudo 29 e pom** — `9e2d8ef`
3. **Task 3: README Para avaliadores e docs** — `7e0c812`

## Decisions Made

- `CI_REPORT=upload-artifact+shell-summary`: sem `dorny/test-reporter`/`mikepenz/action-junit-report` (exigem `checks: write` e ampliam a superfície de supply-chain).
- `CI_RUNNER=ubuntu-24.04` fixo, nunca `ubuntu-latest`.
- `CI_LOCALSTACK_DEDUP`: jobs com LocalStack só rodam em `push` ou PR de fork; o job `e2e` repete o mesmo `if` para ficar explícito (já ficaria `skipped` por herdar o `needs`).
- `CI_E2E=install-then-verify` (Pitfall 6).
- `CI_ACTION_PINS`: checkout v7, setup-java v6, upload-artifact v7 (conferidos na hora).
- `TESTCONTAINERS_VERSION_SOURCE=spring-boot-bom`: caminho da remoção ficou (não foi preciso restaurar o import).
- `SMOKE_SERVICES`: seis serviços (os quatro do D-99 mais catalog e auth, D-96).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Flag do actionlint**
- **Found during:** Task 2, verificação automática
- **Issue:** o comando do plano usa `-color=never`, que o `actionlint 1.7.7` rejeita (`-color` é booleano que força cor).
- **Fix:** usado `-no-color`; resultado exit 0, sem erros.
- **Files modified:** nenhum (só o comando de verificação).

**2. [Rule 2 - Missing critical] `if` também no job e2e**
- **Issue:** o esqueleto do 07-RESEARCH deixava o `e2e` sem o `if` de deduplicação; como ele tem `needs` e `com-localstack` pode ficar `skipped`, adicionei o mesmo `if` para a intenção ficar explícita e o passo do token não ser avaliado em PR de mesmo repositório.
- **Files modified:** `.github/workflows/ci.yml`

Fora isso, o plano foi executado como escrito.

## Human Checks Pendentes (não executáveis pelo executor)

1. **Try it out da Swagger UI (Task 1, suposição A1):** com a stack de pé, abrir `http://localhost:8080/swagger-ui.html`, `POST /auth/login`, Authorize, `POST /orders` pelo Try it out; esperado 201 via `http://localhost:8080/api/orders`, header `X-Correlation-Id` e nenhum erro de CORS. Só confirmável no navegador. (Verificado por máquina apenas que `/swagger-ui.html` responde 302 com a stack de pé.)
2. **CI no GitHub (Task 2, suposições A2/A3 e INFRA-02):** cadastrar o secret `LOCALSTACK_AUTH_TOKEN` em Settings → Secrets and variables → Actions de `Lucasllo/B2B_commercial`; com a stack local derrubada, fazer push e observar o run verde (guardas, 3 pernas em paralelo, 3 pernas em sequência, e2e); depois criar o branch `ci/prova-falha-visivel`, inverter uma asserção de `CorrelationIdFilterTest`, push, observar a perna `sem-localstack (gateway)` vermelha com o resumo apontando a suíte, e apagar o branch local e o remoto. O executor não fez push, não criou secret nem disparou workflow.

## Known Stubs

Nenhum.

## Threat Flags

Nenhum — o workflow usa só `push`/`pull_request`, `permissions: contents: read`, só ações `actions/*`, o secret entra por `env:` e nunca é impresso (T-07-36..T-07-40 mitigados). O README e o smoke não contêm valor de token.

## Self-Check: PASSED

- Arquivos: `scripts/smoke-correlation-id.sh`, `scripts/check-no-skipped-tests.sh`, `scripts/ci-summary.sh`, `.github/workflows/ci.yml`, `estudos/29-github-actions-ci.md` existem; os três scripts são `100755` no git.
- Commits `8f07561`, `9e2d8ef`, `7e0c812` existem; `git rev-list --count 646c581..HEAD` = 3.
