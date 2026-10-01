# Phase 7: Endurecimento, Observabilidade e Entrega - Research

**Researched:** 2026-10-01
**Domain:** Endurecimento de um sistema Spring Boot 3.5 / Spring Cloud Gateway Server WebMVC / Spring Cloud AWS 3.4 já existente: OpenAPI agregado (springdoc), Correlation-ID por MDC propagado por HTTP e por SQS (via outbox), CI no GitHub Actions com Testcontainers + LocalStack, auditoria de testes e ADRs (MADR) em português. Nenhuma biblioteca de runtime nova entra no projeto.
**Confidence:** HIGH para o que está no código (lido nesta sessão) e para o que foi provado por spike local (gateway + springdoc + filtro + padrão de log); MEDIUM para o CI (não executável localmente; `gh` e `actionlint` ausentes) e para a regra de sessão única do LocalStack (a documentação é omissa).

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**OpenAPI pelo Gateway (QUAL-01)**
- **D-87:** **Uma Swagger UI única no Gateway**, com dropdown para os 5 serviços: springdoc no Gateway com `swagger-ui.urls` apontando para `/api/<svc>/v3/api-docs`, e rotas no Gateway que levam cada caminho ao `/v3/api-docs` do serviço. O README aponta para uma URL só (ex.: `http://localhost:8080/swagger-ui.html`). Os serviços continuam com o springdoc que já têm.
- **D-88:** Nível de detalhe **"contratos + erros"**: `@Tag` e `@Operation` em cada endpoint, `@Schema` com exemplos nos DTOs de requisição e resposta, o enum `OrderStatus` documentado com os valores reais, e respostas de erro declaradas (401, 403, 404, 409, 503 conforme o endpoint) no formato `{"error","message","fields"}`.
- **D-89:** **"Try it out" com JWT**: cada serviço declara um `SecurityScheme` `bearerAuth` (HTTP bearer, JWT). O avaliador faz login em `/api/auth/login`, cola o token em Authorize e testa direto da UI.
- **D-90:** **O "Try it out" passa pelo Gateway**: cada spec declara como `server` a URL pública do Gateway com o prefixo da rota (ex.: `http://localhost:8080/api`, considerando o `StripPrefix=1`). Assim a chamada da UI segue o caminho de produção e recebe Correlation-ID.
- **D-91:** `/v3/api-docs/**` e `/swagger-ui/**` (e `/swagger-ui.html`) ficam **públicos**: `permitAll` só nesses caminhos, em cada `SecurityFilterChain`. Todo o resto continua exigindo JWT.

**Correlation-ID ponta a ponta (QUAL-02)**
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

**Pipeline de CI (INFRA-02)**
- **D-100:** Workflow do GitHub Actions em `.github/workflows/ci.yml`, organizado em **matriz por serviço mais um job E2E**. Cada módulo roda `./mvnw -pl <svc> -am verify` (surefire e failsafe), com `actions/setup-java` (Temurin 21) e cache Maven. Uma falha mostra exatamente qual serviço quebrou. O `e2e-tests` roda num job próprio, depois dos módulos.
- **D-101:** **Sessão única do LocalStack Hobby.** O STATE.md registra que o Testcontainers falha (exit 126) quando outra sessão está ativa com o mesmo token. Por isso, auth-service, catalog-service e gateway (sem LocalStack) rodam **em paralelo**, e inventory-service, order-service, notification-service e e2e-tests (com LocalStack) rodam **em sequência** (`max-parallel: 1` numa matriz própria ou `needs` encadeado). O pesquisador deve confirmar se a exclusividade vale no CI. Se várias sessões forem permitidas, a paralelização pode ser ampliada.
- **D-102:** Gatilhos: **`push` em qualquer branch** mais **`pull_request`**.
- **D-103:** **Sem token, a falha é visível**: o `LOCALSTACK_AUTH_TOKEN` vem de um GitHub Actions secret. Se o secret estiver ausente (ex.: PR de fork), o pipeline quebra com mensagem clara; o `LocalStackTestSupport` já faz isso. Nenhum teste é pulado em silêncio. O README explica como configurar o secret.
- **D-104:** Extra: **relatório de testes** publicado no run (summary ou artifact dos relatórios do surefire e do failsafe). Badge, build de imagens Docker e JaCoCo no CI ficaram de fora.

**Lacunas de teste (TEST-01, TEST-02)**
- **D-105:** "Sem lacunas" é comprovado por uma **matriz regra → teste** por serviço (no estilo do `COVERAGE.md` da Fase 6). Cada regra de negócio central é listada com o teste unitário e o teste de integração (Postgres ou LocalStack) que a cobrem. O que estiver sem cobertura é fechado nesta fase. **Sem limite numérico de JaCoCo.** O artefato fica na pasta da fase (ex.: `07-COVERAGE.md`).
- **D-106:** **O gateway ganha testes**: unitário do filtro de Correlation-ID (gera, reaproveita e rejeita valor inválido) e um IT que sobe o gateway com stub downstream e prova roteamento, `StripPrefix`, propagação e devolução do header, e a rota de `/v3/api-docs` de cada serviço.
- **D-107:** **Os warnings WR-01, WR-02 e WR-03 do `06-REVIEW.md` entram nesta fase**, cada um com teste:
  - WR-01: `ShipStock` inválido não pode ser descartado em silêncio com o pedido já SHIPPED (ir para DLQ ou tratar como anomalia técnica).
  - WR-02: desambiguar os `@Recover` de `shipAll`/`releaseAll`.
  - WR-03: um evento `ORDER_*` inválido descartado passa a ser logado com `orderId`.

**ADRs (INFRA-03)**
- **D-108:** Formato **MADR em `docs/adr/`**, um arquivo por decisão (`NNNN-titulo-em-kebab.md`), com Contexto, Decisão, Alternativas consideradas (prós e contras, ao menos uma rejeitada com o motivo) e Consequências. Índice em `docs/adr/README.md`. Texto em português.
- **D-109:** **Escopo dos ADRs**: os 4 obrigatórios do critério 5 (saga por orquestração em vez de coreografia; Transactional Outbox em vez de publicação direta; LocalStack em vez de AWS real; ausência deliberada de service discovery e config server) mais os principais do projeto. Lista indicativa:
  - JWT auto-emitido pelo auth-service e validado localmente pelos resource servers;
  - DynamoDB para o histórico (SQL e NoSQL lado a lado);
  - decisão de crédito serializada por empresa (`company_credit_lock` pessimista);
  - Correlation-ID próprio em vez de tracing distribuído;
  - Spring Cloud Gateway Server WebMVC em vez do gateway reativo.
  A ausência de Resilience4j entra como consequência declarada (fail-closed 503 da Fase 4) num ADR pertinente.
- **D-110:** Status **"Aceito"**, com a **fase de origem** da decisão (ex.: "Fase 5"), links para as decisões D-xx dos CONTEXT.md e para o código que a implementa.

**Entrega para o avaliador**
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

### Deferred Ideas (OUT OF SCOPE)
- Resilience4j (circuit breaker e retry em order → catalog/auth; falha simulada da transportadora): backlog. O fail-closed 503 da Fase 4 cobre o caso, e a ausência é registrada como consequência num ADR.
- Micrometer Tracing / OpenTelemetry com coletor (Zipkin/Jaeger): alternativa registrada no ADR de Correlation-ID.
- Logs estruturados em JSON (ex.: por profile) prontos para ELK/Loki.
- JaCoCo com limite mínimo no build, ou relatório de cobertura no CI.
- Badge de CI no README, `docker compose build` no CI, publicação de imagens.
- WR-03 da Fase 5 (`poll-timeout: 0s` no `SqsAsyncClient` compartilhado): continua como backlog, porque só afeta custo e latência no SQS real.
- Retenção do outbox e limpeza das lápides de reserva (AR-05-02): limitações conhecidas que continuam documentadas.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| QUAL-01 | Documentação OpenAPI/Swagger disponível para cada serviço | springdoc já está nos 5 serviços e o `permitAll` dos caminhos de docs já existe nos 5 `SecurityConfig` (D-91 já cumprida, só precisa de teste-guarda). Falta: anotações (nenhuma existe; 23 endpoints documentáveis), `servers`, schema de erro, enum `OrderStatus`, Swagger UI agregada no Gateway (spike provou que springdoc + Gateway MVC funciona, ver §Architecture Patterns 1). |
| QUAL-02 | Correlation-ID gerado no Gateway e propagado nos logs de todos os serviços | Filtro servlet + wrapper no Gateway (spike provou, com duas armadilhas), `logging.pattern.correlation` (provado), coluna `correlation_id` + `MessageBuilder` header no relay (código-fonte do Spring Cloud AWS 3.4.2 lido), `@Header` no listener, **e linhas de log INFO de caminho feliz que hoje não existem** (Pitfall 1). |
| INFRA-02 | Pipeline de CI builda e testa cada serviço a cada push | `ci.yml` com matriz sem LocalStack (paralela) + matriz com LocalStack (`max-parallel: 1`) + job e2e; runner pinado em `ubuntu-24.04`; e2e via `install -DskipTests` para não duplicar ITs (Pitfall 6). |
| INFRA-03 | Decisões arquiteturais documentadas como ADRs em português | MADR 4.0 traduzido; lista de 11 ADRs com fase de origem; teste de consistência `scripts/check-adrs.sh`. |
| TEST-01 | Testes unitários nas regras centrais de cada serviço | Auditoria concreta: catalog-service tem **zero** testes unitários reais, auth-service só `SeedPasswordHashTest`, inventory-service não tem teste unitário de `Inventory`/`InventoryService`, gateway não tem nada (ver §Test Gap Audit). |
| TEST-02 | Testes de integração contra dependências reais | Todos os serviços de domínio já têm ITs com Postgres/LocalStack reais; o gateway ganha IT com stub JDK (sem container). Novos ITs: atributo SQS → MDC, coluna `correlation_id`, WR-01/02/03. |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

