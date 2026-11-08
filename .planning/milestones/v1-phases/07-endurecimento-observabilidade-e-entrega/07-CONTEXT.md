# Phase 7: Endurecimento, Observabilidade e Entrega - Context

**Gathered:** 2026-10-01
**Status:** Ready for planning

<domain>
## Phase Boundary

Deixa o projeto apresentável e verificável por um avaliador externo. A fase entrega:

- uma Swagger UI única no Gateway, documentando os 5 serviços com contratos e erros;
- um Correlation-ID que nasce no Gateway e aparece nos logs de todos os serviços, inclusive pelo caminho assíncrono outbox → SQS;
- um pipeline do GitHub Actions que builda e testa cada serviço a cada push, com Testcontainers e LocalStack;
- uma auditoria regra → teste que fecha as lacunas de teste por serviço, incluindo o gateway, que hoje não tem nenhum teste;
- ADRs em português (formato MADR) para as decisões-chave.

Entram também, como dívidas de endurecimento, os 3 warnings do review da Fase 6 (WR-01, WR-02, WR-03).

Requisitos: QUAL-01, QUAL-02, INFRA-02, INFRA-03, TEST-01, TEST-02.

**Fora desta fase:** Resilience4j (circuit breaker e retry nas chamadas síncronas); Micrometer Tracing e tracing distribuído; logs em JSON; limite mínimo de cobertura com JaCoCo; badge de CI, build de imagens Docker no CI e publicação em registry; deploy na AWS real; capacidades novas de domínio (cancelamento manual etc.).

</domain>

<decisions>
## Implementation Decisions

### OpenAPI pelo Gateway (QUAL-01)
- **D-87:** **Uma Swagger UI única no Gateway**, com dropdown para os 5 serviços: springdoc no Gateway com `swagger-ui.urls` apontando para `/api/<svc>/v3/api-docs`, e rotas no Gateway que levam cada caminho ao `/v3/api-docs` do serviço. O README aponta para uma URL só (ex.: `http://localhost:8080/swagger-ui.html`). Os serviços continuam com o springdoc que já têm.
- **D-88:** Nível de detalhe **"contratos + erros"**: `@Tag` e `@Operation` em cada endpoint, `@Schema` com exemplos nos DTOs de requisição e resposta, o enum `OrderStatus` documentado com os valores reais, e respostas de erro declaradas (401, 403, 404, 409, 503 conforme o endpoint) no formato `{"error","message","fields"}`.
- **D-89:** **"Try it out" com JWT**: cada serviço declara um `SecurityScheme` `bearerAuth` (HTTP bearer, JWT). O avaliador faz login em `/api/auth/login`, cola o token em Authorize e testa direto da UI.
- **D-90:** **O "Try it out" passa pelo Gateway**: cada spec declara como `server` a URL pública do Gateway com o prefixo da rota (ex.: `http://localhost:8080/api`, considerando o `StripPrefix=1`). Assim a chamada da UI segue o caminho de produção e recebe Correlation-ID.
- **D-91:** `/v3/api-docs/**` e `/swagger-ui/**` (e `/swagger-ui.html`) ficam **públicos**: `permitAll` só nesses caminhos, em cada `SecurityFilterChain`. Todo o resto continua exigindo JWT.

