---
phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
plan: "02"
subsystem: orders
tags: [spring-boot, spring-security, oauth2-resource-server, bean-validation, jdk-httpserver, mockito, junit5]

# Dependency graph
requires:
  - phase: 04-01
    provides: order-service (porta 8085, schema order), OrderCreationService/OrderService, AuthServiceClient/CatalogServiceClient, DownstreamStubServer, TestJwt, ORDER_RESPONSE_CONTRACT
provides:
  - "ORD-01 endurecido: tudo ou nada provado (produto repetido, itens inexistentes/DISCONTINUED, total fora da faixa) e limites de entrada provados (50 itens, 1000000 unidades) — nenhum pedido parcial é gravado em qualquer caminho de recusa"
  - "ORD-02 endurecido: sem limite de crédito confiável não existe pedido — catalog-service e auth-service fora do ar, lentos ou malformados sempre terminam em 503 fail-closed, nunca em aprovação por omissão (D-39)"
  - "Snapshot do pedido provado congelado (D-43): GET /orders/{id} não muda quando o catalog-service muda o produto depois da criação"
  - "Tokens adversariais barrados contra o decoder de produção: outra chave RSA, exp no passado, iss diferente (401); BUYER sem claim company_id (403)"
  - "CreditPolicy.fitsWithinLimit e as transições do agregado Order isoladas em teste unitário sem Spring nem Postgres; orquestração de OrderCreationService coberta por JUnit+Mockito"
  - "DownstreamStubServer com 4 modos de falha reutilizáveis (SERVER_ERROR/SLOW/MALFORMED_BODY/WRONG_COMPANY) e updateProduct — suporte de teste para os planos 04-03/04-04"
affects: [04-03-listagem, 04-04-aprovacao-rejeicao, 04-05-compose-gateway-smoke]

# Actuals (#2632)
actuals:
  tokens: 16874
  tasks: 3
  commits: 3
plan_head_before: 2e61259881b06ad72f7a2b65ebdb9266f66f521c

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ClientConfig.buildRestClient público e static — testes unitários montam o RestClient de produção pela mesma fábrica (mesmo JdkClientHttpRequestFactory, mesmos timeouts), nunca uma cópia que poderia divergir do que roda em produção"
    - "AuthenticationEntryPoint customizado em JSON para 401 — rejeição de JWT acontece no filtro de segurança, ANTES do DispatcherServlet; sem esse bean o corpo de 401 sairia vazio, quebrando o envelope uniforme {error,message} do resto do serviço"
    - "Guarda de total por contagem de dígitos (precision() - scale() > 17), nunca compareTo contra um limiar numérico — mesmo critério do validador de @Digits, aplicado manualmente porque a checagem soma itens já validados, não um campo de DTO isolado"
    - "DownstreamStubServer com modos de falha por enum (Failure) lidos de campos voláteis por requisição — cada requisição de teste roda em thread virtual própria, e a pior corrida possível é benigna (setup vs. primeira requisição do próprio teste)"

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/order/exception/DuplicateOrderItemsException.java
    - order-service/src/main/java/com/orderflow/order/order/exception/OrderTotalOutOfRangeException.java
    - order-service/src/test/java/com/orderflow/order/client/DownstreamClientsTest.java
    - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
    - order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java
  modified:
    - order-service/src/main/java/com/orderflow/order/order/OrderCreationService.java
    - order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java
    - order-service/src/main/java/com/orderflow/order/config/ClientConfig.java
    - order-service/src/main/java/com/orderflow/order/config/SecurityConfig.java
    - order-service/src/main/java/com/orderflow/order/order/dto/CreateOrderRequest.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemRequest.java
    - order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java
    - order-service/src/test/java/com/orderflow/order/support/DownstreamStubServer.java

