---
phase: 07-endurecimento-observabilidade-e-entrega
verified: 2026-11-05T12:00:00Z
status: human_needed
score: 3/5 must-haves verified
covered_files:
  - .github/workflows/ci.yml
  - .planning/REQUIREMENTS.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-01-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-01-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-02-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-02-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-03-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-03-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-04-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-04-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-05-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-05-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-06-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-06-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-07-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-07-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-08-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-08-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-09-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-09-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-10-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-10-SUMMARY.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-11-PLAN.md
  - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-11-SUMMARY.md
  - README.md
  - docs/adr/README.md
  - gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java
  - gateway/src/main/resources/application.yml
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
  - notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java
  - order-service/src/main/java/com/orderflow/order/config/OpenApiConfig.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
  - scripts/check-adrs.sh
  - scripts/check-coverage-matrix.sh
  - scripts/check-no-skipped-tests.sh
  - scripts/ci-summary.sh
  - scripts/smoke-correlation-id.sh
covered_digest: "v1:sha256:05fd61866ff65cc51b03674551283f81606512c791c72668a8372d570b7b3b2e"
behavior_unverified: 2
overrides_applied: 0
behavior_unverified_items:
  - truth: "Uma requisição Gateway -> order-service -> SQS -> inventory-service -> notification-service pode ser seguida pelo mesmo Correlation-ID em todos os logs envolvidos (critério 2)"
    test: "Com a stack real de pé (docker compose up --build --wait, LOCALSTACK_AUTH_TOKEN no .env), rodar `bash scripts/smoke-correlation-id.sh` e guardar a saída"
    expected: "Última linha `SMOKE OK correlation-id <id>`; o mesmo `[<id>]` nos logs de gateway, order-service, inventory-service, notification-service, catalog-service e auth-service; ID inválido (`bad value!`) trocado por UUID e ausente dos logs"
    why_human: "Cada elo da cadeia tem teste verde no CI, mas a cadeia inteira em containers reais só foi provada pelo script, e não há saída gravada de nenhuma execução (só a afirmação do 07-11-SUMMARY). O Docker está parado nesta máquina."
  - truth: "O pipeline falha visivelmente quando algum teste quebra (critério 3)"
    test: "Criar o branch descartável `ci/prova-falha-visivel`, inverter uma asserção de `gateway/src/test/java/com/orderflow/gateway/CorrelationIdFilterTest.java`, dar push, observar o run e apagar o branch local e o remoto"
    expected: "Perna `sem-localstack (gateway)` vermelha, run vermelho, `e2e` ignorado (`needs`), resumo do job apontando a suíte que quebrou"
    why_human: "O histórico do repositório tem só 6 runs, todos `success`, e o remoto só tem a branch master. Nenhum run vermelho jamais foi produzido; a falha visível só foi demonstrada por leitura do YAML e por um XML sintético passado ao ci-summary.sh."
human_verification:
  - test: "Smoke de Correlation-ID na stack real (ver behavior_unverified_items[0])"
    expected: "SMOKE OK com o mesmo ID nos 6 logs"
    why_human: "Docker indisponível aqui e sem evidência gravada de execução real"
  - test: "Prova de falha visível do CI (ver behavior_unverified_items[1])"
    expected: "Run vermelho que identifica a perna e a suíte"
    why_human: "Só um run realmente quebrado confirma"
  - test: "Try it out da Swagger UI agregada: subir a stack, abrir http://localhost:8080/swagger-ui.html, POST /auth/login, Authorize, POST /orders"
    expected: "201 via http://localhost:8080/api/orders, header X-Correlation-Id presente, sem erro de CORS, dropdown com os cinco serviços"
    why_human: "Comportamento de navegador (CORS, server relativo /api); o plano 07-11 registra a suposição A1 como não verificável por máquina. Os specs são provados por OpenApiDocsIT, mas a UI aberta no navegador não."
---

# Fase 7: Endurecimento, Observabilidade e Entrega - Relatório de Verificação