### Correlation-ID ponta a ponta (QUAL-02)
- **D-92:** Header **`X-Correlation-Id`**. O Gateway gera um UUID quando o cliente não manda o header. Quando manda, o Gateway só reaproveita o valor se ele tiver formato válido (ex.: UUID ou `[A-Za-z0-9-]{1,64}`); senão, gera outro, o que evita log injection. O header é devolvido na resposta.
- **D-93:** Cada serviço tem um filtro HTTP que lê `X-Correlation-Id`, põe o valor no **MDC** (`correlationId`) durante a requisição e limpa ao final. Se o header não vier, por exemplo numa chamada direta na porta do serviço, o filtro gera um ID.
- **D-94:** **Pelo SQS o ID viaja como coluna no outbox mais atributo da mensagem.** Nova coluna `correlation_id` na `outbox_event` do order-service e do inventory-service (migrações Flyway novas), gravada pelo `OutboxWriter` na mesma transação a partir do MDC. O `OutboxRelay` envia o valor como **MessageAttribute** SQS. Os listeners (`ReservationCommandListener`, `ReservationResultListener`, `NotificationEventListener`) leem o atributo e põem o valor no MDC durante o processamento. **O envelope de negócio (`eventId`/`eventType`/`occurredAt` + campos) não muda.** — **Reversibility:** costly — a coluna entra nas migrações de dois serviços e o atributo vira parte do contrato de transporte entre order, inventory e notification.
- **D-95:** **Fluxos sem requisição HTTP herdam o ID do pedido.** O `correlation_id` da requisição que criou o pedido fica gravado com o pedido (coluna em `orders`). O `SagaTimeoutJob` e as mensagens que ele dispara (ex.: `ReleaseStock`, `ORDER_CANCELLED`) reaproveitam esse ID, e o relay usa o ID da linha do outbox. Assim o pedido inteiro fica rastreável por um ID só. Transições feitas por endpoints posteriores (`/approve`, `/ship`, `/deliver`) usam o ID da própria requisição; se essas transições devem preferir o ID original do pedido fica a critério do planejador, ver Claude's Discretion. — **Reversibility:** costly — coluna nova em `orders` (migração Flyway).
- **D-96:** **As chamadas HTTP síncronas também propagam o ID**: um interceptor no `RestClient` do order-service (`CatalogServiceClient`, `AuthServiceClient`) copia o `X-Correlation-Id` do MDC para a chamada de saída. O catalog-service e o auth-service registram o mesmo ID.
- **D-97:** **Logs em texto com MDC no pattern**: `logback-spring.xml` (ou `logging.pattern.level`) por serviço, com `[%X{correlationId}]` em cada linha. A saída fica legível em `docker compose logs` e fácil de demonstrar com `grep`. Sem logs em JSON nesta fase.
- **D-98:** **Correlation-ID próprio, sem Micrometer Tracing.** Filtro, MDC e atributo SQS são escritos à mão para mostrar o mecanismo. O tracing distribuído (W3C `traceparent`, Micrometer, OpenTelemetry) aparece no ADR como alternativa considerada e rejeitada por enquanto.
- **D-99:** **Prova em duas camadas:**
  1. Smoke `scripts/smoke-correlation-id.sh`, no estilo dos smokes existentes: manda um `X-Correlation-Id` conhecido, cria um pedido que percorre a saga até CONFIRMED (com timeline) e faz `grep` desse ID em `docker compose logs` do gateway, order-service, inventory-service e notification-service.
  2. Testes automatizados: unitário do filtro do Gateway; IT ou E2E que confere o atributo SQS e o MDC no consumidor (ex.: `OutputCaptureExtension` ou leitura do atributo da mensagem).

### Pipeline de CI (INFRA-02)
- **D-100:** Workflow do GitHub Actions em `.github/workflows/ci.yml`, organizado em **matriz por serviço mais um job E2E**. Cada módulo roda `./mvnw -pl <svc> -am verify` (surefire e failsafe), com `actions/setup-java` (Temurin 21) e cache Maven. Uma falha mostra exatamente qual serviço quebrou. O `e2e-tests` roda num job próprio, depois dos módulos.
- **D-101:** **Sessão única do LocalStack Hobby.** O STATE.md registra que o Testcontainers falha (exit 126) quando outra sessão está ativa com o mesmo token. Por isso, auth-service, catalog-service e gateway (sem LocalStack) rodam **em paralelo**, e inventory-service, order-service, notification-service e e2e-tests (com LocalStack) rodam **em sequência** (`max-parallel: 1` numa matriz própria ou `needs` encadeado). O pesquisador deve confirmar se a exclusividade vale no CI. Se várias sessões forem permitidas, a paralelização pode ser ampliada.
- **D-102:** Gatilhos: **`push` em qualquer branch** mais **`pull_request`**.
- **D-103:** **Sem token, a falha é visível**: o `LOCALSTACK_AUTH_TOKEN` vem de um GitHub Actions secret. Se o secret estiver ausente (ex.: PR de fork), o pipeline quebra com mensagem clara; o `LocalStackTestSupport` já faz isso. Nenhum teste é pulado em silêncio. O README explica como configurar o secret.
- **D-104:** Extra: **relatório de testes** publicado no run (summary ou artifact dos relatórios do surefire e do failsafe). Badge, build de imagens Docker e JaCoCo no CI ficaram de fora.

