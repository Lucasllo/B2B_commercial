---
phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
plan: "04"
subsystem: auth
tags: [spring-security, jpa, bean-validation, exception-handling, jwt, testcontainers, tdd]

requires:
  - phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
    provides: "auth-service com User/Role/UserRepository/SecurityConfig/TokenService/AbstractIntegrationTest, decididos em 01-02"
provides:
  - "Pacote company completo (Company, CompanyRepository, CompanyService, CompanyController, DTOs) — POST /companies restrito a SELLER_ADMIN"
  - "GlobalExceptionHandler — corpo de erro uniforme (error/message/fields) para 400/403/409 em todo o serviço"
  - "Claim company_id do JWT provado ponta a ponta: string de UUID quando BUYER, omitido quando SELLER_ADMIN"
  - "TestTokens — helper de login real via MockMvc reutilizável pelos planos seguintes"
  - "AbstractIntegrationTest corrigido para o padrão singleton container — seguro para múltiplas classes *IT"
affects:
  - "01-05-PLAN.md (CompanyGuard/@PreAuthorize sobre o claim company_id já provado aqui)"
  - "Fases 2-6 (contrato do claim company_id — string de UUID, omitido para SELLER_ADMIN — é herdado sem alteração)"

actuals:
  tokens: 10086
  tasks: 2
  commits: 2
  plan_head_before: f8ac6f885111dcceec7e8bd5f55bfc05e2ce01f1

tech-stack:
  added:
    - "Jakarta Bean Validation em cascata (@Valid aninhado em record) para o payload único de criação de empresa + BUYER"
    - "@JsonIgnoreProperties(ignoreUnknown = true) como defesa explícita contra mass assignment de payload"
    - "org.hibernate.annotations.Generated(GenerationTime.INSERT) para reler created_at gerado pelo default now() do Postgres"
  patterns:
    - "@RestControllerAdvice único por serviço — corpo de erro uniforme (error/message/+fields quando aplicável), nunca stack trace/nome de exceção/senha"
    - "Papel do usuário sempre fixado no código do service, nunca lido do payload de criação (defesa contra mass assignment, T-01-24)"
    - "Testcontainers 'singleton container' (start() em bloco static, sem @Testcontainers/@Container) para bases de integração compartilhadas por múltiplas classes *IT"

key-files:
  created:
    - "auth-service/src/main/java/com/orderflow/auth/company/{Company,CompanyRepository,CompanyService,CompanyController,EmailAlreadyUsedException}.java"
    - "auth-service/src/main/java/com/orderflow/auth/company/dto/{CreateCompanyRequest,CompanyResponse}.java"
    - "auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java"
    - "auth-service/src/test/java/com/orderflow/auth/support/TestTokens.java"
    - "auth-service/src/test/java/com/orderflow/auth/CompanyControllerIT.java"
  modified:
    - "auth-service/src/main/java/com/orderflow/auth/user/User.java (construtor público + created_at insertable=false)"
    - "auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java (padrão singleton container)"
    - "auth-service/src/test/java/com/orderflow/auth/AuthControllerIT.java (2 testes novos de company_id/sub)"

key-decisions:
  - "Payload único de criação de empresa+BUYER numa só requisição transacional (decisão de 'Claude's Discretion' do 01-CONTEXT.md), em vez de um fluxo de dois passos"
  - "@JsonIgnoreProperties(ignoreUnknown = true) em CreateCompanyRequest e no record aninhado BuyerUser — um campo extra de papel enviado pelo cliente é ignorado na desserialização, nunca rejeitado nem lido"
  - "AccessDeniedException tratada em @RestControllerAdvice — o 403 de @PreAuthorize é lançado durante a invocação do método do controller (dentro do despacho normal do Spring MVC), por isso chega ao @ExceptionHandler global; o 401 de request sem token nunca chega aqui, é respondido pelo filtro do OAuth2 Resource Server"

patterns-established:
  - "GlobalExceptionHandler como ponto único de formato de erro para todo o serviço, extensível pelos próximos planos (01-05 depende desta uniformidade para o CompanyGuard)"
  - "Padrão singleton container em AbstractIntegrationTest — todo novo *IT deste serviço (e o padrão a replicar em catalog/inventory/order/notification-service) evita a armadilha de @Testcontainers stop/start por classe"

requirements-completed: [AUTH-01, COMP-01, AUTH-02]