**Objetivo da fase:** Deixar o projeto apresentável e verificável por um avaliador externo: documentação OpenAPI de cada serviço, rastreabilidade por Correlation-ID nos logs, cobertura de testes unitários e de integração sem lacunas, pipeline de CI verde a cada push e as decisões arquiteturais registradas como ADRs em português explicando o "porquê" de cada escolha.
**Verificado em:** 2026-11-05
**Status:** human_needed
**Re-verificação:** Não, verificação inicial

## Origem das evidências

Para não misturar o que eu observei com o que me foi informado:

- **(A) Observado por mim nesta verificação:** leitura do código e dos testes; execução local de `scripts/check-adrs.sh` (ADR CHECK OK 11 ADRs), `scripts/check-coverage-matrix.sh` (COVERAGE CHECK OK 72 regras) e `scripts/check-no-skipped-tests.sh` (NENHUM TESTE PULADO); consulta ao GitHub com `gh run view 38190986896 --log` e `gh run list`, com as contagens de testes por suíte lidas do log do run; `git ls-remote` e `git ls-files -s`.
- **(B) Informado pelo orquestrador:** Docker parado nesta máquina; run 38190986896 no commit fd040ed verde nos 8 jobs. Conferi o run com `gh` e bate (8 jobs com sucesso, 9m19s). O único commit posterior, d39c6fc, só adiciona o 07-REVIEW.md.
- **(C) Apenas afirmado nos SUMMARY, sem artefato que eu possa ver:** execução do smoke de Correlation-ID e do `smoke-order-lifecycle.sh` na stack real (07-11-SUMMARY), `actionlint` sem erros, `docker compose ps -q` vazio ao final.

Não rodei Testcontainers, LocalStack nem a stack compose (sem Docker). Não reexecutei as suítes Maven localmente: usei o log do CI, que é evidência de execução real das mesmas suítes no commit verificado.

## Cumprimento do objetivo

### Verdades observáveis (Critérios de Sucesso do ROADMAP)

