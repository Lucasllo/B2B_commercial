---
phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
plan: "05"
subsystem: infra
tags: [docker-compose, spring-cloud-gateway, springdoc-openapi, bash, smoke-test]

# Dependency graph
requires:
  - phase: 04-01
    provides: order-service (porta 8085, schema order), ORDER_RESPONSE_CONTRACT, POST /orders, GET /orders/{id}
  - phase: 04-02
    provides: superfície de POST /orders endurecida (tudo-ou-nada, falha fechada de vizinho, limites de entrada, tokens adversariais)
  - phase: 04-03
    provides: GET /orders (listagem paginada, filtro ?status=)
  - phase: 04-04
    provides: POST /orders/{id}/approve e /reject (decisão manual do vendedor)
provides:
  - "order-service no docker compose (bloco novo, porta 127.0.0.1:8085, depends_on postgres/auth-service/catalog-service saudáveis); gateway passa a depender de order-service saudável"
  - "Rota order-service-route no Gateway (Path=/api/orders/**, StripPrefix=1), sem configuração de segurança (D-05)"
  - "scripts/smoke-order-flow.sh — prova na stack real, pelo Gateway, os Success Criteria 1 a 4 do ROADMAP em 15 passos numerados"
  - "Swagger UI navegável do order-service (OpenApiConfig + OpenApiDocsIT, esquema bearerAuth)"
  - "README.md, docs/API.md e docs/VISAO-GERAL.md atualizados com os cinco endpoints de pedido, a regra de aprovação por crédito e as limitações conhecidas da Fase 4"
affects: [05-saga-reserva-de-estoque]

# Actuals (#2632)
actuals:
  tokens: 13538
  tasks: 2
  commits: 2
plan_head_before: 9ae57866f9690dcb6d4a8f34f46bdccce4079a97

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "order-service espelha campo a campo o padrão de compose já usado por catalog-service/inventory-service (build/depends_on/healthcheck), sem localstack no depends_on — este serviço não tem mensageria nesta fase"
    - "Sufixo único por execução (UUID gerado dentro do próprio container do Gateway) em e-mails e SKUs do script de smoke — permite reexecutar o script quantas vezes for preciso na mesma base de dados sem colidir com execuções anteriores"
    - "do_request (helper do script de smoke): uma única chamada HTTP devolve status e corpo via '\\n%{http_code}' anexado pelo curl, evitando repetir uma requisição POST com efeito colateral só para capturar as duas informações"
    - "extract_first_string (helper do script de smoke): extração ancorada no início do corpo JSON (^{\"campo\":\"...) para o caso em que o mesmo nome de campo aparece de novo aninhado mais adiante (CompanyResponse.id no topo vs. buyerUser.id) — o padrão greedy do sed capturaria a última ocorrência, não a primeira"

key-files:
  created:
    - scripts/smoke-order-flow.sh
    - order-service/src/main/java/com/orderflow/order/config/OpenApiConfig.java
    - order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java
  modified:
    - docker-compose.yml
    - gateway/src/main/resources/application.yml
    - README.md
    - docs/API.md
    - docs/VISAO-GERAL.md

key-decisions:
  - "order-service sem localstack no depends_on do compose — nenhuma mensageria existe neste serviço nesta fase (a saga com SQS/Outbox chega na Fase 5)"
  - "Script de smoke gera um sufixo único (UUID) dentro do próprio container do Gateway em vez de depender de timestamp do host — evita colisão entre e-mails/SKUs de execuções sucessivas na mesma base sem exigir reset manual do banco"
  - "extract_first_string como extração ancorada no início do corpo JSON, separada de extract_string (greedy): necessário porque CompanyResponse tem 'id' no nível raiz e de novo aninhado em buyerUser.id — sem essa distinção o script capturaria o id do usuário BUYER em vez do id da empresa"

requirements-completed: [ORD-01, ORD-02, ORD-03, ORD-08, ORD-09]

