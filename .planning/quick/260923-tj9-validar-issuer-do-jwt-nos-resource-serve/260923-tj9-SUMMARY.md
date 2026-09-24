---
phase: quick-260923-tj9
plan: 01
subsystem: auth
tags: [jwt, spring-security, oauth2-resource-server, issuer-validation, testcontainers]

# Dependency graph
requires:
  - phase: 03
    provides: "notification-service, catalog-service e inventory-service como resource servers OAuth2 com jwk-set-uri configurado (03-REVIEW.md finding WR-07, threat T-03-02)"
provides:
  - "Validacao do claim iss (issuer-uri) nos tres resource servers de producao, fechando T-03-02"
  - "TestJwt.Config dos tres servicos usando JwkSetUriJwtDecoderBuilderCustomizer em vez de um JwtDecoder de teste proprio — paridade teste/producao (WR-07)"
  - "Padrao replicavel para o resource server do order-service (Fase 4)"
affects: [phase-04-order-service, auth-service, gateway]

# Actuals (#2632)
actuals:
  tokens: 6122
  tasks: 3
  commits: 3
plan_head_before: 8fef964f516a9b1e6560ec3b513a30045d4b3942

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "issuer-uri ao lado de jwk-set-uri no application.yml: sem descoberta OIDC, so valor esperado do claim iss (Spring Boot 3.5.16 IssuerUriCondition)"
    - "JwkSetUriJwtDecoderBuilderCustomizer nos testes, trocando so a fonte de chave (JWSVerificationKeySelector sobre chave publica em memoria) em vez de publicar um JwtDecoder de teste proprio — o decoder, os validadores padrao e o validador de emissor continuam vindo da autoconfiguracao de producao"

key-files:
  created: []
  modified:
    - notification-service/src/main/resources/application.yml
    - notification-service/src/test/java/com/orderflow/notification/support/TestJwt.java
    - notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java
    - catalog-service/src/main/resources/application.yml
    - catalog-service/src/test/java/com/orderflow/catalog/support/TestJwt.java
    - catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java
    - inventory-service/src/main/resources/application.yml
    - inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java
    - inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java

key-decisions:
  - "Usar a propriedade padrao do Boot spring.security.oauth2.resourceserver.jwt.issuer-uri em vez de um bean JwtDecoder proprio em SecurityConfig — uma linha de YAML por servico, sem tocar em SecurityConfig.java, sem duplicar o que o Boot ja faz"
  - "issuer-uri default = orderflow-auth-service (literal, nao URL) — igual a constante que o auth-service ja emite (TokenService.java:20), entao docker-compose.yml nao precisa de variavel nova"
  - "Nos testes, o JwtDecoder.Config antigo foi substituido por um JwkSetUriJwtDecoderBuilderCustomizer que so troca a fonte de chave, para que o teste exercite o MESMO decoder de producao (validadores, emissor incluidos) em vez de um decoder de teste divergente"

patterns-established:
  - "Um IT por resource server que prova 401 para iss errado e para iss ausente, com controle positivo (iss correto) nao-401 na mesma rota, e um RED obrigatorio registrado antes de qualquer linha de configuracao nova"

requirements-completed: [AUTH-03]

coverage:
  - id: D1
    description: "notification-service rejeita (401) token assinado pela chave confiavel com iss errado ou ausente, e aceita (nao-401) com iss correto — exercitando o decoder de producao autoconfigurado"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java#tokenSignedByTrustedKeyButWrongIssuerReturns401"
        status: pass
      - kind: integration
        ref: "./mvnw -B -pl notification-service verify"
        status: pass
    human_judgment: false
  - id: D2
    description: "catalog-service rejeita (401) token assinado pela chave confiavel com iss errado ou ausente, e aceita (nao-401, 404 esperado) com iss correto — exercitando o decoder de producao autoconfigurado"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java#getProductWithTokenSignedByTrustedKeyButWrongIssuerReturns401"
        status: pass
      - kind: integration
        ref: "./mvnw -B -pl catalog-service verify"
        status: pass
    human_judgment: false
  - id: D3
    description: "inventory-service rejeita (401) token assinado pela chave confiavel com iss errado ou ausente, e aceita (nao-401, 404 esperado) com iss correto — exercitando o decoder de producao autoconfigurado; StockReservationConcurrencyIT herda a mudanca de TestJwt.Config sem edicao propria"
    requirement: "AUTH-03"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java#getStockWithTokenSignedByTrustedKeyButWrongIssuerReturns401"
        status: pass
      - kind: integration
        ref: "./mvnw -B -pl inventory-service verify"
        status: pass
    human_judgment: false
  - id: D4
    description: "Verify conjunto dos tres modulos (gate final do plano) e conferencia de escopo do diff contra o SHA de partida"
    verification:
      - kind: integration
        ref: "./mvnw -B -pl notification-service,catalog-service,inventory-service verify"
        status: pass
    human_judgment: false