- Stack travada: Java 21 (Temurin), Spring Boot 3.5.x, Spring Cloud 2025.0.x, springdoc 2.x, Gateway Server WebMVC; GitHub Actions com `actions/setup-java` (Temurin 21) e cache Maven. [VERIFIED: .claude/CLAUDE.md]
- `LOCALSTACK_AUTH_TOKEN` obrigatório desde 2026.03.0; docker-compose e CI devem passá-lo; nunca assumir imagem anônima. [VERIFIED: .claude/CLAUDE.md + docker-compose.yml:22]
- **Proibido:** `WebSecurityConfigurerAdapter`, Hystrix, `docker-compose` v1, Kafka, motor de workflow, Eureka/Zuul/Ribbon (CLAUDE.md §What NOT to Use). Nada disso entra na fase.
- Constraint de processo: **nenhuma tecnologia implementada sem explicação prévia do motivo** (vale para MDC, GitHub Actions, OpenAPI agregado) — planejar arquivos em `estudos/` (índice atual vai até `26-saga-de-reserva-de-estoque.md`) ou justificar a dispensa. A memória do usuário diz que `estudos/*.md` pode ser editado direto, sem `/gsd-quick`.
- Convenção de idiomas: documentação/ADRs em português, código em inglês (PROJECT.md §Context).
- Fluxo GSD: edições de repositório só dentro de um fluxo GSD (execução da fase).
- Testes: JUnit + Mockito (unit), Testcontainers (integração), contrato/E2E. Confiabilidade de eventos via Transactional Outbox (nenhum caminho novo pode publicar no SQS fora do relay).

## Summary

A Fase 7 é de **endurecimento**: nenhuma dependência de runtime nova (springdoc já é gerenciado em `pom.xml:53`, `spring-boot-starter-test` vem do BOM). O trabalho é distribuído em seis frentes independentes o bastante para virar planos paralelos: (1) OpenAPI por serviço + Swagger UI agregada no Gateway; (2) Correlation-ID por HTTP, MDC, outbox e SQS; (3) CI; (4) auditoria e fechamento de lacunas de teste, incluindo WR-01/02/03; (5) ADRs; (6) README/docs/smoke. Eu **li o código** e **rodei um spike** (cópia do módulo gateway em diretório temporário, fora do repositório) que derrubou ou confirmou os pontos mais incertos.

Cinco descobertas mudam o plano em relação ao que o CONTEXT.md supõe. **(a)** D-91 já está implementada: os 5 `SecurityConfig` já liberam `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**` (e os `OpenApiDocsIT` já provam isso para 4 serviços) — a tarefa vira "manter e testar", não "criar". **(b)** Hoje **nenhum serviço loga nada no caminho feliz** (só WARN/ERROR de anomalia); sem linhas INFO novas, o `grep` do smoke (D-99) acharia o ID apenas no gateway. **(c)** No Gateway, o filtro com `HttpServletRequestWrapper` duplica o header para o downstream se `getHeaderNames()` não for case-insensitive, e a resposta sai com **dois** `X-Correlation-Id` se o serviço downstream também ecoar o header — os serviços **não** devem ecoar. **(d)** Para o IT do Gateway, sobrescrever `routes[0].uri` por propriedade **derruba a subida** (substitui a lista inteira); é preciso parametrizar os URIs com placeholders. **(e)** `-pl e2e-tests -am verify` roda também os ITs de order e inventory (duplicando LocalStack); no CI use `install -DskipTests` dos dois e depois `-pl e2e-tests verify`.

Sobre D-101 (sessão única do LocalStack): a documentação oficial não menciona limite de sessões concorrentes (pricing e docs de auth-token/CI consultados), só registra "1 personal sandbox" no Hobby e que CI é permitido. Não consegui testar localmente (o hook de segurança do ambiente bloqueia ler o `.env`, corretamente). Trate a exclusividade como **não confirmada** e mantenha a execução sequencial (decisão travada); a Open Question 1 traz um teste de 2 minutos que o usuário pode rodar para liberar paralelismo depois.

**Primary recommendation:** Entregar em 7 planos (ver §Recommended Plan Slicing): duplicar o pequeno componente de Correlation-ID em cada serviço (sem módulo `common`), usar `MessageBuilder` com header + `@Header(required=false)` para o atributo SQS, adicionar linhas INFO de caminho feliz, agregar a Swagger UI no Gateway com rotas dedicadas `/docs/<svc>/v3/api-docs` + `SetPath`, e escrever o CI com runner pinado em `ubuntu-24.04`, matriz paralela sem LocalStack, matriz `max-parallel: 1` com LocalStack e e2e via `install` + `-pl e2e-tests verify`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Geração/validação do `X-Correlation-Id` na borda | API Gateway (servlet filter) | — | Requisito QUAL-02: ID nasce no Gateway; clientes não confiáveis, então valida regex e regenera (log injection). |
| Propagação do ID no processo (MDC) | Cada serviço (filtro HTTP + helper de contexto) | — | MDC é thread-local, por JVM; precisa existir em cada serviço, sem módulo compartilhado (padrão do projeto, D-62). |
| Propagação do ID em chamada síncrona (order → catalog/auth) | order-service (`RestClient` interceptor) | catalog/auth (filtro HTTP) | Quem chama é quem copia MDC para o header de saída (D-96). |
| Propagação do ID por mensageria | Banco do serviço produtor (coluna `outbox_event.correlation_id`) + relay (MessageAttribute) | Listener consumidor (MDC) | O relay roda em outra thread e outro momento: só a coluna transacional preserva o ID (dual-write evitado). |
| Persistência do ID do pedido para fluxos sem HTTP | order-service / PostgreSQL (`orders.correlation_id`) | — | `SagaTimeoutJob` não tem requisição; herda do pedido (D-95). |
| Documentação OpenAPI (spec) | Cada serviço (springdoc + anotações) | — | O serviço dono do contrato gera o spec; evita spec desatualizado em outro lugar. |
| Agregação/serviço da Swagger UI | API Gateway (springdoc UI + rotas `/docs/*`) | — | Um único ponto para o avaliador; Gateway só proxia specs (continua sem lógica de negócio, D-05). |
| Execução de build/teste a cada push | GitHub Actions (CI) | — | INFRA-02. |
| Registro de decisões | Repositório (`docs/adr/`) | README | INFRA-03. |

## Standard Stack

### Core (já no projeto — nenhuma instalação nova)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| springdoc-openapi-starter-webmvc-ui | 2.9.1 [VERIFIED: pom.xml:53] | Spec OpenAPI + Swagger UI; passa a existir também no `gateway` | Já em uso nos 5 serviços; spike provou que funciona no Gateway MVC |
| spring-cloud-starter-gateway-server-webmvc | trem 2025.0.3 [VERIFIED: pom.xml:41] | Roteamento; filtros `SetPath`, `StripPrefix` | Já em uso |
| Spring Cloud AWS (`spring-cloud-aws-starter-sqs`) | 3.4.2 [VERIFIED: pom.xml:62] | `SqsOperations.send(String, Message<T>)`, `@SqsListener`, `@Header` | Já em uso; headers ↔ message attributes lidos no código-fonte v3.4.2 |
| SLF4J MDC / Logback | gerenciado por Boot 3.5.16 | Contexto de log por thread | Padrão do Spring Boot; `logging.pattern.correlation` provado no spike |
| Spring Retry | 2.0.12/2.0.13 [VERIFIED: ~/.m2/repository/org/springframework/retry/spring-retry/] | `@Retryable(recover="...")` para WR-02 | Já em uso no inventory-service |
| Testcontainers | **1.21.4 efetivo** [VERIFIED: `./mvnw -o -pl order-service dependency:tree` → `org.testcontainers:testcontainers:jar:1.21.4:test`] | Postgres/LocalStack reais nos ITs | O BOM do Spring Boot (importado primeiro) vence o `testcontainers.version` 1.20.4 do `pom.xml:43`; a propriedade do pom é código morto/enganosa |
| JUnit 5 + Mockito + AssertJ + Awaitility + `OutputCaptureExtension` | gerenciados por Boot | Testes unitários e de log | Padrão do projeto |
| GitHub Actions: `actions/checkout@v7`, `actions/setup-java@v6`, `actions/upload-artifact@v7` | majors atuais [CITED: github.com/actions/setup-java README (setup-java@v6, checkout@v7); github.com/actions/upload-artifact/releases (v7)] | CI | Ações de primeira parte, sem risco de terceiros; confirmar tags no momento da implementação |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `spring-boot-starter-test` (no `gateway/pom.xml`) | BOM | Testes do Gateway (hoje o módulo não tem nenhuma dependência de teste) | Plano do Gateway |
| `maven-failsafe-plugin` (declarar no `gateway/pom.xml`) | 3.2.5 [VERIFIED: pom.xml:55] | Rodar `*IT.java` do Gateway | O `pluginManagement` raiz só define; cada módulo precisa declarar o plugin (como `order-service/pom.xml` faz) |
| `rhysd/actionlint` (via Docker, só como ferramenta local) | pinar uma tag | Lint estático do workflow antes do primeiro push | Docker está disponível; `actionlint` não está instalado [ASSUMED: imagem existe com esse nome] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Filtro duplicado em cada serviço | Módulo Maven `common` | Cada um dos 6 Dockerfiles copia `pom.xml` de todos os módulos para `dependency:go-offline` (verificado em `order-service/Dockerfile`); um módulo novo exige editar os 6 Dockerfiles, o `pom.xml` raiz e criar convenção de versionamento interno. O código duplicado são ~40 linhas por serviço, e o projeto já duplicou outbox por decisão (D-62). **Recomendo duplicar.** |
| `upload-artifact` + resumo via shell | `mikepenz/action-junit-report`, `dorny/test-reporter` | As ações de terceiros dão anotações por teste, mas exigem `checks: write` (falha em PR de fork) e ampliam a superfície de supply-chain. **Recomendo só `upload-artifact` + `$GITHUB_STEP_SUMMARY` gerado por um passo shell.** |
| `MessageBuilder` + `send(queue, Message)` | `send(to -> to.queue(..).payload(..).header(..))` | Ambos mapeiam para message attribute. `Message<T>` é mais fácil de afirmar num teste unitário com `ArgumentCaptor`. |
| Rotas dedicadas `/docs/<svc>/v3/api-docs` | `/api/<svc>/v3/api-docs` (literal do D-87) | Rotas `/api/<svc>/...` colidem com as rotas existentes (o `StripPrefix=1` tiraria só `/api` e `catalog`/`inventory` nem têm segmento de serviço em `/api/products`, `/api/inventory`). Dedicadas não colidem (verificado no spike). É Claude's Discretion. |

**Installation:** nenhuma dependência externa nova. Alterações de POM:

```xml
<!-- gateway/pom.xml -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<!-- + <plugin>maven-failsafe-plugin</plugin> em <build><plugins> -->
```

**Version verification:** `springdoc` 2.9.1 está em `~/.m2` e foi compilado no spike com Boot 3.5.16 (build `package -DskipTests` passou). Nenhum pacote npm/PyPI/crates envolvido.

## Package Legitimacy Audit

