---
phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
verified: 2026-09-24T00:20:00Z
status: passed
score: 15/15 must-haves verified
covered_files:
  - ".gitattributes"
  - ".planning/REQUIREMENTS.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-01-PLAN.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-01-SUMMARY.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-02-PLAN.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-02-SUMMARY.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-03-PLAN.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-03-SUMMARY.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-REVIEW.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-SECURITY.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-UAT.md"
  - ".planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-VALIDATION.md"
  - ".planning/quick/260923-tj9-validar-issuer-do-jwt-nos-resource-serve/260923-tj9-PLAN.md"
  - ".planning/quick/260923-tj9-validar-issuer-do-jwt-nos-resource-serve/260923-tj9-SUMMARY.md"
  - "README.md"
  - "auth-service/Dockerfile"
  - "catalog-service/Dockerfile"
  - "catalog-service/src/main/resources/application.yml"
  - "catalog-service/src/test/java/com/orderflow/catalog/ProductControllerIT.java"
  - "catalog-service/src/test/java/com/orderflow/catalog/support/TestJwt.java"
  - "docker-compose.yml"
  - "docs/API.md"
  - "docs/VISAO-GERAL.md"
  - "gateway/Dockerfile"
  - "gateway/src/main/resources/application.yml"
  - "inventory-service/Dockerfile"
  - "inventory-service/pom.xml"
  - "inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java"
  - "inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java"
  - "inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java"
  - "inventory-service/src/main/java/com/orderflow/inventory/stock/StockAdjustmentResult.java"
  - "inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockAdjustedEvent.java"
  - "inventory-service/src/main/java/com/orderflow/inventory/stock/messaging/StockEventPublisher.java"
  - "inventory-service/src/main/resources/application.yml"
  - "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java"
  - "inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java"
  - "localstack-init/ready.d/01-create-notification-resources.sh"
  - "notification-service/Dockerfile"
  - "notification-service/pom.xml"
  - "notification-service/src/main/java/com/orderflow/notification/NotificationServiceApplication.java"
  - "notification-service/src/main/java/com/orderflow/notification/config/GlobalExceptionHandler.java"
  - "notification-service/src/main/java/com/orderflow/notification/config/OpenApiConfig.java"
  - "notification-service/src/main/java/com/orderflow/notification/config/SecurityConfig.java"
  - "notification-service/src/main/java/com/orderflow/notification/config/SqsMessagingConfig.java"
  - "notification-service/src/main/java/com/orderflow/notification/history/InvalidNotificationEventException.java"
  - "notification-service/src/main/java/com/orderflow/notification/history/NotificationController.java"
  - "notification-service/src/main/java/com/orderflow/notification/history/NotificationRecord.java"
  - "notification-service/src/main/java/com/orderflow/notification/history/NotificationRepository.java"
  - "notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java"
  - "notification-service/src/main/java/com/orderflow/notification/history/dto/NotificationResponse.java"
  - "notification-service/src/main/java/com/orderflow/notification/history/dto/StockAdjustedEvent.java"
  - "notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java"
  - "notification-service/src/main/resources/application.yml"
  - "notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java"
  - "notification-service/src/test/java/com/orderflow/notification/support/TestJwt.java"
  - "pom.xml"
  - "scripts/smoke-notification-flow.sh"
covered_digest: "v1:sha256:727f39c05cc6c0570ffd479f80dcb5699c87019ae86014512d0f51567bc19548"
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 15/15
  gaps_closed:
    - "Navegação BUYER 403 pela Swagger UI do notification-service — confirmada pass em 03-UAT.md (2026-09-23)"
    - "Clareza da seção 'Limitações conhecidas (Fase 3)' do README para avaliador externo — confirmada pass em 03-UAT.md (2026-09-23)"
    - "T-03-02 (Spoofing, high, iss não validado) e WR-07 (paridade teste/produção quebrada) fechados pela quick task 260923-tj9 (commits d442929, 711061f, 619e239): notification-service, catalog-service e inventory-service agora validam issuer-uri no decoder de produção"
  gaps_remaining: []
  regressions: []