duration: 20min
completed: 2026-09-23
status: complete
---

# Phase quick-260923-tj9: Validar issuer do JWT nos resource servers Summary

**notification-service, catalog-service e inventory-service passam a validar o claim `iss` do JWT (issuer-uri no Boot autoconfigurado), fechando T-03-02/WR-07, com testes que agora exercitam o mesmo decoder de produção**

## Performance

- **Duration:** ~20min
- **Started:** 2026-09-23T21:2x (não capturado com precisão — primeira leitura de contexto)
- **Completed:** 2026-09-23T21:46:12-03:00 (fim do verify conjunto)
- **Tasks:** 3/3
- **Files modified:** 9

## Accomplishments
- Os três resource servers de produção (notification-service, catalog-service, inventory-service) agora validam o emissor (`iss`) do JWT, além de assinatura e `exp`/`nbf` — fecha a ameaça T-03-02 (Spoofing, high) do 03-SECURITY
- `TestJwt.Config` dos três serviços deixou de publicar um `JwtDecoder` de teste próprio e passou a publicar um `JwkSetUriJwtDecoderBuilderCustomizer`, trocando só a fonte de chave — os testes agora exercitam o MESMO decoder de produção (validadores e emissor incluídos), fechando a finding WR-07
- Um teste adversarial por serviço prova RED (token com `iss` errado ou ausente aceito, 401 esperado mas recebido outro status) antes da linha `issuer-uri`, e GREEN depois — evidência abaixo
- `StockReservationConcurrencyIT` (inventory-service) herda a mudança de `TestJwt.Config` sem edição própria, confirmando que a troca cobre toda `*IT` do módulo
- `SecurityConfig.java`, `docker-compose.yml`, `auth-service` e `gateway` permanecem intocados — conferido pelo diff de escopo

## Evidência RED/GREEN por serviço

### notification-service (Task 1)
RED (`./mvnw -B -pl notification-service verify -Dit.test=NotificationControllerIT`, antes da linha `issuer-uri`):
```
[ERROR] com.orderflow.notification.NotificationControllerIT.tokenSignedByTrustedKeyButWrongIssuerReturns401 -- Time elapsed: 0.039 s <<< FAILURE!
java.lang.AssertionError: Status expected:<401> but was:<200>
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0
```
Só o novo teste falhou; os outros 5 (incluindo `missingOrInvalidTokenReturns401`, chave errada, token expirado, SELLER_ADMIN 200, BUYER 403) continuaram verdes — prova que o decoder de produção estava sendo exercitado e que a ausência da checagem de `iss` era real.

GREEN (depois da linha `issuer-uri`, `./mvnw -B -pl notification-service verify`): `Tests run: 19, Failures: 0, Errors: 0` — BUILD SUCCESS.

### catalog-service (Task 2)
RED (`./mvnw -B -pl catalog-service verify -Dit.test=ProductControllerIT`):
```
[ERROR] com.orderflow.catalog.ProductControllerIT.getProductWithTokenSignedByTrustedKeyButWrongIssuerReturns401 -- Time elapsed: 0.022 s <<< FAILURE!
java.lang.AssertionError: Status expected:<401> but was:<404>
[ERROR] Tests run: 28, Failures: 1, Errors: 0, Skipped: 0
```
GREEN (`./mvnw -B -pl catalog-service verify`): `Tests run: 32, Failures: 0, Errors: 0` — BUILD SUCCESS.

