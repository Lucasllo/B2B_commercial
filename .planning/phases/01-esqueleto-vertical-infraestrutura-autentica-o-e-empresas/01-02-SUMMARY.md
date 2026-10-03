---
phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
plan: "02"
subsystem: auth
tags: [spring-boot, spring-security, jwt, nimbus-jose, flyway, postgres, testcontainers, maven-wrapper, tdd]

requires:
  - phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
    provides: "SPRING_BOOT_LINE=3.5.16 e SPRING_CLOUD_TRAIN=2025.0.3 (Northfields), decididos em 01-01"
provides:
  - "Reactor Maven pai com BOMs Spring Boot/Spring Cloud/Testcontainers e módulos gateway + auth-service"
  - "Maven Wrapper commitado (mvnw/mvnw.cmd/.mvn/wrapper), funcional sem Maven de sistema"
  - "Dockerfiles multi-stage pinados a eclipse-temurin:21.0.12_8 (jdk/jre-jammy)"
  - "Schema auth no Postgres (companies, users) via Flyway V1, SELLER_ADMIN semeado via V2 (D-07)"
  - "Emissão e validação local de JWT RS256 (NimbusJwtEncoder/JwtDecoder, JWKS em /.well-known/jwks.json)"
  - "POST /auth/login e GET /auth/me funcionando ponta a ponta contra Postgres real (Testcontainers)"
  - "auth-service como analog canônico de estrutura de pacotes/SecurityConfig/Flyway/Testcontainers para as Fases 2-6"
affects:
  - "01-03-PLAN.md (docker-compose.yml consumirá os Dockerfiles e a tag postgres:16.15 já fixada aqui)"
  - "01-04-PLAN.md (Company entity e endpoints de criação de empresa/usuário BUYER sobre o mesmo schema auth)"
  - "01-05-PLAN.md (CompanyGuard/@PreAuthorize sobre o claim company_id já emitido por TokenService)"
  - "Fases 2-6 (catalog/inventory/order/notification-service espelham a estrutura deste módulo)"

actuals:
  tokens: 19842
  tasks: 2
  commits: 3
  plan_head_before: df15ce6f89d6767b1ce02b04c6e01969f13e069f

tech-stack:
  added:
    - "Spring Boot 3.5.16 / Spring Cloud 2025.0.3 Northfields (parent reactor, sem spring-boot-starter-parent)"
    - "Maven Wrapper 3.3.2 (distributionType=only-script, apache-maven 3.9.9)"
    - "Spring Cloud Gateway Server WebMVC (spring-cloud-starter-gateway-server-webmvc)"
    - "Spring Security OAuth2 Resource Server + spring-security-oauth2-jose (NimbusJwtEncoder/JwtDecoder)"
    - "Flyway 11.7.2 (flyway-core + flyway-database-postgresql)"
    - "Testcontainers 1.21.4 (postgresql + spring-boot-testcontainers/@ServiceConnection)"
    - "Lombok 1.18.46 (resolvido via lombok.version>=1.18.34)"
  patterns:
    - "auth-service como analog canônico de pacote-por-feature (config/auth/user) para Fases 2-6"
    - "NimbusJwtEncoder + JwksController manual (toPublicJWK) — self-issued JWT sem IdP externo (D-02)"
    - "SecurityFilterChain via HttpSecurity DSL + @EnableMethodSecurity, sem WebSecurityConfigurerAdapter"
    - "AbstractIntegrationTest com PostgreSQLContainer static + @ServiceConnection — um único contexto Spring por suíte (evita rotação de chave RSA em teste, Pitfall 4)"
    - "Flyway V1 (schema) + V2 (seed) — nunca editar após aplicado, mudanças vão para V3__"
    - "spring-boot-maven-plugin repackage com classifier=exec — mantém o jar plano como artefato principal para não corromper o classpath do maven-failsafe-plugin"