Nenhum pacote externo novo é instalado nesta fase.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| org.springdoc:springdoc-openapi-starter-webmvc-ui 2.9.1 | Maven Central | já presente em 5 módulos desde a quick task 260920-g6c | — | github.com/springdoc/springdoc-openapi | n/a (ecossistema Maven não coberto pelo seam `package-legitimacy check`, que aceita npm/pypi/crates) | Já aprovado no projeto; só é adicionado a mais um módulo |
| actions/checkout, actions/setup-java, actions/upload-artifact | GitHub Actions (org `actions`, primeira parte) | — | — | github.com/actions/* | n/a | Aprovado (primeira parte); fixar pelo major ou SHA |

**Packages removed due to [SLOP] verdict:** nenhum
**Packages flagged as suspicious [SUS]:** nenhum
*Se o planejador optar por ações de terceiros (`mikepenz`, `dorny`), taguear `[ASSUMED]` e inserir `checkpoint:human-verify` antes de adotá-las.*

## Architecture Patterns

### System Architecture Diagram

```
Cliente / Swagger UI (http://localhost:8080/swagger-ui.html)
   │  GET /docs/<svc>/v3/api-docs   (7 specs via dropdown)         POST /api/orders  (X-Correlation-Id opcional)
   ▼                                                                   ▼
┌───────────────────────── GATEWAY (porta 8080) ──────────────────────────────┐
│ CorrelationIdFilter (HIGHEST_PRECEDENCE)                                     │
│   valida regex → reaproveita OU gera UUID → MDC + header na resposta         │
│   request wrapper injeta X-Correlation-Id no pedido encaminhado              │
│   log INFO "METHOD path -> status" com [cid]                                 │
│ Rotas: /docs/<svc>/v3/api-docs ──SetPath=/v3/api-docs──► serviço             │
│        /api/<svc-path>/** ───────StripPrefix=1─────────► serviço             │
└───────────────┬─────────────────────────────────────────────────────────────┘
                ▼ HTTP com X-Correlation-Id
   auth/catalog/inventory/notification/order  ── CorrelationIdFilter (lê header, MDC, INFO access log, limpa)
                │
   order-service │ OrderService.create: grava orders.correlation_id (do MDC)
                │ RestClient interceptor: MDC → X-Correlation-Id ──► catalog/auth (mesmo ID nos logs deles)
                │ OutboxWriter.enqueue (mesma tx): outbox_event.correlation_id ← MDC
                ▼
        OutboxRelayJob (thread @Scheduled, sem MDC)
          por evento: MDC ← row.correlation_id; MessageBuilder.setHeader("correlationId") ; INFO "publicado"
                ▼  SQS (message attribute String "correlationId")
   inventory-commands-queue ──► ReservationCommandListener(@Header correlationId) ─ MDC ─► InventoryService
                                   └─ OutboxWriter (MDC) ─► relay ─► order-events-queue
   order-events-queue ──► ReservationResultListener ─ MDC ─► OrderSagaService ─► OrderTimelineEvents ─► outbox
   notification-events-queue ──► NotificationEventListener ─ MDC ─► DynamoDB
   SagaTimeoutJob (sem HTTP): MDC ← orders.correlation_id do pedido vencido ─► ReleaseStock/ORDER_CANCELLED herdam
```

### Recommended Project Structure

```
.github/workflows/ci.yml                 # novo (INFRA-02)
docs/adr/README.md + 0001-…0011-….md      # novos (INFRA-03), em português
scripts/smoke-correlation-id.sh           # novo (D-99)
scripts/check-adrs.sh                     # novo: valida seções/alternativa rejeitada dos ADRs
estudos/27-correlation-id-e-mdc.md        # sugestão (explicar antes de implementar)
estudos/28-github-actions-ci.md
estudos/29-openapi-agregado-no-gateway.md
gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java
gateway/src/test/java/com/orderflow/gateway/{CorrelationIdFilterTest,GatewayRoutingIT}.java (+ stub JDK)
<svc>/src/main/java/com/orderflow/<svc>/observability/{CorrelationContext,CorrelationIdFilter}.java   # por serviço (5x)
order-service/.../config/ClientConfig.java   # + requestInterceptor
order-service/.../db/migration/V4__correlation_id.sql      # orders + outbox_event
inventory-service/.../db/migration/V5__outbox_correlation_id.sql
.planning/phases/07-…/07-COVERAGE.md        # matriz regra → teste (D-105)
```

### Pattern 1: Swagger UI única no Gateway (spike provado)

**What:** springdoc UI no Gateway com `urls` relativas e rotas `SetPath` dedicadas. Provado com Boot 3.5.16 + springdoc 2.9.1 + Gateway MVC, rotas só com predicado `Path` (todas as rotas atuais do projeto são assim).
**Resultado do spike [VERIFIED: spike local 2026-10-01]:** `GET /v3/api-docs/swagger-config` devolveu `{"urls":[{"url":"/docs/auth/v3/api-docs","name":"auth-service"},…]}`; `GET /swagger-ui.html` → 302 para `/swagger-ui/index.html`; `GET /docs/auth/v3/api-docs` chegou ao stub como `/v3/api-docs`; o `/v3/api-docs` do próprio Gateway devolveu 200 (não o liste no dropdown). A issue springdoc #2506 (`Unexpected value: TRACE` quando a rota do Gateway MVC cobre todos os métodos) **não se reproduziu** com predicado `Path` apenas [CITED: github.com/springdoc/springdoc-openapi/issues/2506, status "não corrigido" na leitura]; mantenha as rotas só com `Path`.

```yaml
# gateway/src/main/resources/application.yml (trecho; URIs parametrizadas para o IT — Pitfall 4)
spring:
  cloud:
    gateway:
      server:
        webmvc:
          routes:
            - id: auth-docs
              uri: ${orderflow.gateway.upstream.auth:http://auth-service:8081}
              predicates:
                - Path=/docs/auth/v3/api-docs
              filters:
                - SetPath=/v3/api-docs
            # … catalog (8082), inventory (8083), notification (8084), order (8085) idem …
            - id: auth-service-route                       # existente, só com uri parametrizada
              uri: ${orderflow.gateway.upstream.auth:http://auth-service:8081}
              predicates:
                - Path=/api/auth/**,/api/companies/**
              filters:
                - StripPrefix=1
springdoc:
  swagger-ui:
    path: /swagger-ui.html
    urls:
      - { name: auth-service,         url: /docs/auth/v3/api-docs }
      - { name: catalog-service,      url: /docs/catalog/v3/api-docs }
      - { name: inventory-service,    url: /docs/inventory/v3/api-docs }
      - { name: notification-service, url: /docs/notification/v3/api-docs }
      - { name: order-service,        url: /docs/order/v3/api-docs }
```

O Gateway **não tem Spring Security** (D-05), então não precisa de `permitAll` ali. As URLs relativas fazem a UI buscar os specs na mesma origem (sem CORS).

### Pattern 2: Filtro de Correlation-ID do Gateway (spike provado, com a correção da Pitfall 2)

```java
// Source: spike local 2026-10-01 (Boot 3.5.16); nome do header e do MDC: Claude's Discretion/D-92
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String id = (incoming != null && VALID.matcher(incoming).matches()) ? incoming : UUID.randomUUID().toString();
        res.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override public String getHeader(String n) { return HEADER.equalsIgnoreCase(n) ? id : super.getHeader(n); }
                @Override public Enumeration<String> getHeaders(String n) {
                    return HEADER.equalsIgnoreCase(n) ? Collections.enumeration(List.of(id)) : super.getHeaders(n); }
                @Override public Enumeration<String> getHeaderNames() {
                    Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);   // ← obrigatório
                    names.addAll(Collections.list(super.getHeaderNames()));
                    names.add(HEADER);
                    return Collections.enumeration(names);
                }
            }, res);
        } finally {
            log.info("{} {} -> {}", req.getMethod(), req.getRequestURI(), res.getStatus());
            MDC.remove(MDC_KEY);
        }
    }
}
```

Pattern de log (provado nos dois formatos; prefira `correlation`, que fica depois da thread e não mexe no nível):

```yaml
# application.yml de cada serviço e do gateway
logging:
  pattern:
    correlation: "[%X{correlationId:-}] "
```
Saída real do spike: `INFO 17268 --- [gateway] [io-18080-exec-1] [cid-corr-pattern] c.orderflow.gateway.CorrelationIdFilter : GET /api/orders/x -> 200`.

### Pattern 3: Contexto de correlação por serviço (helper pequeno, duplicado)

```java
// <svc>/observability/CorrelationContext.java  (duplicado nos 5 serviços — decisão de custo acima)
public final class CorrelationContext {
    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";
    public static final String SQS_ATTRIBUTE = "correlationId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** Valida (a fila é fronteira de confiança) ou gera; devolve escopo que RESTAURA o valor anterior ao fechar. */
    public static Scope open(String raw) {
        String previous = MDC.get(MDC_KEY);
        String id = (raw != null && VALID.matcher(raw).matches()) ? raw : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        return () -> { if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous); };
    }
    public static String current() { return MDC.get(MDC_KEY); }
    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
}
```

O filtro HTTP de cada serviço usa `open(req.getHeader(HEADER))` num `OncePerRequestFilter`, loga uma linha INFO de acesso (Pitfall 1) e **não escreve** o header na resposta (Pitfall 3). Registrado como `@Component` — `@AutoConfigureMockMvc` aplica filtros-bean, então os ITs existentes (MockMvc) já exercitam o filtro.

### Pattern 4: Outbox + relay + listener

```java
// OutboxWriter.enqueue (order e inventory): grava a coluna a partir do MDC, mesma transação
outboxEventRepository.save(OutboxEvent.pending(eventId, eventType, aggregateId, json, now,
        CorrelationContext.current()));   // coluna correlation_id VARCHAR(64) NULL (linhas legadas = null)

// OutboxRelay.publishPendingBatch: por evento, MDC ← linha; header → message attribute
try (var ignored = CorrelationContext.open(event.getCorrelationId())) {   // linha legada (null) → ID novo só para o log
    Message<String> message = MessageBuilder.withPayload(event.getPayload())
            .setHeader(CorrelationContext.SQS_ATTRIBUTE, event.getCorrelationId())   // null remove o header
            .build();
    sqsOperations.send(queueName, message);
    log.info("Evento outbox publicado eventId={} eventType={} fila='{}'", event.getId(), event.getEventType(), queueName);
    …
}