### inventory-service (Task 3)
RED (`./mvnw -B -pl inventory-service verify -Dit.test=InventoryControllerIT`):
```
[ERROR] com.orderflow.inventory.InventoryControllerIT.getStockWithTokenSignedByTrustedKeyButWrongIssuerReturns401 -- Time elapsed: 0.018 s <<< FAILURE!
java.lang.AssertionError: Status expected:<401> but was:<404>
[ERROR] Tests run: 22, Failures: 1, Errors: 0, Skipped: 0
```
GREEN (`./mvnw -B -pl inventory-service verify`): `Tests run: 37, Failures: 0, Errors: 0` — BUILD SUCCESS, inclui `StockReservationConcurrencyIT` (3 testes) que importa `TestJwt.Config` diretamente e herdou a mudança sem edição.

### Gate final (Task 3, fechamento)
`./mvnw -B -pl notification-service,catalog-service,inventory-service verify`:
```
[INFO] Reactor Summary for catalog-service 1.0.0-SNAPSHOT:
[INFO] catalog-service .................................... SUCCESS [ 24.691 s]
[INFO] inventory-service .................................. SUCCESS [01:12 min]
[INFO] notification-service ............................... SUCCESS [01:00 min]
[INFO] BUILD SUCCESS
```

## Conferência de escopo

`START_SHA` (capturado antes de qualquer edição): `8fef964f516a9b1e6560ec3b513a30045d4b3942`

`git diff --name-only 8fef964f516a9b1e6560ec3b513a30045d4b3942..HEAD` listou exatamente os 9 arquivos de `files_modified` do plano:
```
catalog-service/src/main/resources/application.yml
catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java
catalog-service/src/test/java/com/orderflow/catalog/support/TestJwt.java
inventory-service/src/main/resources/application.yml
inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java
inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java
notification-service/src/main/resources/application.yml
notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java
notification-service/src/test/java/com/orderflow/notification/support/TestJwt.java
```
Nenhum `docker-compose.yml`, `SecurityConfig.java`, arquivo de `auth-service/`, `gateway/` ou `.claude/worktrees/` foi tocado. Nenhuma outra finding do 03-REVIEW.md foi alterada.

## Task Commits

Cada task foi commitada atomicamente:

1. **Task 1 (tracer): notification-service** - `bfda7ec` (fix)
2. **Task 2: catalog-service** - `d128b28` (fix)
3. **Task 3: inventory-service** - `805d5c2` (fix)

_Nota: commit de metadados (SUMMARY/STATE) fica a cargo do orquestrador, conforme constraint da execução._

## Files Created/Modified
- `notification-service/src/main/resources/application.yml` - Adiciona `issuer-uri` com default `orderflow-auth-service`
- `notification-service/src/test/java/com/orderflow/notification/support/TestJwt.java` - `Config` troca `JwtDecoder` próprio por `JwkSetUriJwtDecoderBuilderCustomizer`
- `notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java` - Novo teste `tokenSignedByTrustedKeyButWrongIssuerReturns401`
- `catalog-service/src/main/resources/application.yml` - Adiciona `issuer-uri` com default `orderflow-auth-service`
- `catalog-service/src/test/java/com/orderflow/catalog/support/TestJwt.java` - `Config` troca `JwtDecoder` próprio por `JwkSetUriJwtDecoderBuilderCustomizer`
- `catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java` - Novo teste `getProductWithTokenSignedByTrustedKeyButWrongIssuerReturns401`
- `inventory-service/src/main/resources/application.yml` - Adiciona `issuer-uri` com default `orderflow-auth-service`
- `inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java` - `Config` troca `JwtDecoder` próprio por `JwkSetUriJwtDecoderBuilderCustomizer`
- `inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java` - Novo teste `getStockWithTokenSignedByTrustedKeyButWrongIssuerReturns401`

## Decisions Made
- Propriedade padrão do Boot (`issuer-uri`) em vez de bean `JwtDecoder` próprio em `SecurityConfig` — alternativa do WR-07 rejeitada por duplicar a autoconfiguração e manter o teste divergente da produção (ver `<context>` do plano para a justificativa completa)
- Default do `issuer-uri` é o literal `orderflow-auth-service`, idêntico à constante já emitida pelo auth-service — nenhuma variável nova no docker-compose.yml
- `JwkSetUriJwtDecoderBuilderCustomizer` nos testes troca só a fonte de chave; o decoder de produção (validadores padrão + validador de emissor) permanece o mesmo em teste e produção