key-files:
  created:
    - "pom.xml (reactor pai)"
    - "mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties"
    - ".gitignore, .gitattributes"
    - "gateway/pom.xml, gateway/Dockerfile, gateway/src/main/java/com/orderflow/gateway/GatewayApplication.java"
    - "auth-service/pom.xml, auth-service/Dockerfile, auth-service/src/main/java/com/orderflow/auth/AuthServiceApplication.java"
    - "auth-service/src/main/java/com/orderflow/auth/user/{Role,User,UserRepository,CustomUserDetailsService}.java"
    - "auth-service/src/main/java/com/orderflow/auth/config/{JwtIssuerConfig,JwksController,SecurityConfig}.java"
    - "auth-service/src/main/java/com/orderflow/auth/auth/{TokenService,AuthController}.java + dto/{LoginRequest,LoginResponse,CurrentUserResponse}.java"
    - "auth-service/src/main/resources/application.yml, db/migration/{V1__init_auth_schema,V2__seed_seller_admin}.sql"
    - "auth-service/src/test/java/com/orderflow/auth/{AbstractIntegrationTest,AuthControllerIT,SeedPasswordHashTest}.java"
    - "auth-service/src/test/resources/application-test.yml"
  modified: []

key-decisions:
  - "Maven Wrapper bootstrapado pelo caminho de contingência do plano: o Spring Initializr passou a exigir bootVersion>=4.0.0 e rejeitou 3.5.16 (HTTP 400) — wrapper 3.3.2 baixado direto da tag maven-wrapper-3.3.2 do apache/maven-wrapper, com wrapperUrl explícito em maven-wrapper.properties para resolver o jar sob demanda sem commitá-lo"
  - "gateway artifact: spring-cloud-starter-gateway-server-webmvc resolveu diretamente no trem 2025.0.3 (Northfields) — não foi necessário o nome legado spring-cloud-starter-gateway-mvc"
  - "eclipse-temurin:21.0.12_8-jdk-jammy (build) / 21.0.12_8-jre-jammy (runtime) confirmados como as tags de patch correntes via Docker Hub v2 tags API (Docker estava indisponível no momento da Task 1, disponível na retomada da Task 2)"
  - "spring-boot-maven-plugin repackage com classifier=exec no pom.xml raiz — sem isso, o goal repackage substitui o artefato principal do módulo pelo jar gordo, e o maven-failsafe-plugin (que roda depois, na fase integration-test) monta o classpath de teste a partir de BOOT-INF/classes, invisível a um classloader comum, quebrando toda classe da aplicação com NoClassDefFoundError"
  - "Conversor de authority customizado (lambda) em vez do JwtGrantedAuthoritiesConverter padrão do Spring Security — o claim role é uma string singular, não uma lista, e o conversor padrão espera list em scope/scp"
  - "iss do JWT lido sempre via jwt.getClaimAsString(\"iss\"), nunca jwt.getIssuer() — orderflow-auth-service não é uma URL válida por design (D-02/D-03), e Jwt#getIssuer() lança exceção ao tentar convertê-la"

patterns-established:
  - "Pacote-por-feature (config/auth/user) em vez de pacote-por-camada — convenção que catalog/inventory/order/notification-service devem espelhar"
  - "AbstractIntegrationTest como classe base de Testcontainers reutilizável entre serviços futuros"
  - "Migrações Flyway V1 (schema) + V2 (seed), nunca editadas após aplicadas"

requirements-completed: [INFRA-01, AUTH-02, AUTH-03]

