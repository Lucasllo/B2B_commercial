---
phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
plan: "05"
subsystem: auth
tags: [spring-security, method-security, spel, jpa, jwt, jwks, testcontainers, tdd, adversarial-testing]

requires:
  - phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
    provides: "Pacote company (Company/CompanyRepository/CompanyService/CompanyController), TokenService com claim company_id, JwtIssuerConfig/JwksController, decididos em 01-02/01-04"
provides:
  - "CompanyGuard (@companyGuard) — bean de isolamento por empresa consultado via @PreAuthorize, reutilizável pela Fase 4 para escopar pedidos por empresa (ORD-08)"
  - "GET/PUT /companies/{companyId}/credit-limit com autorização assimétrica: SELLER_ADMIN lê/escreve qualquer empresa, BUYER só lê a própria"
  - "Prova por teste de que o isolamento por empresa não vira oráculo de existência (403 idêntico para empresa de outro vs. inexistente)"
  - "JwksAccessCounter + JwksContractIT — medição automatizada de que validar um token não dispara chamada em tempo de execução ao emissor (D-03)"
affects:
  - "Fase 4 (ORD-08) — CompanyGuard é o mecanismo de isolamento a reutilizar para escopar pedidos por empresa"
  - "Fases 2+ — JwksContractIT é a prova de que resource servers futuros podem validar localmente a partir do JWKS publicado, sem acoplamento síncrono ao auth-service"

actuals:
  tokens: 10700
  tasks: 2
  commits: 2
  plan_head_before: 5774847f70a36efae6339ab5d394f69a24800b22

tech-stack:
  added:
    - "Spring Security method security via SpEL (@PreAuthorize(\"@companyGuard.isSelfOrSeller(#companyId)\")) resolvendo um @Component por nome de bean"
    - "maven-compiler-plugin <parameters>true</parameters> — obrigatório para @PathVariable e SpEL de @PreAuthorize resolverem nomes de parâmetro sem reflection explícita, já que o reactor não herda de spring-boot-starter-parent"
    - "com.nimbusds.jose/jwt (já transitivo via spring-security-oauth2-jose) usado em escopo de teste para forjar tokens adversariais (outra chave, adulterado, expirado) e para parsear o JWKS publicado"
  patterns:
    - "Guard explícito de isolamento por empresa (não filtro global do Hibernate) — falha alto com 403 comprovado por teste, em vez de silenciosamente devolver dados sem escopo com 200"
    - "@PreAuthorize assimétrico por verbo HTTP: leitura usa o guard de auto-ou-vendedor, escrita usa hasRole('SELLER_ADMIN') puro — a checagem de nível de objeto só é necessária onde o dado é escopado por empresa"
    - "Contador de acesso via OncePerRequestFilter de precedência máxima — transforma uma suposição de arquitetura (\"validação é stateless\") em medição automatizada, sem tocar o contexto Spring"

key-files:
  created:
    - "auth-service/src/main/java/com/orderflow/auth/company/CompanyGuard.java"
    - "auth-service/src/main/java/com/orderflow/auth/company/CompanyNotFoundException.java"
    - "auth-service/src/main/java/com/orderflow/auth/company/dto/UpdateCreditLimitRequest.java"
    - "auth-service/src/main/java/com/orderflow/auth/company/dto/CreditLimitResponse.java"
    - "auth-service/src/test/java/com/orderflow/auth/JwksContractIT.java"
    - "auth-service/src/test/java/com/orderflow/auth/support/JwksAccessCounter.java"
  modified:
    - "auth-service/src/main/java/com/orderflow/auth/company/Company.java (método changeCreditLimit, sem setScale/round)"
    - "auth-service/src/main/java/com/orderflow/auth/company/CompanyController.java (GET/PUT /companies/{companyId}/credit-limit)"
    - "auth-service/src/main/java/com/orderflow/auth/company/CompanyService.java (getCreditLimit/updateCreditLimit)"
    - "auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java (handler de CompanyNotFoundException → 404)"
    - "auth-service/src/test/java/com/orderflow/auth/CompanyControllerIT.java (22 testes novos: Task 1 + Task 2)"
    - "pom.xml (reactor pai — <parameters>true</parameters> no maven-compiler-plugin)"

