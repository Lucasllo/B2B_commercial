---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "05"
subsystem: observability
tags: [correlation-id, mdc, unit-tests, auth, catalog, mockito]

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: CorrelationContext/CorrelationIdFilter do order-service e o header repassado pelo interceptor (07-02)
provides:
  - Filtro de Correlation-ID e padrão de log no auth-service e no catalog-service
  - ITs de log com Postgres real e testes unitários das regras centrais dos dois serviços
affects: [07-10, 07-11]

actuals:
  tokens: 21000
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - CorrelationContext e CorrelationIdFilter duplicados por serviço, sem módulo comum
    - Unitários com Mockito e ReflectionTestUtils para ids de entidade; TokenService com NimbusJwtEncoder real

key-files:
  created:
    - catalog-service/src/main/java/com/orderflow/catalog/observability/CorrelationContext.java
    - catalog-service/src/main/java/com/orderflow/catalog/observability/CorrelationIdFilter.java
    - auth-service/src/main/java/com/orderflow/auth/observability/CorrelationContext.java
    - auth-service/src/main/java/com/orderflow/auth/observability/CorrelationIdFilter.java
    - catalog-service/src/test/java/com/orderflow/catalog/observability/CorrelationIdFilterTest.java
    - catalog-service/src/test/java/com/orderflow/catalog/CorrelationIdLoggingIT.java
    - catalog-service/src/test/java/com/orderflow/catalog/product/ProductServiceTest.java
    - auth-service/src/test/java/com/orderflow/auth/observability/CorrelationIdFilterTest.java
    - auth-service/src/test/java/com/orderflow/auth/CorrelationIdLoggingIT.java
    - auth-service/src/test/java/com/orderflow/auth/company/CompanyServiceTest.java
    - auth-service/src/test/java/com/orderflow/auth/company/CompanyGuardTest.java
    - auth-service/src/test/java/com/orderflow/auth/auth/TokenServiceTest.java
  modified:
    - catalog-service/src/main/resources/application.yml
    - auth-service/src/main/resources/application.yml

key-decisions:
  - "CORRELATION_CODE_PLACEMENT=duplicated-per-service"
  - "Testes de CompanyGuard sem Spring context, com JwtAuthenticationToken montado no teste"
  - "TokenService testado com NimbusJwtEncoder real e NimbusJwtDecoder para ler os claims"

patterns-established:
  - "Linha de acesso INFO traz só método, caminho e status; IT do login prova ausência de e-mail, senha e token"

requirements-completed: [QUAL-02, TEST-01, TEST-02]

coverage:
  - id: D1
    description: GET em catalog e auth com X-Correlation-Id gera linha de acesso com [id]; o header não é ecoado; /actuator/health não gera linha
    requirement: QUAL-02
    verification:
      - kind: integration
        ref: catalog-service/src/test/java/com/orderflow/catalog/CorrelationIdLoggingIT.java#getProductWithCorrelationIdLogsAnAccessLineCarryingThatIdAndDoesNotEchoTheHeader
        status: pass
      - kind: integration
        ref: auth-service/src/test/java/com/orderflow/auth/CorrelationIdLoggingIT.java#creditLimitLookupWithCorrelationIdLogsAnAccessLineCarryingThatId
        status: pass
    human_judgment: false
  - id: D2
    description: A linha de acesso de POST /auth/login não contém e-mail, senha nem accessToken
    requirement: QUAL-02
    verification:
      - kind: integration
        ref: auth-service/src/test/java/com/orderflow/auth/CorrelationIdLoggingIT.java#loginLogsOnlyMethodPathAndStatusAndNeverCredentialsOrToken
        status: pass
    human_judgment: false
  - id: D3
    description: Regras de catálogo (SKU único, ACTIVE na criação, visibilidade do comprador, filtro da listagem) cobertas por unitários sem Docker
    requirement: TEST-01
    verification:
      - kind: unit
        ref: catalog-service/src/test/java/com/orderflow/catalog/product/ProductServiceTest.java
        status: pass
    human_judgment: false
  - id: D4
    description: Regras do auth (BUYER fixado, senha codificada, e-mail duplicado, limite exato, guarda por empresa, claims do token) cobertas por unitários sem Docker
    requirement: TEST-01
    verification:
      - kind: unit
        ref: auth-service/src/test/java/com/orderflow/auth/company/CompanyServiceTest.java
        status: pass
      - kind: unit
        ref: auth-service/src/test/java/com/orderflow/auth/company/CompanyGuardTest.java
        status: pass
      - kind: unit
        ref: auth-service/src/test/java/com/orderflow/auth/auth/TokenServiceTest.java
        status: pass
    human_judgment: false