| # | Verdade | Status | Evidência |
|---|---------|--------|-----------|
| 1 | Cada serviço expõe OpenAPI/Swagger navegável pelo Gateway, com status do pedido e contratos de requisição/resposta | ✓ VERIFIED | O Gateway agrega os cinco specs em `/docs/<svc>/v3/api-docs` (`SetPath=/v3/api-docs`, `application.yml` linhas 20-49) e lista os cinco no dropdown do springdoc (`springdoc.swagger-ui.urls`, linhas 88-102, primário `order-service`). Cada serviço declara `server /api` (ex.: `OpenApiConfig` do order-service lê `orderflow.openapi.server-url`, default `/api`) e `ErrorResponse` documental. `OpenApiDocsIT` existe e passou no CI em auth (8), catalog (9), inventory (9) e notification (8); o do order-service é o que compara o enum do spec com `OrderStatus.values()` na mesma ordem (`orderStatusEnumInBothResponsesMatchesOrderStatusValues`), então um status novo sem documentação quebra o build. `GatewayRoutingIT` (10) verde. O Try it out no navegador é item humano (abaixo), mas a existência e o conteúdo dos specs estão provados. |
| 2 | Uma requisição Gateway -> order -> SQS -> inventory -> notification pode ser seguida pelo mesmo Correlation-ID em todos os logs | ⚠️ PRESENT_BEHAVIOR_UNVERIFIED | Os elos existem, estão ligados e têm teste verde no CI. Gateway: `CorrelationIdFilter` valida `[A-Za-z0-9-]{1,64}`, troca valor inválido por UUID, grava um único header no pedido encaminhado e na resposta, limpa o MDC no `finally` (`CorrelationIdFilterTest` 8, `GatewayRoutingIT` 10). Order: filtro HTTP -> `Order`/outbox (V4) -> `OutboxRelay` envia o atributo SQS `correlationId` lido da linha (linhas 69-71); `ReservationResultListener` reabre o escopo a partir do atributo (`CorrelationIdPropagationIT` 7). Inventory: `ReservationCommandListener` lê o atributo (linhas 62-63), grava `correlation_id` no outbox (V5) e devolve na resposta; `CorrelationIdE2EIT` (e2e job, 1 teste, 71,9 s) prova order e inventory reais com o mesmo ID nos dois listeners e na linha `StockReserved`. Notification: `NotificationEventListener` abre o escopo pelo atributo (linhas 51-52); `CorrelationIdConsumptionIT` (4) verde. O que falta é a cadeia inteira nos contêineres reais: o `CorrelationIdE2EIT` declara que deixa o notification de fora por custo e o cobre "pelo smoke", e o smoke não tem saída gravada. Ver itens humanos. |
| 3 | Um push dispara o pipeline que builda e testa todos os serviços (incl. Testcontainers/LocalStack) e falha visivelmente quando um teste quebra | ⚠️ PRESENT_BEHAVIOR_UNVERIFIED | Metade provada: `.github/workflows/ci.yml` roda em `push` e `pull_request`, tem `guardas`, `sem-localstack` (auth/catalog/gateway), `com-localstack` (inventory/order/notification, `max-parallel: 1`) e `e2e`; o run 38190986896 teve os 8 jobs verdes e o log mostra suítes reais com Testcontainers/LocalStack (order-service `Tests run: 146`, `ReservationResultListenerIT` 13, inventory 97 + 72 ITs, notification 61 + 40, e2e 18, todos `Skipped: 0`). Sem `continue-on-error` ativo no YAML, `fail-fast: false` para mostrar qual perna quebrou, passo `::error::` quando falta o secret. A outra metade, "falha visivelmente", nunca foi exercida: nenhum run vermelho existe (6 runs, todos success; remoto só com master). A prova de falha prevista no plano 07-11 (branch `ci/prova-falha-visivel`) não foi feita. Maven já falha o job com teste quebrado e o `ci-summary.sh` roda com `if: always()`, então o comportamento é muito provável, mas é inferência de leitura. |
| 4 | Todo serviço tem unitários nas regras centrais e ao menos um teste de integração contra dependência real, sem lacunas herdadas | ✓ VERIFIED | `07-COVERAGE.md` tem 72 regras em 7 módulos, sem `LACUNA`; `check-coverage-matrix.sh` rodou local (COVERAGE CHECK OK 72 regras) e no job `guardas`. Os testes citados passaram no CI, com ITs contra Postgres/LocalStack reais em todos os serviços: auth 24 unitários + 56 totais, catalog 15 + 39, gateway 14 + 10 (stub de upstream, sem dependência real: ver Aviso 1), inventory 97 + 72, notification 61 + 40, order 146 totais, e2e 18. As lacunas herdadas (WR-01/02/03 da Fase 6) têm planos próprios (07-07, 07-09). Ressalva de qualidade da guarda em WR-08 (abaixo). |
| 5 | Decisões-chave registradas como ADRs em português, cada uma com alternativa rejeitada e motivo | ✓ VERIFIED | `docs/adr/` tem 11 ADRs MADR em português mais `README.md`; os quatro do critério existem: 0001 saga por orquestração, 0002 Transactional Outbox, 0003 LocalStack em vez de AWS real, 0004 sem service discovery nem config server. Os quatro que li têm as seções Contexto e problema, Fatores de decisão, Alternativas consideradas, Decisão, Prós e contras das alternativas, Mais informações. `check-adrs.sh` (rodei local e passa no CI) confere formato, alternativa rejeitada, links, citações D-xx contra os CONTEXT e índice. Não li o conteúdo dos 7 ADRs restantes além do que o script confere. |

**Pontuação:** 3/5 verdades verificadas (2 presentes e ligadas, com comportamento de ponta a ponta não provado; ver Verificação Humana)

### Itens adiados

Nenhum. Os itens abertos não são cobertos por nenhuma fase posterior (a Fase 7 é a última do marco).

### Artefatos requeridos