key-decisions:
  - "MAX_ITEMS_PER_ORDER=50 e MAX_QUANTITY_PER_ITEM=1000000 (Claude's Discretion, nenhuma decisão do contexto fixa limites) — o primeiro defende contra amplificação de chamadas síncronas ao catalog-service (T-04-11), o segundo contra quantidade absurda sem ganho para um pedido legítimo"
  - "Produto repetido sai como 400 validation_failed com fields.items, o mesmo envelope da validação de bean, em vez de um código de erro novo — o cliente trata um formato só"
  - "Guarda de total (order_total_out_of_range, 422) usa precision()-scale() > 17, mesmo critério do @Digits(integer=17, fraction=2) do auth-service — checada depois de precificar todos os itens e ANTES de consultar o limite de crédito, a única checagem restante que faz rede"
  - "companyId trocado na resposta do limite de crédito (WRONG_COMPANY) é tratado como indisponibilidade do auth-service, nunca como dado confiável (D-39) — o cliente já fazia essa checagem desde o 04-01; a Task 2 apenas provou o caminho com o novo modo de falha do stub"
  - "AuthenticationEntryPoint customizado adicionado em SecurityConfig (Rule 2 — funcionalidade crítica ausente): sem ele, o 401 de token adversarial saía com corpo vazio, quebrando o contrato uniforme de erro do serviço; a rejeição de JWT acontece no filtro de segurança, antes do GlobalExceptionHandler poder atuar"

requirements-completed: [ORD-01, ORD-02]