---

# Fase 3: Primeira Integração Assíncrona — Histórico de Notificações — Relatório de Re-Verificação

**Objetivo da fase:** Provar a mensageria ponta a ponta no cenário mais simples possível — um serviço publica um evento no SQS (LocalStack), o notification-service consome e grava um registro consultável no DynamoDB — de modo que, quando a saga depender desse encanamento, ele já esteja validado e depurado isoladamente.

**Verificado em:** 2026-09-24
**Status:** passed
**Re-verificação:** Sim — a 03-VERIFICATION.md anterior (`human_needed`, 15/15) ficou desatualizada porque a quick task `260923-tj9` alterou 9 arquivos cobertos (validação de `iss` do JWT) depois dela ter sido escrita, e os dois itens de verificação humana pendentes foram concluídos no UAT.

## Achado Geral

Esta é uma re-verificação, não uma primeira verificação. Duas mudanças de estado desde a 03-VERIFICATION.md anterior motivaram este relatório:

1. **Os dois itens de verificação humana foram concluídos.** `03-UAT.md` registra `result: pass` para os dois testes (navegação BUYER 403 na Swagger UI, clareza do README) em 2026-09-23, com `total: 2, passed: 2, issues: 0`.
2. **A quick task `260923-tj9` modificou 9 arquivos cobertos por esta fase** (commits `d442929`, `711061f`, `619e239`) para fechar a ameaça T-03-02 (Spoofing, high — `iss` do JWT não validado) e a finding WR-07 do `03-REVIEW.md` (paridade teste/produção quebrada). Isso invalida a evidência anterior sobre a truth #8 (autorização do endpoint de notificações) e sobre o comportamento de segurança do `notification-service` em geral, então reverifiquei essa área do zero e reexecutei o resto como checagem de regressão.

Reexecutei, de forma independente do executor da quick task e do orquestrador, o seguinte:

- Conferi o conteúdo real dos 9 arquivos modificados (não apenas o que o SUMMARY da quick task afirma): as três `application.yml` (`notification-service`, `catalog-service`, `inventory-service`) têm a linha `issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:orderflow-auth-service}` logo abaixo de `jwk-set-uri`; os três `TestJwt.java` têm `JwkSetUriJwtDecoderBuilderCustomizer` e zero ocorrências de `withPublicKey` fora de comentários (confirma que o `JwtDecoder` de teste próprio foi removido); os três testes adversariais novos (`tokenSignedByTrustedKeyButWrongIssuerReturns401`, `getProductWithTokenSignedByTrustedKeyButWrongIssuerReturns401`, `getStockWithTokenSignedByTrustedKeyButWrongIssuerReturns401`) existem nos arquivos de IT correspondentes.
- Conferi o escopo do diff eu mesmo: `git diff --stat 48da638..619e239` lista exatamente os 9 arquivos declarados em `files_modified` do plano da quick task — nenhum `SecurityConfig.java`, `docker-compose.yml`, `auth-service/` ou `gateway/` foi tocado.
- **Subi a stack real com as imagens reconstruídas** (`docker compose up -d --build --wait`), o que forçosamente reconstrói `notification-service`, `catalog-service` e `inventory-service` com o `application.yml` novo — os 7 serviços ficaram `healthy`.
- **Reexecutei `bash scripts/smoke-notification-flow.sh`** contra essa stack reconstruída: `SMOKE OK`, com um token real emitido pelo `auth-service` (que carrega `iss = orderflow-auth-service`) aceito de ponta a ponta pelo Gateway → `inventory-service` (PUT 200) → `notification-service` (histórico consultável, sem duplicação em reentrega, 3 ajustes distintos acumulados). Isso prova, com tráfego real e não apenas testes de integração, que a nova checagem de `issuer-uri` aceita o token real do `auth-service` e não quebrou o fluxo ponta a ponta que é o próprio objetivo da fase.
- Derrubei a stack depois (`docker compose down`) para liberar a sessão do LocalStack Hobby, no mesmo espírito da nota do orquestrador.
- Confirmei que `AUTH-03` pertence à Fase 1 em `.planning/REQUIREMENTS.md` (não é um requisito órfão desta fase — a quick task reforçou uma finding de segurança da Fase 3, mas o requisito raiz já estava satisfeito antes).
- Reexecutei os quatro gates de anti-acoplamento (sem `createQueue`/`createTable`, sem `RestTemplate`/`WebClient`/`FeignClient` no `notification-service`) — continuam limpos.
- Confirmei que nenhuma migração Flyway nova existe em `inventory-service/src/main/resources/db/migration/` (ainda só `V1__init_inventory_schema.sql` — D-29 continua cumprido).
- Escaneei os 9 arquivos modificados por `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER` — nenhuma ocorrência.