| Artefato | Esperado | Status | Detalhes |
|----------|----------|--------|----------|
| `gateway/.../CorrelationIdFilter.java` | Origem do ID, header único, MDC, log de acesso | ✓ VERIFIED | Substantivo, `@Order(HIGHEST_PRECEDENCE)`, wrappers de pedido e resposta, testado |
| `gateway/src/main/resources/application.yml` | Rotas `/docs/<svc>/v3/api-docs` e dropdown | ✓ VERIFIED | 5 rotas de docs + 5 URLs no springdoc |
| `order-service/.../OutboxRelay.java` e o do inventory | Atributo SQS `correlationId` vindo da linha | ✓ VERIFIED | `setHeader(SQS_ATTRIBUTE, event.getCorrelationId())` |
| `V4__correlation_id.sql` (order), `V5__outbox_correlation_id.sql` (inventory) | Colunas `correlation_id` | ✓ VERIFIED | Presentes; `E2eContextsSmokeIT` ajustado para V4/V5 |
| Listeners de inventory, order e notification | Escopo de MDC a partir do atributo | ✓ VERIFIED | `@Header(name = SQS_ATTRIBUTE, required = false)` + `CorrelationContext.open` |
| `OpenApiConfig` + `ErrorResponse` por serviço | server `/api`, esquema de erro | ✓ VERIFIED | Cinco serviços |
| `docs/adr/0001..0011` + `README.md` | ADRs em português | ✓ VERIFIED | `check-adrs.sh` OK |
| `07-COVERAGE.md` + `check-coverage-matrix.sh` | Matriz regra -> teste | ✓ VERIFIED | 72 regras; ressalva WR-08 |
| `.github/workflows/ci.yml` | Pipeline | ✓ VERIFIED (existência/uso) | Run real verde; falha visível não exercida |
| `scripts/smoke-correlation-id.sh` | Smoke de ponta a ponta | ⚠️ PRESENT | Existe, é `100755` no git, mas sem saída de execução gravada |
| `scripts/check-no-skipped-tests.sh`, `scripts/ci-summary.sh` | Guarda e resumo | ✓ VERIFIED | Guarda rodada local e no CI |

### Verificação de ligações (key links)

| De | Para | Via | Status | Detalhes |
|----|------|-----|--------|----------|
| Cliente -> Gateway | Serviços | `CorrelationIdFilter` + wrapper de request | WIRED | Header único encaminhado (`GatewayRoutingIT`) |
| `OrderService` | `outbox_event.correlation_id` | `Order.recordCorrelationId` + `OutboxWriter` | WIRED | `CorrelationIdPropagationIT` |
| `OutboxRelay` (order/inventory) | SQS | `MessageBuilder.setHeader` | WIRED | `OutboxRelayTest`, `CorrelationIdE2EIT` |
| SQS | Listeners | `@Header(name="correlationId")` | WIRED | Três consumidores |
| order-service | catalog/auth | `RestClient` com interceptor que lê o MDC | WIRED | `DownstreamClientsTest` |
| Gateway | Swagger dos serviços | Rotas `/docs/*` -> `SetPath` | WIRED | `GatewayRoutingIT` |
| CI | `guardas` -> scripts | `bash scripts/check-*.sh` | WIRED | Saídas lidas no log do run |

### Fluxo de dados (Nível 4)

| Artefato | Variável | Fonte | Dados reais | Status |
|----------|----------|-------|-------------|--------|
| Linhas de log `[id]` | MDC `correlationId` | Header HTTP validado ou atributo SQS lido da linha do outbox | Sim (log do CI mostra IDs reais como `it-result-cid-3`) | ✓ FLOWING |
| Spec do order-service (enum `status`) | `OrderStatus.values()` | Enum real via `@Schema(implementation=...)` | Sim, travado por teste | ✓ FLOWING |

### Verificações comportamentais

| Comportamento | Comando | Resultado | Status |
|---------------|---------|-----------|--------|
| ADRs completos | `bash scripts/check-adrs.sh` | `ADR CHECK OK 11 ADRs` | ✓ PASS |
| Matriz regra -> teste | `bash scripts/check-coverage-matrix.sh` | `COVERAGE CHECK OK 72 regras` | ✓ PASS |
| Nenhum teste desabilitado | `bash scripts/check-no-skipped-tests.sh` | `NENHUM TESTE PULADO` | ✓ PASS |
| Suítes do CI | `gh run view 38190986896 --log` | `BUILD SUCCESS` em todos os módulos, `Skipped: 0` | ✓ PASS (CI, não local) |
| Smoke de Correlation-ID | `bash scripts/smoke-correlation-id.sh` | Não executado (sem Docker) | ? SKIP -> humano |

### Execução de probes

Não há probes `probe-*.sh` declarados nem convencionais nesta fase. Os scripts de guarda e smoke foram tratados como verificações comportamentais acima.