### Lacunas de teste (TEST-01, TEST-02)
- **D-105:** "Sem lacunas" é comprovado por uma **matriz regra → teste** por serviço (no estilo do `COVERAGE.md` da Fase 6). Cada regra de negócio central é listada com o teste unitário e o teste de integração (Postgres ou LocalStack) que a cobrem. O que estiver sem cobertura é fechado nesta fase. **Sem limite numérico de JaCoCo.** O artefato fica na pasta da fase (ex.: `07-COVERAGE.md`).
- **D-106:** **O gateway ganha testes**: unitário do filtro de Correlation-ID (gera, reaproveita e rejeita valor inválido) e um IT que sobe o gateway com stub downstream e prova roteamento, `StripPrefix`, propagação e devolução do header, e a rota de `/v3/api-docs` de cada serviço.
- **D-107:** **Os warnings WR-01, WR-02 e WR-03 do `06-REVIEW.md` entram nesta fase**, cada um com teste:
  - WR-01: `ShipStock` inválido não pode ser descartado em silêncio com o pedido já SHIPPED (ir para DLQ ou tratar como anomalia técnica).
  - WR-02: desambiguar os `@Recover` de `shipAll`/`releaseAll`.
  - WR-03: um evento `ORDER_*` inválido descartado passa a ser logado com `orderId`.

### ADRs (INFRA-03)
- **D-108:** Formato **MADR em `docs/adr/`**, um arquivo por decisão (`NNNN-titulo-em-kebab.md`), com Contexto, Decisão, Alternativas consideradas (prós e contras, ao menos uma rejeitada com o motivo) e Consequências. Índice em `docs/adr/README.md`. Texto em português.
- **D-109:** **Escopo dos ADRs**: os 4 obrigatórios do critério 5 (saga por orquestração em vez de coreografia; Transactional Outbox em vez de publicação direta; LocalStack em vez de AWS real; ausência deliberada de service discovery e config server) mais os principais do projeto. Lista indicativa:
  - JWT auto-emitido pelo auth-service e validado localmente pelos resource servers;
  - DynamoDB para o histórico (SQL e NoSQL lado a lado);
  - decisão de crédito serializada por empresa (`company_credit_lock` pessimista);
  - Correlation-ID próprio em vez de tracing distribuído;
  - Spring Cloud Gateway Server WebMVC em vez do gateway reativo.
  A ausência de Resilience4j entra como consequência declarada (fail-closed 503 da Fase 4) num ADR pertinente.
- **D-110:** Status **"Aceito"**, com a **fase de origem** da decisão (ex.: "Fase 5"), links para as decisões D-xx dos CONTEXT.md e para o código que a implementa.

### Entrega para o avaliador
- **D-111:** O README ganha uma seção **"Para avaliadores"** perto do topo, com:
  - como subir a stack (incluindo o token);
  - a URL da Swagger UI no Gateway;
  - o índice de ADRs;
  - como seguir um Correlation-ID nos logs (smoke e `grep`);
  - onde ver o CI;
  - os scripts de smoke.
  `docs/VISAO-GERAL.md` e `docs/API.md` passam a referenciar o Swagger e os ADRs.

### Claude's Discretion
- Regex exato de validação do `X-Correlation-Id` e nome da chave do MDC.
- Se o filtro de Correlation-ID dos 5 serviços fica duplicado em cada um ou num módulo compartilhado, como `common`. O projeto ainda não tem módulo compartilhado, então criar um é decisão estrutural: avaliar o custo.
- Se `/approve`, `/ship` e `/deliver` usam o ID da própria requisição ou preferem o `correlation_id` original do pedido (D-95).
- Nome e formato do MessageAttribute SQS (ex.: `correlationId`, tipo String) e como ler esse atributo no `@SqsListener` (header do Spring Messaging).
- Ação do CI que publica o relatório de testes (`dorny/test-reporter`, `mikepenz/action-junit-report` ou `upload-artifact` + job summary).
- Números e títulos dos ADRs e lista final dos ADRs "principais" (D-109 é indicativa).
- Organização das rotas de docs no Gateway (ex.: `/api/<svc>/v3/api-docs` reaproveitando as rotas existentes ou rotas dedicadas).
- Se cada tecnologia nova da fase (Correlation-ID/MDC, GitHub Actions, OpenAPI agregado) ganha um arquivo explicativo em `estudos/`. O projeto exige explicar o "porquê" antes de implementar.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Escopo e requisitos
- `.planning/ROADMAP.md` §Phase 7 — goal e os 5 critérios de sucesso (OpenAPI pelo Gateway, Correlation-ID em todos os logs, CI falhando visivelmente, testes sem lacunas, ADRs com alternativa rejeitada)
- `.planning/REQUIREMENTS.md` — QUAL-01, QUAL-02, INFRA-02, INFRA-03, TEST-01, TEST-02, e a nota sobre requisitos transversais (a Fase 7 fecha lacunas, não adia testes)
- `.planning/PROJECT.md` §Constraints, §Context (convenção de idiomas: documentação e ADRs em português, código em inglês) e §Key Decisions (matéria-prima dos ADRs)

