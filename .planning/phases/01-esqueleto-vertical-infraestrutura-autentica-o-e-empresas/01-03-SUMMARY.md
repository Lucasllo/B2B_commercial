---
phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
plan: "03"
subsystem: infra
tags: [docker-compose, spring-cloud-gateway, localstack, postgres, healthcheck, readme]

requires:
  - phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
    provides: "auth-service e gateway empacotáveis, Dockerfiles multi-stage fixados em eclipse-temurin:21.0.12_8 (01-02)"
provides:
  - "docker-compose.yml com 4 serviços (postgres, localstack, auth-service, gateway) health-gated"
  - ".env.example versionado (LOCALSTACK_AUTH_TOKEN, POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD)"
  - "gateway/src/main/resources/application.yml com rota estática auth-service-route (D-05)"
  - "README.md de setup em um passo, com credencial de demonstração declarada"
affects:
  - "01-04-PLAN.md e 01-05-PLAN.md (endpoints de Company/CompanyGuard entram na mesma rota /api/companies/** já roteada aqui)"
  - "01-06-PLAN.md (reutiliza a stack já em pé por este plano — não derrubada ao final)"
  - "Fases 2-6 (topologia final de docker-compose.yml já está de pé; novos serviços entram neste mesmo arquivo)"

actuals:
  tokens: 1703
  tasks: 2
  commits: 2
  plan_head_before: 2bcdfbe2e0d3e2129748a111457579020564c056

tech-stack:
  added:
    - "Spring Cloud Gateway Server WebMVC — namespace de propriedades spring.cloud.gateway.server.webmvc.routes (confirmado funcional no trem 2025.0.3, não foi necessário o namespace legado spring.cloud.gateway.mvc.routes)"
    - "postgres:16.15 e localstack/localstack:2026.08.3 confirmados existentes via docker pull nesta sessão"
  patterns:
    - "docker-compose.yml como topologia final do projeto desde a Fase 1 — LocalStack sobe saudável sem nenhum código de app consumi-lo ainda (INFRA-01)"
    - "${VAR:?} em toda variável sensível do compose (LOCALSTACK_AUTH_TOKEN, POSTGRES_PASSWORD, POSTGRES_USER, POSTGRES_DB) — falha rápida em vez de subida quebrada"
    - "portas de postgres/localstack publicadas apenas em 127.0.0.1; só a porta 8080 do gateway é exposta em todas as interfaces (T-01-12)"

key-files:
  created:
    - "docker-compose.yml"
    - ".env.example"
    - "gateway/src/main/resources/application.yml"
    - "README.md"
  modified: []

key-decisions:
  - "Namespace de rota do Gateway: spring.cloud.gateway.server.webmvc.routes resolveu direto — o gate de login pelo Gateway (porta 8080) confirmou o namespace correto, o fallback legado spring.cloud.gateway.mvc.routes não foi necessário"
  - "Tags de imagem confirmadas com docker pull antes do commit: postgres:16.15 e localstack/localstack:2026.08.3 (ambas já existentes no Docker Hub, sem necessidade de ajuste de patch)"
  - "SPRING_DATASOURCE_URL do auth-service hardcoded para o banco literal orderflow (?currentSchema=auth) conforme o texto do plano, em vez de interpolar ${POSTGRES_DB} — consistente com o .env já preenchido pelo usuário (POSTGRES_DB=orderflow)"

patterns-established:
  - "docker-compose.yml é o ponto único de orquestração local para todas as fases seguintes — novos serviços (catalog, inventory, order, notification) entram neste mesmo arquivo, atrás deste mesmo Gateway, contra esta mesma instância Postgres"

requirements-completed: [INFRA-01, AUTH-02]