### Cobertura de requisitos

Os seis IDs do ROADMAP aparecem nos frontmatters dos planos e na tabela de rastreabilidade do REQUIREMENTS.md; nenhum órfão (nenhum ID da Fase 7 no REQUIREMENTS sem plano que o reivindique, e nenhum plano cita ID de outra fase).

| Requisito | Planos de origem | Descrição | Status | Evidência |
|-----------|------------------|-----------|--------|-----------|
| QUAL-01 | 07-01, 07-03, 07-07, 07-08, 07-11 | OpenAPI/Swagger de cada serviço | ✓ SATISFIED | Critério 1; `OpenApiDocsIT` nos cinco serviços, Swagger agregada no Gateway |
| QUAL-02 | 07-01, 07-02, 07-04, 07-05, 07-07, 07-11 | Correlation-ID gerado no Gateway e propagado nos logs | ⚠️ SATISFIED por partes | Todos os elos testados; cadeia completa na stack real sem evidência gravada (humano) |
| INFRA-02 | 07-11 | CI builda e testa cada serviço a cada push | ✓ SATISFIED | Run verde com 8 jobs; a parte "falha visível" é verificação humana |
| INFRA-03 | 07-06 | ADRs em português | ✓ SATISFIED | 11 ADRs, `check-adrs.sh` |
| TEST-01 | 07-01, 07-02, 07-04, 07-05, 07-07, 07-09, 07-10 | Unitários nas regras centrais por serviço | ✓ SATISFIED | Matriz 72 regras, suítes verdes no CI |
| TEST-02 | 07-01, 07-02, 07-04, 07-05, 07-07, 07-09, 07-10 | Integração com dependências reais via Testcontainers | ✓ SATISFIED | ITs com Postgres/LocalStack verdes no CI (Aviso 1 sobre o gateway) |

### Anti-padrões encontrados

Varredura nos arquivos modificados pela fase: nenhum `TBD`/`FIXME`/`XXX` sem referência, nenhum retorno vazio de stub, nenhum `continue-on-error` ativo. Os achados abaixo vêm do 07-REVIEW.md (0 críticos, 9 avisos, 8 info) e eu confirmei no código os que pesam nos critérios.

| Arquivo | Linha | Padrão | Severidade | Impacto |
|---------|-------|--------|------------|---------|
| `scripts/smoke-correlation-id.sh` | 130 | `grep -q` sob `pipefail` (WR-01) | ⚠️ Aviso | O smoke pode reprovar de forma intermitente com log grande (SIGPIPE, 141). Isso enfraquece a prova do critério 2 por falso negativo, não por falso positivo |
| `.github/workflows/ci.yml` | 29-31 | `concurrency` por ref, token LocalStack global (WR-02) | ⚠️ Aviso | Dois pushes em refs distintas rodam LocalStack ao mesmo tempo com o mesmo token; pode gerar falha intermitente |
| `.github/workflows/ci.yml` | jobs | Sem `timeout-minutes` (WR-03) | ⚠️ Aviso | Job travado prende o runner por até 6 h |
| `order-service/.../OrderController.java` | 81 | `Location: /orders/{id}` sem o prefixo `/api` (WR-04) | ⚠️ Aviso | Confirmado: a linha 81 usa `URI.create("/orders/...")` e o `@ApiResponse` (linha 61) documenta esse caminho; pelo Gateway ele dá 404. É inconsistência entre doc e comportamento, mas não é o contrato de status/requisição/resposta do critério 1 |
| `inventory-service/.../SagaCommandParser.java` | 178 | `asLong()` sem `canConvertToLong()` (WR-05) | ⚠️ Aviso | Confirmado: quantidade de 64+ bits é truncada e pode passar na faixa. Fora do escopo dos critérios, mas é defeito de validação real |
| `order-service/.../OutboxRelay.java` (e inventory) | 69-85 | `catch` fora do escopo de MDC (WR-06) | ⚠️ Aviso | Confirmado: o `log.warn` de falha do relay sai sem `[id]`. O caminho feliz do critério 2 não é afetado |
| `OutboxRelay` (order/inventory) | 69-72 | ID gerado no relay para linha legada sem ID (WR-09) | ℹ️ Info | Só linhas anteriores à migração; logs mostram ID que nenhum outro serviço verá |
| `gateway/src/main/resources/application.yml` | rotas | Sem timeout de upstream (WR-07) | ⚠️ Aviso | Robustez da borda; não afeta nenhum critério |
| `scripts/check-coverage-matrix.sh` | 63-90 | Não distingue coluna unitária de coluna de integração (WR-08) | ⚠️ Aviso | A guarda "sem lacunas" é mais fraca do que o cabeçalho promete; a matriz foi montada à mão e coerente, mas o gate mecânico poderia aprovar uma linha com dois `*Test` |