coverage:
  - id: D11
    description: "POST /companies com token SELLER_ADMIN cria empresa + BUYER numa única requisição transacional; resposta 201 com id/name/creditLimit/buyerUser.role=BUYER"
    requirement: "AUTH-01"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#createCompanyWithBuyerReturns201WithExpectedBody"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#createCompanyIncreasesCompaniesAndUsersRowCountsByOne"
        status: pass
    human_judgment: false
  - id: D12
    description: "Papel do usuário criado é sempre BUYER, fixado no servidor — payload com campo de papel extra é ignorado"
    requirement: "COMP-01"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#createCompanyIgnoresClientSuppliedRoleAndAlwaysCreatesBuyer"
        status: pass
    human_judgment: false
  - id: D13
    description: "Senha do BUYER nunca aparece na resposta (nem texto claro, nem chave password/passwordHash)"
    requirement: "COMP-01"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#createCompanyResponseNeverLeaksPassword"
        status: pass
    human_judgment: false
  - id: D14
    description: "BUYER recebe 403 e SELLER_ADMIN sem token recebe 401 em POST /companies"
    requirement: "AUTH-01"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#createCompanyWithoutAuthorizationHeaderReturns401"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#createCompanyWithBuyerTokenReturns403AndDoesNotInsertRow"
        status: pass
    human_judgment: false
  - id: D15
    description: "creditLimit com 3 casas decimais ou negativo é rejeitado com 400 sem criar empresa; 0.00 é aceito; valor exato de 2 casas é persistido sem alteração"
    requirement: "COMP-01"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#createCompanyWithThreeDecimalCreditLimitReturns400AndCreatesNoCompany"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#createCompanyWithNegativeCreditLimitReturns400"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#createCompanyWithZeroCreditLimitReturns201"
        status: pass
      - kind: integration
        ref: "CompanyControllerIT#createCompanyWithBuyerReturns201WithExpectedBody"
        status: pass
    human_judgment: false
  - id: D16
    description: "Email duplicado devolve 409 e nenhuma linha nova é gravada em companies (rollback transacional)"
    requirement: "COMP-01"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#createCompanyWithDuplicateEmailReturns409AndRollsBackTransaction"
        status: pass
    human_judgment: false
  - id: D17
    description: "Corpo de erro de 400/403/409 é uniforme (error+message), sem stack trace, nome de exceção ou senha"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#errorBodiesForValidationForbiddenAndConflictShareUniformShapeWithoutLeakage"
        status: pass
    human_judgment: false
  - id: D18
    description: "BUYER criado faz login e recebe JWT com company_id (string de UUID) igual ao id da empresa; GET /auth/me confirma companyId/role"
    requirement: "AUTH-02"
    verification:
      - kind: integration
        ref: "CompanyControllerIT#buyerCreatedByAdminLogsInAndReceivesCompanyIdClaimMatchingCreatedCompany"
        status: pass
    human_judgment: false
  - id: D19
    description: "JWT do SELLER_ADMIN não possui o claim company_id (ausência da chave, não valor nulo)"
    requirement: "AUTH-02"
    verification:
      - kind: integration
        ref: "AuthControllerIT#sellerAdminTokenDoesNotHaveCompanyIdClaim"
        status: pass
    human_judgment: false
  - id: D20
    description: "sub do JWT do BUYER é o UUID do próprio usuário, nunca o da empresa"
    requirement: "AUTH-02"
    verification:
      - kind: integration
        ref: "AuthControllerIT#buyerTokenSubjectIsBuyerUserIdNotCompanyId"
        status: pass
    human_judgment: false

duration: ~45min
completed: 2026-09-18
status: complete
---

# Phase 1 Plan 4: Criação de empresa compradora + JWT com company_id Summary

**SELLER_ADMIN cria empresas compradoras com limite de crédito exato e o usuário BUYER vinculado numa única requisição transacional; o BUYER autentica e recebe um JWT cujo claim `company_id` (string de UUID, omitido para SELLER_ADMIN) é o contrato herdado pelas Fases 2-6 — tudo provado contra PostgreSQL real via Testcontainers.**

## Performance

- **Duration:** ~45 min de execução ativa
- **Completed:** 2026-09-18
- **Tasks:** 2/2
- **Files modified:** 13 (8 novos, 5 modificados)