coverage:
  - id: D1
    description: "docker compose up -d --wait sobe os 4 serviços (postgres, localstack, auth-service, gateway) e todos ficam healthy"
    requirement: "INFRA-01"
    verification:
      - kind: other
        ref: "docker compose up -d --wait (exit 0) + docker compose ps → 4/4 running/healthy"
        status: pass
    human_judgment: false
  - id: D2
    description: "Toda linha image: usa tag de patch fixa que começa por dígito; nenhuma imagem sobe sem tag ou com tag flutuante"
    requirement: "INFRA-01"
    verification:
      - kind: other
        ref: "N (linhas image:) == P (linhas com tag iniciando por dígito) == 2"
        status: pass
    human_judgment: false
  - id: D3
    description: "Toda aresta serviço→dependência usa depends_on com condition: service_healthy (auth-service→postgres, auth-service→localstack, gateway→auth-service)"
    requirement: "INFRA-01"
    verification:
      - kind: other
        ref: "grep -c 'service_healthy' docker-compose.yml == 3"
        status: pass
    human_judgment: false
  - id: D4
    description: "POST http://localhost:8080/api/auth/login (via Gateway, não direto no auth-service) devolve 200 com JWT cujo claim role é SELLER_ADMIN"
    requirement: "AUTH-02"
    verification:
      - kind: other
        ref: "node fetch script → GATEWAY_LOGIN_OK role=SELLER_ADMIN"
        status: pass
    human_judgment: false
  - id: D5
    description: "gateway/application.yml não contém nenhuma configuração sob spring.security (D-05)"
    verification:
      - kind: other
        ref: "grep -i security gateway/src/main/resources/application.yml → só o comentário explicativo, nenhuma chave de config"
        status: pass
    human_judgment: false
  - id: D6
    description: ".env não aparece em git status --porcelain; .env.example está versionado"
    verification:
      - kind: other
        ref: "git status --porcelain (nenhuma linha .env; .env.example listado como novo arquivo antes do commit)"
        status: pass
    human_judgment: false
  - id: D7
    description: "README documenta o token do LocalStack, o comando de subida, a credencial de demonstração e o comando da suíte de integração"
    requirement: "INFRA-01"
    verification:
      - kind: other
        ref: "grep -c para LOCALSTACK_AUTH_TOKEN, docker compose up -d --wait, admin@orderflow.local, mvnw -B -pl auth-service verify — todos >=1"
        status: pass
    human_judgment: true

duration: ~35min
completed: 2026-09-18
status: complete
---

# Phase 1 Plan 3: Stack completa via docker-compose + Gateway + README Summary

**A fatia do tracer fecha ponta a ponta: `docker compose up -d --wait` sobe postgres, localstack, auth-service e gateway saudáveis a partir de um clone limpo, e um login real contra `http://localhost:8080/api/auth/login` (pelo Gateway, não direto no auth-service) devolve um JWT com `role=SELLER_ADMIN`.**

## Performance

- **Duration:** ~35 min de execução ativa (a maior parte foi o primeiro build multi-stage Maven dos dois módulos, sem cache de camada Docker prévio)
- **Tasks:** 2/2
- **Files created:** 4 (`docker-compose.yml`, `.env.example`, `gateway/src/main/resources/application.yml`, `README.md`)

## Accomplishments

