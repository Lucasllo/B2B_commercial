---
phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
plan: "01"
subsystem: orders
tags: [spring-boot, spring-security, oauth2-resource-server, jpa, restclient, pessimistic-locking, postgresql, flyway, testcontainers, virtual-threads]

# Dependency graph
requires:
  - phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
    provides: JWKS do auth-service, claims role/company_id/sub, GET /companies/{id}/credit-limit, convenção BigDecimal/NUMERIC(19,2), padrão de erro uniforme, Dockerfile multi-stage
  - phase: 02-cat-logo-e-estoque
    provides: GET /products/{id} do catalog-service (404 esconde DISCONTINUED de BUYER, D-24), padrão de resource server (SecurityConfig/GlobalExceptionHandler/TestJwt/AbstractIntegrationTest)
provides:
  - Módulo order-service completo no reactor Maven e nas seis imagens Docker (porta 8085, schema order)
  - "ORD-01: POST /orders — BUYER cria pedido validado item a item no catalog-service, snapshot gravado (productId/sku/name/unitPrice/quantity/subtotal), total com escala 2"
  - "ORD-02: decisão de crédito automática (APPROVED dentro do limite, PENDING_APPROVAL acima) sob trava por empresa (company_credit_lock, PESSIMISTIC_WRITE), com exposição calculada como SUM ao vivo sobre APPROVED/CONFIRMED/SHIPPED/DELIVERED"
  - "ORD-08/ORD-09: GET /orders/{id} — BUYER só vê pedidos da própria empresa (404 idêntico ao inexistente), SELLER_ADMIN vê qualquer um"
  - "Success Criteria 5 do ROADMAP comprovado por socket real: 10 pedidos simultâneos na fronteira de crédito nunca aprovam além do limite"
  - Suporte de teste reaproveitável pelos planos 04-02 a 04-04 (TestJwt com sub fixável, DownstreamStubServer, OrderTestInfrastructure, ConcurrentRequests)
affects: [04-02-endurecimento-da-criacao, 04-03-listagem, 04-04-aprovacao-rejeicao, 04-05-compose-gateway-smoke]

# Actuals (#2632)
actuals:
  tokens: 31537
  tasks: 2
  commits: 2
plan_head_before: f83ee1781daa7c6c7db38569b99df5b4eb354e73

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Orquestração não transacional (OrderCreationService, faz todas as chamadas HTTP) separada da decisão transacional (OrderService, nunca importa cliente HTTP) — D-41, evita segurar a trava/conexão durante I/O de rede"
    - "Trava por linha sentinela (company_credit_lock, PESSIMISTIC_WRITE) para serializar um invariante computado (soma de exposição) — nunca um saldo desnormalizado (D-37)"
    - "RestClient com timeout explícito de conexão e leitura via JdkClientHttpRequestFactory (não ClientHttpRequestFactories, depreciada na linha 3.4+) — mesmo HttpClient da JDK usado pelos testes de socket real"
    - "Stub HTTP da própria JDK (com.sun.net.httpserver.HttpServer, executor de thread virtual por requisição) em vez de MockRestServiceServer — exercita timeouts reais e repasse de header por socket, serve igualmente a MockMvc e a testes de concorrência"
    - "Visibilidade por empresa resolvida na camada de serviço (sellerView boolean), nunca via @PreAuthorize SpEL — o companyId dono só é conhecido após o fetch (Pattern 3)"