coverage:
  - id: D1
    description: "Reactor Maven + wrapper produzem jar executável para gateway e auth-service a partir de um clone limpo, sem Maven de sistema"
    requirement: "INFRA-01"
    verification:
      - kind: other
        ref: "./mvnw -B -pl auth-service,gateway package -DskipTests → BUILD SUCCESS"
        status: pass
    human_judgment: false
  - id: D2
    description: "Dockerfiles multi-stage do auth-service e do gateway pinados a uma tag de patch real do eclipse-temurin (21.0.12_8), curl instalado, usuário não-root"
    requirement: "INFRA-01"
    verification:
      - kind: other
        ref: "grep -cE '^FROM eclipse-temurin:21\\.[0-9]+\\.[0-9]+' auth-service/Dockerfile gateway/Dockerfile"
        status: pass
    human_judgment: false
  - id: D3
    description: "POST /auth/login com o SELLER_ADMIN semeado por Flyway devolve 200 com accessToken, tokenType=Bearer, expiresIn=3600"
    requirement: "AUTH-02"
    verification:
      - kind: integration
        ref: "AuthControllerIT#loginWithValidCredentialsReturns200WithAccessToken"
        status: pass
    human_judgment: false
  - id: D4
    description: "JWT emitido é RS256, iss=orderflow-auth-service, sub=UUID do usuário, role=SELLER_ADMIN, sem claim company_id, exp-iat=3600, kid bate com o publicado no JWKS"
    requirement: "AUTH-02"
    verification:
      - kind: integration
        ref: "AuthControllerIT#issuedTokenHasExpectedClaimsAndHeader"
        status: pass
    human_judgment: false
  - id: D5
    description: "Senha errada e email inexistente devolvem 401 com corpo byte a byte idêntico; campos em branco devolvem 400 via Bean Validation"
    requirement: "AUTH-02"
    verification:
      - kind: integration
        ref: "AuthControllerIT#loginWithWrongPasswordAndUnknownEmailReturnIdenticalUnauthorizedBody"
        status: pass
      - kind: integration
        ref: "AuthControllerIT#loginWithBlankEmailReturns400"
        status: pass
      - kind: integration
        ref: "AuthControllerIT#loginWithBlankPasswordReturns400"
        status: pass
    human_judgment: false
  - id: D6
    description: "GET /.well-known/jwks.json devolve exatamente uma chave RSA pública de assinatura, sem nenhum campo de material privado"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "AuthControllerIT#jwksEndpointReturnsPublicKeyOnly"
        status: pass
    human_judgment: false
  - id: D7
    description: "GET /auth/me com token válido devolve os claims do próprio token (userId, role, companyId nulo); rejeita ausência de header, token expirado e token assinado por outra chave — tudo sem chamada em tempo de execução ao auth-service"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "AuthControllerIT#meWithValidTokenReturns200WithClaims"
        status: pass
      - kind: integration
        ref: "AuthControllerIT#meWithoutAuthorizationHeaderReturns401"
        status: pass
      - kind: integration
        ref: "AuthControllerIT#meWithExpiredTokenReturns401"
        status: pass
      - kind: integration
        ref: "AuthControllerIT#meWithTokenSignedByDifferentKeyReturns401"
        status: pass
    human_judgment: false
  - id: D8
    description: "GET /actuator/health responde 200 publicamente, consumível pelo healthcheck do docker-compose (01-03)"
    requirement: "INFRA-01"
    verification:
      - kind: integration
        ref: "AuthControllerIT#actuatorHealthIsPubliclyAccessible"
        status: pass
    human_judgment: false
  - id: D9
    description: "Tabela companies criada com credit_limit NUMERIC(19,2) (D-06)"
    verification:
      - kind: other
        ref: "grep -c 'NUMERIC(19,2)' auth-service/src/main/resources/db/migration/V1__init_auth_schema.sql"
        status: pass
    human_judgment: false
  - id: D10
    description: "Senha do SELLER_ADMIN persistida apenas como hash BCrypt; SeedPasswordHashTest prova a correspondência com a senha de demonstração documentada, sem texto claro na migração"
    verification:
      - kind: unit
        ref: "SeedPasswordHashTest#seedHashMatchesDemoPassword"
        status: pass
      - kind: unit
        ref: "SeedPasswordHashTest#seedHashHasBCryptPrefixAndMinimumCost"
        status: pass
    human_judgment: false