// Listener (3x): atributo → MDC pelo tempo do processamento
@SqsListener("${orderflow.messaging.inventory-commands-queue}")
public void onMessage(String payload,
                      @Header(name = "correlationId", required = false) String correlationId) {
    try (var ignored = CorrelationContext.open(correlationId)) {
        log.info("Mensagem recebida da fila '{}'", queueName);
        …
    }
}
```

**Provas no código-fonte do Spring Cloud AWS v3.4.2** [VERIFIED: raw.githubusercontent.com/awspring/spring-cloud-aws/v3.4.2/.../SqsHeaderMapper.java]: `fromHeaders` converte **todos** os headers (exceto group id, deduplication id, trace header, delay, `id`, `timestamp`) em message attributes **com o mesmo nome** e `dataType=String` para valores `String`; `toHeaders` copia message attributes para headers **com o mesmo nome** (só os *system* attributes ganham prefixo `Sqs_MSA_`); a documentação 3.4 cita um prefixo `Sqs_MA_` que o código não aplica — confie no código. O container usa `messageAttributeNames` padrão = todos [VERIFIED: SqsContainerOptions.java "Default is ALL"]. `SqsOperations` tem `send(String queue, Message<T> message)` [VERIFIED: MessagingOperations.java:60]. O `SqsMessagingMessageConverter` do projeto já usa `doNotSendPayloadTypeHeader()` (`SqsMessagingConfig`), então o único atributo enviado será `correlationId`.

Para o `SagaTimeoutJob`: dentro de `OrderSagaService.expireReservation`, depois de travar o pedido, abrir `CorrelationContext.open(order.getCorrelationId())` (gera um novo se a linha legada tiver `null`) antes de `orderTimelineEvents.cancelled(order)` e do `outboxWriter.enqueue(ReleaseStock…)`.

**Decisão recomendada para o planejador (D-95, Claude's Discretion):** `/approve`, `/ship` e `/deliver` usam **o ID da própria requisição** (zero código extra; o log da requisição e o outbox daquela transição continuam ligados). Só jobs sem HTTP herdam `orders.correlation_id`. A prova do critério 2 é "uma requisição pode ser seguida", que isso satisfaz. Registre no ADR de Correlation-ID que o "ID por pedido" completo (todas as transições sob um ID) é uma extensão futura barata (ler a coluna em vez do MDC).

### Pattern 5: OpenAPI por serviço

`OpenApiConfig` de cada serviço ganha `servers` configurável (default relativo) — evita CORS quando a UI é aberta por `127.0.0.1` em vez de `localhost`:

```java
.servers(List.of(new Server().url(serverUrl).description("API Gateway")))   // @Value("${orderflow.openapi.server-url:/api}")
```
**Desvio do exemplo literal da D-90** (`http://localhost:8080/api`): o valor relativo `/api` é o prefixo público do Gateway e segue o mesmo caminho de produção; a propriedade permite pôr a URL absoluta por variável de ambiente. Marcado em A1 (confirmar no UAT manual da Swagger UI). Efeito colateral aceito: o "Try it out" pela porta direta do serviço (8081–8085) deixa de funcionar — a UI canônica passa a ser a do Gateway.

Itens específicos encontrados no código:
- `JwksController` (`auth-service`, `GET /.well-known/jwks.json`) apareceria no spec, mas o Gateway **não** roteia esse caminho (rotas do auth: `/api/auth/**`, `/api/companies/**`) → anotar `@Hidden`.
- Todos os erros são `ResponseEntity<Map<String,Object>>` (verificado em `order-service/.../GlobalExceptionHandler.java`): o springdoc **não** inventa schema. Criar um record só-documentação `ErrorResponse(error, message, fields)` por serviço e referenciá-lo em `@ApiResponse(content = @Content(schema = @Schema(implementation = ErrorResponse.class)))`. Códigos `error` reais vistos no handler do order-service: `validation_failed`, `malformed_request`, `invalid_parameter`, `order_not_found`, `order_not_pending`, `invalid_order_transition`, `invalid_order_items`, `order_total_out_of_range`, `catalog_service_unavailable`, `auth_service_unavailable` (+ `unauthorized`/`forbidden` do `SecurityConfig`).
- `OrderResponse.status` e `OrderSummaryResponse` usam `String` (verificado em `OrderResponse.java`), logo o enum **não** sai automaticamente: usar `@Schema(implementation = OrderStatus.class)` (ou `allowableValues`) no campo. Valores reais [VERIFIED: OrderStatus.java:28-36]: `CREATED, PENDING_APPROVAL, APPROVED, REJECTED, RESERVING, CONFIRMED, CANCELLED, SHIPPED, DELIVERED`. Teste: `OpenApiDocsIT` do order-service afirma `components.schemas.OrderResponse.properties.status.enum` == `OrderStatus.values()`.
- Contagem de endpoints a documentar: auth 5 (+`JwksController` oculto), catalog 5, inventory 4, notification 2, order 7 = **23** [VERIFIED: grep de `@*Mapping` em `*Controller.java`: 24 no total, 1 é o JWKS].

### Anti-Patterns to Avoid
- **Filtro de Correlation-ID que só faz `MDC.put`/`MDC.remove`** nos listeners SQS: threads do container são reutilizadas; sempre `try/finally` (o helper restaura o valor anterior).
- **Ler MDC dentro do `OutboxRelay` sem abrir contexto por linha:** o relay roda em `@Scheduled`, sem MDC; o ID vem da **linha**.
- **Ecoar `X-Correlation-Id` nos serviços:** duplica o header na resposta do Gateway (Pitfall 3).
- **Sobrescrever `routes[N].uri` por propriedade nos testes:** derruba a subida (Pitfall 4).
- **`@Header("correlationId")` sem `required=false`:** mensagens legadas e mensagens postadas direto na fila pelos ITs existentes não têm o atributo → falha de binding e reentrega.
- **Publicar no SQS fora do relay** (por conveniência no smoke/teste de Correlation-ID): viola o gate de grep da Fase 5 (T-05-01).
- **Colocar `permitAll` genérico** (`/**`) para "facilitar" a UI: manter só os 4 caminhos de docs (já é assim).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Agregar specs OpenAPI de 5 serviços | Gerador/merger de specs ou página HTML própria | springdoc `swagger-ui.urls` + rotas `SetPath` | Spike: funciona sem código; o merge em um único JSON perderia o dropdown por serviço |
| Mapear header ↔ message attribute SQS | `SendMessageRequest`/`MessageAttributeValue` na mão | `MessageBuilder.setHeader` + `SqsOperations.send(queue, Message)` e `@Header` | `SqsHeaderMapper` já faz o mapeamento nos dois sentidos |
| Padrão de log com MDC | `logback-spring.xml` por serviço | `logging.pattern.correlation` no `application.yml` | Uma linha por serviço, comportamento provado; XML só se precisar de appender customizado |
| Resumo de testes no CI | Parser de XML em Python/Node | `grep -h`/`awk` sobre `target/*-reports/TEST-*.xml` + `$GITHUB_STEP_SUMMARY`; relatórios em `upload-artifact` | Sem dependência de terceiros; ambiente não tem Python |
| Desambiguar `@Recover` | Renomear tipos/retornos artificialmente | `@Retryable(recover = "nomeDoMetodo")` | O código do Spring Retry 2.0.12 filtra por nome quando `recover` está preenchido |
| Template de ADR | Formato próprio | MADR 4.0 traduzido (front matter `status`, `date`, `decision-makers`) | Formato conhecido por avaliadores; D-108 já o fixa |
| Stub HTTP para o IT do Gateway | WireMock/MockServer | `com.sun.net.httpserver.HttpServer` (como `DownstreamStubServer`) | Zero dependência, padrão já estabelecido no projeto |

**Key insight:** esta fase é quase toda "ligar peças que o framework já tem" — o risco está nos detalhes de integração (case de header, lista de rotas, linhas de log inexistentes), não em bibliotecas faltando.

## Runtime State Inventory

> A fase **não** é rename/refactor, mas adiciona colunas e muda o contrato de transporte. Inventário do que persiste em runtime:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `orders` e `outbox_event` (order-service) e `outbox_event` (inventory-service) passam a ter `correlation_id`; volumes `postgres-data` persistentes podem ter linhas antigas (inclusive `outbox_event` ainda não publicado) | **Migração de schema** (V4 order, V5 inventory) com coluna **nullable** sem default; **código** trata `null` (relay não envia atributo; listener gera ID). Sem backfill de dados. |
| Live service config | Filas/DLQs do LocalStack são criadas pelos init hooks; **nenhuma fila ou atributo precisa ser provisionado** para um message attribute | Nenhuma |
| OS-registered state | Nenhum | None — verificado: não há tarefas/serviços do SO neste projeto |
| Secrets/env vars | Novo secret do GitHub `LOCALSTACK_AUTH_TOKEN` (cadastro manual no repositório `Lucasllo/B2B_commercial`); nenhum secret novo local | Documentar no README; planejar passo `human-action` para cadastrar o secret |
| Build artifacts | `target/` já ignorado; `springdoc` entra no `gateway` → jar gordo maior; nenhum artefato instalado fica obsoleto | Nenhuma |

## Common Pitfalls

### Pitfall 1: Nenhum serviço loga no caminho feliz — o `grep` do smoke não acharia nada
**What goes wrong:** `grep` do Correlation-ID em `docker compose logs` do inventory/notification/order devolve vazio mesmo com o MDC certo, porque não há linha de log INFO nesses caminhos (verificado: os `log.*` existentes em `*/src/main` são só WARN/ERROR de anomalia e dois INFO de duplicata).
**Why it happens:** MDC só decora linhas que alguém escreve.
**How to avoid:** acrescentar (a) INFO de acesso no filtro HTTP de cada serviço (`METHOD path -> status`, sem query string e sem `Authorization`); (b) INFO "Mensagem recebida da fila X" em cada listener; (c) INFO "Evento outbox publicado eventId eventType fila" no relay; (d) INFO de transição no `OrderSagaService` (confirmou/cancelou `orderId`) e no `NotificationService.record` (`eventType`/`entityId` gravado). Nunca logar payload (T-05-03) nem token.
**Warning signs:** o teste `OutputCapture` do passo seguinte procura o ID e não encontra.

### Pitfall 2: Header duplicado no pedido encaminhado pelo Gateway
**What goes wrong:** o downstream recebe `X-Correlation-Id=[abc-123, abc-123]`.
**Why it happens:** [VERIFIED: spike] o wrapper que só faz `LinkedHashSet.add(HEADER)` em `getHeaderNames()` não deduplica `X-Correlation-Id` (do cliente) com a mesma chave em outro case; com `TreeSet(String.CASE_INSENSITIVE_ORDER)` o downstream recebe um valor só (`X-correlation-id=[abc-123]`).
**How to avoid:** usar o conjunto case-insensitive e cobrir no IT do Gateway os três casos (sem header, header válido em maiúsculas e em minúsculas, header inválido).
**Warning signs:** stub do IT enxerga lista de 2 valores.