## Accomplishments
- Pacote `company` completo: `Company` (JPA, `credit_limit NUMERIC(19,2)`, `BigDecimal` escala 2), `CompanyRepository`, `CompanyService` (`@Transactional`, papel `BUYER` fixado no código, nunca lido do payload), `CompanyController` (`POST /companies`, `@PreAuthorize("hasRole('SELLER_ADMIN')")`), DTOs `CreateCompanyRequest`/`CompanyResponse` e `EmailAlreadyUsedException`
- `GlobalExceptionHandler` unifica o corpo de erro (`error`/`message`, `+fields` para validação) para `MethodArgumentNotValidException` (400), `AccessDeniedException` (403), `EmailAlreadyUsedException`/`DataIntegrityViolationException` (409) e `AuthenticationException` (401) — sem stack trace, nome de exceção ou senha em nenhum corpo
- 12 testes de integração em `CompanyControllerIT` provam contra PostgreSQL real: criação transacional 201, contagem de linhas antes/depois, ausência de senha na resposta, papel do cliente sempre ignorado (mass assignment), 401/403 por autorização, validação monetária exata (3 casas/negativo rejeitados, zero aceito), rollback em email duplicado, e uniformidade do corpo de erro
- Claim `company_id` do JWT provado ponta a ponta: teste encadeado (admin cria empresa → BUYER loga → `company_id` bate em texto com o `id` da empresa), mais dois testes dedicados em `AuthControllerIT` — ausência da chave (não valor nulo) para SELLER_ADMIN, e `sub` do BUYER sendo o UUID do usuário, nunca o da empresa
- `TestTokens`, helper de login real via `MockMvc`, extraído para reutilização pelos próximos planos

## Task Commits

Cada task foi commitada atomicamente:

1. **Task 1: Criação de empresa compradora com usuário BUYER vinculado** - `5254d44` (feat, TDD: RED confirmado via falha de compilação com o pacote `company`/`GlobalExceptionHandler` temporariamente movidos para fora do classpath, depois GREEN)
2. **Task 2: O BUYER criado se autentica e o JWT carrega o ID da sua empresa** - `8b610e7` (test — `TokenService` já emitia o claim corretamente desde o plano 01-02; nenhuma mudança de produção necessária, apenas prova)

## Files Created/Modified
- `auth-service/.../company/{Company,CompanyRepository,CompanyService,CompanyController,EmailAlreadyUsedException}.java` - domínio de empresa compradora
- `auth-service/.../company/dto/{CreateCompanyRequest,CompanyResponse}.java` - payload único de criação e resposta sem campo de senha
- `auth-service/.../config/GlobalExceptionHandler.java` - corpo de erro uniforme do serviço
- `auth-service/src/test/.../support/TestTokens.java` - helper de login real via MockMvc
- `auth-service/src/test/.../CompanyControllerIT.java` - 13 testes de integração (AUTH-01, COMP-01, AUTH-02)
- `auth-service/.../user/User.java` - construtor público para o BUYER + `created_at insertable=false` (deviation)
- `auth-service/src/test/.../AbstractIntegrationTest.java` - padrão singleton container (deviation)
- `auth-service/src/test/.../AuthControllerIT.java` - 2 testes novos (ausência de `company_id`, `sub` do BUYER)