coverage:
  - id: D1
    description: "Produto repetido no mesmo pedido é recusado com 400 validation_failed/fields.items antes de qualquer chamada de rede; mix de item válido/inexistente/DISCONTINUED é recusado inteiro com 422 invalid_order_items listando os dois ids inválidos na ordem do pedido, com as 4 chamadas ao catálogo feitas e nenhuma ao auth-service"
    requirement: "ORD-01"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#duplicateProductIdInOrderIsRejectedWith400BeforeAnyCatalogCall"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#mixOfValidAndInvalidItemsRejectsWholeOrderListingAllInvalidIdsInOrder"
        status: pass
    human_judgment: false
  - id: D2
    description: "Catalog-service em SERVER_ERROR, MALFORMED_BODY ou SLOW faz POST /orders devolver 503 catalog_service_unavailable sem chamar auth-service e sem gravar pedido; o caso SLOW responde em menos de 2,5s com timeout de leitura de 1s configurado"
    requirement: "ORD-01"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#catalogServiceServerErrorReturns503WithoutCallingAuthServiceOrSavingOrder"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#catalogServiceMalformedBodyReturns503WithoutSavingOrder"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#catalogServiceSlowReturns503WithinTwoAndAHalfSeconds"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/client/DownstreamClientsTest.java#catalogServiceClientAgainstClosedPortThrowsCatalogServiceUnavailableException"
        status: pass
    human_judgment: false
  - id: D3
    description: "Auth-service em SERVER_ERROR, MALFORMED_BODY, WRONG_COMPANY, SLOW, ou empresa sem limite registrado faz POST /orders devolver 503 auth_service_unavailable sem gravar pedido — nunca aprovação por omissão (D-39)"
    requirement: "ORD-02"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#authServiceServerErrorReturns503WithoutSavingOrder"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#authServiceMalformedBodyReturns503WithoutSavingOrder"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#authServiceWrongCompanyInCreditLimitResponseReturns503WithoutSavingOrder"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#authServiceSlowReturns503WithinTwoAndAHalfSeconds"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#companyWithoutRegisteredCreditLimitReturns503WithoutSavingOrder"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/client/DownstreamClientsTest.java#authServiceClientAgainstClosedPortThrowsAuthServiceUnavailableException"
        status: pass
    human_judgment: false
  - id: D4
    description: "Nenhum corpo de 422 ou 503 contém http://, 127.0.0.1, porta do stub, nome de exceção ou stack trace"
    requirement: "ORD-01"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java (assertNoLeak em 5 testes de 503 + assertThat sem leak no teste de 422 misto)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Entrada estruturalmente inválida devolve 400 sem nenhuma chamada a vizinho: lista vazia ou ausente, mais de 50 itens, productId nulo, quantity nula/0/-1/1000001, corpo que não é JSON (malformed_request)"
    requirement: "ORD-01"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#emptyItemsListIsRejectedWith400WithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#missingItemsFieldIsRejectedWith400WithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#moreThan50ItemsIsRejectedWith400WithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#nullProductIdIsRejectedWith400WithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#nullQuantityIsRejectedWith400WithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#zeroQuantityIsRejectedWith400WithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#negativeQuantityIsRejectedWith400WithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#quantityAboveMaxIsRejectedWith400WithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#nonJsonBodyIsRejectedWith400MalformedRequestWithoutCallingStub"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#exactly50DistinctItemsWithSufficientLimitIsCreated"
        status: pass
    human_judgment: false
  - id: D6
    description: "Total que não cabe em NUMERIC(19,2) é recusado com 422 order_total_out_of_range antes de consultar o limite de crédito, em vez de estourar como 500 no banco"
    requirement: "ORD-01"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#totalThatDoesNotFitInColumnReturns422WithoutSavingOrder"
        status: pass
    human_judgment: false
  - id: D7
    description: "Token de outra chave RSA, expirado, ou com iss diferente recebe 401 unauthorized contra o decoder de produção; token de BUYER sem claim company_id recebe 403 forbidden sem chamada ao stub"
    requirement: "ORD-02"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#tokenSignedByDifferentKeyReturns401Unauthorized"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#tokenWithExpirationInThePastReturns401Unauthorized"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#tokenWithDifferentIssuerReturns401Unauthorized"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#buyerTokenWithoutCompanyIdClaimReturns403ForbiddenWithoutCallingStub"
        status: pass
    human_judgment: false
  - id: D8
    description: "Depois de criado, o pedido não muda quando o catálogo muda: trocar preço e nome do produto no catalog-service não altera unitPrice/name/subtotal/total devolvidos por GET /orders/{id} (D-43)"
    requirement: "ORD-01"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java#orderSnapshotDoesNotChangeWhenCatalogProductChangesAfterCreation"
        status: pass
    human_judgment: false
  - id: D9
    description: "CreditPolicy.fitsWithinLimit provado isolado (igualdade aprova mesmo com escalas diferentes, um centavo acima recusa); Order.create/approveAutomatically/holdForApproval e a exceção de transição inválida; OrderStatus com os 8 estados da ORD-10 na ordem certa"
    requirement: "ORD-02"
    verification:
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java#fitsWithinLimitApprovesAtExactEqualityEvenWithDifferentScales"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java#transitioningFromAnyStatusOtherThanCreatedThrowsIllegalStateException"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java#orderStatusHasExactlyTheEightStatesOfOrd10InOrder"
        status: pass
    human_judgment: false
  - id: D10
    description: "Orquestração de OrderCreationService prova a ordem de recusas com clientes mockados: produto ausente e falha do catálogo nunca chamam getCreditLimit; falha do auth-service nunca chama createWithCreditCheck; caminho feliz monta PricedItem com lineNumber 1..n e subtotal correto"
    requirement: "ORD-01"
    verification:
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java#missingProductInCatalogThrowsInvalidOrderItemsExceptionWithoutCheckingCreditOrCreatingOrder"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java#catalogServiceUnavailableOnSecondItemPropagatesWithoutCheckingCreditLimit"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java#authServiceUnavailablePropagatesWithoutCreatingOrder"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java#happyPathBuildsPricedItemsInOrderWithCorrectSubtotalsAndForwardsCreditLimit"
        status: pass
    human_judgment: false
  - id: D11
    description: "Nenhuma linha de log do order-service recebe o token do BUYER ou o header Authorization (gate de fonte via grep)"
    verification:
      - kind: other
        ref: "! grep -rEq '(log|logger|LOG|LOGGER)\\.(trace|debug|info|warn|error)\\(.*(bearerToken|getTokenValue|AUTHORIZATION)' order-service/src/main"
        status: pass
    human_judgment: false

