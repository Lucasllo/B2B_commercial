---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "01"
subsystem: api
tags: [correlation-id, mdc, springdoc, gateway, openapi]

requires:
  - phase: 01-infraestrutura-e-autenticacao
    provides: Gateway MVC com cinco rotas de negócio e StripPrefix=1, sem Spring Security
provides:
  - Correlation-ID único na borda (header, pedido encaminhado, MDC e linha de acesso)
  - Swagger UI única no Gateway com dropdown dos cinco specs
affects: [07-02, 07-04, 07-05, 07-06, 07-07, 07-11]

actuals:
  tokens: 11593
  tasks: 2
  commits: 4

tech-stack:
  added: [springdoc-openapi-starter-webmvc-ui no gateway, spring-boot-starter-test, maven-failsafe-plugin]
  patterns: [filtro OncePerRequestFilter com wrappers case-insensitive, URIs de upstream por placeholder, rotas /docs com SetPath]

key-files:
  created:
    - gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java
    - gateway/src/test/java/com/orderflow/gateway/CorrelationIdFilterTest.java
    - gateway/src/test/java/com/orderflow/gateway/GatewayRoutingIT.java
    - gateway/src/test/java/com/orderflow/gateway/support/UpstreamStubServer.java
    - estudos/27-correlation-id-e-mdc.md
    - estudos/28-openapi-agregado-no-gateway.md
  modified:
    - gateway/src/main/resources/application.yml
    - gateway/pom.xml
    - estudos/README.md

key-decisions:
  - "CORRELATION_HEADER=X-Correlation-Id"
  - "CORRELATION_MDC_KEY=correlationId"
  - "CORRELATION_ID_PATTERN=[A-Za-z0-9-]{1,64}"
  - "CORRELATION_LOG_PATTERN=[%X{correlationId:-}] "
  - "GATEWAY_RESPONSE_HEADER_OWNER=gateway"
  - "DOCS_ROUTES=/docs/<svc>/v3/api-docs"
  - "SWAGGER_PRIMARY=order-service"
  - "ACCESS_LOG_SKIP=/actuator"

patterns-established:
  - "Correlation-ID: o Gateway valida, grava um único header e limpa o MDC no finally"
  - "Upstream do Gateway: orderflow.gateway.upstream.* com default do docker-compose, trocado no IT por @DynamicPropertySource"
  - "Docs: SetPath=/v3/api-docs em /docs/<svc>/v3/api-docs, predicado só Path"

requirements-completed: [QUAL-01, QUAL-02, TEST-01, TEST-02]

coverage:
  - id: D1
    description: Um único X-Correlation-Id válido na resposta, no pedido encaminhado e no MDC, com valor inválido trocado por UUID e eco do downstream ignorado
    requirement: QUAL-02
    verification:
      - kind: unit
        ref: gateway/src/test/java/com/orderflow/gateway/CorrelationIdFilterTest.java
        status: pass
    human_judgment: false
  - id: D2
    description: A requisição real pelo Gateway entrega o mesmo ID uma vez ao stub, loga MÉTODO caminho -> status com [id], e não loga /actuator, query string nem Authorization
    requirement: TEST-02
    verification:
      - kind: integration
        ref: gateway/src/test/java/com/orderflow/gateway/GatewayRoutingIT.java#validCorrelationIdReachesOrderOnceAndIsLogged
        status: pass
    human_judgment: false
  - id: D3
    description: swagger-config lista os cinco specs em /docs/<svc>/v3/api-docs com primaryName order-service, e cada rota de negócio chega ao stub certo com StripPrefix
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: gateway/src/test/java/com/orderflow/gateway/GatewayRoutingIT.java#swaggerConfigListsExactlyTheFiveServiceSpecs
        status: pass
    human_judgment: false
  - id: D4
    description: A Swagger UI no navegador abre o dropdown dos cinco serviços sem erro de CORS
    requirement: QUAL-01
    verification: []
    human_judgment: true
    rationale: A renderização da Swagger UI e o dropdown são JavaScript no navegador; o IT prova o swagger-config e as rotas, e o plano colhe essa conferência no fim da fase

duration: 19min
completed: 2026-10-01
status: complete
plan_head_before: 02788cea2fb7f43c8266007001791040b3bb1a4f
plan_head_after: 2e6c0045e8b3f0bce8917fa63b62189e720d9fec
---

# Phase 07 Plan 01: Correlation-ID e Swagger UI única Summary

**O Gateway passa a emitir um único `X-Correlation-Id` (o do cliente, se for válido) e a servir uma Swagger UI com os cinco specs em `/docs/<svc>/v3/api-docs`.**

## Performance

- **Duration:** 19 min
- **Started:** 2026-10-02T02:27:00Z
- **Completed:** 2026-10-02T02:46:15Z
- **Tasks:** 2
- **Files modified:** 9

## Accomplishments

- `CorrelationIdFilter` valida `[A-Za-z0-9-]{1,64}`, gera UUID quando o header falta ou é inválido, deduplica o nome ignorando caixa no pedido encaminhado e ignora o eco do serviço na resposta.
- A linha INFO é `MÉTODO caminho -> status` com `[<id>]` no padrão de log; `/actuator` não gera linha de acesso, e query string e `Authorization` ficam de fora.
- As cinco rotas de negócio usam `orderflow.gateway.upstream.*` (defaults do docker-compose) e o Gateway ganhou unitário mais IT contra cinco stubs HTTP reais.
- `http://localhost:8080/swagger-ui.html` redireciona para a UI cujo `swagger-config` lista auth, catalog, inventory, notification e order, abrindo em order-service.