key-decisions:
  - "CompanyGuard como mecanismo único de isolamento por empresa — filtro global do Hibernate descartado deliberadamente (esquecer de habilitar devolve 200 com dados sem escopo; filtros não cobrem consultas nativas nem escritas)"
  - "Company.changeCreditLimit grava o BigDecimal exatamente como recebido — nunca setScale/round, para não alterar em silêncio o valor que o vendedor informou"
  - "AtomicInteger de contagem resetado por @BeforeEach em JwksContractIT, em vez de @DirtiesContext — mantém o contexto Spring único por classe (par de chaves RSA estável) e torna a asserção 'contador == 1' independente da ordem de execução dos métodos @Test"

patterns-established:
  - "CompanyGuard é o guard de isolamento por empresa a copiar/estender na Fase 4 para escopar pedidos (ORD-08) — mesmo nome de bean, mesma defesa contra ClassCastException quando o principal não é um Jwt"
  - "JwksContractIT é o modelo de prova de contrato que cada resource server das Fases 2+ pode replicar: construir o decoder apenas do JWKS publicado, nunca de um bean interno da aplicação"

requirements-completed: [COMP-02, COMP-03, AUTH-03]

coverage:
  - id: D21
    description: "SELLER_ADMIN consulta e atualiza o limite de crédito de qualquer empresa com valor monetário exato; BUYER lê apenas o da própria empresa e não escreve"
    requirement: "COMP-02"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#sellerAdminGetsCreditLimitOfAnyCompanyWithExactValue"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#sellerAdminUpdatesCreditLimitAndSubsequentGetReturnsNewValue"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#buyerGetsCreditLimitOfOwnCompanyReturns200WithCorrectValue"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#buyerUpdatesCreditLimitOfOwnCompanyReturns403"
        status: pass
    human_judgment: false
  - id: D22
    description: "Atualização de limite com mais de 2 casas decimais ou negativa é rejeitada com 400 e o valor anterior permanece inalterado"
    requirement: "COMP-02"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#sellerAdminUpdatesCreditLimitWithThreeDecimalsReturns400AndPreviousValuePersists"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#sellerAdminUpdatesCreditLimitWithNegativeValueReturns400"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#sellerAdminUpdatesCreditLimitWithMissingFieldReturns400"
        status: pass
    human_judgment: false
  - id: D23
    description: "Empresa inexistente devolve 404 no mesmo formato de corpo de erro dos demais handlers"
    requirement: "COMP-02"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#sellerAdminGetsCreditLimitOfNonexistentCompanyReturns404WithSameBodyShapeAsOtherErrors"
        status: pass
    human_judgment: false
  - id: D24
    description: "BUYER de uma empresa pedindo dados de outra recebe 403 sem vazar nome/limite da outra empresa"
    requirement: "COMP-03"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#buyerOfCompanyAGettingCompanyBCreditLimitReturns403WithoutLeakingCompanyBData"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#buyerOfCompanyAGettingOwnCompanyCreditLimitReturns200"
        status: pass
    human_judgment: false
  - id: D25
    description: "403 de empresa de outro é byte-a-byte idêntico ao 403 de empresa inexistente — isolamento não vira oráculo de existência"
    requirement: "COMP-03"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#forbiddenResponseForOtherCompanyIsByteIdenticalToForbiddenResponseForNonexistentCompany"
        status: pass
    human_judgment: false
  - id: D26
    description: "BUYER de uma empresa não consegue escrever o limite de outra; o valor da outra permanece inalterado após a tentativa"
    requirement: "COMP-03"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#buyerOfCompanyAPuttingCompanyBCreditLimitReturns403AndCompanyBLimitUnchanged"
        status: pass
    human_judgment: false
  - id: D27
    description: "Token expirado, assinado por outra chave, malformado e com payload adulterado são todos rejeitados com 401"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#expiredTokenReturns401"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#tokenSignedByDifferentKeyReturns401"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#malformedAuthorizationHeaderReturns401"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#tokenWithTamperedPayloadAfterSigningReturns401"
        status: pass
    human_judgment: false
  - id: D28
    description: "Decoder construído apenas do JWKS publicado valida um token real e rejeita expirado/outra-chave; o endpoint JWKS é acessado exatamente uma vez após 5 requisições autenticadas + 5 decodificações offline (D-03 medido)"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "JwksContractIT#decoderBuiltFromJwksEndpointValidatesRealTokenAndExpectedCallCountStaysAtOne"
        status: pass
      - kind: integration
        ref: "JwksContractIT#decoderBuiltFromJwksEndpointRejectsExpiredToken"
        status: pass
      - kind: integration
        ref: "JwksContractIT#decoderBuiltFromJwksEndpointRejectsTokenSignedByDifferentKey"
        status: pass
    human_judgment: false