Nenhum dos nove avisos derruba um critério de sucesso. Os que mais se aproximam são WR-01 (enfraquece a prova do critério 2 por falsos negativos) e WR-08 (enfraquece a garantia "sem lacunas" do critério 4); ambos reduzem a confiança no gate, não negam o resultado, que eu conferi diretamente nas suítes do CI. Recomendo tratá-los antes de entregar a avaliadores, em especial WR-04 (corrige-se em uma linha ou na doc), WR-05 e WR-02.

### Verificação humana necessária

#### 1. Smoke de Correlation-ID na stack real

**Teste:** com a stack de pé e `LOCALSTACK_AUTH_TOKEN` no `.env`, rodar `bash scripts/smoke-correlation-id.sh` e guardar a saída (se falhar por WR-01, repetir; é intermitente).
**Esperado:** `SMOKE OK correlation-id <id>`, o mesmo `[<id>]` nos logs dos seis serviços e o ID inválido trocado por UUID.
**Por que humano:** exige Docker/LocalStack; sem saída gravada nos artefatos da fase e sem Docker nesta máquina.

#### 2. Prova de falha visível do pipeline

**Teste:** criar `ci/prova-falha-visivel`, inverter uma asserção de `CorrelationIdFilterTest` do gateway, dar push, observar e apagar a branch local e a remota.
**Esperado:** perna `sem-localstack (gateway)` vermelha, `e2e` ignorado, resumo do job apontando a suíte.
**Por que humano:** só um run realmente quebrado prova a visibilidade. Esse passo estava previsto no 07-11 e ficou para o humano.

#### 3. Try it out da Swagger UI agregada

**Teste:** abrir `http://localhost:8080/swagger-ui.html`, fazer `POST /auth/login`, Authorize, `POST /orders`.
**Esperado:** 201 via `http://localhost:8080/api/orders`, header `X-Correlation-Id`, sem erro de CORS, cinco serviços no dropdown.
**Por que humano:** comportamento de navegador; não confirmável por máquina.

### Resumo das lacunas

Não há lacuna bloqueante: nenhuma verdade falhou, nenhum artefato é stub ou está sem ligação, e os seis requisitos têm implementação com testes verdes em um run real do GitHub Actions no commit verificado. Todo o código da fase que tem teste foi comprovado pelo log do CI.

O status é `human_needed` porque duas verdades têm uma metade que só um humano ou um run real confirma: o rastreio ponta a ponta na stack real (critério 2, sem saída gravada do smoke) e a falha visível do pipeline (critério 3, nenhum run vermelho existe). Além disso, a Swagger UI no navegador (critério 1) é verificação visual. Esses três pontos são o que falta para uma nota cheia, e a ação é curta: rodar o smoke uma vez e forçar uma falha em uma branch descartável.

Pontos de atenção para o avaliador externo, sem bloquear a fase: WR-04 (o `Location` do 201 contradiz o contrato publicado), WR-05 (truncamento de inteiro no parser de comandos), WR-02 e WR-03 (robustez do CI com o token Hobby), WR-01 e WR-08 (confiabilidade das próprias guardas).

**Aviso 1 (transparência):** no gateway, o "teste de integração contra dependência real" é o `GatewayRoutingIT` com `UpstreamStubServer`; o gateway não tem banco nem fila, então não existe dependência de infraestrutura real a testar. A matriz e o critério 4 aceitam isso para o gateway.

---

_Verificado: 2026-11-05_
_Verificador: Claude (gsd-verifier)_