coverage:
  - id: D1
    description: "order-service entra no docker-compose.yml com as cinco variáveis de configuração, porta 127.0.0.1:8085, healthcheck e depends_on de postgres/auth-service/catalog-service saudáveis; gateway passa a depender de order-service saudável — a stack inteira (oito serviços) sobe e fica saudável com um único docker compose up"
    requirement: "ORD-01"
    verification:
      - kind: other
        ref: "docker compose up -d --build --wait (oito serviços Healthy)"
        status: pass
      - kind: other
        ref: "docker compose down (exit 0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Rota order-service-route no Gateway (Path=/api/orders/**, StripPrefix=1) roteia corretamente para o order-service — provado indiretamente por todo o script de smoke, que só fala com a stack através de http://localhost:8080/api/orders/**, nunca da porta 8085 direta"
    requirement: "ORD-01"
    verification:
      - kind: e2e
        ref: "scripts/smoke-order-flow.sh (15 passos, todos pelo Gateway)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Success Criteria 1 e 2 do ROADMAP na stack real: pelo Gateway, com auth-service e catalog-service reais (não os stubs dos testes), um pedido de 400.00 (limite 1000.00) nasce APPROVED com decidedBy=SYSTEM e um pedido de 700.00 na sequência nasce PENDING_APPROVAL"
    requirement: "ORD-02"
    verification:
      - kind: e2e
        ref: "scripts/smoke-order-flow.sh passos 5 e 6 (SMOKE OK em duas execuções seguidas na mesma base)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Success Criteria 3 do ROADMAP na stack real: o vendedor encontra o pedido pendente em GET /api/orders?status=PENDING_APPROVAL, aprova com motivo e decidedBy bate com o próprio userId de GET /api/auth/me; rejeita um pedido subsequente com motivo obrigatório (sem corpo → 400) e uma segunda decisão sobre o mesmo pedido devolve 409"
    requirement: "ORD-03"
    verification:
      - kind: e2e
        ref: "scripts/smoke-order-flow.sh passos 9, 11 e 13"
        status: pass
    human_judgment: false
  - id: D5
    description: "Recusa de produto descontinuado contra o catalog-service real (não o stub dos testes): pedido com um produto DISCONTINUED devolve 422 invalid_order_items com o id do produto no corpo"
    requirement: "ORD-01"
    verification:
      - kind: e2e
        ref: "scripts/smoke-order-flow.sh passo 7"
        status: pass
    human_judgment: false
  - id: D6
    description: "Success Criteria 4 do ROADMAP: comprador de outra empresa recebe 404 order_not_found ao abrir pedido alheio e totalElements=0 na própria listagem; o vendedor abre qualquer pedido; a listagem do comprador dono reflete os 3 pedidos criados para a própria empresa"
    requirement: "ORD-08"
    verification:
      - kind: e2e
        ref: "scripts/smoke-order-flow.sh passos 14 e 15"
        status: pass
    human_judgment: false
  - id: D7
    description: "POST /api/orders pelo Gateway sem token devolve 401 (D-05: o Gateway só roteia, o order-service valida o JWT localmente)"
    requirement: "ORD-09"
    verification:
      - kind: e2e
        ref: "scripts/smoke-order-flow.sh passo 4"
        status: pass
    human_judgment: false
  - id: D8
    description: "Script de smoke nunca imprime um token de acesso, e está registrado no índice git com o bit de execução (modo 100755)"
    verification:
      - kind: other
        ref: "bash scripts/smoke-order-flow.sh | grep -iE 'eyJ|accessToken' (sem match)"
        status: pass
      - kind: other
        ref: "git ls-files -s scripts/smoke-order-flow.sh (100755)"
        status: pass
    human_judgment: false
  - id: D9
    description: "Swagger UI do order-service (http://localhost:8085/swagger-ui.html) abre sem token, o spec OpenAPI declara o esquema bearerAuth, e GET /orders continua exigindo token — mesma prova estrutural já usada em catalog-service/notification-service"
    requirement: "ORD-01"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#specJsonIsAccessibleWithoutTokenAndDeclaresBearerAuth"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#swaggerConfigUnderApiDocsIsAccessibleWithoutToken"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#swaggerUiHtmlLetsSecurityChainPassWithoutToken"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#businessEndpointStillRequiresTokenGuardAgainstRegression"
        status: pass
    human_judgment: false
  - id: D10
    description: "README.md, docs/API.md e docs/VISAO-GERAL.md documentam os cinco endpoints de pedido, a regra de aprovação por crédito (exposição acumulada, ordem das chamadas, a trava por empresa) e as limitações conhecidas da Fase 4 — nenhum texto novo descreve APPROVED como confirmado ou com estoque reservado"
    requirement: "ORD-01"
    verification:
      - kind: other
        ref: "grep -c \"/api/orders/{orderId}/approve\" docs/API.md (2)"
        status: pass
      - kind: other
        ref: "grep -c \"Limitações conhecidas (Fase 4)\" README.md (1)"
        status: pass
    human_judgment: true
    rationale: "A legibilidade do texto para um avaliador externo (a clareza da explicação da regra de crédito e das limitações) é julgamento humano, não verificável por grep — mesmo <human-check> do plano; grep confirma apenas presença estrutural do conteúdo"

duration: 27min
completed: 2026-09-25
status: complete
---