duration: 40min
completed: 2026-09-25
status: complete
---

# Phase 4 Plan 2: Endurecimento da Criação de Pedido Summary

**Toda a superfície de `POST /orders` provada por teste: tudo ou nada em cada recusa, falha fechada de vizinho dentro do timeout, limites de entrada contra abuso, tokens adversariais barrados contra o decoder de produção, snapshot congelado sob mudança do catálogo, e a regra de crédito isolada em teste unitário sem Spring.**

## Performance

- **Duration:** ~40 min (Task 1 concluída e commitada em sessão anterior; esta execução retomou da Task 2 em progresso e completou a Task 3)
- **Started:** 2026-09-25T22:20:00Z (aproximado, retomada)
- **Completed:** 2026-09-25T22:39:50Z
- **Tasks:** 3 (Task 1: tracer de tudo-ou-nada; Task 2: falha fechada de vizinho e tokens ruins; Task 3: limites de entrada, total fora da faixa, snapshot congelado, testes unitários)
- **Files modified:** 13 (5 novos, 8 modificados)

## Accomplishments
- ORD-01 endurecido: produto repetido vira 400 `validation_failed`/`fields.items` antes de qualquer chamada de rede (D-44); mix de item válido/inexistente/DISCONTINUED é recusado inteiro com 422 `invalid_order_items` listando os dois ids na ordem do pedido; total que não cabe em `NUMERIC(19,2)` vira 422 `order_total_out_of_range` (nunca 500 do banco) antes de consultar o limite de crédito
- ORD-01 endurecido: limites de entrada contra abuso — `@Size(max=50)` na lista de itens (defesa contra amplificação de chamadas síncronas, T-04-11) e `@Max(1000000)` na quantidade, cada um provado na fronteira (50 itens passa, 51 recusa; 1000000 implícito, 1000001 recusa)
- ORD-02 endurecido: catalog-service e auth-service fora do ar, lentos (SLOW dorme 3s > timeout de leitura de 1s) ou com corpo malformado sempre terminam em 503 (`catalog_service_unavailable`/`auth_service_unavailable`) sem gravar pedido — nunca aprovação por omissão (D-39); `companyId` trocado na resposta do limite é tratado como indisponibilidade, não como dado confiável
- Snapshot do pedido provado congelado (D-43): `updateProduct` muda nome e preço no stub depois da criação, e `GET /orders/{id}` continua devolvendo o `unitPrice`/`name`/`subtotal`/`total` originais
- Tokens adversariais barrados contra o decoder de produção real (não um mock): outra chave RSA, `exp` no passado, `iss` diferente → 401 `unauthorized`; BUYER sem claim `company_id` → 403 `forbidden` sem nenhuma chamada ao stub — exigiu adicionar um `AuthenticationEntryPoint` customizado em JSON, pois a rejeição de JWT acontece no filtro de segurança, antes do `GlobalExceptionHandler` poder atuar (Rule 2)
- `CreditPolicy.fitsWithinLimit` e as transições do agregado `Order` providas isoladas em `OrderDomainTest` (sem Spring, sem Postgres); a ordem de chamadas de `OrderCreationService` provada com clientes mockados em `OrderCreationServiceTest` (JUnit + Mockito)
- Nenhum corpo de 422/503 vaza host, porta do stub, nome de exceção ou stack trace; gate de fonte confirma que nenhum log dos clientes inclui o token ou o header `Authorization`

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — produto repetido recusado inteiro antes de qualquer chamada ao catálogo** - `0ef69d0` (feat) — commitada em sessão anterior
2. **Task 2: Vizinho fora do ar, lento ou quebrado falha fechado com 503, e tokens ruins são barrados** - `c83e482` (feat) — RED (compilação falhou, `DownstreamClientsTest` e os novos métodos do stub não existiam) → GREEN (26 testes em `OrderCreationEdgeCasesIT`, 2 em `DownstreamClientsTest`)
3. **Task 3: Limites de entrada, total fora da faixa, snapshot congelado e regra de crédito em teste unitário** - `29f99b9` (feat) — RED (compilação falhou, `OrderTotalOutOfRangeException` e os dois testes unitários não existiam) → GREEN (42 testes no módulo inteiro)