key-files:
  created:
    - order-service/pom.xml
    - order-service/Dockerfile
    - order-service/src/main/resources/application.yml
    - order-service/src/main/resources/db/migration/V1__init_order_schema.sql
    - order-service/src/main/java/com/orderflow/order/OrderServiceApplication.java
    - order-service/src/main/java/com/orderflow/order/config/SecurityConfig.java
    - order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java
    - order-service/src/main/java/com/orderflow/order/config/ClientProperties.java
    - order-service/src/main/java/com/orderflow/order/config/ClientConfig.java
    - order-service/src/main/java/com/orderflow/order/client/AuthServiceClient.java
    - order-service/src/main/java/com/orderflow/order/client/CatalogServiceClient.java
    - order-service/src/main/java/com/orderflow/order/client/AuthServiceUnavailableException.java
    - order-service/src/main/java/com/orderflow/order/client/CatalogServiceUnavailableException.java
    - order-service/src/main/java/com/orderflow/order/client/dto/CreditLimitResponse.java
    - order-service/src/main/java/com/orderflow/order/client/dto/CatalogProductResponse.java
    - order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLock.java
    - order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLockRepository.java
    - order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLocker.java
    - order-service/src/main/java/com/orderflow/order/credit/CreditPolicy.java
    - order-service/src/main/java/com/orderflow/order/order/Order.java
    - order-service/src/main/java/com/orderflow/order/order/OrderItem.java
    - order-service/src/main/java/com/orderflow/order/order/OrderStatus.java
    - order-service/src/main/java/com/orderflow/order/order/PricedItem.java
    - order-service/src/main/java/com/orderflow/order/order/OrderRepository.java
    - order-service/src/main/java/com/orderflow/order/order/OrderController.java
    - order-service/src/main/java/com/orderflow/order/order/OrderCreationService.java
    - order-service/src/main/java/com/orderflow/order/order/OrderService.java
    - order-service/src/main/java/com/orderflow/order/order/dto/CreateOrderRequest.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemRequest.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderResponse.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemResponse.java
    - order-service/src/main/java/com/orderflow/order/order/exception/OrderNotFoundException.java
    - order-service/src/main/java/com/orderflow/order/order/exception/InvalidOrderItemsException.java
    - order-service/src/test/resources/application-test.yml
    - order-service/src/test/java/com/orderflow/order/AbstractIntegrationTest.java
    - order-service/src/test/java/com/orderflow/order/support/TestJwt.java
    - order-service/src/test/java/com/orderflow/order/support/OrderTestInfrastructure.java
    - order-service/src/test/java/com/orderflow/order/support/DownstreamStubServer.java
    - order-service/src/test/java/com/orderflow/order/support/ConcurrentRequests.java
    - order-service/src/test/java/com/orderflow/order/OrderControllerIT.java
    - order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java
    - order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java
  modified:
    - pom.xml
    - auth-service/Dockerfile
    - gateway/Dockerfile
    - catalog-service/Dockerfile
    - inventory-service/Dockerfile
    - notification-service/Dockerfile

key-decisions:
  - "ORDER_RESPONSE_CONTRACT=id,companyId,status,total,createdBy,createdAt,decidedBy,decidedAt,reason,items[lineNumber,productId,sku,name,unitPrice,quantity,subtotal] — id primeiro (o smoke do plano 04-05 extrai o id do começo do corpo)"
  - "Porta 8085; schema order; default_schema do Hibernate gravado como '\"order\"' (aspas duplas dentro de aspas simples do YAML) — 'order' é palavra reservada do SQL, e o marcador nativo {h-schema} de CompanyCreditLockRepository.ensureExists herda a mesma forma entre aspas"
  - "Espera pela trava (company_credit_lock) sem lock_timeout, de propósito ilimitada — nada que segura a trava faz I/O de rede (D-41, 04-RESEARCH.md Assumption A2); revisitar se uma fase futura acrescentar trabalho lento dentro da mesma transação"
  - "Cláusula de bloqueio emitida pelo Hibernate/PostgreSQL para @Lock(PESSIMISTIC_WRITE) é 'for no key update', não 'for update' — comportamento padrão do dialeto Postgres do Hibernate 6.6 para uma entidade sem FK-alvo; ambas as formas são mutuamente exclusivas entre si no PostgreSQL, então a serialização exigida pelo Success Criteria 5 vale igualmente (comprovado pelos 11/11 testes, incluindo os três cenários de concorrência)"
  - "DownstreamStubServer (com.sun.net.httpserver.HttpServer da JDK) escolhido no lugar do MockRestServiceServer recomendado pela pesquisa da fase: aquele mock troca a fábrica de requisições do cliente e nunca exercitaria os timeouts configurados em ClientConfig nem o repasse do header Authorization por um socket real; o stub da JDK serve igualmente aos testes com MockMvc (Task 1) e aos de socket real (Task 2)"
  - "422 (não 409) para item de pedido inválido/indisponível — o pedido é bem formado mas referencia item que não pode ser vendido, sem conflito de estado (04-RESEARCH.md Assumption A1)"

requirements-completed: [ORD-01, ORD-02, ORD-08, ORD-09]