duration: ~40min
completed: 2026-09-18
status: complete
---

# Phase 1 Plan 5: Limite de crédito com isolamento por empresa e contrato JWKS Summary

**O vendedor consulta e atualiza o limite de crédito de qualquer empresa; o comprador lê apenas o da própria empresa via `CompanyGuard` (`@PreAuthorize` por SpEL) — isolamento comprovado por teste adversarial, incluindo a prova de que o 403 não vira oráculo de existência — e a validação stateless de token (D-03) deixou de ser suposição: o endpoint JWKS é acessado exatamente uma vez em dez operações, medido por `JwksAccessCounter`.**

## Performance

- **Duration:** ~40 min de execução ativa
- **Completed:** 2026-09-18
- **Tasks:** 2/2
- **Files modified:** 12 (6 novos, 6 modificados, incluindo o `pom.xml` do reactor)

## Accomplishments
- `CompanyGuard` (`@Component("companyGuard")`): `isSelfOrSeller(UUID)` devolve `true` para SELLER_ADMIN, compara `company_id` do JWT para BUYER, e devolve `false` (nunca lança) quando o principal não é um `Jwt` — mecanismo único de isolamento por empresa desta fase, sem filtro global do Hibernate
- `GET`/`PUT /companies/{companyId}/credit-limit` com autorização assimétrica: leitura via `@companyGuard.isSelfOrSeller`, escrita via `hasRole('SELLER_ADMIN')`; `UpdateCreditLimitRequest` espelha exatamente as restrições de escala monetária (`@DecimalMin`, `@Digits(fraction=2)`) do DTO de criação (D-06)
- `CompanyNotFoundException` + handler 404 no formato uniforme de erro (`error`/`message`)
- 22 novos testes em `CompanyControllerIT`: 11 de consulta/atualização do limite (Task 1) e 11 adversariais de isolamento por empresa + rejeição de token (Task 2), incluindo a comparação byte-a-byte entre "empresa de outro" e "empresa inexistente"
- `JwksAccessCounter` (`@TestConfiguration`) + `JwksContractIT`: um decoder construído apenas do JSON publicado em `/.well-known/jwks.json` valida um token real e rejeita expirado/outra-chave; o contador de acessos ao endpoint permanece em 1 após 5 chamadas autenticadas + 5 decodificações offline — a suposição A4 da pesquisa (validação sem chamada por token) virou medição
- Full suite verde: `AuthControllerIT` (13), `CompanyControllerIT` (30), `JwksContractIT` (3), `SeedPasswordHashTest` (2) — 46 testes de integração + 2 unitários, sem regressão

## Task Commits

Cada task foi commitada atomicamente:

1. **Task 1: Consulta e atualização do limite de crédito, com autorização granular por papel e por empresa** - `8e2d90a` (feat, TDD: RED confirmado com 12 falhas de 404 antes da implementação de `CompanyGuard`/endpoints/DTOs, depois GREEN)
2. **Task 2: Isolamento por empresa e rejeição de token — comprovados, incluindo a ausência de chamada ao emissor** - `877e391` (test — nenhum furo real de produção encontrado: todos os 12 testes adversariais novos + 3 do `JwksContractIT` passaram com a implementação da Task 1)

## Files Created/Modified
- `auth-service/.../company/CompanyGuard.java` - bean de isolamento por empresa consultado via SpEL
- `auth-service/.../company/CompanyNotFoundException.java` - exceção de domínio para empresa inexistente
- `auth-service/.../company/dto/{UpdateCreditLimitRequest,CreditLimitResponse}.java` - payload/resposta do limite de crédito
- `auth-service/.../company/Company.java` - método `changeCreditLimit` (sem `setScale`/`round`)
- `auth-service/.../company/{CompanyController,CompanyService}.java` - endpoints e métodos de consulta/atualização
- `auth-service/.../config/GlobalExceptionHandler.java` - handler de 404 para `CompanyNotFoundException`
- `auth-service/src/test/.../CompanyControllerIT.java` - 22 testes novos (Task 1 + Task 2)
- `auth-service/src/test/.../JwksContractIT.java` - prova do contrato JWKS e ausência de chamada por token
- `auth-service/src/test/.../support/JwksAccessCounter.java` - filtro contador de acessos ao endpoint JWKS
- `pom.xml` - `<parameters>true</parameters>` no `maven-compiler-plugin` do reactor (deviation)

