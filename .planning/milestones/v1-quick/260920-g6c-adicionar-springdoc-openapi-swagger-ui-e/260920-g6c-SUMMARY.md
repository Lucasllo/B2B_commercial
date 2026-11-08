---
phase: quick-260920-g6c
plan: "01"
subsystem: infra
tags: [springdoc, openapi, swagger, security-config, auth-service, catalog-service, inventory-service]

requires:
  - phase: 02-cat-logo-e-estoque
    provides: catalog-service e inventory-service existentes, com SecurityConfig por servico

provides:
  - Swagger UI navegavel em http://localhost:8081|8082|8083/swagger-ui.html (auth/catalog/inventory)
  - Spec OpenAPI 3 (/v3/api-docs) com securityScheme bearerAuth (http/bearer/JWT) e SecurityRequirement global
  - Botao Authorize funcional na UI — cola um JWT e exercita qualquer endpoint protegido via Try it out
  - OpenApiDocsIT por servico provando UI/spec acessiveis sem token e endpoint de negocio ainda exigindo token

affects: []

actuals:
  tokens: 32000
  tasks: 3
  commits: 3

tech-stack:
  added: ["org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.1"]
  patterns:
    - "OpenApiConfig por servico: bean OpenAPI com titulo/descricao injetados via @Value (parametro de metodo, nunca campo), porque springdoc nao tem propriedades springdoc.info.*"
    - "Caminhos do springdoc liberados literalmente (nunca wildcard amplo) na lista permitAll() de cada SecurityConfig, com .anyRequest().authenticated() intacto como fallback"

key-files:
  created:
    - auth-service/src/main/java/com/orderflow/auth/config/OpenApiConfig.java
    - catalog-service/src/main/java/com/orderflow/catalog/config/OpenApiConfig.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/OpenApiConfig.java
    - auth-service/src/test/java/com/orderflow/auth/OpenApiDocsIT.java
    - catalog-service/src/test/java/com/orderflow/catalog/OpenApiDocsIT.java
    - inventory-service/src/test/java/com/orderflow/inventory/OpenApiDocsIT.java
  modified:
    - pom.xml
    - auth-service/pom.xml
    - catalog-service/pom.xml
    - inventory-service/pom.xml
    - auth-service/src/main/java/com/orderflow/auth/config/SecurityConfig.java
    - catalog-service/src/main/java/com/orderflow/catalog/config/SecurityConfig.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/SecurityConfig.java
    - auth-service/src/main/resources/application.yml
    - catalog-service/src/main/resources/application.yml
    - inventory-service/src/main/resources/application.yml
    - README.md

key-decisions:
  - "springdoc.version=2.9.1 fixado no dependencyManagement do pom raiz — springdoc nao esta no BOM do Spring Boot, entao a versao precisa ser travada a mao; 2.9.1 e o topo da linha 2.x e declara suporte explicito a Boot 3.5.16"
  - "Titulo/descricao de cada spec vem de propriedades orderflow.openapi.title/description (namespace proprio do projeto) injetadas no bean OpenAPI via @Value, ja que springdoc nao expoe springdoc.info.* nem securitySchemes via application.yml"
  - "Nenhum modulo common criado para o OpenApiConfig duplicado nos tres servicos — bean de ~25 linhas, reactor sem modulo compartilhado, acoplaria os servicos por ganho desprezivel"
  - "Gateway deliberadamente fora de escopo: Swagger UI roda na porta direta de cada servico (127.0.0.1, ja exposta para depuracao local), nao roteada pelo gateway"

patterns-established:
  - "Pattern: OpenApiConfig por servico — bean OpenAPI + SecurityScheme bearerAuth + SecurityRequirement global, replicavel por qualquer servico futuro (order-service, notification-service)"

requirements-completed: [QUAL-01]

coverage:
  - id: D1
    description: "Swagger UI e spec OpenAPI acessiveis sem token nos tres servicos, com titulo distinto por servico"
    requirement: "QUAL-01"
    verification:
      - kind: integration
        ref: "auth-service/src/test/java/com/orderflow/auth/OpenApiDocsIT.java"
        status: pass
      - kind: integration
        ref: "catalog-service/src/test/java/com/orderflow/catalog/OpenApiDocsIT.java"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/OpenApiDocsIT.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "Endpoint de negocio continua exigindo JWT nos tres servicos apos liberar os caminhos do springdoc (guarda de regressao)"
    requirement: "QUAL-01"
    verification:
      - kind: integration
        ref: "OpenApiDocsIT (guarda de regressao 401) nos tres servicos, mais AuthControllerIT/ProductControllerIT/InventoryControllerIT existentes"
        status: pass
    human_judgment: false
  - id: D3
    description: "Fluxo completo no navegador: abrir as tres URLs, ver titulo correto, colar token no Authorize, Try it out em endpoint protegido devolve 200"
    verification:
      - kind: manual
        ref: "Verificado pelo orquestrador no host: docker compose up -d --build --wait (6/6 saudaveis); curl direto nas tres portas confirmou swagger-ui.html/v3/api-docs acessiveis sem token, titulo correto em cada spec (Auth/Catalog/Inventory Service API), bearerAuth (http/bearer/JWT) presente no spec dos tres; GET /products sem token 401, com token 200 (simula Authorize + Try it out); GET /auth/me e GET /inventory/{uuid} sem token 401 nos outros dois servicos"
        status: pass
    human_judgment: true
    rationale: "O executor rodou em worktree isolado sem .env populado e sem garantia de Docker Desktop acessivel, entao a verificacao visual no navegador foi deferida ao orquestrador (que tem .env e Docker no host) — mesma situacao ja registrada no plano 02-03 desta fase."