coverage:
  - id: D1
    description: "order-service entra no reactor Maven (módulo novo em pom.xml) e nas seis imagens Docker (COPY order-service/pom.xml nos cinco Dockerfiles existentes + Dockerfile próprio)"
    requirement: "ORD-01"
    verification:
      - kind: other
        ref: "./mvnw -pl gateway,auth-service,catalog-service,inventory-service,notification-service,order-service package -DskipTests"
        status: pass
      - kind: other
        ref: "docker build -f order-service/Dockerfile . && docker build -f notification-service/Dockerfile ."
        status: pass
    human_judgment: false
  - id: D2
    description: "BUYER cria pedido (POST /orders) com itens validados e precificados via GET /products/{id} síncrono no catalog-service (JWT do próprio BUYER repassado); snapshot gravado (sku/name/unitPrice/subtotal); total com escala 2; companyId do JWT, nunca do corpo"
    requirement: "ORD-01"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderControllerIT.java#buyerCreatesOrderValidatedAgainstCatalogAndDecidedByCreditLimitBoundary"
        status: pass
    human_judgment: false
  - id: D3
    description: "Decisão de crédito automática sob trava por empresa (company_credit_lock, PESSIMISTIC_WRITE): dentro do limite → APPROVED (decidedBy=SYSTEM); acima → PENDING_APPROVAL; igualdade aprova; PENDING_APPROVAL anterior não consome"
    requirement: "ORD-02"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderControllerIT.java#buyerCreatesOrderValidatedAgainstCatalogAndDecidedByCreditLimitBoundary"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java#sumTotalByCompanyIdAndStatusInSumsExactlyTheCreditConsumingStatuses"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java#confirmedOrderInsertedDirectlyConsumesCreditAndRejectedOrderOfAnotherCompanyDoesNot"
        status: pass
    human_judgment: false
  - id: D4
    description: "GET /orders/{id}: BUYER só vê pedidos da própria empresa (outra empresa → 404 order_not_found, idêntico ao de um id inexistente); SELLER_ADMIN vê qualquer pedido"
    requirement: "ORD-08"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderControllerIT.java#getByIdScopesVisibilityByCompanyAndCollapsesUnauthorizedCompanyToTheSame404AsNonexistent"
        status: pass
    human_judgment: false
  - id: D5
    description: "GET /orders/{id} aberto a SELLER_ADMIN sem restrição de empresa (visão de vendedor)"
    requirement: "ORD-09"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderControllerIT.java#getByIdScopesVisibilityByCompanyAndCollapsesUnauthorizedCompanyToTheSame404AsNonexistent"
        status: pass
    human_judgment: false
  - id: D6
    description: "Success Criteria 5 do ROADMAP: pedidos simultâneos por socket real na fronteira de crédito nunca aprovam além do limite (10 contendores, limite 100.00 → exatamente 1 APPROVED; limite 180.00 → exatamente 3 APPROVED; duas empresas não se misturam)"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java#limite100ComDezContendoresDeSessenta_exatamenteUmAprovado"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java#limite180ComDezContendoresDeSessenta_exatamenteTresAprovados"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java#duasEmpresasNovasAlternando_travaDeUmaNaoDecidePelaOutra"
        status: pass
    human_judgment: false

duration: 48min
completed: 2026-09-25
status: complete
---

# Phase 4 Plan 1: Núcleo do Pedido — Tracer e Prova de Concorrência Summary

**order-service novo no reactor (porta 8085, schema `order`): POST /orders valida itens no catalog-service e decide APPROVED/PENDING_APPROVAL contra o limite de crédito do auth-service sob trava pessimista por empresa, com a fronteira provada por 10 pedidos simultâneos via socket real.**

## Performance

- **Duration:** ~48 min
- **Started:** 2026-09-25T01:15:00Z (aproximado)
- **Completed:** 2026-09-25T02:02:23Z
- **Tasks:** 2 (Task 1: tracer; Task 2: prova de concorrência)
- **Files modified:** 48 (6 modificados — reactor pom.xml + 5 Dockerfiles — e 42 novos em `order-service/`)