## Contracts for downstream plans

```
CORRELATION_HEADER=X-Correlation-Id
CORRELATION_MDC_KEY=correlationId
CORRELATION_ID_PATTERN=[A-Za-z0-9-]{1,64}
CORRELATION_LOG_PATTERN=[%X{correlationId:-}] 
DOCS_ROUTES=/docs/<svc>/v3/api-docs
GATEWAY_RESPONSE_HEADER_OWNER=gateway
SWAGGER_PRIMARY=order-service
ACCESS_LOG_SKIP=/actuator
```

## Task Commits

Each task was committed atomically:

1. **Task 1: Correlation-ID único na resposta, no destino e no log** — `20fa352` (test, RED) e `e32e78e` (feat, GREEN)
2. **Task 2: Swagger UI única com dropdown dos 5 serviços** — `5361ed6` (test, RED) e `2e6c004` (feat, GREEN)

## Files Created/Modified

- `gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java` — origem do Correlation-ID, MDC e log de acesso
- `gateway/src/main/resources/application.yml` — upstreams parametrizados, rotas `/docs`, swagger-ui.urls e padrão de log
- `gateway/pom.xml` — starter de teste, failsafe e springdoc sem versão
- `gateway/src/test/java/com/orderflow/gateway/CorrelationIdFilterTest.java` — unitário do filtro
- `gateway/src/test/java/com/orderflow/gateway/GatewayRoutingIT.java` — IT de roteamento, log e docs
- `gateway/src/test/java/com/orderflow/gateway/support/UpstreamStubServer.java` — stub JDK que ecoa o header
- `estudos/27-correlation-id-e-mdc.md` — explicação do ID, do MDC e do caminho pelo SQS
- `estudos/28-openapi-agregado-no-gateway.md` — explicação da UI única
- `estudos/README.md` — entradas 27 e 28

## Decisions Made

- `CORRELATION_HEADER=X-Correlation-Id`, `CORRELATION_ID_PATTERN=[A-Za-z0-9-]{1,64}`, `CORRELATION_LOG_PATTERN=[%X{correlationId:-}] `.
- `GATEWAY_RESPONSE_HEADER_OWNER=gateway` — o header é gravado na resposta real e o wrapper ignora `setHeader`/`addHeader` desse nome em qualquer caixa.
- `DOCS_ROUTES=/docs/<svc>/v3/api-docs` com `SetPath=/v3/api-docs`, fora de `/api`, para não colidir com as rotas de negócio.
- `SWAGGER_PRIMARY=order-service`. O spec do próprio Gateway não entra no dropdown.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Security] A linha de acesso não pode levar query string nem Authorization**
- **Found during:** Task 1 (Correlation-ID)
- **Issue:** T-07-03 exige que a linha tenha só método, caminho sem query e status. O `<behavior>` não pedia essa prova.
- **Fix:** `accessLogOmitsQueryStringAndAuthorization` manda `?token=secret` e `Authorization: Bearer super-secret` e exige a linha `GET /api/orders/abc -> 200` sem esses valores. O filtro loga `getRequestURI()`.
- **Files modified:** `GatewayRoutingIT.java`, `CorrelationIdFilter.java`
- **Verification:** `./mvnw -B -pl gateway verify` — 6 unitários e 10 ITs verdes
- **Committed in:** `20fa352` (teste) e `e32e78e` (filtro)

**Total deviations:** 1 auto-fixed (Rule 2)
**Impact on plan:** Trava a mitigação que o threat model já marcava como teste. Sem mudança de desenho.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `20fa352` — `CorrelationIdFilterTest` 5 falhas de asserção, veredito `RED_EVIDENCE_OK` | `e32e78e` | — |
| 2 | `5361ed6` — `swaggerConfigListsExactlyTheFiveServiceSpecs` 404, veredito `RED_EVIDENCE_OK` | `2e6c004` | — |

Não houve commit de refactor. O tracer da Task 1 passou em `./mvnw -B -pl gateway verify` antes da Task 2.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- 07-02/07-04/07-05/07-07 podem copiar `HEADER`, `MDC_KEY`, o regex e `logging.pattern.correlation`. Os serviços não devem ecoar `X-Correlation-Id` na resposta.
- A conferência visual de `http://localhost:8080/swagger-ui.html` (dropdown, sem CORS) fica para o harvest humano do fim da fase. O IT já prova `swagger-config`, o redirect e as rotas `/docs`.

## Self-Check: PASSED

- FOUND: gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java
- FOUND: gateway/src/main/resources/application.yml
- FOUND: gateway/src/test/java/com/orderflow/gateway/GatewayRoutingIT.java
- FOUND: estudos/27-correlation-id-e-mdc.md
- FOUND: estudos/28-openapi-agregado-no-gateway.md
- FOUND: 20fa352
- FOUND: e32e78e
- FOUND: 5361ed6
- FOUND: 2e6c004

---
*Phase: 07-endurecimento-observabilidade-e-entrega*
*Completed: 2026-10-01*