### Pitfall 3: Header duplicado na resposta
**What goes wrong:** cliente recebe `X-Correlation-Id` do Gateway e `x-correlation-id` do serviço (verificado no spike com stub que ecoa).
**How to avoid:** os 5 filtros de serviço **não** escrevem o header de resposta (D-93 não exige). O IT do Gateway usa um stub que ecoa o header para travar essa regressão **ou** o filtro do Gateway remove/define o header depois do `chain.doFilter` — a primeira opção é mais simples.

### Pitfall 4: IT do Gateway não consegue trocar o URI das rotas
**What goes wrong:** `--spring.cloud.gateway.server.webmvc.routes[0].uri=…` faz o contexto **falhar** (`IllegalArgumentException: Predicate must not be null`) — [VERIFIED: spike] uma lista definida por uma segunda fonte substitui a lista inteira.
**How to avoid:** placeholders `${orderflow.gateway.upstream.<svc>:http://<svc>:<porta>}` nos `uri` do `application.yml` (defaults mantêm o docker-compose intacto); o IT define `orderflow.gateway.upstream.*` via `@DynamicPropertySource` apontando para o stub JDK. Assim o IT exercita a tabela de rotas **real** (predicados + `StripPrefix` + `SetPath`).

### Pitfall 5: Testes unitários e ITs existentes quebram com as assinaturas novas
**What goes wrong:** `OutboxRelayTest` (order e inventory) usa `sqsOperations.send(eq(QUEUE_NAME), eq(payload))` e `verify(...).send(anyString(), any())` — passam a falhar quando o relay chama `send(String, Message)`; `NotificationEventListenerTest` chama `onMessage(String)` diretamente; vários ITs enviam mensagens à fila sem atributo.
**How to avoid:** atualizar os dois `OutboxRelayTest` primeiro (captor de `Message<String>`); manter um construtor/overload de conveniência nos listeners para os testes; garantir que o caminho `correlationId == null` seja coberto (Pattern 4).

### Pitfall 6: `-pl e2e-tests -am verify` duplica ITs com LocalStack
**What goes wrong:** `-am` inclui `order-service` e `inventory-service`, que rodam seus próprios ITs (cada um sobe um LocalStack) antes do e2e — dobra o tempo e a exposição à regra de sessão.
**How to avoid:** no job e2e: `./mvnw -B -ntp -pl order-service,inventory-service -am install -DskipTests` e depois `./mvnw -B -ntp -pl e2e-tests verify` (o `classifier=exec` mantém o jar plano como artefato principal, `pom.xml:117-140`).

### Pitfall 7: Runner `ubuntu-latest` vai mudar de versão no meio da fase
**What goes wrong:** `ubuntu-latest` migra gradualmente de 24.04 para 26.04 entre 2026-10-19 e 2026-11-19 [CITED: devopsboys.com/blog/github-actions-ubuntu-26-migration-2026 — fonte secundária, confirmar no changelog do GitHub]. Imagem `ubuntu-24.04` em 2026-09-20 traz Docker 28.0.4, Java 21.0.12, Maven 3.9.16 [CITED: github.com/actions/runner-images Ubuntu2404-Readme.md].
**How to avoid:** `runs-on: ubuntu-24.04` explícito (nunca `ubuntu-latest`). Se algum dia a imagem trouxer Docker 29, o Testcontainers 1.21.4 efetivo negocia a API 1.44 [CITED: PRs de bump "1.21.4 for Docker 29 compatibility" encontradas por busca; corrige o erro `client version 1.32 is too old`] e funciona localmente contra Docker Server 29.8.0 (`docker info` desta máquina). Rede de segurança: `src/test/resources/docker-java.properties` com `api.version=1.44` [CITED: coffeesprout.nl, tratado lá como paliativo].

### Pitfall 8: PR de fork não tem o secret
**What goes wrong:** `LOCALSTACK_AUTH_TOKEN` vazio em PRs de fork [CITED: docs.github.com using-secrets: "with the exception of GITHUB_TOKEN, secrets are not passed to the runner when a workflow is triggered from a forked repository"].
**How to avoid:** não usar `pull_request_target` (nunca expor secret a código de PR). `LocalStackTestSupport.resolveAuthToken()` já falha com `IllegalStateException` citando só o nome da variável. Acrescentar um passo inicial `if [ -z "$LOCALSTACK_AUTH_TOKEN" ]; then echo "::error::LOCALSTACK_AUTH_TOKEN ausente …"; exit 1; fi` para a mensagem aparecer na UI em vez de dentro do stack trace do Maven.

### Pitfall 9: Push + PR do mesmo repositório rodam o CI duas vezes em paralelo
**What goes wrong:** com D-102 (`push` em qualquer branch + `pull_request`), um PR de branch do próprio repo dispara dois runs simultâneos, e ambos sobem LocalStack com o mesmo token — exatamente a situação que D-101 quer evitar.
**How to avoid (escolha do planejador):** (i) `if:` nos jobs com LocalStack: `github.event_name == 'push' || github.event.pull_request.head.repo.full_name != github.repository` (PR de mesmo repo é coberto pelo run de push na mesma SHA); ou (ii) grupo de `concurrency` dedicado — cuidado: por padrão só um job **pendente** é mantido no grupo e os demais são **cancelados**; `queue: max` permite até 100 enfileirados [CITED: docs.github.com using-concurrency; sintaxe a validar com `actionlint` antes do push]. `concurrency` no nível do job, aplicado a uma **matriz**, faz as pernas disputarem o mesmo grupo — prefira (i), ou um único job `localstack-suite` com passos sequenciais se quiser (ii).

### Pitfall 10: `@Recover` ambíguo (WR-02) e comportamento real diferente do documentado
**What goes wrong:** `shipAll` e `releaseAll` têm a mesma assinatura e retorno `void`; o Spring Retry só distingue por tipo de exceção/args/retorno, então `shipAll` resolve `recoverReleaseAllInconsistentBook` e o WARN de `recoverShipAllInconsistentBook` nunca sai (já descrito no Javadoc de `InventoryService.java:551-557` e no review).
**How to avoid:** `@Retryable(…, recover = "recoverShipAll")` em `shipAll` e `recover = "recoverReleaseAll"` em `releaseAll`, e renomear os dois pares para que cada nome tenha as duas sobrecargas (`DataAccessException` e `IllegalStateException`). [VERIFIED: spring-retry 2.0.12 `RecoverAnnotationRecoveryHandler.java:117,145,196` — com `recover` preenchido só métodos com esse nome entram na busca]. Teste: IT com `OutputCaptureExtension` provando que a anomalia de `ShipStock` emite o WARN do recover correto (hoje não emite).

### Pitfall 11: WR-01 — `InvalidSagaMessageException` de `ShipStock` é descartada com ack
**What goes wrong:** `ReservationCommandListener.onMessage` captura qualquer `InvalidSagaMessageException`, loga WARN e retorna; para `ShipStock` o pedido já está `SHIPPED` e a baixa se perde sem DLQ.
**How to avoid:** o parser já sabe o `eventType` antes de validar os campos (`SagaCommandParser.parse`, linhas do `if (ShipStockCommand.EVENT_TYPE.equals(eventType))`). Introduzir `InvalidShipStockException extends InvalidSagaMessageException` (ou um campo `eventType` na exceção), lançada de `parseShipStock`; no listener, `catch (InvalidShipStockException e) { log.error("ShipStock invalido …"); throw e; }` **antes** do `catch (InvalidSagaMessageException)`. A exceção propagada deixa o SQS reentregar até a DLQ (`maxReceiveCount=3` no init hook `02-create-order-saga-resources.sh`). Testes: unitário do parser (qual exceção sai) + do listener (relança) + IT em `ShipStockConsumptionIT` (mensagem com `quantity` acima do teto vai à DLQ e a baixa NÃO é feita em silêncio). Um teste de contrato amarrando `SagaCommandParser.MAX_ITEMS_PER_ORDER`/`MAX_QUANTITY_PER_ITEM` aos limites de `CreateOrderRequest`/`OrderItemRequest` (módulos diferentes → o teste fica no e2e-tests, que enxerga os dois) é recomendado pelo review mas opcional.

### Pitfall 12: WR-03 — evento `ORDER_*` inválido some sem `orderId`
**How to avoid:** em `NotificationService.record`, antes de validar, extrair `orderId` do JSON de forma tolerante (campo texto que case com UUID, senão `"?"`) e passá-lo à `InvalidNotificationEventException` (novo construtor) para o `log.warn` do `NotificationEventListener` incluir `orderId=` e `eventType=` (ambos sanitizados por `sanitizeForLog`, que já existe em `NotificationService.java:270`). Teste unitário em `NotificationServiceTest` + `NotificationEventListenerTest` (`OutputCapture` vê o `orderId`).

## Code Examples

### Workflow CI (esqueleto para o planejador — validar com actionlint)

```yaml
# .github/workflows/ci.yml  — Source: D-100…D-104 + achados desta pesquisa
name: CI
on:
  push:
  pull_request:
permissions:
  contents: read
env:
  MAVEN_ARGS: -B -ntp
jobs:
  sem-localstack:                     # auth/catalog/gateway: só Postgres Testcontainers ou nenhum container → paralelo
    runs-on: ubuntu-24.04
    strategy:
      fail-fast: false
      matrix:
        module: [auth-service, catalog-service, gateway]
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -pl ${{ matrix.module }} -am verify
      - if: always()
        uses: actions/upload-artifact@v7
        with:
          name: reports-${{ matrix.module }}
          path: |
            ${{ matrix.module }}/target/surefire-reports
            ${{ matrix.module }}/target/failsafe-reports
          if-no-files-found: ignore
      - if: always()
        run: ./scripts/ci-summary.sh ${{ matrix.module }} >> "$GITHUB_STEP_SUMMARY"   # grep/awk nos TEST-*.xml

  com-localstack:                     # sessão única: uma perna por vez
    runs-on: ubuntu-24.04
    if: github.event_name == 'push' || github.event.pull_request.head.repo.full_name != github.repository
    strategy:
      max-parallel: 1
      fail-fast: false
      matrix:
        module: [inventory-service, order-service, notification-service]
    env:
      LOCALSTACK_AUTH_TOKEN: ${{ secrets.LOCALSTACK_AUTH_TOKEN }}
    steps:
      - run: |
          if [ -z "$LOCALSTACK_AUTH_TOKEN" ]; then
            echo "::error::Secret LOCALSTACK_AUTH_TOKEN ausente (PR de fork ou repositório sem o secret). Veja a seção 'Para avaliadores' do README."
            exit 1
          fi
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -pl ${{ matrix.module }} -am verify
      # + upload-artifact/summary iguais ao job acima

  e2e:
    needs: com-localstack             # só roda depois das três pernas; falha se qualquer uma falhar
    runs-on: ubuntu-24.04
    env:
      LOCALSTACK_AUTH_TOKEN: ${{ secrets.LOCALSTACK_AUTH_TOKEN }}
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -pl order-service,inventory-service -am install -DskipTests    # Pitfall 6
      - run: ./mvnw -pl e2e-tests verify
```
`mvnw` tem modo `100755` no git e `.gitattributes` força LF em `mvnw` e `*.sh` [VERIFIED: `git ls-files -s mvnw` → `100755`; `.gitattributes`] — os init hooks copiados para o LocalStack não sofrem de CRLF no runner Linux. Com `fail-fast: false`, uma perna vermelha não oculta as outras; o job `e2e` fica `skipped` se `com-localstack` falhar (visível no run).