# Phase 4 Plan 5: Compose, Gateway, Smoke e Documentação Summary

**order-service entra no `docker compose up` atrás do API Gateway (rota `/api/orders/**`), com um script de smoke de 15 passos que prova na stack real — auth-service e catalog-service reais, nunca os stubs dos testes — os Success Criteria 1 a 4 do ROADMAP: pedido aprovado e pendente pelo limite de crédito, decisão do vendedor com auditoria, recusa de produto descontinuado e isolamento por empresa; Swagger UI e documentação (README/docs) fecham a fase para um avaliador externo.**

## Performance

- **Duration:** ~27 min
- **Started:** 2026-09-25T23:25:00Z (aproximado, logo após o commit de encerramento do 04-04)
- **Completed:** 2026-09-25T23:52:05Z
- **Tasks:** 2 (Task 1: tracer — compose/Gateway/smoke; Task 2: Swagger e documentação)
- **Files modified:** 8 (3 novos, 5 modificados)

## Accomplishments
- `order-service` entra no `docker-compose.yml`: bloco novo espelhando `catalog-service` (build, `depends_on` de `postgres`/`auth-service`/`catalog-service` saudáveis — sem `localstack`, este serviço não tem mensageria nesta fase), porta `127.0.0.1:8085`, healthcheck, e as cinco variáveis de configuração apontando para os nomes de serviço do compose (nunca `localhost`); o `gateway` passa a depender de `order-service` saudável
- Rota `order-service-route` no Gateway (`Path=/api/orders/**`, `StripPrefix=1`), sem nenhuma configuração de segurança (D-05 — o Gateway só roteia)
- `scripts/smoke-order-flow.sh`: 15 passos numerados pelo Gateway, com auth-service e catalog-service reais (não os stubs dos testes de unidade/integração), provando os Success Criteria 1 a 4 do ROADMAP: pedido de 400.00 nasce `APPROVED` (`decidedBy=SYSTEM`), pedido de 700.00 nasce `PENDING_APPROVAL`, produto `DISCONTINUED` é recusado com `422 invalid_order_items`, o vendedor aprova o pendente com `decidedBy` igual ao próprio `userId`, rejeita um segundo pedido acima do limite (D-38) com motivo obrigatório, uma segunda decisão devolve `409`, e o comprador de outra empresa recebe `404`/`totalElements=0` no isolamento por empresa — `SMOKE OK` confirmado em **duas execuções seguidas na mesma base**, sem reset de banco entre elas, e sem nunca imprimir um token
- Registrado o bit de execução do script no índice git (`git update-index --chmod=+x`, confirmado `100755` via `git ls-files -s`)
- `OpenApiConfig` (esquema `bearerAuth`) e `OpenApiDocsIT` (4 testes) trazem a Swagger UI do `order-service` ao mesmo padrão já usado em `catalog-service`/`notification-service` — suíte completa do módulo em 48/48 testes, `BUILD SUCCESS`
- `README.md` ganha as subseções "Pedidos (Fase 4)", "Como a aprovação por crédito funciona" (exposição acumulada, ordem catálogo → limite → transação com trava, D-38) e "Limitações conhecidas (Fase 4)" — deixando explícito que `APPROVED` ainda não reserva estoque nem confirma o pedido; `docs/API.md` ganha 5 linhas na visão geral, 7 códigos de erro novos e uma seção por endpoint; `docs/VISAO-GERAL.md` ganha o `order-service` no estado atual, na tabela de serviços e na arquitetura em alto nível

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — pelo Gateway, na stack real, um comprador cria pedidos decididos pelo crédito e o vendedor decide o pendente** - `4a2c73f` (feat)
2. **Task 2: Swagger do order-service e documentação da fase para quem avalia o projeto** - `e8075c3` (feat)

**Plan metadata:** commit de documentação a ser criado logo após este SUMMARY.

## Files Created/Modified
- `docker-compose.yml` - bloco `order-service` (build, depends_on, healthcheck, 5 variáveis); `gateway` passa a depender de `order-service` saudável
- `gateway/src/main/resources/application.yml` - rota `order-service-route` (`/api/orders/**`, `StripPrefix=1`)
- `scripts/smoke-order-flow.sh` - script de smoke de 15 passos, executável (`100755`)
- `order-service/src/main/java/com/orderflow/order/config/OpenApiConfig.java` - bean `openApi` com esquema `bearerAuth`
- `order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java` - 4 testes de integração do spec/Swagger UI
- `README.md` - subseções de pedidos, regra de crédito e limitações da Fase 4
- `docs/API.md` - endpoints de pedido, códigos de erro novos, seção por endpoint
- `docs/VISAO-GERAL.md` - estado atual, tabela de serviços e arquitetura atualizados