### Decisões anteriores que se aplicam
- `.planning/phases/06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid/06-CONTEXT.md` — D-78/D-79 (eventos `ORDER_*` pelo outbox), D-83/D-84 (tabela de transições e diagrama), D-85/D-86 (estilo de smoke e escopo do E2E)
- `.planning/phases/06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid/06-REVIEW.md` — WR-01, WR-02, WR-03 (entram pela D-107)
- `.planning/phases/06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid/COVERAGE.md` — modelo da matriz regra → teste (D-105)
- `.planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-CONTEXT.md` — outbox e relay por serviço, envelope plano, timeout da saga, módulo e2e-tests (D-68), estilo de smoke (D-69)
- `.planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-CONTEXT.md` — fail-closed 503, `company_credit_lock`, Resilience4j adiado
- `.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/` — Gateway só roteia (D-05) e cada serviço valida o JWT localmente

### Pesquisa e estado
- `.planning/research/ARCHITECTURE.md` e `.planning/research/PITFALLS.md` — base para as alternativas rejeitadas dos ADRs (Pitfall 2 outbox, Pitfall 5 LocalStack, Pitfall 7 testes adiados)
- `.planning/STATE.md` §Accumulated Context (`SAGA_MESSAGE_CONTRACT`, `OUTBOX_RELAY_DEFAULTS`) e §Blockers (sessão LocalStack Hobby única por token, que afeta D-101; WR-03 da Fase 5 `poll-timeout: 0s`)
- `.claude/CLAUDE.md` — stack (Spring Boot 3.5.x, springdoc 2.x, Gateway Server WebMVC, GitHub Actions com Temurin 21) e o alerta do `LOCALSTACK_AUTH_TOKEN`

### Documentação existente a atualizar
- `README.md` — seção "Para avaliadores" (D-111) e instruções do secret do CI
- `docs/VISAO-GERAL.md`, `docs/API.md` — links para Swagger e ADRs
- `estudos/13-springdoc-openapi.md`, `estudos/15-testes.md`, `estudos/24-scripts-smoke.md` — material de estudo existente relacionado

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- springdoc já está nos 5 serviços (`application.yml` com `springdoc.api-docs.path: /v3/api-docs` e `swagger-ui.path: /swagger-ui.html`; quick task 260920-g6c). Ainda não há nenhuma anotação `@Operation`/`@Schema`/`@Tag` no código.
- `gateway/src/main/resources/application.yml`: rotas estáticas `/api/<svc>/**` com `StripPrefix=1`. O springdoc entra aqui, junto com as rotas de docs e o filtro de Correlation-ID.
- `*/config/SecurityConfig.java` (auth, catalog, inventory, order, notification): onde entra o `permitAll` dos caminhos de docs (D-91).
- `*/config/GlobalExceptionHandler.java`: formato de erro `{"error","message","fields"}` a documentar como schema de erro (D-88).
- `order-service/.../client/CatalogServiceClient.java`, `AuthServiceClient.java`: pontos do interceptor de Correlation-ID (D-96).
- `order-service/.../saga/outbox/OutboxWriter.java`, `OutboxRelay.java`, `OutboxEvent.java` e os equivalentes em `inventory-service/.../saga/outbox/`: coluna e atributo `correlation_id` (D-94).
- Listeners: `inventory-service/.../saga/messaging/ReservationCommandListener.java`, `order-service/.../saga/messaging/ReservationResultListener.java`, `notification-service/.../history/messaging/NotificationEventListener.java`. Eles leem o atributo e põem o ID no MDC.
- `order-service/.../saga/SagaTimeoutJob.java`: herda o ID do pedido (D-95).
- `e2e-tests/` (`E2eInfrastructure`, `LocalStackTestSupport`, `DownstreamStubServer`): base para o teste automatizado do Correlation-ID. O `LocalStackTestSupport` já resolve `LOCALSTACK_AUTH_TOKEN` pelo ambiente ou pelo `.env` e falha com mensagem clara.
- `scripts/smoke-*.sh`: estilo do novo `smoke-correlation-id.sh`.
- `.planning/phases/06-.../COVERAGE.md`: modelo da matriz de cobertura.

