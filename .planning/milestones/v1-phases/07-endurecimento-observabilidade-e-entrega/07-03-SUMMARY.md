---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "03"
subsystem: api
tags: [openapi, swagger, error-response, catalog, auth, gateway]

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: Swagger UI única no Gateway em /docs/<svc>/v3/api-docs
provides:
  - Spec de catalog e auth no nível contratos + erros, com server /api
  - ErrorResponse só de documentação e OpenApiDocsIT de contrato inteiro
affects: [07-05, 07-07, 07-08, 07-11]

actuals:
  tokens: 4200
  tasks: 2
  commits: 4

tech-stack:
  added: []
  patterns:
    - ErrorResponse record por serviço, só para o schema OpenAPI; o handler segue devolvendo Map
    - server relativo /api via orderflow.openapi.server-url
    - POST /auth/login com SecurityRequirements vazio; JWKS com @Hidden

key-files:
  created:
    - catalog-service/src/main/java/com/orderflow/catalog/config/ErrorResponse.java
    - auth-service/src/main/java/com/orderflow/auth/config/ErrorResponse.java
  modified:
    - catalog-service/src/main/java/com/orderflow/catalog/config/OpenApiConfig.java
    - catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java
    - catalog-service/src/test/java/com/orderflow/catalog/OpenApiDocsIT.java
    - auth-service/src/main/java/com/orderflow/auth/config/OpenApiConfig.java
    - auth-service/src/main/java/com/orderflow/auth/config/JwksController.java
    - auth-service/src/main/java/com/orderflow/auth/auth/AuthController.java
    - auth-service/src/main/java/com/orderflow/auth/company/CompanyController.java
    - auth-service/src/test/java/com/orderflow/auth/OpenApiDocsIT.java

key-decisions:
  - "OPENAPI_SERVER_URL=/api"
  - "ERROR_SCHEMA=ErrorResponse"
  - "PUBLIC_OPERATION_MARK=SecurityRequirements vazio em POST /auth/login"
  - "JWKS_IN_SPEC=hidden"

patterns-established:
  - "Padrão contratos + erros para 07-07 e 07-08: ErrorResponse, server /api, OpenApiDocsIT percorrendo paths"
  - "SecurityConfig não é alterado para o Try it out"

requirements-completed: [QUAL-01]

coverage:
  - id: D1
    description: O spec do catalog-service declara server /api, summary e tag em cada operação, erros reais apontando para ErrorResponse, e ProductResponse.status igual a ProductStatus
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: catalog-service/src/test/java/com/orderflow/catalog/OpenApiDocsIT.java
        status: unknown
    human_judgment: false
  - id: D2
    description: O spec do auth-service deixa POST /auth/login público, esconde o JWKS, e documenta os erros reais de login e empresas
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: auth-service/src/test/java/com/orderflow/auth/OpenApiDocsIT.java
        status: unknown
    human_judgment: false

duration: manual close-out
completed: 2026-10-03
status: complete
plan_head_before: 88ad3c399744ac641ff83c9d234a1621a152b37d
plan_head_after: 631faba768f3f8c6416d5f2acb841c278f98e53a
---

# Phase 07 Plan 03: OpenAPI de catalog e auth Summary

**Catalog e auth publicam contratos e erros reais, com server `/api`, login público e JWKS fora do spec.**

## Performance

- **Duration:** commits da sessão de 2026-10-02; fechamento manual em 2026-10-03
- **Started:** 2026-10-02T04:04:29Z
- **Completed:** 2026-10-03T13:52:00Z
- **Tasks:** 2
- **Files modified:** 21

## Accomplishments

- O catalog-service documenta os 5 endpoints de produto com `@Tag`/`@Operation`, erros reais via `ErrorResponse` e `servers[0].url = /api`.
- O auth-service repete o padrão: login com `@SecurityRequirements` vazio, JWKS com `@Hidden`, e empresas com os códigos reais do handler.
- Os dois `OpenApiDocsIT` travam o contrato inteiro. O `SecurityConfig` dos dois serviços não foi alterado.

## Task Commits

1. **Task 1 RED: contrato OpenAPI do catalog** - `c92c91c` (test)
2. **Task 1 GREEN: ErrorResponse, server /api e anotações de produto** - `5c331fa` (feat)
3. **Task 2 RED: contrato OpenAPI do auth** - `06c68d1` (test)
4. **Task 2 GREEN: ErrorResponse, login público e JWKS oculto** - `631faba` (feat)

## Decisions Made

- `OPENAPI_SERVER_URL=/api`, lido de `orderflow.openapi.server-url` (env `ORDERFLOW_OPENAPI_SERVER_URL`).
- `ERROR_SCHEMA=ErrorResponse` por serviço, record só de documentação.
- `PUBLIC_OPERATION_MARK`: `POST /auth/login` anula o `bearerAuth` global com `@SecurityRequirements` vazio.
- `JWKS_IN_SPEC=hidden`: `JwksController` com `@Hidden`.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
| ---- | --- | ----- | -------- |
| 1    | c92c91c | 5c331fa | — |
| 2    | 06c68d1 | 631faba | — |

O GREEN da Task 2 já estava no working tree e foi commitado neste fechamento manual. Não houve commit de refactor.

## Files Created/Modified

- `ErrorResponse` e `OpenApiConfig.servers` em catalog e auth.
- `ProductController`, `AuthController` e `CompanyController` com operações e erros.
- `@Schema` nos DTOs de produto, login, usuário e empresa. O exemplo de e-mail é `admin@orderflow.local`.
- `@Hidden` em `JwksController`.
- Casos novos em `catalog` e `auth` `OpenApiDocsIT`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Close-out] GREEN da Task 2 estava sem commit**
- **Found during:** fechamento manual depois do gate de retomada
- **Issue:** `06c68d1` já tinha os testes do auth, e a implementação estava só no working tree, sem `07-03-SUMMARY.md`
- **Fix:** commit `631faba` com a implementação já escrita, depois este summary
- **Files modified:** controllers, DTOs, `OpenApiConfig`, `JwksController` e `ErrorResponse` do auth-service
- **Commit:** `631faba`

## Verification

- `./mvnw -B -pl auth-service verify -Dit.test=OpenApiDocsIT` não chegou a executar os casos: o daemon Docker não está no ar (`Could not find a valid Docker environment`). O status de cobertura ficou `unknown`.
- O `SecurityConfig` de catalog e auth não está no diff deste plano.

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: catalog-service/src/main/java/com/orderflow/catalog/config/ErrorResponse.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/config/ErrorResponse.java
- FOUND: catalog-service/src/test/java/com/orderflow/catalog/OpenApiDocsIT.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/OpenApiDocsIT.java
- FOUND: c92c91c
- FOUND: 5c331fa
- FOUND: 06c68d1
- FOUND: 631faba