**Plan metadata:** commit de documentação a ser criado logo após este SUMMARY.

_Nota: as três tasks carregavam `tdd="true"`; `workflow.tdd_mode` está desativado neste projeto (mesma configuração dos planos anteriores), então o gate rígido de commits separados `test(...)`/`feat(...)` não se aplicava — RED/GREEN documentado dentro do commit único de cada task, conforme o protocolo padrão já usado em `04-01`._

## Files Created/Modified
- `order-service/src/main/java/com/orderflow/order/order/exception/DuplicateOrderItemsException.java` - exceção de produto repetido (Task 1)
- `order-service/src/main/java/com/orderflow/order/order/exception/OrderTotalOutOfRangeException.java` - exceção de total fora da faixa (Task 3)
- `order-service/src/main/java/com/orderflow/order/order/OrderCreationService.java` - checagem de produto repetido (Task 1) e guarda de total por contagem de dígitos (Task 3)
- `order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java` - handlers para as duas novas exceções
- `order-service/src/main/java/com/orderflow/order/config/ClientConfig.java` - `buildRestClient` tornado `public static` (Task 2, para `DownstreamClientsTest` montar o RestClient de produção)
- `order-service/src/main/java/com/orderflow/order/config/SecurityConfig.java` - `AuthenticationEntryPoint` customizado em JSON para 401 (Task 2, Rule 2)
- `order-service/src/main/java/com/orderflow/order/order/dto/CreateOrderRequest.java` - `@Size(max=50)` + constante `MAX_ITEMS_PER_ORDER`
- `order-service/src/main/java/com/orderflow/order/order/dto/OrderItemRequest.java` - `@Max(1000000)` + constante `MAX_QUANTITY_PER_ITEM`
- `order-service/src/test/java/com/orderflow/order/support/DownstreamStubServer.java` - enum `Failure` (4 modos), `failCatalogWith`/`failAuthWith`/`clearFailures`/`updateProduct` (Task 2)
- `order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java` - 24 novos testes (Tasks 2 e 3), 26 no total
- `order-service/src/test/java/com/orderflow/order/client/DownstreamClientsTest.java` - 2 testes de porta fechada contra a mesma fábrica de produção (Task 2)
- `order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java` - 6 testes unitários da regra de crédito e do agregado (Task 3)
- `order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java` - 4 testes unitários da ordem de orquestração com Mockito (Task 3)