### Established Patterns
- Reactor Maven multi-módulo com surefire (`*Test.java`) e failsafe (`*IT.java`) no `pom.xml` raiz; `./mvnw verify` roda os dois.
- Flyway `V<n>__*.sql` por serviço com `ddl-auto: validate`: próxima migração é V4 no order-service e V5 no inventory-service (coluna `correlation_id`).
- Imagens fixadas (`localstack/localstack:2026.08.3`, `postgres:16`), nunca `latest`.
- Envelope plano das mensagens SQS e `doNotSendPayloadTypeHeader`; o relay é o único caminho de envio SQS.
- Contagem atual de testes (unit/IT): auth 2/4, catalog 1/2, inventory 3/9, order 12/15, notification 3/7, gateway 0/0, e2e 3 ITs.

### Integration Points
- `.github/workflows/` ainda não existe e é criado nesta fase. O remoto é `github.com/Lucasllo/B2B_commercial`, e o secret `LOCALSTACK_AUTH_TOKEN` precisa ser cadastrado no repositório.
- `docker-compose.yml`: o Gateway passa a servir a Swagger UI. Pode ser preciso ajustar as URLs públicas.
- Migrações novas em order-service (`orders.correlation_id`, `outbox_event.correlation_id`) e inventory-service (`outbox_event.correlation_id`).

</code_context>

<specifics>
## Specific Ideas

- O avaliador abre uma URL só (Swagger no Gateway), faz login, cola o token e percorre o fluxo do pedido sem curl. Depois, com um único `grep` do Correlation-ID, vê a mesma requisição passar por gateway, order-service, inventory-service e notification-service.
- O Correlation-ID escrito à mão é um ponto de entrevista: mostra que o candidato entende MDC, propagação HTTP e propagação por mensageria, e por que a coluna no outbox é necessária, já que o relay roda em outra thread e em outro momento.
- O CI falhar de forma visível sem o token é intencional. Pular em silêncio esconderia exatamente os testes que mais importam (saga, outbox, LocalStack).
- Os ADRs contam o "porquê" de decisões já tomadas e registram a fase de origem, então funcionam como um diário de arquitetura navegável.
- Preferência do projeto: nenhuma tecnologia nova sem explicação prévia do motivo, o que vale para GitHub Actions, MDC e OpenAPI agregado.

</specifics>

<deferred>
## Deferred Ideas

- Resilience4j (circuit breaker e retry em order → catalog/auth; falha simulada da transportadora): backlog. O fail-closed 503 da Fase 4 cobre o caso, e a ausência é registrada como consequência num ADR.
- Micrometer Tracing / OpenTelemetry com coletor (Zipkin/Jaeger): alternativa registrada no ADR de Correlation-ID.
- Logs estruturados em JSON (ex.: por profile) prontos para ELK/Loki.
- JaCoCo com limite mínimo no build, ou relatório de cobertura no CI.
- Badge de CI no README, `docker compose build` no CI, publicação de imagens.
- WR-03 da Fase 5 (`poll-timeout: 0s` no `SqsAsyncClient` compartilhado): continua como backlog, porque só afeta custo e latência no SQS real.
- Retenção do outbox e limpeza das lápides de reserva (AR-05-02): limitações conhecidas que continuam documentadas.

</deferred>

---

*Phase: 07-endurecimento-observabilidade-e-entrega*
*Context gathered: 2026-10-01*