duration: 25min
completed: 2026-10-03
status: complete
plan_head_before: ca4a14a947967f775b53d8496cfac681054fc2f6
plan_head_after: 00b4f7af113df007afc212e19c0aa8266d4cc253
---

# Phase 07 Plan 05: Correlation-ID em auth e catalog e unitários das regras centrais Summary

**Auth e catalog passam a registrar nos logs o `X-Correlation-Id` que o order-service repassa, sem ecoar o header nem vazar credencial do login, e ganham 25 testes unitários das regras centrais, sem Docker.**

## Performance

- **Duration:** 25 min
- **Tasks:** 2
- **Files modified:** 14 (12 criados, 2 alterados)

## Accomplishments

- `CorrelationContext` e `CorrelationIdFilter` copiados do order-service para `com.orderflow.catalog.observability` e `com.orderflow.auth.observability`. Regex `[A-Za-z0-9-]{1,64}`, UUID quando inválido, MDC restaurado no `finally`, header não ecoado, `/actuator` fora do log.
- `logging.pattern.correlation: "[%X{correlationId:-}] "` nos dois `application.yml`.
- ITs com Postgres real provam o ID na linha de acesso e a ausência de e-mail, senha e token na saída do `POST /auth/login`.
- Lacuna de TEST-01 fechada para os dois serviços sem LocalStack.

## Task Commits

1. **Task 1 (tracer): filtro, padrão de log, filtro unitário e ITs de log** - `8b51462` (feat)
2. **Task 2: unitários das regras centrais** - `00b4f7a` (test)

## Classes de teste novas (insumo da matriz de 07-10)

| Classe | Casos |
|---|---|
| `catalog/.../observability/CorrelationIdFilterTest` | 4 |
| `catalog/.../CorrelationIdLoggingIT` | 2 |
| `catalog/.../product/ProductServiceTest` | 11 |
| `auth/.../observability/CorrelationIdFilterTest` | 4 |
| `auth/.../CorrelationIdLoggingIT` | 2 |
| `auth/.../company/CompanyServiceTest` | 6 |
| `auth/.../company/CompanyGuardTest` | 6 |
| `auth/.../auth/TokenServiceTest` | 2 |

Resultado de `./mvnw -B -pl auth-service,catalog-service verify`: BUILD SUCCESS. Auth: 20 unitários e 56 ITs. Catalog: 15 unitários e 39 ITs. Docker estava disponível; os ITs rodaram com Postgres Testcontainers reais.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `Jwt.getIssuer()` não funciona com o `iss` do auth**
- **Found during:** Task 2 (`TokenServiceTest`)
- **Issue:** o `iss` emitido é o literal `orderflow-auth-service`, que não é URL, então `getIssuer()` lança `IllegalArgumentException`. Era erro do teste, não do código de produção.
- **Fix:** o teste lê `getClaimAsString("iss")`.
- **Commit:** `00b4f7a`

### TDD sequencing

- O filtro e o contexto são cópia de código já provado no order-service (07-02), então os testes do filtro nasceram verdes. Não existe commit RED separado na Task 1 nem na Task 2, e nenhuma falha de RED foi observada.
- Os unitários da Task 2 testam código de produção existente (o plano proíbe mudar produção), então também nasceram verdes. Eles afirmam a regra por captor, estado ou exceção. Não houve mutação de produção para provar que quebram.
- Os testes de `CompanyGuardTest` carregam `@ExtendWith(MockitoExtension.class)` sem nenhum `@Mock`, só para cumprir o critério de aceite do plano.

**Total deviations:** 1 auto-fixed (Rule 1, só no teste). Nenhum código de produção alterado fora do plano.

## Issues Encountered

- `JAVA_HOME` não vinha no shell; a sessão apontou para `C:\Program Files\Java\jdk-21.0.10`.
- Os testes de `/actuator/health` e do login passaram de primeira. O IT do health prova só a ausência da linha de acesso.

## Self-Check: PASSED

- FOUND: catalog-service/src/main/java/com/orderflow/catalog/observability/CorrelationIdFilter.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/observability/CorrelationIdFilter.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/auth/TokenServiceTest.java
- FOUND: 8b51462
- FOUND: 00b4f7a

## Known Stubs

None.