## Deviations from Plan

### Auto-fixed Issues (ambiente, Rule 3)

**1. [Rule 3 - Blocking] Stack docker-compose da Fase 3 concorrendo pela sessão LocalStack Hobby**
- **Found during:** Task 1 (primeira tentativa de RED do notification-service)
- **Issue:** A stack `docker-compose` da Fase 3 estava ativa há ~1h (containers healthy, sobrando de uma sessão anterior de UAT/smoke). O container Testcontainers do LocalStack para o teste falhou ao subir (`Container exited with code 126`, `Timed out waiting for log output matching '.*Ready\.'`), aparentemente por concorrência de sessão sob o mesmo `LOCALSTACK_AUTH_TOKEN` (tier Hobby). Isso não tem relação com a mudança de código desta task — era puramente ambiente.
- **Fix:** `docker compose down` (sem `-v`, sem remover volumes/dados) para liberar a sessão LocalStack. Reexecução do mesmo `mvnw verify` funcionou normalmente e produziu o RED esperado.
- **Files modified:** Nenhum (ação apenas de ambiente)
- **Verificação:** RED reproduzido corretamente logo em seguida (`Status expected:<401> but was:<200>`, só o novo teste falhando)
- **Committed in:** N/A (não é mudança de código)

---

**Total deviations:** 1 (ambiente, não relacionado ao código; sem impacto no escopo do plano)
**Impact on plan:** Nenhum — a correção foi puramente operacional (parar uma stack concorrente), documentada para não ser confundida com um bug do decoder.

## Issues Encountered
Nenhum além da deviation de ambiente acima documentada. Todos os RED/GREEN saíram exatamente como o plano previa (`<behavior>` de cada task cumprido: iss errado → 401, iss ausente → 401, iss correto → não-401, suíte existente intocada).

## Smoke Test Opcional (docker-compose)

**Não executado.** O plano condiciona o smoke (`scripts/smoke-notification-flow.sh`) a "a stack do docker compose da Fase 3 já estiver de pé e o rebuild for barato". A stack estava de pé no início desta execução, mas precisou ser derrubada (`docker compose down`) para resolver a concorrência de sessão LocalStack descrita acima — recolocá-la de pé com rebuild das três imagens deixaria de ser "barato". Confiança de que o comportamento em produção está correto vem de duas fontes independentes já verificadas nesta execução: (1) o valor padrão do placeholder `issuer-uri` (`orderflow-auth-service`) é textualmente idêntico à constante `TokenService.ISSUER`/`JwtIssuerConfig.ISSUER` que o auth-service já emite, então nenhuma divergência de valor é possível sem uma mudança deliberada de configuração; (2) os testes de integração de cada serviço agora rodam sobre o decoder de produção real (mesmo `application.yml`, mesmo `issuer-uri`), então o GREEN de cada `verify` já é, na prática, a mesma prova que o smoke daria — só sem os contêineres do compose.

## User Setup Required

None - nenhuma configuração de serviço externo necessária. Nenhuma variável de ambiente nova (o `issuer-uri` tem default idêntico ao emitido pelo auth-service; `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` é opcional, para sobrescrever se necessário).

## Next Phase Readiness
- T-03-02 e WR-07 (03-REVIEW.md) podem ser marcados como fechados, citando os commits `bfda7ec`, `d128b28`, `805d5c2`
- O padrão (`issuer-uri` + `JwkSetUriJwtDecoderBuilderCustomizer` nos testes) fica pronto para ser herdado pelo resource server do order-service na Fase 4
- Nenhum blocker identificado para a Fase 4

## Self-Check: PASSED

Todos os 3 commits (`bfda7ec`, `d128b28`, `805d5c2`) e os 9 arquivos de `files_modified` (mais este próprio SUMMARY.md) foram confirmados presentes no repositório via `git log --oneline --all` e checagem de existência de arquivo.

---
*Phase: quick-260923-tj9*
*Completed: 2026-09-23*