## Decisions Made
- **MAX_ITEMS_PER_ORDER=50, MAX_QUANTITY_PER_ITEM=1000000** — Claude's Discretion, registrado no frontmatter `key-decisions` acima.
- **Produto repetido como 400 `validation_failed`/`fields.items`**, mesmo envelope da validação de bean, não um código novo.
- **Guarda de total por contagem de dígitos** (`precision()-scale() > 17`), mesmo critério do `@Digits(integer=17, fraction=2)` do auth-service, checada antes de consultar o limite de crédito.
- **`companyId` trocado na resposta de limite é indisponibilidade**, nunca dado confiável (D-39, comportamento já existente desde `04-01`, agora provado por teste).
- **`AuthenticationEntryPoint` customizado adicionado em `SecurityConfig`** (Rule 2 — funcionalidade crítica ausente): sem ele o 401 de token adversarial saía com corpo vazio, quebrando o envelope uniforme de erro do serviço.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] `AuthenticationEntryPoint` customizado para 401 em JSON**
- **Found during:** Task 2 (tokens adversariais)
- **Issue:** Rejeição de JWT (outra chave, `exp` no passado, `iss` diferente, token ausente) acontece no filtro de segurança do Spring Security, ANTES do `DispatcherServlet` — o `GlobalExceptionHandler` (`@RestControllerAdvice`) nunca vê essa exceção. Sem um ponto de entrada customizado, o corpo do 401 saía vazio, quebrando o envelope uniforme `{error,message}` usado pelo resto do serviço.
- **Fix:** Bean `AuthenticationEntryPoint` em `SecurityConfig` que escreve diretamente o corpo JSON `{"error":"unauthorized","message":"Authentication is required"}` com status 401, plugado em `oauth2ResourceServer(...).authenticationEntryPoint(...)`.
- **Files modified:** `order-service/src/main/java/com/orderflow/order/config/SecurityConfig.java`
- **Verification:** `tokenSignedByDifferentKeyReturns401Unauthorized`, `tokenWithExpirationInThePastReturns401Unauthorized`, `tokenWithDifferentIssuerReturns401Unauthorized` — todos verificam `$.error` igual a `unauthorized`.
- **Committed in:** `c83e482` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 missing critical — Rule 2)
**Impact on plan:** Correção essencial para o contrato de erro uniforme do serviço; sem ela os testes de token adversarial da própria Task 2 não teriam como passar. Nenhum scope creep — a mudança é local ao filtro de segurança, sem novo endpoint ou dependência.

## Issues Encountered

Nenhum bloqueante. Toda a implementação (Task 2 retomada de WIP não commitado de uma sessão anterior + Task 3 nova) passou na primeira execução completa dos testes, sem necessidade de reexecuções de correção.

## User Setup Required

None - nenhuma configuração de serviço externo é necessária.

## Next Phase Readiness
- `order-service` está com a superfície de `POST /orders` inteiramente coberta por teste: caminho feliz (`04-01`), tudo ou nada, falha fechada, limites de entrada, tokens adversariais e snapshot congelado (`04-02`).
- `DownstreamStubServer` com os 4 modos de falha e `updateProduct` fica disponível para os planos `04-03` (listagem) e `04-04` (aprovação/rejeição manual), que podem reutilizar o mesmo suporte de teste.
- Nenhum bloqueio conhecido. A reserva de estoque, o Transactional Outbox e a saga permanecem fora de escopo desta fase (Fase 5), como planejado.

---
*Phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito*
*Completed: 2026-09-25*

## Self-Check: PASSED

All key files verified present on disk (`DuplicateOrderItemsException.java`, `OrderTotalOutOfRangeException.java`, `DownstreamClientsTest.java`, `OrderDomainTest.java`, `OrderCreationServiceTest.java`, `SecurityConfig.java`, `OrderCreationEdgeCasesIT.java`). All three task commits (`0ef69d0`, `c83e482`, `29f99b9`) verified present in `git log --oneline --all`. Full module suite re-verified green (`./mvnw -pl order-service verify` — `BUILD SUCCESS`, 0 failures/errors across `OrderCreationEdgeCasesIT` 26/26, `DownstreamClientsTest` 2/2, `OrderDomainTest` 6/6, `OrderCreationServiceTest` 4/4, `OrderControllerIT` 3/3, `CreditLockAndExposureIT` 5/5, `CreditLimitBoundaryConcurrencyIT` 3/3). The plan-level `<verification>` combined command (`test -Dtest=DownstreamClientsTest,OrderDomainTest,OrderCreationServiceTest`) re-run and passing. Source gate re-checked: no log statement in `order-service/src/main` references `bearerToken`/`getTokenValue`/`AUTHORIZATION`.
