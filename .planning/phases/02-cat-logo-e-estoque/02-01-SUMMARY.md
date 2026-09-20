---
phase: 02-cat-logo-e-estoque
plan: "01"
subsystem: catalog
tags: [spring-boot, spring-security, oauth2-resource-server, jpa, flyway, postgresql, testcontainers, mockmvc]

# Dependency graph
requires:
  - phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
    provides: reactor Maven multi-módulo, Dockerfile pattern (eclipse-temurin pinned), JWKS do auth-service, convenção BigDecimal/NUMERIC(19,2), padrão de erro uniforme (GlobalExceptionHandler)
provides:
  - Módulo catalog-service completo no reactor (pom.xml, Dockerfile, application.yml, migração Flyway)
  - "CAT-01: POST/GET/PUT /products, PUT /products/{id}/status — CRUD de produto com soft-delete por status"
  - "CAT-02: GET /products paginado (Pageable/Page), filtrado por papel (BUYER vê ACTIVE, SELLER_ADMIN vê tudo)"
  - Template de resource server (SecurityConfig, GlobalExceptionHandler, TestJwt, AbstractIntegrationTest) pronto para inventory-service replicar (plano 02-02)
affects: [02-02-inventory-service, 02-03-gateway-docker-compose, 04-orders]

# Actuals (#2632)
actuals:
  tokens: 19453
  tasks: 3
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Resource-server-only Spring Security config (sem passwordEncoder/authenticationManager, apenas validação JWT via jwk-set-uri)"
    - "TestJwt: par de chaves RSA de teste + JwtDecoder de teste publicado via @TestConfiguration, sem depender do auth-service estar no ar"
    - "Enum-as-VARCHAR-with-CHECK (EnumType.STRING + CHECK constraint) para status de produto"
    - "Visão de papel derivada de Authentication.getAuthorities(), nunca de query param/corpo"

key-files:
  created:
    - catalog-service/pom.xml
    - catalog-service/Dockerfile
    - catalog-service/src/main/resources/application.yml
    - catalog-service/src/main/resources/db/migration/V1__init_catalog_schema.sql
    - catalog-service/src/main/java/com/orderflow/catalog/product/Product.java
    - catalog-service/src/main/java/com/orderflow/catalog/product/ProductStatus.java
    - catalog-service/src/main/java/com/orderflow/catalog/product/ProductRepository.java
    - catalog-service/src/main/java/com/orderflow/catalog/product/ProductService.java
    - catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java
    - catalog-service/src/main/java/com/orderflow/catalog/config/SecurityConfig.java
    - catalog-service/src/main/java/com/orderflow/catalog/config/GlobalExceptionHandler.java
    - catalog-service/src/test/java/com/orderflow/catalog/support/TestJwt.java
    - catalog-service/src/test/java/com/orderflow/catalog/AbstractIntegrationTest.java
    - catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java
  modified:
    - pom.xml
    - auth-service/Dockerfile
    - gateway/Dockerfile

key-decisions:
  - "PRODUCT_STATUS_CONTRACT=status-two-values"
  - "Porta HTTP do catalog-service: 8082; jwk-set-uri padrão: http://localhost:8081/.well-known/jwks.json"
  - "GET /products devolve o envelope padrão Page<T> do Spring Data (content, totalElements, totalPages, size, number, ...) — não PagedModel/HATEOAS"

patterns-established:
  - "Pattern: resource-server-only SecurityConfig (catalog/inventory replicam este arquivo quase verbatim, plano 02-02)"
  - "Pattern: TestJwt local (RSA de teste + JwtDecoder de teste) — nenhum teste de serviço-dependente-de-auth precisa subir o auth-service"

requirements-completed: [CAT-01, CAT-02]