duration: ~25min
completed: 2026-09-20
status: complete
---

# Quick Task 260920-g6c: Swagger UI (springdoc-openapi) Summary

**auth-service, catalog-service e inventory-service agora servem Swagger UI navegavel com autenticacao Bearer JWT funcional (botao Authorize + Try it out), sem tocar no gateway.**

## Performance

- **Duration:** ~25 min (execucao) + verificacao manual pelo orquestrador
- **Commits:** 3 (um por task do plano)

## Accomplishments

- `pom.xml` (raiz): `springdoc.version=2.9.1` fixado em `<properties>` e uma entrada normal em `dependencyManagement` para `org.springdoc:springdoc-openapi-starter-webmvc-ui`, permitindo que os tres poms de servico declarem a dependencia sem versao.
- Cada servico (`auth-service`, `catalog-service`, `inventory-service`) ganhou:
  - `OpenApiConfig.java` — bean `OpenAPI` com `Info` (titulo/descricao via `@Value` de `orderflow.openapi.title`/`.description`), `SecurityScheme` `bearerAuth` (`http`/`bearer`/`JWT`) e `SecurityRequirement` global.
  - `SecurityConfig.java` — os quatro caminhos do springdoc (`/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**`) acrescentados literalmente ao `permitAll()`, com `.anyRequest().authenticated()` mantido intacto.
  - `application.yml` — bloco `springdoc:` (`show-actuator: false`, ordenacao de operacoes/tags) e `orderflow.openapi.title`/`.description` proprios.
  - `OpenApiDocsIT.java` — 4 testes: spec acessivel sem token com `bearerAuth` correto no JSON; `/v3/api-docs/swagger-config` acessivel; `/swagger-ui.html` deixa passar pela cadeia de seguranca; guarda de regressao provando que o endpoint de negocio do servico continua 401 sem token.
- `README.md` — nova subsecao "Documentacao interativa (Swagger UI)" com as tres URLs, a explicacao de que rodam na porta direta (sem o prefixo `/api` do gateway) e o passo a passo do botao Authorize.
- `gateway/` **nao foi tocado** — confirmado por `git status --porcelain -- gateway` vazio, tanto pelo executor quanto apos o merge.

Cada task foi commitada atomicamente:

1. **Task 1: Swagger UI ponta a ponta no catalog-service** — `5b12a12` (feat) — fatia vertical completa num unico servico antes de replicar.
2. **Task 2: Replicar para auth-service e inventory-service** — `d9dcd04` (feat) — mesmo molde, com as diferencas reais registradas no plano (auth-service aninha `openapi` dentro da chave `orderflow` ja existente; inventory-service cria a chave `orderflow` pela primeira vez).
3. **Task 3: README + confirmacao de que o gateway nao mudou** — `94792e6` (docs).

## Deviations

Nenhuma — plano executado exatamente como escrito, sem ajustes de Regra 1-4.

## Verificacao manual (human-check) — executada pelo orquestrador, nao pelo executor

O executor rodou em worktree isolado sem `.env` populado, entao deferiu a verificacao visual no navegador. O orquestrador executou no host:

- `docker compose up -d --build --wait` → 6/6 servicos saudaveis (o `--build` reconstruiu as imagens com a dependencia nova).
- `curl` direto nas tres portas: `swagger-ui.html` (302, redirect esperado para `/swagger-ui/index.html`) e `/v3/api-docs` (200) acessiveis sem header `Authorization` nos tres servicos.
- Titulo de cada spec confirmado: `OrderFlow — Auth Service API` (8081), `OrderFlow — Catalog Service API` (8082), `OrderFlow — Inventory Service API` (8083).
- `bearerAuth` (`http`/`bearer`/`JWT`) presente no spec dos tres servicos — e o que faz o botao Authorize aparecer na UI.
- Fluxo Authorize + Try it out simulado via curl: `GET /products` no catalog-service sem token → 401; com token obtido em `POST /auth/login` → 200. `GET /auth/me` (auth-service) e `GET /inventory/{uuid}` (inventory-service) sem token → 401 nos dois, confirmando que a superficie de negocio nao foi ampliada.

## Known Stubs

Nenhum. As tres integracoes estao completas e testadas contra o spec real gerado em runtime, sem placeholder.

## Next Steps

Nenhum obrigatorio — tarefa autocontida. Se um profile de producao for introduzido no futuro, os quatro caminhos do springdoc liberados em cada `SecurityConfig` devem ser reavaliados/fechados (ja registrado como condicao aceita no plano e no comentario de cada `SecurityConfig`).