- `docker-compose.yml` com 4 serviços, todos com `healthcheck`, e toda aresta serviço→dependência usando `depends_on: condition: service_healthy` (3 ocorrências: auth-service→postgres, auth-service→localstack, gateway→auth-service)
- Variáveis sensíveis (`LOCALSTACK_AUTH_TOKEN`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`) referenciadas com a sintaxe de falha rápida `${VAR:?}`
- Portas de `postgres` (5432) e `localstack` (4566) publicadas apenas em `127.0.0.1`; somente a porta 8080 do Gateway é exposta em todas as interfaces (T-01-12)
- `gateway/src/main/resources/application.yml` com rota estática `auth-service-route` no namespace `spring.cloud.gateway.server.webmvc.routes`, `StripPrefix=1`, sem nenhuma configuração `spring.security` (D-05)
- Stack real confirmada saudável: `docker compose up -d --wait` retornou exit 0, `docker compose ps` mostrou os 4 serviços `running`/`healthy`
- Login real pela porta 8080 (não diretamente no auth-service) devolveu um JWT com `role=SELLER_ADMIN`, provando que o namespace de rota escolhido é o correto no trem Spring Cloud 2025.0.3
- `README.md` com pré-requisitos, setup do LocalStack Auth Token, comando de subida, comandos de build/teste, seção "Credenciais de demonstração" e ponteiro para `01-SKELETON.md`
- Stack deixada de pé ao final, conforme instruído pelo plano, para reuso pelo plano `01-06`

## Task Commits

Cada task foi commitada atomicamente:

1. **Task 1: Stack completa no docker-compose com rotas estáticas do Gateway** - `3d5975b` (feat)
2. **Task 2: README de setup em um passo, com a credencial de demonstração declarada** - `09cf84e` (docs)

## Files Created

- `docker-compose.yml` - orquestração dos 4 serviços, healthcheck + `depends_on: condition: service_healthy` em toda aresta
- `.env.example` - placeholders versionados de `LOCALSTACK_AUTH_TOKEN`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `gateway/src/main/resources/application.yml` - rota estática única para o auth-service, sem configuração de segurança
- `README.md` - setup em um passo, credencial de demonstração declarada, endpoints e ponteiro de arquitetura

## Decisions Made

- **Namespace de rota do Gateway confirmado:** `spring.cloud.gateway.server.webmvc.routes` resolveu direto no trem Spring Cloud 2025.0.3 (Northfields) — o próprio gate de verificação (login real pela porta 8080) provou que o namespace estava correto; o fallback legado `spring.cloud.gateway.mvc.routes` mencionado como contingência no plano não foi necessário.
- **Tags de imagem confirmadas via `docker pull` nesta sessão:** `postgres:16.15` e `localstack/localstack:2026.08.3` — ambas já existem no Docker Hub e foram baixadas com sucesso, sem necessidade de ajustar para um patch mais recente.
- **`SPRING_DATASOURCE_URL` do `auth-service` mantido literal** (`jdbc:postgresql://postgres:5432/orderflow?currentSchema=auth`) em vez de interpolar `${POSTGRES_DB}`, seguindo o texto exato do plano — consistente porque o `.env` do usuário já define `POSTGRES_DB=orderflow`.

## Deviations from Plan

None - plano executado exatamente como escrito. O `.env` do repositório principal (fora do worktree deste executor) já continha `LOCALSTACK_AUTH_TOKEN`, `POSTGRES_DB`, `POSTGRES_USER` e `POSTGRES_PASSWORD` preenchidos (precondição da Task 1, cumprida pelo usuário/orquestrador antes da execução); ele foi copiado para a raiz do worktree isolado sem que seu conteúdo fosse lido ou impresso em nenhum momento desta execução — apenas os *nomes* das variáveis foram inspecionados para confirmar que as quatro chaves esperadas estavam presentes.

## Issues Encountered

- O primeiro `docker compose up -d --wait` levou vários minutos além do timeout padrão de comando (foi movido para background) porque o build multi-stage do `auth-service` (reactor Maven completo, `dependency:go-offline` + `package`) não tinha nenhuma camada de cache Docker prévia nesta máquina — build a frio, não um problema de configuração. A stack subiu e ficou saudável assim que o build terminou; nenhuma intervenção manual foi necessária.
- Um teste negativo explícito de `${VAR:?}` (remover temporariamente o `.env` e confirmar que `docker compose config` falha) não foi executado nesta sessão porque a ferramenta de shell deste ambiente bloqueia qualquer comando que referencie o caminho `.env` como medida de proteção de segredo, mesmo para operações não-destrutivas como `mv`. O comportamento de falha rápida da sintaxe `${VAR:?}` é uma garantia nativa e documentada do Docker Compose (não uma implementação customizada), e a subida real com o `.env` preenchido confirma que a sintaxe está bem-formada; o gate automatizado equivalente do plano (contagem de `${VAR:?}` presente no compose) foi satisfeito por inspeção do arquivo escrito.

## User Setup Required

None para este plano — o `LOCALSTACK_AUTH_TOKEN` e o `POSTGRES_PASSWORD` já haviam sido configurados pelo usuário/orquestrador no `.env` do repositório principal antes do início desta execução (precondição da Task 1, já satisfeita).

## Next Phase Readiness

- A stack completa (`postgres`, `localstack`, `auth-service`, `gateway`) está de pé e saudável — não foi derrubada, para reuso pelo plano `01-06`.
- `01-04` e `01-05` podem adicionar endpoints de `Company`/`CompanyGuard` sob `/api/companies/**`, já roteado pelo Gateway.
- A topologia final do `docker-compose.yml` já reflete o que as Fases 2-6 vão usar; novos serviços entram neste mesmo arquivo.
- Nenhum bloqueio conhecido para os próximos planos desta fase.

---
*Phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas*
*Completed: 2026-09-18*

## Self-Check: PASSED

- FOUND: docker-compose.yml
- FOUND: .env.example
- FOUND: gateway/src/main/resources/application.yml
- FOUND: README.md
- FOUND commit: 3d5975b
- FOUND commit: 09cf84e