## Accomplishments
- `order-service` nasce como o quinto microsserviço: módulo Maven no reactor, imagem Docker própria, e as cinco imagens existentes continuam construindo depois de ganhar a linha `COPY order-service/pom.xml`
- ORD-01: `POST /orders` valida cada item por `GET /products/{id}` síncrono no catalog-service (repassando o JWT do próprio BUYER), grava o snapshot (sku/nome/preço/quantidade/subtotal) e o total com escala 2 — `companyId` só do claim `company_id`, nunca do corpo
- ORD-02: decisão de crédito automática — dentro do limite (`exposição + total <= limite`) nasce `APPROVED` com `decidedBy=SYSTEM`; acima, `PENDING_APPROVAL`; a igualdade aprova; a exposição é uma soma ao vivo sobre `APPROVED/CONFIRMED/SHIPPED/DELIVERED`, nunca um saldo desnormalizado
- ORD-08/ORD-09: `GET /orders/{id}` — BUYER só abre pedidos da própria empresa (pedido alheio devolve o mesmo 404 de um id inexistente, nunca 403); SELLER_ADMIN abre qualquer um
- Success Criteria 5 do ROADMAP: `CreditLimitBoundaryConcurrencyIT` dispara 10 requisições HTTP realmente simultâneas (socket real, `CyclicBarrier`, threads virtuais) e comprova que a trava por linha (`company_credit_lock`, `PESSIMISTIC_WRITE`) nunca deixa passar mais aprovações do que o limite permite — inclusive na fronteira exata e com duas empresas concorrendo ao mesmo tempo
- Toda chamada HTTP de saída acontece antes da transação que segura a trava (D-41) — a separação entre `OrderCreationService` (orquestração, sem `@Transactional`) e `OrderService` (decisão, sem cliente HTTP) é reforçada por dois gates de fonte (`grep` no `<verify>` do plano)

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — pedido criado a partir do catálogo e decidido pelo limite de crédito** - `22293e1` (feat) — RED (compilação falhou, classes de produção inexistentes) → GREEN (3/3 testes)
2. **Task 2: A trava aguenta concorrência real** - `8b7c7bc` (feat) — RED (compilação falhou, `ConcurrentRequests`/classes de teste inexistentes) → GREEN (8/8 testes novos; 11/11 no módulo inteiro)

**Plan metadata:** commit de documentação a ser criado logo após este SUMMARY.

_Nota: as duas tasks carregavam `tdd="true"`; `workflow.tdd_mode` está desativado neste projeto (mesma configuração da Fase 2), então o gate rígido de commits separados `test(...)`/`feat(...)` não se aplicava — RED/GREEN documentado abaixo em "TDD Notes" e dentro do commit único de cada task, conforme o protocolo padrão de `type="execute"`/`type="tracer"` já usado nos planos 02-01/02-02/02-03._

## Files Created/Modified
- `pom.xml` - acrescenta `order-service` a `<modules>`
- `auth-service/Dockerfile`, `gateway/Dockerfile`, `catalog-service/Dockerfile`, `inventory-service/Dockerfile`, `notification-service/Dockerfile` - ganham `COPY order-service/pom.xml order-service/pom.xml`
- `order-service/pom.xml` - dependências idênticas ao template resource-server, sem Spring Cloud AWS/Retry/AOP/LocalStack/Resilience4j/WireMock (nenhuma mensageria nesta fase)
- `order-service/Dockerfile` - build multi-stage, mesma tag fixa `eclipse-temurin:21.0.12_8`
- `order-service/src/main/resources/application.yml` - porta 8085, schema `order` (com `default_schema` entre aspas duplas), timeouts explícitos dos dois clientes
- `order-service/src/main/resources/db/migration/V1__init_order_schema.sql` - `orders`, `order_items` (snapshot), `company_credit_lock` (trava), CHECK com os 8 estados da ORD-10
- `.../client/{AuthServiceClient,CatalogServiceClient}.java` - `RestClient` com timeout explícito, JWT repassado, colapso de 404/indisponibilidade
- `.../credit/{CompanyCreditLock,CompanyCreditLockRepository,CompanyCreditLocker,CreditPolicy}.java` - trava por linha e regra de fronteira (`compareTo`)
- `.../order/{Order,OrderItem,OrderStatus,PricedItem,OrderRepository,OrderCreationService,OrderService,OrderController}.java` - agregado, orquestração não transacional e decisão transacional
- `.../order/dto/*.java`, `.../order/exception/*.java` - DTOs sem campo de preço/empresa e exceções de domínio
- `.../config/{SecurityConfig,GlobalExceptionHandler,ClientProperties,ClientConfig}.java` - resource server, erros uniformes, clientes com timeout
- `.../test/.../support/{TestJwt,DownstreamStubServer,OrderTestInfrastructure,ConcurrentRequests}.java` - suporte de teste reaproveitável pelos planos seguintes
- `.../test/.../{AbstractIntegrationTest,OrderControllerIT,CreditLimitBoundaryConcurrencyIT,CreditLockAndExposureIT}.java` - 11 testes de integração