duration: 23min
completed: 2026-09-18
status: complete
---

# Phase 1 Plan 2: Esqueleto de build + tracer de autenticação Summary

**Reactor Maven com Wrapper 3.3.2 bootstrapado por contingência, dois módulos empacotáveis, e o tracer completo do OrderFlow: SELLER_ADMIN semeado por Flyway faz login e recebe um JWT RS256 auto-emitido (NimbusJwtEncoder + JWKS manual) que o próprio serviço valida localmente e aceita/rejeita corretamente em `GET /auth/me`.**

## Performance

- **Duration:** ~23 min de execução ativa (Task 1 ~10 min; checkpoint por Docker Desktop indisponível; Task 2 ~13 min após retomada)
- **Started:** 2026-09-18T22:06:22-03:00 (HEAD do plano)
- **Completed:** 2026-09-18T22:35:18-03:00
- **Tasks:** 2/2
- **Files modified:** 31

## Accomplishments
- Reactor Maven pai (BOMs Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / Testcontainers) com módulos `gateway` e `auth-service`, empacotando jar executável a partir de um clone limpo sem Maven de sistema
- Maven Wrapper commitado via caminho de contingência (Spring Initializr passou a exigir Boot >=4.0.0)
- Dockerfiles multi-stage pinados a `eclipse-temurin:21.0.12_8-jdk-jammy`/`21.0.12_8-jre-jammy`, confirmados via Docker Hub API
- Schema `auth` (companies/users, `credit_limit NUMERIC(19,2)`) criado por Flyway V1; SELLER_ADMIN de bootstrap semeado por V2 com hash BCrypt pré-computado
- Emissão de JWT RS256 self-issued (par de chaves gerado no startup, D-02) e validação 100% local via `NimbusJwtDecoder` + `JwtValidators.createDefaultWithIssuer` (D-03/AUTH-03, sem chamada em tempo de execução)
- `POST /auth/login` e `GET /auth/me` provados ponta a ponta contra PostgreSQL real via Testcontainers — 13 testes (2 unitários + 11 de integração), todos verdes

## Task Commits

Cada task foi commitada atomicamente:

1. **Task 1: Reactor Maven, wrapper commitado, módulos empacotáveis e Dockerfiles fixados** - `2b32fbf` (feat) + `d589b0f` (fix: bit executável do mvnw)
2. **Task 2: Tracer — login real do SELLER_ADMIN semeado, do Flyway ao JWT verificável** - `954dd70` (feat, TDD: RED confirmado por falha de compilação/NoClassDefFoundError antes da implementação, depois GREEN)

**Plan metadata:** (a ser adicionado no commit final de documentação, fora deste SUMMARY)

_Nota: a Task 2 é `type="tracer" tdd="true"` — testes (`SeedPasswordHashTest`, `AuthControllerIT`) escritos antes da implementação, RED confirmado (falha de compilação por classes de produção inexistentes), depois GREEN com `./mvnw -B -pl auth-service verify`._