### Smoke de Correlation-ID (estilo `smoke-order-lifecycle.sh`)

```bash
# scripts/smoke-correlation-id.sh — esqueleto
CID="smoke-$(date +%s)-$RANDOM"                       # casa [A-Za-z0-9-]{1,64}
… login, cria produto/estoque/empresa como nos outros smokes …
do_request -X POST "${GATEWAY_URL}/api/orders" -H "X-Correlation-Id: ${CID}" \
    -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d "$body"
# confere que o Gateway devolveu o MESMO ID (um único valor no header)
# espera CONFIRMED + timeline (polling como no smoke-order-saga.sh)
for svc in gateway order-service inventory-service notification-service; do
  docker compose logs --no-color "$svc" | grep -F "[${CID}]" >/dev/null \
    || fail "ID ${CID} ausente nos logs de ${svc}"
done
echo "SMOKE OK correlation-id ${CID}"
```
Também provar o caminho do ID inválido: enviar `X-Correlation-Id: bad value!` e verificar que o ID devolvido é um UUID novo.

### MADR 4.0 traduzido (modelo de ADR)

```markdown
---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---
# 0002 — Transactional Outbox em vez de publicação direta no SQS

## Contexto e problema
… (2–4 frases; fase de origem: Fase 5; D-59/D-62 …)

## Fatores de decisão
…

## Alternativas consideradas
* Publicação direta após o commit (dual-write) — **rejeitada**: …
* Transactional Outbox com relay — **escolhida**
* CDC (Debezium) — **rejeitada**: …

## Decisão
Escolhida a alternativa "…" porque …

### Consequências
* Bom: …  * Ruim: … (ex.: outbox sem retenção, AR-05-02)

## Prós e contras das alternativas
…

## Mais informações
Fase de origem: Fase 5. Decisões: D-59, D-62 (05-CONTEXT.md). Código: `order-service/.../saga/outbox/OutboxWriter.java`.
```
Estrutura MADR 4.0.0 [CITED: adr.github.io/madr — front matter `status`/`date`/`decision-makers`/`consulted`/`informed`; seções Context and Problem Statement, Decision Drivers, Considered Options, Decision Outcome, Consequences, Confirmation, Pros and Cons of the Options, More Information].

### Lista indicativa de ADRs (nomes de arquivo `NNNN-kebab`; confirmar D-xx nos CONTEXT.md antigos)

| # | Título | Alternativa rejeitada obrigatória | Origem (conferir) |
|---|--------|-----------------------------------|-------------------|
| 0001 | Saga por orquestração em vez de coreografia | coreografia pura; motor de workflow (Camunda/Temporal/Step Functions) | Fase 5 |
| 0002 | Transactional Outbox em vez de publicação direta | dual-write após commit (a Fase 3 usou e declarou a perda, D-29/D-30); CDC | Fases 3→5 |
| 0003 | LocalStack em vez de AWS real | AWS real com deploy contínuo; mocks em memória | Fase 1/3 |
| 0004 | Sem service discovery nem config server | Eureka/Consul; Spring Cloud Config | Fase 1 (D-05: Gateway só roteia) |
| 0005 | JWT auto-emitido + validação local por JWKS | IdP externo (Keycloak); introspecção por chamada | Fase 1 (D-03) |
| 0006 | DynamoDB para o histórico (SQL e NoSQL lado a lado) | Postgres para tudo | Fase 3 (partição `entityId` na 6) |
| 0007 | Decisão de crédito serializada por empresa (`company_credit_lock` pessimista) | lock otimista; serializable isolation; advisory lock | Fase 4 |
| 0008 | Correlation-ID próprio em vez de tracing distribuído | Micrometer Tracing/OpenTelemetry + Zipkin | Fase 7 |
| 0009 | Spring Cloud Gateway Server WebMVC em vez do reativo | Gateway WebFlux | Fase 1 |
| 0010 | Falha fechada (503) sem Resilience4j | circuit breaker/retry nas chamadas síncronas (backlog) | Fase 4 |
| 0011 | Código duplicado por serviço em vez de módulo `common` | módulo Maven compartilhado | Fases 5 e 7 |

Só os D-xx citados em 07-CONTEXT.md, PROJECT.md e STATE.md foram confirmados nesta sessão (D-03, D-05, D-29/D-30, D-59, D-62 aparecem nesses arquivos); as demais referências devem ser lidas dos `0N-CONTEXT.md` pelo executor.

## Test Gap Audit (TEST-01 / TEST-02, D-105)

Contagem **real** de classes de teste unitário (`*Test.java` concretas; `AbstractIntegrationTest` casa com `*Test.java` mas é abstrata — por isso os números do CONTEXT "auth 2 / catalog 1 / …" incluem um falso positivo) [VERIFIED: listagem de `src/test` e `grep -c @Test`]:

| Serviço | Unit hoje | IT hoje | Lacunas candidatas (confirmar na matriz) |
|---------|-----------|---------|------------------------------------------|
| auth-service | `SeedPasswordHashTest` (2 testes) | `AuthControllerIT`(13), `CompanyControllerIT`(30), `JwksContractIT`(4), `OpenApiDocsIT`(4) | **Unit** de `CompanyService` (criação empresa+usuário, e-mail duplicado, atualização de limite), `TokenService` (claims `role`/`companyId`/`iss`/`exp`), `CompanyGuard` (comprador só na própria empresa) — nenhum tem teste unitário (grep em `*Test.java` vazio) |
| catalog-service | **0** | `ProductControllerIT`(28), `OpenApiDocsIT`(4) | **Unit** de `ProductService` (SKU único, transição ACTIVE/DISCONTINUED, preço) |
| inventory-service | `SagaCommandParserTest`(39), `OutboxRelayTest`(4) | 9 classes IT (concorrência, tombstone, ship, retry, contrato) | **Unit** de `Inventory` (`reserve`/`release`/`ship`/`availableQuantity`/limites) e `StockReservation` (marcas `released`/`shipped`, CHECK lógico); `OutboxWriter` |
| notification-service | `NotificationServiceTest`(19), `NotificationEventListenerTest`(2) | 6 classes IT | Poucas; WR-03 + atributo→MDC |
| order-service | 9 classes (`OrderDomainTest` 22, `SagaEventParserTest` 27, `OutboxRelayTest` 6, etc.) | 13+ classes IT | `CompanyCreditLocker` e `OrderDecisionService`/`OrderShipmentService` só por IT (regra→teste existe; decidir se basta); `OutboxWriter`; `CorrelationContext` |
| gateway | **0** | **0** | Filtro (unit) + IT de roteamento (D-106) |
| e2e-tests | — | 3 ITs | + `CorrelationIdE2EIT` (ver Validation Architecture) |

`@Disabled`/`Assumptions`/`@EnabledIf` não existem em nenhum teste [VERIFIED: grep em `*/src/test`] — "nenhum teste pulado em silêncio" já é verdade; manter como invariante (pode virar passo do `check` do CI: `grep -rE "@Disabled|assumeTrue" */src/test` deve falhar o build).

**Formato de `07-COVERAGE.md`:** a "COVERAGE.md" das fases 3–6 é, na verdade, um arquivo de **uma linha** sobre integração com API externa (193 bytes na Fase 6), **não** uma matriz regra→teste — [VERIFIED: leitura dos arquivos]. O formato da matriz precisa ser definido nesta fase (tabela: Serviço | Regra central | Teste unitário | Teste de integração | Status), ao contrário do que o CONTEXT sugere ("no estilo do COVERAGE.md da Fase 6").

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Spring Cloud Gateway reativo + springdoc WebFlux | Gateway Server WebMVC + springdoc WebMVC UI | Spring Cloud 2025.0.0 | A Swagger UI agregada roda no mesmo modelo servlet dos serviços (provado) |
| `LOCALSTACK_AUTH_TOKEN` opcional | Obrigatório mesmo no Hobby (imagem única) | 2026-03-23 [CITED: CLAUDE.md + localstack.cloud] | Secret no CI e falha visível (D-103) |
| `docker-compose` v1 | `docker compose` V2 | — | Já usado |
| Testcontainers 1.20/1.21.3 contra Docker 29 | 1.21.4 negocia API 1.44 | 1.21.4 (Dez/2025) | Já efetivo no projeto via BOM do Boot |
| `ubuntu-latest` = 24.04 | `ubuntu-latest` → 26.04 gradualmente (2026-10-19 a 2026-11-19) | out–nov/2026 | Pinar `ubuntu-24.04` |