## Decisions Made
- `CompanyGuard` como mecanismo único de isolamento por empresa — filtro global do Hibernate descartado deliberadamente (falha silenciosa com 200 em vez de falha alta com 403 comprovada por teste)
- `Company.changeCreditLimit` grava o `BigDecimal` exatamente como recebido — a validação de escala já aconteceu no DTO; arredondar aqui alteraria em silêncio o limite informado pelo vendedor
- Contador de acesso ao JWKS resetado por `@BeforeEach` em vez de `@DirtiesContext` — mantém o contexto Spring único por classe (par de chaves RSA estável, `01-RESEARCH.md` Pitfall 4) e evita que a asserção "contador == 1" dependa da ordem de execução dos testes

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `maven-compiler-plugin` sem `<parameters>true</parameters>` quebrava `@PathVariable` e o SpEL de `@PreAuthorize`**
- **Found during:** Task 1, primeira execução de `CompanyControllerIT` contra os novos endpoints
- **Issue:** O reactor pai não herda de `spring-boot-starter-parent` (BOMs importados manualmente por decisão do 01-01), e por isso nunca recebeu a flag `-parameters` que esse parent ativaria por padrão. Sem ela, nem `@PathVariable UUID companyId` nem o SpEL `#companyId` de `@PreAuthorize` conseguiam resolver o nome do parâmetro via reflection — toda requisição a `GET`/`PUT /companies/{companyId}/credit-limit` falhava com 500 (`IllegalArgumentException: Name for argument of type [java.util.UUID] not specified`) em vez do 200/403/404 esperado.
- **Fix:** Adicionado `<parameters>true</parameters>` à configuração do `maven-compiler-plugin` em `pluginManagement` do `pom.xml` raiz — aplica-se a todos os módulos do reactor (auth-service e os futuros catalog/inventory/order/notification-service), prevenindo a mesma falha nas próximas fases.
- **Files modified:** `pom.xml` (raiz)
- **Commit:** `8e2d90a`

**Total deviations:** 1 auto-fixed (Rule 3 - blocking)
**Impact on plan:** Correção necessária para que qualquer endpoint com `@PathVariable` + `@PreAuthorize` baseado em SpEL funcionasse — sem ela, a Fase 4 (que também vai reutilizar `CompanyGuard`-like guards com `@PathVariable`) reproduziria o mesmo 500 silencioso. Nenhum scope creep: a mudança é de configuração de build, não de comportamento de negócio.

## Issues Encountered
- Nenhum bloqueio não resolvido. A Task 2 (isolamento adversarial + rejeição de token) não encontrou nenhum furo real de produção: todos os 12 testes adversariais de `CompanyControllerIT` e os 3 de `JwksContractIT` passaram com a implementação já feita na Task 1 — o valor desta task foi a prova, não código de produção novo, exatamente como o plano previa.
- Um ajuste de teste foi necessário em `JwksContractIT`: o `AtomicInteger` de contagem é um bean singleton compartilhado por todos os métodos `@Test` da classe (contexto único, sem `@DirtiesContext`); sem resetá-lo a cada teste, a ordem de execução do JUnit vazava contagem de um método para outro. Resolvido com `@BeforeEach` — não é uma mudança de produção, apenas isolamento correto do teste.

## User Setup Required

None - nenhuma configuração de serviço externo é necessária para este plano.

## Next Phase Readiness
- `CompanyGuard` está pronto no formato que a Fase 4 vai reutilizar para escopar pedidos por empresa (ORD-08)
- `JwksContractIT` é o modelo de prova de contrato que cada resource server das Fases 2+ pode replicar para validar tokens localmente, sem acoplamento síncrono ao auth-service
- `<parameters>true</parameters>` no `maven-compiler-plugin` do reactor previne a mesma falha silenciosa de `@PathVariable`/SpEL em qualquer módulo futuro (catalog/inventory/order/notification-service)
- Nenhum bloqueio conhecido para os próximos planos

---
*Phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas*
*Completed: 2026-09-18*

## Self-Check: PASSED

- FOUND: auth-service/src/main/java/com/orderflow/auth/company/CompanyGuard.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/company/CompanyNotFoundException.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/company/dto/UpdateCreditLimitRequest.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/company/dto/CreditLimitResponse.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/JwksContractIT.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/support/JwksAccessCounter.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/CompanyControllerIT.java
- FOUND: pom.xml
- FOUND commit: 8e2d90a
- FOUND commit: 877e391