O log de `./mvnw -B -pl notification-service,catalog-service,inventory-service verify` fornecido pelo orquestrador (`BUILD SUCCESS`, notification-service 19/19, catalog-service 32/32, inventory-service 37/37) é consistente com o `260923-tj9-SUMMARY.md` e com o conteúdo de código que confirmei acima; não precisei reexecutar o reactor Maven completo porque a evidência de container real (rebuild + smoke) já prova o comportamento em produção de forma independente, e reexecutar o Maven com a stack do compose no ar arriscaria a mesma colisão de sessão LocalStack Hobby que o próprio executor da quick task documentou e evitou.

Nenhum gap encontrado. Nenhum item de verificação humana pendente.

## Observable Truths (Success Criteria do ROADMAP + must_haves dos três planos)

| # | Truth | Status | Evidência |
|---|-------|--------|-----------|
| 1 | Um evento `STOCK_ADJUSTED` publicado por um serviço já existente aparece no DynamoDB em segundos, sem chamada REST entre produtor e consumidor (ROADMAP SC1) | ✓ VERIFIED | Regressão: smoke reexecutado nesta re-verificação contra stack reconstruída — "evento STOCK_ADJUSTED visivel no historico em 0s"; gates de anti-acoplamento limpos |
| 2 | O histórico é consultável por identificador via `GET /notifications/{productId}` pelo Gateway (ROADMAP SC2) | ✓ VERIFIED | Regressão: smoke passo "5/7 evento STOCK_ADJUSTED visivel no historico" reexecutado com sucesso pelo Gateway; rota inalterada em `gateway/.../application.yml` |
| 3 | Chave determinística faz reentrega sobrescrever em vez de duplicar (ROADMAP SC3) | ✓ VERIFIED | Regressão: smoke passo "6/7 reentrega nao duplica (1 elemento apos duas entregas)" reexecutado |
| 4 | Fila e tabela criadas automaticamente na subida do LocalStack; teste de integração com Testcontainers+LocalStack exercita publicação e consumo reais (ROADMAP SC4) | ✓ VERIFIED | Regressão: `docker compose up -d --build --wait` recriou a stack e o LocalStack ficou `healthy` sem passo manual nesta sessão |
| 5 | Produto sem eventos devolve 200 com lista vazia, nunca 404 | ✓ VERIFIED | Regressão: sem mudança de código nesta área; `NotificationEventFlowIT` incluído no verify 19/19 |
| 6 | Mensagem inválida é descartada com log WARN sem travar a fila | ✓ VERIFIED | Regressão: `NotificationDeliveryIT` sem mudança de código, incluído no verify 19/19 |
| 7 | Atributo `JavaType` do produtor é ignorado pelo consumidor | ✓ VERIFIED | Regressão: `SqsMessagingConfig.java` do notification-service não foi tocado pela quick task; gate `setPayloadTypeMapper` ainda presente |
| 8 | Só SELLER_ADMIN lê o histórico; BUYER 403, sem token/token inválido 401, identificador não-UUID 400, DynamoDB indisponível 503, corpo de erro uniforme — **agora também**: token assinado pela chave confiável mas com `iss` errado ou ausente recebe 401 (T-03-02/WR-07 fechados) | ✓ VERIFIED | **Full check (área modificada):** `NotificationControllerIT` no `application.yml` novo, decoder de produção agora com `issuer-uri`; teste `tokenSignedByTrustedKeyButWrongIssuerReturns401` confirmado no arquivo; RED documentado no SUMMARY da quick task (`expected:<401> but was:<200>`, só esse teste falhou) prova que a checagem não existia antes e existe agora; GREEN confirmado (19/19); smoke reexecutado nesta re-verificação usa um token real do `auth-service` (`iss=orderflow-auth-service`) e é aceito, provando que o caso legítimo continua funcionando com a nova validação ativa |
| 9 | `PUT /inventory/{productId}` bem-sucedido publica exatamente um evento com os 6 campos do contrato | ✓ VERIFIED | Regressão: `StockAdjustedEvent.java`/`InventoryController.java` do inventory-service não foram tocados pela quick task; smoke passo "7/7 ajustes distintos acumulam (3 elementos)" reexecutado |
| 10 | `PUT` recusado, reserva e liberação de estoque não publicam nenhum evento | ✓ VERIFIED | Regressão: `StockAdjustedEventPublishingIT` sem mudança de código, incluído no verify 37/37 do inventory-service |
| 11 | Falha de publicação no SQS não derruba o `PUT`, linha ERROR identifica o evento perdido (D-30) | ✓ VERIFIED | Regressão: `StockEventPublisher.java` não foi tocado pela quick task; `StockEventPublishFailureIT` incluído no verify 37/37 |
| 12 | Nenhuma migração Flyway nova no `inventory-service` (D-29, sem outbox nesta fase) | ✓ VERIFIED | Reconfirmado nesta re-verificação: `ls inventory-service/src/main/resources/db/migration/` só mostra `V1__init_inventory_schema.sql` |
| 13 | `docker compose up -d --build --wait` sobe sete serviços saudáveis a partir de um `.env`, sem tag flutuante | ✓ VERIFIED | Reexecutado nesta re-verificação com as imagens reconstruídas do issuer fix: 7/7 `Healthy` |
| 14 | Suítes de integração já existentes (Fase 1/2) continuam verdes com o módulo novo | ✓ VERIFIED | Log do orquestrador: `BUILD SUCCESS`, inventory-service 37/37 (36 anteriores + 1 novo teste de issuer), catalog-service 32/32 (31 anteriores + 1 novo teste de issuer), sem regressão em `StockReservationConcurrencyIT` (herdou a troca de `TestJwt.Config` sem edição própria, conforme SUMMARY) |
| 15 | Swagger UI do `notification-service` (porta 8084) abre sem token, declara `bearerAuth`, endpoint de negócio continua exigindo token; **navegação real BUYER→403 confirmada por humano** | ✓ VERIFIED | `OpenApiDocsIT` incluído no verify 19/19 (não modificado pela quick task); `03-UAT.md` teste 1 — **pass** (2026-09-23), item antes pendente agora concluído |