**Deprecated/outdated:**
- `testcontainers.version=1.20.4` em `pom.xml:43`: o valor não é o efetivo (BOM do Boot vence). Corrigir o comentário/valor no mesmo plano do CI para não enganar o avaliador, ou remover a propriedade e o import redundante (decisão do planejador; verificar com `dependency:tree`).
- Documentação do Spring Cloud AWS 3.4 sobre prefixo `Sqs_MA_`: não corresponde ao código v3.4.2.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `server.url=/api` (relativo) é resolvido pela Swagger UI contra a origem da página e o "Try it out" chega ao Gateway | Pattern 5 | Try it out falha; fallback: URL absoluta via `ORDERFLOW_OPENAPI_SERVER_URL=http://localhost:8080/api` (conforme D-90 literal). Validar no UAT manual |
| A2 | LocalStack Hobby só admite uma sessão ativa por token, também no CI | Summary / Pitfall 9 | Se várias sessões forem permitidas, a sequência força CI mais lento do que o necessário (custo de tempo, não de correção) |
| A3 | Um token Hobby pode ser usado como secret de repositório no Actions (a doc fala em "CI Auth Token" de workspace) | Open Question 2 | CI falha na subida do LocalStack por token inválido; resolver gerando o token certo na conta |
| A4 | `concurrency: { queue: max }` existe na sintaxe do GitHub Actions hoje | Pitfall 9 | Workflow inválido; por isso a recomendação padrão é `if:` sem `concurrency` |
| A5 | Testcontainers 1.21.4 funciona no Docker 29 do runner caso a imagem 24.04 troque de 28.0.4 para 29 | Pitfall 7 | CI vermelho por `client version 1.32 is too old`; mitigação: `docker-java.properties api.version=1.44` |
| A6 | `MessageBuilder.setHeader(name, null)` remove o header (relay não envia atributo para linhas legadas) | Pattern 4 | Atributo com string vazia "null"; cobrir com teste do relay com `correlationId == null` |
| A7 | Limites de message attribute do SQS (nome com alfanuméricos/`_-.`, ≤256, sem prefixo `aws.`/`amazon.`) aceitam `correlationId` | Pattern 4 | Nenhum (nome é seguro); ITs com LocalStack provam o envio |
| A8 | Tags de ação `checkout@v7`, `setup-java@v6`, `upload-artifact@v7` ainda são as correntes na data da implementação | Standard Stack | Workflow usa versão antiga (funciona, aviso de depreciação); conferir antes de commitar |
| A9 | `rhysd/actionlint` existe como imagem Docker utilizável | Standard Stack | Sem lint local; validar só no primeiro push |
| A10 | `@Hidden` no `JwksController` remove a rota do spec | Pattern 5 | Rota aparece no spec e dá 404 pelo Gateway; teste do `OpenApiDocsIT` do auth-service pode afirmar a ausência |
| A11 | `ubuntu-latest` migra para 26.04 entre 2026-10-19 e 2026-11-19 | Pitfall 7 | Nenhum se já pinarmos `ubuntu-24.04` (recomendado de qualquer forma) |
| A12 | O repositório `Lucasllo/B2B_commercial` é público (runners de 4 vCPU/16 GB); se for privado, runners têm menos recursos | Environment | Tempo de CI maior; sem impacto de correção |

## Open Questions

1. **A exclusividade da sessão LocalStack vale no CI? (D-101 pediu confirmação)**
   - What we know: docs de pricing/auth-token/ci-cd silenciam sobre sessões concorrentes; Hobby = "1 personal sandbox", CI permitido, uso comercial proibido. O STATE.md registra a falha local (exit 126) com a stack do compose ocupando o token.
   - What's unclear: se é limite por token, por IP ou outro fator. Tentei confirmar localmente subindo dois containers com o mesmo token, mas o ambiente bloqueia ler `.env` (guarda de segredos, corretamente) — não contornei.
   - Recommendation: manter **sequencial** (travado). Teste opcional para o usuário (2 min): `docker run -d -e LOCALSTACK_AUTH_TOKEN=… -p 14566:4566 localstack/localstack:2026.08.3` duas vezes (portas diferentes) e ver se o segundo sai. Se ambos ficarem saudáveis, trocar `max-parallel: 1` por paralelo.
2. **Qual token usar no Actions: o de desenvolvedor da conta Hobby serve?**
   - Docs distinguem *Developer* (por usuário) e *CI Auth Token* (workspace); não dizem qual o Hobby oferece. D-103/README já tratam o secret como o mesmo token do `.env`.
   - Recommendation: passo `human-action` no plano do CI: cadastrar o secret e disparar o primeiro push; se a subida falhar por licença, gerar o CI token no workspace.