coverage:
  - id: D1
    description: "SELLER_ADMIN cria produto (POST /products), lê de volta (GET /products/{id}), preço exato em BigDecimal/NUMERIC(19,2), status sempre ACTIVE fixado no servidor"
    requirement: "CAT-01"
    verification:
      - kind: integration
        ref: "catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java#createProductWithSellerAdminTokenReturns201WithLocationAndExpectedBody"
        status: pass
      - kind: integration
        ref: "catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java#createProductIgnoresClientSuppliedStatusAndAlwaysCreatesActive"
        status: pass
    human_judgment: false
  - id: D2
    description: "SELLER_ADMIN atualiza nome/preço/descrição (PUT /products/{id}) e retira/reativa produto por status (PUT /products/{id}/status) — nunca remoção física"
    requirement: "CAT-01"
    verification:
      - kind: integration
        ref: "catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java#updateProductWithSellerAdminTokenReturns200AndSubsequentGetReturnsNewValuesWithSkuUnchanged"
        status: pass
      - kind: integration
        ref: "catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java#changeStatusToDiscontinuedReturns200AndNeverDeletesRow"
        status: pass
    human_judgment: false
  - id: D3
    description: "BUYER lista catálogo paginado vendo apenas ACTIVE; SELLER_ADMIN vê tudo; detalhe de produto DISCONTINUED é 404 para BUYER e 200 para SELLER_ADMIN"
    requirement: "CAT-02"
    verification:
      - kind: integration
        ref: "catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java#buyerListingHidesDiscontinuedProductAndSellerAdminSeesBoth"
        status: pass
      - kind: integration
        ref: "catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java#getDiscontinuedProductReturns404ForBuyerAnd200ForSellerAdmin"
        status: pass
    human_judgment: false
  - id: D4
    description: "Verificação manual da listagem por papel com a stack completa (gateway + docker-compose) no ar — depende do plano 02-03, ainda não existente"
    verification: []
    human_judgment: true
    rationale: "O <human-check> da Task 3 exige a stack do gateway/docker-compose que só o plano 02-03 cria; registrado em .planning/WINDOWS.md como unrun-verify até lá."

duration: 26min
completed: 2026-09-20
status: complete
---

# Phase 2 Plan 1: Catálogo de Produtos Summary

**catalog-service novo no reactor — CRUD completo de produto (SKU, preço BigDecimal/NUMERIC(19,2), soft-delete por status) protegido por JWT validado localmente via JWKS do auth-service, com listagem paginada filtrada por papel (D-24)**

## Performance

- **Duration:** 26 min (execução; Task 1 foi um checkpoint de decisão resolvido antes do início da implementação)
- **Started:** 2026-09-20T01:53:00Z
- **Completed:** 2026-09-20T02:19:10Z
- **Tasks:** 3 (Task 1: checkpoint de decisão; Task 2: tracer; Task 3: expansão completa)
- **Files modified:** 25 (22 no commit da Task 2, 9 no commit da Task 3, com sobreposição)

## Accomplishments
- `catalog-service` entra no reactor Maven como módulo irmão de `auth-service`/`gateway`, com Dockerfile próprio (`eclipse-temurin:21.0.12_8`, jar `-exec`) e migração Flyway dona do schema `catalog`
- CAT-01: SELLER_ADMIN cria, lê, atualiza e retira/reativa produtos; preço nunca é arredondado em silêncio (`@Digits(fraction = 2)` rejeita, nunca normaliza) e SKU é único e imutável após a criação
- CAT-02: BUYER lista o catálogo paginado (`Pageable`/`Page<T>`) vendo apenas produtos `ACTIVE`; SELLER_ADMIN vê `ACTIVE` e `DISCONTINUED`; detalhe de produto descontinuado é 404 para BUYER
- Nenhum caminho do serviço apaga fisicamente uma linha — a retirada do catálogo é sempre `PUT /products/{id}/status`, comprovada por contagem de linhas antes/depois em teste real
- Template de resource server (SecurityConfig, GlobalExceptionHandler, TestJwt, AbstractIntegrationTest) replicável pelo `inventory-service` no plano 02-02
- Reactor Maven e as imagens Docker de `auth-service`/`gateway` continuam construindo com sucesso depois da entrada do novo módulo

## Task Commits

Each task was committed atomically:

1. **Task 1: Congelar o contrato de status e retirada de produto do catálogo** - checkpoint de decisão, sem código produzido; decisão registrada abaixo em Decisions Made
2. **Task 2: Tracer — criação e leitura de produto real** - `0e76ca4` (feat) — RED (compilação falhou por classes de produção inexistentes) → GREEN (12/12 testes)
3. **Task 3: Catálogo completo — atualização, retirada por status e listagem** - `fd5083f` (feat) — RED (12 falhas de asserção + 2 erros de constraint) → GREEN (27/27 testes)

**Plan metadata:** commit de documentação final a ser criado logo após este SUMMARY.

_Nota: Tasks 2 e 3 carregavam `tdd="true"`; cada uma seguiu RED→GREEN dentro do seu próprio commit único (ver seção "TDD Notes" abaixo), conforme a nota de execução recebida do orquestrador para este plano._