## Decisions Made
- **ORDER_RESPONSE_CONTRACT** registrado acima (frontmatter `key-decisions`) — contrato consumido pelo smoke do plano `04-05` e pela documentação.
- **Porta 8085, schema `order`** com `default_schema` entre aspas duplas dentro de aspas simples do YAML (`'"order"'`) — `order` é palavra reservada do SQL.
- **Espera pela trava sem `lock_timeout`**, ilimitada de propósito (D-41, nada de I/O de rede sob a trava).
- **Cláusula de bloqueio real emitida pelo Hibernate/PostgreSQL: `for no key update`**, não `for update` — comportamento padrão do dialeto Postgres do Hibernate 6.6 para uma entidade sem alvo de FK; ambas as formas se excluem mutuamente no PostgreSQL, então a serialização por linha continua válida (comprovado pelos três cenários de concorrência, todos com contagem exata de aprovações).
- **`DownstreamStubServer` (JDK pura) em vez de `MockRestServiceServer`** (divergência da pesquisa da fase, registrada e justificada no javadoc da classe e aqui): o mock do Spring troca a fábrica de requisições do cliente, então os timeouts de `ClientConfig` nunca seriam exercitados e o repasse do header `Authorization` não passaria por um socket real; o stub da JDK atende igualmente `MockMvc` (Task 1) e os testes de socket real (Task 2).
- **422 para item de pedido inválido/indisponível**, não 409 (Claude's Discretion resolvida conforme 04-RESEARCH.md Assumption A1).

## Deviations from Plan

None - plan executed exactly as written. Nenhum ajuste de Rules 1-4 foi necessário: todos os testes passaram na primeira execução depois da implementação completa, e nenhuma acceptance criteria exigiu correção.

## TDD Notes

Ambas as tasks carregavam `tdd="true"`. Como `workflow.tdd_mode` está desativado globalmente neste projeto (mesmo estado documentado em `02-01-SUMMARY.md`), o gate rígido de commits separados não se aplicava — RED/GREEN documentado dentro do commit único de cada task:

- **Task 1 (tracer):** RED foi uma falha de **compilação** de `OrderControllerIT` e das classes de suporte (`AbstractIntegrationTest`, `TestJwt`, `DownstreamStubServer`, `OrderTestInfrastructure`) — esperado num módulo inteiramente novo, onde o teste referencia tipos de produção que ainda não existiam (`OrderController`, `OrderService`, etc.). Depois da implementação completa do módulo (schema, entidades, clientes, trava, serviços, controller), GREEN: 3/3 testes na primeira execução.
- **Task 2 (concorrência):** RED foi também uma falha de **compilação** — `ConcurrentRequests`, `CreditLimitBoundaryConcurrencyIT` e `CreditLockAndExposureIT` não existiam. Depois de escritos, GREEN: 8/8 testes novos na primeira execução (nenhuma correção de produção foi necessária — a trava e a soma de exposição já estavam corretas desde a Task 1). Suíte completa do módulo: 11/11 testes, `BUILD SUCCESS`.

## Issues Encountered

Nenhum bloqueante. Um ajuste de qualidade de código, sem impacto em comportamento: `DownstreamStubServer` usava `SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN` do Jackson (depreciado); trocado por `JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN` (a forma não depreciada, mesmo efeito) antes do primeiro commit — corrigido durante a implementação, não é uma correção pós-commit.

## User Setup Required

None - nenhuma configuração de serviço externo é necessária. `order-service` usa apenas o PostgreSQL já provisionado e os endpoints HTTP já existentes de `auth-service`/`catalog-service`.

## Next Phase Readiness
- `order-service` está pronto para os planos `04-02` (endurecimento da criação: limites de itens, tokens adversariais, mensagens de erro sem vazamento), `04-03` (listagem paginada `GET /orders`) e `04-04` (aprovação/rejeição manual — reaproveita `ConcurrentRequests` e a mesma trava).
- O plano `04-05` (compose, Gateway, smoke, Swagger, documentação) depende do `ORDER_RESPONSE_CONTRACT` registrado acima e da porta 8085.
- Nenhum bloqueio conhecido. A reserva de estoque, o Transactional Outbox e a saga permanecem fora de escopo desta fase (Fase 5), como planejado.

---
*Phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito*
*Completed: 2026-09-25*

## Self-Check: PASSED

All key files verified present on disk (`order-service/pom.xml`, `Dockerfile`, `application.yml`, `V1__init_order_schema.sql`, `OrderServiceApplication.java`, `OrderController.java`, `OrderService.java`, `OrderCreationService.java`, `CompanyCreditLocker.java`, `OrderControllerIT.java`, `CreditLimitBoundaryConcurrencyIT.java`, `CreditLockAndExposureIT.java`, `ConcurrentRequests.java`). Both task commits (`22293e1`, `8b7c7bc`) verified present in `git log --oneline --all`. Full module suite re-verified green (`./mvnw -pl order-service verify` — 11/11 tests, `BUILD SUCCESS`) and all plan-level `<verification>` commands (reactor package, two Docker builds, four source-gate greps) re-run and passing.