## Decisions Made
- Payload único de criação de empresa+BUYER numa só requisição transacional (Claude's Discretion do 01-CONTEXT.md)
- `@JsonIgnoreProperties(ignoreUnknown = true)` em `CreateCompanyRequest`/`BuyerUser` — campo extra de papel do cliente é ignorado silenciosamente na desserialização, nunca rejeita a requisição nem é lido
- `AccessDeniedException` tratada no `@RestControllerAdvice` global — o 403 de `@PreAuthorize` é lançado durante a invocação do método do controller, dentro do despacho normal do Spring MVC, por isso chega ao handler; o 401 de request sem token é respondido pelo filtro do OAuth2 Resource Server, antes do `DispatcherServlet`, e nunca passa por aqui
- **Formato final do claim `company_id` (contrato herdado pelas Fases 2-6):** string de texto do UUID da empresa quando o usuário é BUYER; claim inteiramente omitido (não nulo) quando o usuário é SELLER_ADMIN — comparável por igualdade direta com o `id` devolvido por `POST /companies`, sem conversão

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `User` não tinha construtor/setter para criar o BUYER a partir de `CompanyService`**
- **Found during:** Task 1, implementação de `CompanyService.createCompanyWithBuyer`
- **Issue:** A entidade `User` (criada no plano 01-02) só expunha `@Getter` e um construtor sem argumentos `protected` — não havia forma programática de construir um novo `User` com email/hash/papel/companyId a partir de outro pacote.
- **Fix:** Adicionado um construtor público `User(String email, String passwordHash, Role role, UUID companyId)`, mantendo o construtor sem argumentos `protected` para o Hibernate. O papel é sempre passado explicitamente pelo chamador (nunca a partir de payload de cliente).
- **Files modified:** `auth-service/src/main/java/com/orderflow/auth/user/User.java`
- **Commit:** `5254d44`

**2. [Rule 1 - Bug] `User.createdAt` sem `insertable=false` violava o NOT NULL da coluna ao persistir um BUYER via JPA**
- **Found during:** Task 1, primeira execução de `CompanyControllerIT`
- **Issue:** A coluna `users.created_at` é `NOT NULL DEFAULT now()`. O mapeamento original do campo `createdAt` não tinha `insertable=false`, então o Hibernate incluía a coluna no INSERT com valor `NULL` (nenhum construtor da aplicação a preenchia), violando a constraint. Isso nunca havia sido exercitado porque o único usuário existente (SELLER_ADMIN) é semeado via INSERT SQL direto na migração V2, nunca via JPA.
- **Fix:** Adicionado `insertable = false, updatable = false` à anotação `@Column(name = "created_at")`, deixando o valor default do Postgres assumir — mesmo padrão já usado em `Company.createdAt`.
- **Files modified:** `auth-service/src/main/java/com/orderflow/auth/user/User.java`
- **Commit:** `5254d44`

**3. [Rule 1 - Bug] `AbstractIntegrationTest` quebrava ao ganhar uma segunda classe `*IT`**
- **Found during:** Task 1, primeira execução de `./mvnw -B -pl auth-service verify` (suíte completa com `AuthControllerIT` + `CompanyControllerIT`)
- **Issue:** `AbstractIntegrationTest` usava `@Testcontainers` + `@Container static PostgreSQLContainer` — um padrão que, com um único `*IT` (só `AuthControllerIT`, no plano 01-02), funcionava sem problema. Ao herdar o mesmo campo `static` em uma SEGUNDA classe (`CompanyControllerIT`), a extensão JUnit 5 `@Testcontainers` chamava `stop()` no container compartilhado ao final de `AuthControllerIT` (`afterAll`), e ao iniciar `CompanyControllerIT` recriava um container **novo** (ID e porta diferentes), enquanto o pool de conexões Hikari da aplicação ainda apontava para a porta antiga — produzindo `Connection refused`, `CannotCreateTransactionException` e `401` aleatórios em qualquer teste que dependesse de login, incluindo em `AuthControllerIT` isoladamente quando rodado depois. Reproduzido de forma consistente em duas execuções completas da suíte.
- **Fix:** Trocado o padrão para "singleton container" (documentado pelo próprio Testcontainers): removidas as anotações `@Testcontainers`/`@Container`; o container agora é `static final` e chama `.start()` dentro de um bloco `static` — iniciado uma única vez por JVM, nunca interrompido entre classes de teste. A limpeza fica a cargo do Ryuk/encerramento da JVM, como já acontecia antes para o `testcontainers/ryuk`.
- **Files modified:** `auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java`
- **Commit:** `5254d44`

---

**Total deviations:** 3 auto-fixed (1 Rule 3 - blocking, 2 Rule 1 - bug)
**Impact on plan:** Todos os auto-fixes eram necessários para que `./mvnw -B -pl auth-service verify` (a verificação exigida pelo próprio plano) passasse com a suíte completa. Nenhum scope creep — nenhuma funcionalidade fora do escopo de AUTH-01/COMP-01/AUTH-02 foi adicionada. A correção do `AbstractIntegrationTest` é infraestrutura de teste compartilhada (não listada em `files_modified` do plano) mas bloqueante: sem ela, nenhum plano futuro poderia adicionar uma segunda classe `*IT` sem reproduzir a mesma falha intermitente.

## Issues Encountered
- Nenhum bloqueio não resolvido. O maior tempo de diagnóstico foi gasto identificando a causa raiz da falha intermitente do Testcontainers (deviation 3) — os sintomas (401 aleatório em `adminToken()`, depois `CannotCreateTransactionException`) inicialmente pareciam um bug de aplicação, não de infraestrutura de teste.

## User Setup Required

None - nenhuma configuração de serviço externo é necessária para este plano.

## Next Phase Readiness
- `01-05` pode implementar `CompanyGuard`/`@PreAuthorize` sabendo que o claim `company_id` está provado como string de UUID, comparável por igualdade direta com o `id` de `Company`
- O padrão singleton container em `AbstractIntegrationTest` está pronto para receber quantas classes `*IT` os planos seguintes desta fase precisarem, sem repetir a falha intermitente encontrada aqui
- Nenhum bloqueio conhecido para os próximos planos desta fase

---
*Phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas*
*Completed: 2026-09-18*

## Self-Check: PASSED

- FOUND: auth-service/src/main/java/com/orderflow/auth/company/Company.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/company/CompanyService.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/company/CompanyController.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/config/GlobalExceptionHandler.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/CompanyControllerIT.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/support/TestTokens.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/AuthControllerIT.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/AbstractIntegrationTest.java
- FOUND: auth-service/src/main/java/com/orderflow/auth/user/User.java
- FOUND commit: 5254d44
- FOUND commit: 8b610e7