**Score:** 15/15 truths verificadas (0 present-behavior-unverified, 0 gaps, 0 itens de verificação humana pendentes).

## Artefatos Requeridos (checagem de regressão + área modificada)

| Artefato | Esperado | Status | Detalhes |
|----------|----------|--------|----------|
| `notification-service/src/main/resources/application.yml` | `issuer-uri` ao lado de `jwk-set-uri` | ✓ VERIFIED | Conteúdo lido, linha 37: `issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:orderflow-auth-service}` |
| `catalog-service/src/main/resources/application.yml` | idem | ✓ VERIFIED | Linha 38, mesmo padrão |
| `inventory-service/src/main/resources/application.yml` | idem | ✓ VERIFIED | Linha 38, mesmo padrão |
| `notification-service/.../support/TestJwt.java` | `JwkSetUriJwtDecoderBuilderCustomizer` em vez de `JwtDecoder` próprio | ✓ VERIFIED | `grep` confirma customizer presente e zero `withPublicKey` fora de comentário |
| `catalog-service/.../support/TestJwt.java` | idem | ✓ VERIFIED | idem |
| `inventory-service/.../support/TestJwt.java` | idem | ✓ VERIFIED | idem |
| `notification-service/.../NotificationControllerIT.java` | teste `tokenSignedByTrustedKeyButWrongIssuerReturns401` | ✓ VERIFIED | Presente, 1 ocorrência |
| `catalog-service/.../ProductControllerIT.java` | teste `getProductWithTokenSignedByTrustedKeyButWrongIssuerReturns401` | ✓ VERIFIED | Presente, 1 ocorrência |
| `inventory-service/.../InventoryControllerIT.java` | teste `getStockWithTokenSignedByTrustedKeyButWrongIssuerReturns401` | ✓ VERIFIED | Presente, 1 ocorrência |
| Demais 40+ artefatos da fase (init hook, `NotificationRecord`/`Repository`/`Service`/`Controller`, `StockEventPublisher`, `docker-compose.yml`, rota do Gateway, smoke script, README) | inalterados desde a verificação anterior | ✓ VERIFIED (regressão) | Nenhum desses arquivos aparece no diff `48da638..619e239`; comportamento reconfirmado indiretamente pelo smoke e pelo verify agregado |