## Files Created/Modified
- `pom.xml` - reactor pai com BOMs e execução de `repackage` (classifier `exec`)
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` - Maven Wrapper 3.3.2, `distributionType=only-script`
- `.gitignore`, `.gitattributes` - exclui `target/`/jar do wrapper, força `eol=lf` em `mvnw`
- `gateway/pom.xml`, `gateway/Dockerfile`, `gateway/.../GatewayApplication.java` - módulo gateway (D-05: sem segurança)
- `auth-service/pom.xml`, `auth-service/Dockerfile`, `auth-service/.../AuthServiceApplication.java` - módulo auth-service
- `auth-service/.../user/{Role,User,UserRepository,CustomUserDetailsService}.java` - domínio de usuário
- `auth-service/.../config/{JwtIssuerConfig,JwksController,SecurityConfig}.java` - emissão/validação de JWT e segurança
- `auth-service/.../auth/{TokenService,AuthController}.java` + `dto/*` - login e `/auth/me`
- `auth-service/.../db/migration/{V1__init_auth_schema,V2__seed_seller_admin}.sql` - schema e seed
- `auth-service/src/test/.../{AbstractIntegrationTest,AuthControllerIT,SeedPasswordHashTest}.java` - suíte TDD

## Decisions Made
- Maven Wrapper: caminho de contingência (Spring Initializr rejeitou `bootVersion=3.5.16`, range agora exige `>=4.0.0`) — wrapper 3.3.2 baixado da tag `maven-wrapper-3.3.2` do `apache/maven-wrapper`, `wrapperUrl` explícito no `maven-wrapper.properties`
- Artefato do gateway: `spring-cloud-starter-gateway-server-webmvc` resolveu direto no trem 2025.0.3 — nome legado não foi necessário
- Tag do eclipse-temurin: `21.0.12_8-jdk-jammy`/`21.0.12_8-jre-jammy`, confirmada via Docker Hub v2 tags API
- `classifier=exec` no `spring-boot-maven-plugin` — evita que `repackage` substitua o artefato principal do módulo pelo jar gordo, o que quebraria o classpath do `maven-failsafe-plugin`
- Conversor de authority customizado em `SecurityConfig` (claim `role` é string singular, não lista)
- `iss` sempre lido via `getClaimAsString`, nunca `getIssuer()` (que exige URL válida)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `spring-boot-maven-plugin` repackage sem classifier corrompia o classpath do `maven-failsafe-plugin`**
- **Found during:** Task 2, primeira execução de `./mvnw -B -pl auth-service verify`
- **Issue:** O goal `repackage` (ligado à fase `package`, antes de `integration-test`) substituía o artefato principal `auth-service.jar` pelo jar gordo do Spring Boot. O `maven-failsafe-plugin`, ao montar o classpath de teste na fase seguinte, encontrava as classes da aplicação apenas dentro de `BOOT-INF/classes/` do jar gordo — invisível a um `URLClassLoader` comum — resultando em `NoClassDefFoundError` para toda classe do pacote `com.orderflow.auth.*` referenciada no teste de integração.
- **Fix:** Adicionado `<classifier>exec</classifier>` à execução `repackage` no `pom.xml` raiz. O jar plano (`auth-service.jar`) permanece o artefato principal (usado por Maven/failsafe); o jar executável passa a se chamar `auth-service-exec.jar` (idem para o gateway).
- **Files modified:** `pom.xml`, `auth-service/Dockerfile`, `gateway/Dockerfile` (Dockerfiles atualizados para copiar `*-exec.jar`)
- **Commit:** `954dd70`

**2. [Rule 3 - Blocking] `spring-boot-testcontainers` ausente da lista de dependências do plano**
- **Found during:** Task 2, RED — erro de compilação `package org.springframework.boot.testcontainers.service.connection does not exist`
- **Issue:** `@ServiceConnection` (usado em `AbstractIntegrationTest`) vem do artefato `spring-boot-testcontainers`, não capturado na lista de dependências do plano original.
- **Fix:** Adicionada a dependência de teste `org.springframework.boot:spring-boot-testcontainers` ao `auth-service/pom.xml`.
- **Files modified:** `auth-service/pom.xml`
- **Commit:** `954dd70`

**3. [Rule 1 - Bug] `mvnw` commitado sem bit executável (Windows `core.filemode=false`)**
- **Found during:** Task 1, após o primeiro commit
- **Issue:** `git ls-files -s mvnw` mostrava modo `100644`; um checkout Linux (CI GitHub Actions) sem `chmod` prévio falharia com "Permission denied" ao rodar `./mvnw`.
- **Fix:** `git update-index --chmod=+x mvnw`, commitado separadamente.
- **Files modified:** `mvnw` (apenas metadado de modo, sem alteração de conteúdo)
- **Commit:** `d589b0f`

**4. [Rule 2 - Missing Critical] Comentário de `V2__seed_seller_admin.sql` embutia a senha de demonstração em texto claro**
- **Found during:** Task 2, verificação final dos acceptance criteria ("não contém a senha em texto claro")
- **Issue:** O comentário explicativo da migração citava literalmente `"ChangeMe!123"` como exemplo de como o hash foi gerado — violando ao pé da letra o acceptance criteria do plano, mesmo sendo um comentário e não um valor de coluna.
- **Fix:** Reescrito o comentário para referenciar o README do projeto e `SeedPasswordHashTest` em vez de citar a senha literal.
- **Files modified:** `auth-service/src/main/resources/db/migration/V2__seed_seller_admin.sql`
- **Commit:** `954dd70`

**5. [Rule 3 - Blocking] `maven-failsafe-plugin` sem versão explícita no `pluginManagement`**
- **Found during:** Task 2, warning do Maven ("It is highly recommended to fix these problems")
- **Issue:** O plano já declarava o plugin em `pluginManagement` sem `<version>`, deixando a resolução a cargo do Maven — inconsistente com a exigência de nunca deixar versão implícita/flutuante.
- **Fix:** Adicionada a propriedade `maven-failsafe-plugin.version=3.2.5` e referenciada na declaração do plugin.
- **Files modified:** `pom.xml`
- **Commit:** `954dd70`

---

**Total deviations:** 5 auto-fixed (2 Rule 1 - bug, 2 Rule 3 - blocking, 1 Rule 2 - missing critical/security)
**Impact on plan:** Todos os auto-fixes eram necessários para correção (build funcional) ou para atender literalmente aos próprios acceptance criteria do plano. Nenhum scope creep — nenhuma funcionalidade fora do escopo da Task 1/2 foi adicionada.

## Issues Encountered
- **Spring Initializr agora exige `bootVersion>=4.0.0`:** o caminho preferido do plano (`curl .../starter.zip?...bootVersion=3.5.16...`) retornou HTTP 400 (`Invalid Spring Boot version '3.5.16'`). Resolvido pelo caminho de contingência documentado no próprio plano (wrapper 3.3.2 baixado direto do `apache/maven-wrapper`).
- **Docker Desktop indisponível no início da Task 2:** `docker info` retornava exit 1 (engine não respondia via named pipe). A execução foi pausada em um checkpoint `human-verify`/`blocking-human` conforme o protocolo de precondition — nenhum arquivo da Task 2 foi tocado até a confirmação humana de que o Docker Desktop estava operacional. Retomado sem incidentes após a confirmação.

## User Setup Required

None - nenhuma configuração de serviço externo é necessária para este plano (LocalStack/`LOCALSTACK_AUTH_TOKEN` é escopo do plano `01-03`).

## Next Phase Readiness
- `auth-service` está pronto para servir de analog canônico às Fases 2-6 (estrutura de pacotes, `SecurityConfig`, layout Flyway, `AbstractIntegrationTest`)
- `01-03` pode consumir os Dockerfiles e a tag `postgres:16.15` já fixada no `AbstractIntegrationTest`
- `01-04` pode adicionar `Company`, `CompanyRepository`, `CompanyController` e o endpoint de criação de empresa+usuário BUYER sobre o mesmo schema `auth`
- `01-05` pode implementar `CompanyGuard`/`@PreAuthorize` sobre o claim `company_id` já emitido por `TokenService`
- Nenhum bloqueio conhecido para os próximos planos desta fase

---
*Phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas*
*Completed: 2026-09-18*

## Self-Check: PASSED

- FOUND: pom.xml
- FOUND: auth-service/src/main/java/com/orderflow/auth/auth/AuthController.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/AuthControllerIT.java
- FOUND: auth-service/src/test/java/com/orderflow/auth/SeedPasswordHashTest.java
- FOUND commit: 2b32fbf
- FOUND commit: d589b0f
- FOUND commit: 954dd70