## Files Created/Modified
- `pom.xml` - adiciona `catalog-service` a `<modules>`
- `auth-service/Dockerfile`, `gateway/Dockerfile` - ganham `COPY catalog-service/pom.xml catalog-service/pom.xml` para não quebrar o build de imagem depois que o reactor exige o novo módulo
- `catalog-service/pom.xml` - dependências idênticas a `auth-service` menos `spring-security-oauth2-jose` (nunca emite token)
- `catalog-service/Dockerfile` - build multi-stage, mesma tag fixa `eclipse-temurin:21.0.12_8`
- `catalog-service/src/main/resources/application.yml` - porta 8082, schema `catalog`, `jwk-set-uri`, `pageable.default/max-page-size`
- `catalog-service/src/main/resources/db/migration/V1__init_catalog_schema.sql` - tabela `products` (NUMERIC(19,2), status VARCHAR+CHECK, índice por status)
- `catalog-service/.../product/Product.java` - entidade JPA, `updateDetails`/`changeStatus` como mutadores nomeados, sem setter para `sku`
- `catalog-service/.../product/ProductStatus.java` - enum `ACTIVE`/`DISCONTINUED`
- `catalog-service/.../product/ProductRepository.java` - `existsBySku`, `findByStatus(Pageable)`
- `catalog-service/.../product/ProductService.java` - `create`, `getById(id, sellerView)`, `update`, `changeStatus`, `list(pageable, sellerView)`
- `catalog-service/.../product/ProductController.java` - `POST`, `GET/{id}`, `PUT/{id}`, `PUT/{id}/status`, `GET` (lista paginada)
- `catalog-service/.../product/SkuAlreadyUsedException.java` - 409 (Task 3)
- `catalog-service/.../product/dto/{CreateProductRequest,UpdateProductRequest,UpdateProductStatusRequest,ProductResponse}.java`
- `catalog-service/.../config/SecurityConfig.java` - resource server puro (sem beans de emissão de token)
- `catalog-service/.../config/GlobalExceptionHandler.java` - 400/401/403/404/409, corpo uniforme sem stack trace/SQL
- `catalog-service/src/test/.../support/TestJwt.java` - chave RSA de teste + `JwtDecoder` de teste, emissão de token SELLER_ADMIN/BUYER
- `catalog-service/src/test/.../AbstractIntegrationTest.java` - container Postgres singleton (padrão herdado de `auth-service`)
- `catalog-service/src/test/.../ProductControllerIT.java` - 27 testes de integração contra PostgreSQL real

## Decisions Made
- **PRODUCT_STATUS_CONTRACT=status-two-values** — dois estados (`ACTIVE`/`DISCONTINUED`), retirada/reativação via `PUT /products/{productId}/status`, reversível para `ACTIVE`. Nenhuma rota de remoção física existe no serviço; a única forma de tirar um produto do catálogo é essa transição de status.
- **Porta e JWKS registrados para o plano 02-03:** `catalog-service` escuta em `8082`; `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` tem default `http://localhost:8081/.well-known/jwks.json` (aponta para o auth-service local).
- **Formato da listagem paginada registrado para a Fase 4:** `GET /products` devolve o envelope padrão `Page<T>` do Spring Data (`content`, `totalElements`, `totalPages`, `size`, `number`, `numberOfElements`, `first`, `last`, `empty`) — não foi introduzido um DTO de página customizado nem `PagedModel`/HATEOAS.
- **Unicidade de SKU implementada com duas camadas:** checagem antecipada via `existsBySku` (evita gravar em cenários sem corrida) somada ao handler de `DataIntegrityViolationException` (garantia real da constraint `UNIQUE` do banco) — ambas mapeadas para o mesmo código `sku_already_used`/409, mesma filosofia já usada em `CompanyService`/`EmailAlreadyUsedException`.

## Deviations from Plan

### Auto-fixed Issues

Nenhum desvio de código nas Rules 1-4 foi necessário — o plano foi executado como escrito. Dois ajustes operacionais (não de código) foram feitos e são registrados por transparência:

**1. [Ambiente] Docker Desktop não estava respondendo no início da execução**
- **Found during:** Precondition check da Task 2 (`docker info` retornava exit 1 — engine não estava rodando)
- **Fix:** Iniciado `Docker Desktop.exe` localmente e aguardada a resposta do daemon (~10s) antes de prosseguir. Nenhum arquivo do repositório foi afetado; ação puramente de ambiente local, não uma correção de código.
- **Verificação:** `docker info` retornou exit 0 antes de qualquer comando de build/Testcontainers ser executado.