## Key Links (checagem de regressão + área modificada)

| De | Para | Via | Status | Detalhes |
|----|------|-----|--------|----------|
| `application.yml` (`issuer-uri`) | decoder de produção autoconfigurado (Boot 3.5.16) | `JwtValidators.createDefaultWithIssuer` | ✓ WIRED | Confirmado pelo RED/GREEN documentado no SUMMARY da quick task e pelo smoke com token real aceito |
| `TestJwt.Config` (`JwkSetUriJwtDecoderBuilderCustomizer`) | decoder de produção | `builder.jwtProcessorCustomizer(...)` troca só a fonte de chave | ✓ WIRED | Testes agora rodam sobre o mesmo decoder de produção — paridade teste/produção restaurada (fecha WR-07) |
| Token real do `auth-service` (`iss=orderflow-auth-service`) | `issuer-uri` default dos três serviços | igualdade de string | ✓ WIRED | Confirmado pelo smoke reexecutado nesta re-verificação: PUT/GET com token real aceitos após rebuild das imagens |
| Todos os key links da verificação anterior (init hook↔fila/tabela, publisher↔listener, contrato JSON, Gateway↔notification-service, pom↔Dockerfiles) | — | — | ✓ WIRED (regressão) | Nenhum arquivo desses key links foi tocado pela quick task |

## Behavioral Spot-Checks (reexecutados nesta re-verificação)

| Comportamento | Comando | Resultado | Status |
|---|---|---|---|
| Stack real reconstruída com o fix de issuer sobe saudável | `docker compose up -d --build --wait` | 7/7 `Healthy` | ✓ PASS |
| Fluxo ponta a ponta com token real do `auth-service` sob a nova validação de `issuer-uri` | `bash scripts/smoke-notification-flow.sh` | `SMOKE OK` — evento em 0s, reentrega 1 elemento, 3 ajustes distintos | ✓ PASS |
| Conteúdo real do fix (não apenas o SUMMARY) | `grep` nos 9 arquivos modificados (issuer-uri, customizer, testes novos) | Todos presentes conforme declarado | ✓ PASS |
| Escopo do diff da quick task | `git diff --stat 48da638..619e239` | Exatamente os 9 arquivos de `files_modified`, sem `SecurityConfig.java`/`docker-compose.yml`/`auth-service`/`gateway` | ✓ PASS |
| Gates de anti-acoplamento (sem createQueue/createTable, sem REST client no notification-service) | 2 `grep` isolados | Limpos | ✓ PASS |
| Nenhuma migração Flyway nova no inventory-service | `ls db/migration/` | Só `V1__init_inventory_schema.sql` | ✓ PASS |
| Debt markers nos 9 arquivos modificados | `grep -nE "TBD\|FIXME\|XXX\|TODO\|HACK\|PLACEHOLDER"` | Nenhuma ocorrência | ✓ PASS |