## Decisions Made
- **order-service sem `localstack` no `depends_on`** — nenhuma mensageria existe neste serviço nesta fase.
- **Sufixo único (UUID) gerado dentro do container do Gateway** para e-mails/SKUs do smoke — permite reexecutar o script na mesma base sem colidir com dados de execuções anteriores (provado rodando o script duas vezes seguidas).
- **`extract_first_string` como extração ancorada no início do corpo JSON**, separada da extração `greedy` padrão — necessário porque `CompanyResponse` tem `id` tanto no nível raiz quanto aninhado em `buyerUser.id`; sem a distinção, o script capturaria o id do usuário BUYER em vez do id da empresa.

## Deviations from Plan

None - plan executed exactly as written. O texto do motivo de aprovação/rejeição no script de smoke usa "cliente estrategico"/"limite excedido" sem acentuação (em vez de "estratégico"/"limite excedido" com acento, como no texto do plano) — escolha puramente cosmética para evitar qualquer risco de mangling de encoding dentro de aspas aninhadas no Git Bash do Windows; nenhuma asserção do script depende do conteúdo exato do campo `reason`, então não afeta nenhum critério de aceitação.

## Issues Encountered

Nenhum bloqueante. O script de smoke passou de primeira na primeira tentativa completa (15/15 passos), e uma segunda execução na mesma base confirmou que a geração do sufixo único evita qualquer colisão de dados entre execuções sem exigir `docker compose down -v` ou reset manual do banco entre elas.

## User Setup Required

None - nenhuma configuração de serviço externo nova é necessária (o `.env` já existente, com `LOCALSTACK_AUTH_TOKEN`/`POSTGRES_*` preenchidos pelas fases anteriores, foi suficiente para `docker compose up -d --build --wait` subir os oito serviços saudáveis).

## Next Phase Readiness

- A Fase 4 está completa e apresentável: `docker compose up` sobe a stack inteira (oito serviços) com um único comando, o script de smoke prova os Success Criteria 1 a 4 do ROADMAP na stack real pelo Gateway, e a Swagger UI + documentação (`README.md`, `docs/API.md`, `docs/VISAO-GERAL.md`) explicam o estado atual e as limitações a um avaliador externo sem prometer o que a Fase 5 ainda vai entregar.
- Success Criteria 5 do ROADMAP (pedidos simultâneos na fronteira de crédito) já estava provado por socket real desde o `04-01` (`CreditLimitBoundaryConcurrencyIT`) e está citado na documentação nova.
- Nenhum bloqueio conhecido para a Fase 5 (saga de reserva de estoque, Transactional Outbox, orquestração por eventos via SQS entre `order-service` e `inventory-service`).
- **Item pendente para verificação humana** (harvested por `/gsd-verify-work` ao final da fase, per `workflow.human_verify_mode: end-of-phase`): abrir `http://localhost:8085/swagger-ui.html` com a stack no ar, autorizar com um token de BUYER gerado pelo smoke, exercitar `POST /orders`/`GET /orders` via Try it out, e ler as seções "Como a aprovação por crédito funciona" e "Limitações conhecidas (Fase 4)" do README para confirmar clareza a um avaliador externo.

---
*Phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito*
*Completed: 2026-09-25*

## Self-Check: PASSED

All key files verified present on disk (`docker-compose.yml` order-service block, `gateway/src/main/resources/application.yml` order-service-route, `scripts/smoke-order-flow.sh` at mode 100755, `order-service/src/main/java/com/orderflow/order/config/OpenApiConfig.java`, `order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java`, `README.md`/`docs/API.md`/`docs/VISAO-GERAL.md` with the new sections). Both task commits (`4a2c73f`, `e8075c3`) verified present in `git log --oneline --all`. Full stack re-verified healthy end-to-end: `docker compose up -d --build --wait` brought all eight services to `Healthy`, `scripts/smoke-order-flow.sh` produced `SMOKE OK` on two consecutive runs against the same base (proving no manual reset is required), a token-leak grep over the smoke output found no match, `git ls-files -s scripts/smoke-order-flow.sh` confirmed mode `100755`, `docker compose down` exited 0, and `./mvnw -B -pl order-service verify` passed 48/48 tests (`BUILD SUCCESS`, including the 4 new `OpenApiDocsIT` tests). Both plan-level `<verification>` grep commands re-run and passing (`/api/orders/{orderId}/approve` in docs/API.md, `Limitações conhecidas (Fase 4)` in README.md).