**2. [Ambiente/Git] Commits feitos diretamente em `master`**
- **Contexto:** O dispatch deste executor instruiu modo sequencial "na main working tree" porque a criação de worktree foi degradada (origin/HEAD não resolvível — o repositório não tem remote configurado). `.planning/config.json` declara `git.branching_strategy: "none"`, e todo o histórico anterior do projeto (incluindo os commits de planejamento da Fase 2) já vive diretamente em `master`, o único branch existente.
- **Ação:** Os dois commits de tarefa deste plano (`0e76ca4`, `fd5083f`) foram feitos em `master`, seguindo a instrução explícita do orquestrador e a convenção já estabelecida no repositório, apesar de a heurística genérica de nome de branch protegido (`main|master|develop|trunk|release/*`) sinalizar `master` como protegido.
- **Impacto:** Nenhum — não há remote, não há PR/CI gate sendo contornado, e nenhum outro agente/branch concorrente existe neste repositório local.

---

**Total deviations:** 0 de código (Rules 1-4); 2 ajustes operacionais documentados acima.
**Impact on plan:** Nenhum impacto no escopo ou na qualidade do código entregue.

## TDD Notes

Tasks 2 e 3 carregavam `tdd="true"`. Como `workflow.tdd_mode` está desativado globalmente neste projeto, o gate rígido de commits separados (`test(...)`/`feat(...)`) não se aplicava; a nota de execução recebida instruiu documentar RED/GREEN dentro do commit único de cada task (protocolo padrão de `type="execute"`).

- **Task 2 (tracer):** RED foi uma falha de **compilação** de `ProductControllerIT` (`package com.orderflow.catalog.product does not exist`, `cannot find symbol: ProductRepository`) — esperado num módulo inteiramente novo, onde o teste referencia tipos de produção que ainda não existem. Depois da implementação completa (entidade, repositório, serviço, controller, config), GREEN: 12/12 testes.
- **Task 3 (expansão):** RED foi uma falha genuína de **asserção em runtime** — 12 falhas (405 Method Not Allowed nas rotas `PUT`/`GET` ainda não implementadas, 404 na rota de status inexistente) e 2 erros (`DataIntegrityViolationException` não traduzida em 409 para SKU duplicado) — evidência de RED mais forte que a da Task 2, pois o módulo já compilava e rodava. Depois da implementação (mutadores em `Product`, `findByStatus`, `update`/`changeStatus`/`list` em `ProductService`, novas rotas em `ProductController`, handlers de conflito/malformado), GREEN: 27/27 testes.
- Nenhum commit `test(...)` isolado foi criado; a evidência RED/GREEN está documentada aqui e nas mensagens de commit `feat(02-01): ...` de cada task.

## Issues Encountered
Nenhum problema bloqueante. O aviso do Spring Data (`Serializing PageImpl instances as-is is not supported...`) apareceu nos logs de teste — é o comportamento padrão documentado do Spring Boot 3.5 ao serializar `Page<T>` sem `PagedModel`; a decisão de manter o envelope padrão (em vez de introduzir `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`) foi deliberada e está registrada em "Decisions Made" — não é um bug.

## Known Stubs
Nenhum. Todas as rotas entregues (`POST`, `GET/{id}`, `PUT/{id}`, `PUT/{id}/status`, `GET` paginado) têm implementação completa e testada contra PostgreSQL real, sem dados mock ou placeholder.

## Threat Flags
Nenhuma superfície nova além das já registradas no `<threat_model>` do plano (T-02-01 a T-02-09, T-02-SC) — todas com disposição `mitigate`/`accept` já implementadas e cobertas por teste (guardas de papel, status fixado no servidor, validação de escala do preço, filtragem de visibilidade por papel, corpo de erro uniforme, teto de paginação).

## User Setup Required
None - nenhuma configuração de serviço externo necessária.

## Next Phase Readiness
- O plano `02-02` (inventory-service) pode replicar diretamente o template de resource server estabelecido aqui (`SecurityConfig`, `GlobalExceptionHandler`, `TestJwt`, `AbstractIntegrationTest`, `pom.xml`, `Dockerfile`).
- O plano `02-03` (rotas do Gateway + docker-compose) consome a porta `8082` e o `jwk-set-uri` default registrados acima, e deve executar o `<human-check>` da Task 3 (listagem por papel via `http://localhost:8080/api/products`) assim que a stack completa estiver no ar — registrado como item aberto em `.planning/WINDOWS.md` (kind `unrun-verify`).
- Nenhum bloqueio conhecido para a Fase 4 (order-service): o contrato de produto (id, sku, price em `BigDecimal`/`NUMERIC(19,2)`, status `ACTIVE`/`DISCONTINUED`, envelope `Page<T>` padrão) está estável e documentado.

---
*Phase: 02-cat-logo-e-estoque*
*Completed: 2026-09-20*

## Self-Check: PASSED

All 14 created files verified present on disk (catalog-service module + SUMMARY.md itself); both task commits (`0e76ca4`, `fd5083f`) verified present in `git log --oneline --all`. No missing items.