3. **`rotas /docs/<svc>/...` vs `/api/<svc>/...` do D-87**: recomendei rotas dedicadas (Claude's Discretion). O planejador confirma.
4. **Se o `testcontainers.version` morto deve ser removido** (ver State of the Art). Baixo risco.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 21 | build/testes | ✓ | 21.0.10 (Oracle) — `JAVA_HOME` não definido, `mvnw` funciona | — |
| Maven Wrapper | build | ✓ | 3.9.9 | — |
| Docker daemon | Testcontainers, smoke | ✓ | Server 29.8.0 (API 1.56, min 1.40); nenhum container rodando | — |
| `LOCALSTACK_AUTH_TOKEN` | ITs com LocalStack, compose | presente no `.env` (não lido — guarda de segredos); `.env.example` define a variável | — | exportar variável de ambiente |
| `gh` CLI | observar CI/rodar `gh run` | ✗ | — | UI do GitHub; instalar `gh` é opcional |
| Python/`actionlint` | lint do workflow | ✗ | — | `docker run rhysd/actionlint` (pinar tag) |
| Remote GitHub | CI | ✓ | `B2B_commercial` → `github.com/Lucasllo/B2B_commercial` | — |
| Secret de repo `LOCALSTACK_AUTH_TOKEN` | CI | ✗ (precisa ser cadastrado no GitHub) | — | **Bloqueante para o CI verde**: passo humano |

**Missing dependencies with no fallback:** secret do GitHub (ação humana).
**Missing dependencies with fallback:** `gh`, `actionlint` (Docker).
*Regra do ambiente a lembrar no plano:* derrubar a stack do compose (`docker compose down`) antes de rodar ITs com LocalStack localmente.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + AssertJ + Awaitility + Spring Boot Test (MockMvc / RANDOM_PORT) + Testcontainers 1.21.4 (efetivo) |
| Config file | `pom.xml` raiz (surefire `*Test`, failsafe `*IT` via `pluginManagement`); `application-test.yml` por serviço |
| Quick run command | `./mvnw -B -pl <svc> test` (só unitários, sem Docker) |
| Full suite command | `./mvnw -B -pl <svc> verify` (por serviço) e `./mvnw -B -pl order-service,inventory-service -am install -DskipTests && ./mvnw -B -pl e2e-tests verify` (e2e) — derrubar o compose antes |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| QUAL-01 | Spec de cada serviço tem `servers[0].url`, tags, `bearerAuth`, enum de status, schema de erro; caminhos de docs públicos e negócio protegido | IT (MockMvc) | `./mvnw -B -pl <svc> verify -Dit.test=OpenApiDocsIT` | ✅ (estender os 5 existentes) |
| QUAL-01 | Todo `operation` tem `summary` e respostas de erro; `JwksController` ausente do spec | IT | idem (`OpenApiContractIT` novo por serviço, ou assert no existente) | ❌ Wave 0 |
| QUAL-01 | Gateway serve `swagger-config` com 5 `urls` e `/docs/<svc>/v3/api-docs` chega ao upstream como `/v3/api-docs` | IT (RANDOM_PORT + stub JDK) | `./mvnw -B -pl gateway verify` | ❌ Wave 0 |
| QUAL-01 | Try it out pelo Gateway funciona na UI real | manual (UAT) | abrir `http://localhost:8080/swagger-ui.html`, login, Authorize, executar `POST /orders` | manual-only: JS do Swagger UI não é testável sem navegador |
| QUAL-02 | Filtro do Gateway gera, reaproveita (maiúscula e minúscula) e rejeita valor inválido; header único na resposta e no pedido encaminhado | unit + IT | `./mvnw -B -pl gateway verify` | ❌ Wave 0 |
| QUAL-02 | Filtro de cada serviço: lê header, MDC durante a requisição, limpa depois, gera sem header, não ecoa header | unit/IT por serviço | `./mvnw -B -pl <svc> test` | ❌ Wave 0 (5x) |
| QUAL-02 | `OutboxWriter` grava `correlation_id` do MDC; `null` sem MDC | IT (Postgres) | `./mvnw -B -pl order-service verify -Dit.test=OutboxCorrelationIT` (nome sugerido) | ❌ Wave 0 (2 serviços) |
| QUAL-02 | `OutboxRelay` envia atributo `correlationId`; sem ID, não envia | unit (Mockito, captor `Message<String>`) | `./mvnw -B -pl order-service test -Dtest=OutboxRelayTest` | ✅ existe — **precisa ser atualizado** (Pitfall 5) |
| QUAL-02 | Atributo SQS real → `@Header` → MDC no listener (3 serviços) e linhas de log com `[cid]` | IT (LocalStack + `OutputCaptureExtension`) | `./mvnw -B -pl inventory-service verify -Dit.test=…` | ❌ Wave 0 (3x) |
| QUAL-02 | Mesmo ID em order e inventory no E2E (duas contextos no mesmo JVM → `OutputCapture` vê os dois) | E2E | `./mvnw -B -pl e2e-tests verify -Dit.test=CorrelationIdE2EIT` | ❌ Wave 0 |
| QUAL-02 | `SagaTimeoutJob`/`expireReservation` herda `orders.correlation_id` em `ReleaseStock` e `ORDER_CANCELLED` | IT (LocalStack/Postgres, estende `SagaTimeoutIT`) | `./mvnw -B -pl order-service verify -Dit.test=SagaTimeoutIT` | ✅ estender |
| QUAL-02 | `RestClient` interceptor propaga o ID a catalog/auth (stub enxerga o header) | unit/IT com `DownstreamStubServer` | `./mvnw -B -pl order-service verify` | ❌ Wave 0 |
| QUAL-02 | Gateway→order→inventory→notification na stack real | smoke | `bash scripts/smoke-correlation-id.sh` (stack de pé) | ❌ Wave 0 |
| INFRA-02 | Workflow é sintaticamente válido | lint | `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:<tag>` | ❌ Wave 0 |
| INFRA-02 | Push dispara o CI; falha de teste deixa o run vermelho e identifica o serviço | manual (observação) | push de branch com teste quebrado deliberado, conferir o run, reverter | manual-only: depende do GitHub; **checkpoint humano** |
| INFRA-02 | Nenhum teste desabilitado em silêncio | script | `! grep -rEn "@Disabled|assumeTrue|@EnabledIf|@DisabledIf" */src/test --include=*.java` | ❌ Wave 0 (passo do CI ou script) |
| INFRA-03 | Cada ADR tem seções e uma alternativa rejeitada; índice lista todos; links resolvem | script | `bash scripts/check-adrs.sh` | ❌ Wave 0 |
| TEST-01 | Cada serviço tem unitários nas regras da matriz | unit | `./mvnw -B -pl <svc> test` | ❌ lacunas auth/catalog/inventory/gateway |
| TEST-02 | Cada serviço tem ≥1 IT com dependência real | IT | `./mvnw -B -pl <svc> verify` | ✅ domínio; ❌ gateway (IT com stub, sem container) |
| WR-01 | `ShipStock` inválido vai à DLQ, não é descartado | unit (parser/listener) + IT (LocalStack) | `./mvnw -B -pl inventory-service verify -Dit.test=ShipStockConsumptionIT` | ✅ estender |
| WR-02 | `shipAll` anômalo emite o WARN do `recoverShipAll`; `releaseAll` o do seu recover | IT + `OutputCaptureExtension` | idem | ✅ estender |
| WR-03 | Evento `ORDER_*` inválido loga `orderId`/`eventType` | unit | `./mvnw -B -pl notification-service test` | ✅ estender `NotificationServiceTest`/`NotificationEventListenerTest` |

### Sampling Rate
- **Per task commit:** `./mvnw -B -pl <módulo tocado> test` (< 30 s, sem Docker); em tarefas de outbox/listener, `-Dit.test=<IT específico>` com o compose derrubado.
- **Per wave merge:** `./mvnw -B -pl <módulos da wave> verify` (módulos com LocalStack em sequência).
- **Phase gate:** todos os serviços `verify` + e2e + `scripts/smoke-correlation-id.sh` + `scripts/check-adrs.sh` + CI verde no GitHub antes de `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `gateway/pom.xml` — `spring-boot-starter-test`, springdoc, failsafe; `gateway/src/test/.../CorrelationIdFilterTest`, `GatewayRoutingIT`, stub JDK (copiar `DownstreamStubServer` simplificado)
- [ ] `<svc>/observability/CorrelationContext` + filtro + teste (5 serviços)
- [ ] Atualizar os dois `OutboxRelayTest` para `send(String, Message)` antes de mexer no relay
- [ ] ITs de atributo SQS→MDC (inventory, order, notification) com `OutputCaptureExtension`
- [ ] `scripts/smoke-correlation-id.sh`, `scripts/check-adrs.sh`, `scripts/ci-summary.sh`
- [ ] Unitários: `CompanyService`, `TokenService`, `CompanyGuard`, `ProductService`, `Inventory`, `StockReservation`, `OutboxWriter` (order e inventory)
- [ ] `07-COVERAGE.md` (matriz regra→teste) — **primeira tarefa da frente de testes**, para decidir o que realmente falta antes de escrever testes

## Security Domain

`security_enforcement` está ligado (`config.json`, ASVS nível 1, bloqueio em `high`).

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | não muda | JWT existente; a UI Swagger só cola token, não guarda credencial |
| V3 Session Management | não | API stateless |
| V4 Access Control | sim (superfície de docs) | `permitAll` restrito a 4 padrões de caminho (já existe); teste-guarda `businessEndpointStillRequiresToken…` já existe |
| V5 Input Validation | sim | regex `[A-Za-z0-9-]{1,64}` para `X-Correlation-Id` no Gateway **e** para o atributo SQS (fronteira de confiança) |
| V6 Cryptography | não | nada novo |
| V7 Error Handling and Logging | sim | sem log injection; não logar `Authorization`, payload de mensagem, tokens; query string fora do log de acesso |
| V14 Configuration | sim (CI) | `permissions: contents: read`, sem `pull_request_target`, ações de primeira parte, secret só por `secrets.*`, imagens e runner pinados |

### Known Threat Patterns for esta fase

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Log injection/forging via `X-Correlation-Id` (CRLF, delimitadores) | Tampering | Validar regex e regenerar (D-92); mesma validação no listener para o atributo SQS |
| Header duplicado/ambíguo (`abc, abc`) em rastreamento | Repudiation/Tampering | Wrapper case-insensitive (Pitfall 2); teste no IT do Gateway |
| Spec/Swagger UI expõe superfície da API sem token | Information Disclosure | Já aceito para ferramenta local (README); Gateway publica em `0.0.0.0:8080` (`docker-compose.yml:197`) enquanto serviços usam `127.0.0.1` — **registrar** no ADR/README que o Gateway já era público por desenho, ou ligar a `127.0.0.1:8080` se a demo for só local |
| Vazamento do token LocalStack em log de CI | Information Disclosure | `LocalStackTestSupport` nunca imprime o valor; passar o secret só por `env:` do job; não usar `set -x` nem `echo` do secret |
| Execução de código não confiável com acesso a secret (PR de fork) | Elevation of Privilege | Gatilho `pull_request` (sem secret em fork), nunca `pull_request_target`; falha explícita (D-103) |
| Atributo SQS forjado por quem consegue postar na fila | Spoofing | A fila já é fronteira de confiança (STOCK_RESERVED_ITEMS_CHECK); ID só afeta log, não decisão de negócio; validar formato |
| Dependência de Action comprometida | Tampering | Apenas `actions/*`; fixar major (ou SHA) |

Nota de processo: STATE.md registra que `06-SECURITY.md` ainda não existe (`/gsd-secure-phase 6` pendente) — fora do escopo de pesquisa, mas relevante para o gate de fechamento.

## Recommended Plan Slicing (para o planejador)

| Plano | Escopo | Depende de | Observação de conflito de arquivos |
|-------|--------|------------|-----------------------------------|
| 07-01 | **Gateway:** deps, filtro, rotas parametrizadas + `/docs/*`, springdoc `urls`, logging pattern, unit + IT; `estudos/29` | — | só toca `gateway/` |
| 07-02 | **Correlation-ID nos serviços (HTTP):** `CorrelationContext` + filtro + log de acesso + `logging.pattern.correlation` ×5; interceptor do `RestClient` (D-96) | — | toca `application.yml` e `config/` de cada serviço |
| 07-03 | **Correlation-ID por mensageria:** migrações V4/V5, `OutboxEvent`/`OutboxWriter`, `OutboxRelay` (+ testes atualizados), listeners ×3, `orders.correlation_id`, `SagaTimeoutJob`, logs INFO de caminho feliz, E2E `CorrelationIdE2EIT`, `estudos/27` | 07-02 | toca `saga/outbox/*`, listeners — **mesmo arquivo** que 07-05 (inventory listener/WR-01): sequenciar |
| 07-04 | **OpenAPI por serviço ×5:** `@Tag/@Operation/@Schema/@ApiResponse`, `ErrorResponse`, `servers`, `@Hidden` JWKS, enum, testes `OpenApiDocsIT` | 07-01 (para o smoke da UI) | toca `OpenApiConfig`/controllers/DTOs; **não** `SecurityConfig` (já pronto) |
| 07-05 | **WR-01/02/03 + auditoria de testes:** `07-COVERAGE.md`, unitários que faltam, correções com testes | 07-03 (listeners) | toca `ReservationCommandListener`, `InventoryService`, `NotificationService` |
| 07-06 | **CI:** `ci.yml`, `ci-summary.sh`, verificação "sem testes desabilitados", README do secret, correção do `testcontainers.version`; checkpoint humano (cadastrar secret, push, observar run, provar falha visível); `estudos/28` | 07-01…05 (suítes completas) | — |
| 07-07 | **ADRs + entrega:** `docs/adr/*`, `check-adrs.sh`, README "Para avaliadores", `docs/VISAO-GERAL.md`/`API.md`, `smoke-correlation-id.sh` | tudo | README/docs só aqui, para evitar conflito |

## Sources

### Primary (HIGH confidence)
- Código-fonte do repositório lido nesta sessão: `pom.xml`, `gateway/pom.xml`, `gateway/.../application.yml`, `docker-compose.yml`, 5 `SecurityConfig`/`OpenApiConfig`, `OpenApiDocsIT` (order), `OutboxWriter/Relay/Event` (order), listeners (3), `SagaTimeoutJob`, `OrderSagaService`, `InventoryService` (trechos), `SagaCommandParser`, `SqsMessagingConfig`, `ClientConfig`, `LocalStackTestSupport`, `e2e-tests/pom.xml`, `order-service/pom.xml`, Dockerfile, `.gitattributes`, `06-REVIEW.md`, `STATE.md`, `REQUIREMENTS.md`, `PROJECT.md` (Key Decisions)
- Spike local (cópia do gateway em diretório temporário, Boot 3.5.16 + springdoc 2.9.1): swagger-config, SetPath, filtro, duplicação de header, `logging.pattern.correlation` vs `logging.pattern.level`, falha ao sobrescrever `routes[0].uri`
- `raw.githubusercontent.com/awspring/spring-cloud-aws/v3.4.2/.../SqsHeaderMapper.java`, `SqsContainerOptions.java`, `MessagingOperations.java`
- `raw.githubusercontent.com/spring-projects/spring-retry/v2.0.12/.../RecoverAnnotationRecoveryHandler.java`, `Retryable.java`
- `./mvnw -o dependency:tree` (Testcontainers 1.21.4 efetivo); `docker info`/`docker version` (29.8.0)

### Secondary (MEDIUM confidence)
- docs.spring.io Spring Boot 3.5 logging (`logging.pattern.level` documentado; `logging.pattern.correlation` não documentado ali, mas funcional — provado no spike)
- docs.awspring.io Spring Cloud AWS 3.4 SQS (interceptors e `@Header`; texto sobre prefixo diverge do código)
- adr.github.io/madr (MADR 4.0.0); docs.github.com (secrets em fork; concurrency e `queue: max`); github.com/actions/setup-java, checkout, upload-artifact; github.com/actions/runner-images Ubuntu2404-Readme; docs.github.com github-hosted-runners (labels e recursos)
- localstack.cloud/pricing e docs.localstack.cloud (auth-token, ci-cd): CI permitido no Hobby, uso comercial proibido, sem informação de sessões concorrentes

### Tertiary (LOW confidence)
- github.com/springdoc/springdoc-openapi/issues/2506 (conflito com Gateway MVC "todos os métodos"; não corrigido na leitura; não reproduzido no spike)
- devopsboys.com (migração `ubuntu-latest` → 26.04), PRs de terceiros sobre Testcontainers 1.21.4 e Docker 29, coffeesprout.nl (`docker-java.properties`)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — nenhuma dependência nova; versões lidas do `pom.xml` e da árvore de dependências efetiva.
- Architecture (Gateway, filtro, MDC, outbox, SQS attribute): HIGH — spike executado e código-fonte do Spring Cloud AWS 3.4.2 lido; o único elo não executado é o ciclo completo order→SQS→inventory (será provado pelos ITs/E2E do plano).
- CI: MEDIUM — não executável localmente; regra de sessão LocalStack e tipo de token não confirmados.
- Pitfalls: HIGH para os verificados por spike/código (1–6, 10, 11); MEDIUM para os de CI (7–9).
- ADRs: HIGH para a estrutura MADR; MEDIUM para as referências D-xx (a conferir nos CONTEXT.md antigos).

**Research date:** 2026-10-01
**Valid until:** 2026-10-19 para o runner (`ubuntu-latest` começa a migrar); 30 dias para o restante.