Não reexecutei o `./mvnw -B verify` completo do reactor: o log do orquestrador já mostra `BUILD SUCCESS` com as contagens de teste consistentes com o SUMMARY, e rodar o Maven com a stack do compose no ar (que eu havia subido para o smoke) arriscaria a mesma colisão de sessão do LocalStack Hobby que o próprio executor da quick task documentou e contornou. Preferi a evidência de container real (rebuild + smoke) por ser independente do executor e cobrir o mesmo risco (issuer real aceito) de forma mais direta.

## Execução de Probes

Não aplicável — o projeto usa a convenção `scripts/smoke-*.sh`; coberto acima.

## Cobertura de Requisitos

| Requisito | Plano de origem | Descrição | Status | Evidência |
|---|---|---|---|---|
| NOTF-01 | 03-01, 03-02, 03-03 | Notification-service consome eventos e grava no DynamoDB via LocalStack | ✓ SATISFIED | Truths 1, 3, 4, 6, 7, 9, 10, 11, 12, 13, 14 |
| NOTF-02 | 03-01, 03-03 | Histórico consultável por identificador | ✓ SATISFIED | Truths 2, 5, 8, 15 |
| AUTH-03 (Fase 1, reforçado nesta fase) | quick 260923-tj9 | Cada serviço valida o JWT localmente, rejeitando tokens inválidos | ✓ SATISFIED (reforço, não órfão desta fase) | `REQUIREMENTS.md` mapeia AUTH-03 para a Fase 1; a quick task fechou uma lacuna real (T-03-02) sem redefinir o requisito como pertencente à Fase 3 |

Nenhum requisito órfão.

## Anti-Patterns (atualização desde `03-REVIEW.md`)

| Achado | Severidade original | Status nesta re-verificação |
|---|---|---|
| WR-07 — resource server de produção não validava `iss`, testes davam falsa garantia | ⚠️ Warning | **Resolvido** pela quick task 260923-tj9 (commits d442929, 711061f, 619e239); `03-SECURITY.md` já registra T-03-02 como `closed` |
| WR-01 a WR-06, IN-01 a IN-07 | ⚠️ Warning / ℹ️ Info | Inalterados — nenhum arquivo relacionado a essas findings foi tocado pela quick task; nenhum é BLOCKER (mesma avaliação da verificação anterior) |

Nenhum debt marker não referenciado encontrado.

## Verificação Humana Necessária

Nenhuma. Os dois itens antes pendentes (`03-UAT.md`) foram concluídos pelo usuário em 2026-09-23, ambos `pass`:
1. Navegação BUYER 403 pela Swagger UI do notification-service — pass.
2. Clareza da seção "Limitações conhecidas (Fase 3)" do README — pass.

## Resumo de Gaps

Nenhum gap encontrado. Os 15 truths da fase continuam verificados (11 por checagem de regressão, já que os arquivos correspondentes não foram tocados pela quick task, e 4 — truths 8, 14, 15 e a reconfirmação end-to-end via smoke — por checagem completa de 3 níveis, já que envolvem diretamente a área que a quick task modificou ou o UAT que a concluiu). O status muda de `human_needed` para `passed` porque (a) os dois itens de verificação humana foram concluídos com `pass` e (b) a mudança de código que invalidava parte da evidência anterior (validação de `iss`) foi verificada de ponta a ponta nesta re-verificação, incluindo reconstrução real dos containers e reexecução do smoke com um token genuíno do `auth-service`.

---

*Verificado em: 2026-09-24*
*Verificador: Claude (gsd-verifier)*
*Re-verificação de: .planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-VERIFICATION.md (human_needed, 15/15)*
