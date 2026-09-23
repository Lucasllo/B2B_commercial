---
phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
verified: 2026-09-23T23:59:00Z
status: human_needed
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
  - "README.md"
  - "auth-service/Dockerfile"
  - "catalog-service/Dockerfile"
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
  - "pom.xml"
  - "scripts/smoke-notification-flow.sh"
covered_digest: "v1:sha256:6b49a4d1ca4f4ade75f77a43bee1216aba405524d110e2fdf2add0acdf56d92d"
behavior_unverified: 0
overrides_applied: 0
human_verification:
  - test: "Autenticar como BUYER na stack real, abrir http://localhost:8084/swagger-ui.html, clicar em Authorize, colar o token do BUYER e executar GET /notifications/{productId} via Try it out."
    expected: "A resposta é 403 (o comprador não acessa o histórico de notificações do vendedor), o botão Authorize funciona corretamente para o esquema bearerAuth, e a experiência de navegação pela Swagger UI está coerente para um avaliador que nunca abriu o código."
    why_human: "O 403 já está provado por teste automatizado dentro do serviço (NotificationControllerIT#buyerTokenReturns403WithoutLeakingEventData) e pelo Gateway apenas para ausência de token (401); a navegação visual pelo botão Authorize e o comportamento do Try it out da Swagger UI para um usuário BUYER real não são verificáveis por grep/teste — item de human-check explicitamente deferido pelo próprio plano 03-03-PLAN.md (Task 2) para o UAT de fim de fase (workflow.human_verify_mode=end-of-phase)."
  - test: "Ler a seção 'Limitações conhecidas (Fase 3)' do README.md do ponto de vista de um avaliador externo que não vai abrir o código-fonte."
    expected: "O texto deixa claro, sem jargão de implementação, que (1) um evento de ajuste de estoque pode se perder se o SQS falhar exatamente após o commit (dual-write sem Transactional Outbox, D-29/D-30), (2) não há fila de mensagens mortas nesta fase, e que a Fase 5 resolve o primeiro ponto com o padrão Outbox — sem reivindicar uma garantia de entrega que o código ainda não oferece."
    why_human: "Clareza de prosa para um leitor não-técnico é um julgamento humano, não uma checagem automatizável; este item é um must_have `prohibitions` de verification-tier `judgment` no `03-03-PLAN.md` ('MUST NOT apresentar a publicação direta como entrega garantida'), e o próprio plano registra em `03-03-SUMMARY.md` (D6) que ele foi deferido para o UAT de fim de fase em vez de resolvido nesta execução."
---

# Fase 3: Primeira Integração Assíncrona — Histórico de Notificações — Relatório de Verificação

**Objetivo da fase:** Provar a mensageria ponta a ponta no cenário mais simples possível — um serviço publica um evento no SQS (LocalStack), o notification-service consome e grava um registro consultável no DynamoDB — de modo que, quando a saga depender desse encanamento, ele já esteja validado e depurado isoladamente.

**Verificado em:** 2026-09-23
**Status:** human_needed
**Re-verificação:** Não — primeira verificação

## Achado geral

O núcleo funcional da fase está implementado, testado e comprovado contra uma stack real (não apenas em teoria ou em SUMMARY.md). Reexecutei, de forma independente do executor, os artefatos e comandos abaixo e todos confirmaram o que os SUMMARYs alegam:

- `git ls-files -s` confirma que `localstack-init/ready.d/01-create-notification-resources.sh` e `scripts/smoke-notification-flow.sh` estão no índice com modo `100755`.
- Os quatro gates de anti-acoplamento (nenhum `createQueue`/`createTable` em código Java, nenhum cliente REST de saída no `notification-service`, `InventoryService` sem nenhuma referência a mensageria, nenhuma referência ao endereço do `notification-service` no `inventory-service`) passam — reexecutados nesta verificação, não apenas lidos do SUMMARY.
- `docker compose ps` mostra os sete serviços `running healthy` na stack que o executor deixou no ar.
- `bash scripts/smoke-notification-flow.sh` foi reexecutado por este verificador (não é o log do executor) e terminou com `SMOKE OK`, confirmando fluxo (evento visível em 0s), reentrega sem duplicação (1 elemento) e ajustes distintos acumulando (3 elementos).
- O log de `./mvnw -B verify` fornecido pelo orquestrador mostra `BUILD SUCCESS` nos 5 módulos com Testcontainers/LocalStack reais (não mocks) e é consistente com as contagens de teste citadas nos três SUMMARYs (18 testes no `notification-service`, 36 no `inventory-service`).
- Nenhuma migração Flyway nova existe em `inventory-service/src/main/resources/db/migration/` (D-29 cumprido — sem outbox nesta fase).

O único motivo para o status não ser `passed` é a existência de dois itens de verificação humana que o próprio plano `03-03-PLAN.md` (Task 2) já havia deferido explicitamente para o UAT de fim de fase, e que ainda não foram confirmados por um humano (ver seção "Verificação Humana Necessária"). Nenhum gap bloqueante foi encontrado.

## Observable Truths (Success Criteria do ROADMAP + must_haves dos três planos)

| # | Truth | Status | Evidência |
|---|-------|--------|-----------|
| 1 | Um evento `STOCK_ADJUSTED` publicado por um serviço já existente (ajuste de estoque) aparece no DynamoDB em segundos, sem chamada REST entre produtor e consumidor (ROADMAP SC1) | ✓ VERIFIED | `NotificationEventFlowIT` (4/4 pass), `StockAdjustedEventPublishingIT` (4/4 pass); gates `! grep RestTemplate/WebClient/FeignClient` e `! grep http://notification-service` limpos; smoke reexecutado: "evento STOCK_ADJUSTED visivel no historico em 0s" |
| 2 | O histórico é consultável por identificador via `GET /notifications/{productId}` pelo Gateway (ROADMAP SC2) | ✓ VERIFIED | `NotificationControllerIT` (5/5 pass); rota `notification-service-route` em `gateway/.../application.yml` com `Path=/api/notifications/**`/`StripPrefix=1`; smoke passo 5 confirmado nesta verificação |
| 3 | Chave determinística (`productId`/`eventType#eventId`) faz reentrega sobrescrever em vez de duplicar (ROADMAP SC3) | ✓ VERIFIED | `NotificationDeliveryIT#redeliveringSameMessageOverwritesInsteadOfDuplicating` (pass, `recordedAt` avança); smoke passo 6 reexecutado: "reentrega nao duplica (1 elemento apos duas entregas)" |
| 4 | Fila e tabela criadas automaticamente na subida do LocalStack, sem passo manual; teste de integração com Testcontainers+LocalStack exercita publicação e consumo reais (ROADMAP SC4) | ✓ VERIFIED | `localstack-init/ready.d/01-create-notification-resources.sh` é o único criador (gate `! grep createQueue\|createTable` limpo); healthcheck do `docker-compose.yml` verifica `sqs get-queue-url`+`dynamodb describe-table`; `docker compose ps` mostra `localstack running healthy` sem passo manual nesta sessão; `NotificationEventFlowIT`/`StockAdjustedEventPublishingIT` sobem LocalStack real via Testcontainers copiando o mesmo script |
| 5 | Produto sem eventos devolve 200 com lista vazia, nunca 404 | ✓ VERIFIED | Caso incluído em `NotificationEventFlowIT` (comportamento testado no plano `03-01`, Task 1) |
| 6 | Mensagem inválida (JSON malformado, campo ausente, tipo não suportado) é descartada com log WARN sem travar a fila; evento válido publicado depois continua sendo registrado | ✓ VERIFIED | `NotificationDeliveryIT#poisonMessageIsDiscardedWithWarnLogAndConsumptionStaysAlive` (pass); `NotificationServiceTest#invalidBodiesThrowAndNeverCallRepository` (pass) |
| 7 | Atributo `JavaType` do produtor é ignorado pelo consumidor (decisão de tipo vem do parâmetro do listener) | ✓ VERIFIED | `NotificationDeliveryIT#foreignTypeAttributeIsIgnoredAndEventIsRecordedNormally` (pass); gate `grep setPayloadTypeMapper` presente |
| 8 | Só SELLER_ADMIN lê o histórico; BUYER 403, sem token/token inválido 401, identificador não-UUID 400, DynamoDB indisponível 503, corpo de erro uniforme sem detalhe do SDK/stack trace | ✓ VERIFIED | `NotificationControllerIT` (403/401/400 cobertos, pass); `NotificationStoreUnavailableIT` (503 sem `software.amazon`/`Exception`, pass) |
| 9 | `PUT /inventory/{productId}` bem-sucedido publica exatamente um evento com os 6 campos do contrato, `previousQuantityOnHand` correto (0 na criação, valor real capturado dentro da mesma transação/reexecução) | ✓ VERIFIED | `StockAdjustedEventPublishingIT#adjustingStockTwicePublishesOneContractMessagePerAdjustmentWithCorrectPreviousQuantity` (pass); corpo JSON observado registrado em `03-02-SUMMARY.md` bate campo a campo com `NOTIFICATION_EVENT_CONTRACT` de `03-01-SUMMARY.md` |
| 10 | `PUT` recusado (400/403/409), reserva e liberação de estoque não publicam nenhum evento | ✓ VERIFIED | `StockAdjustedEventPublishingIT#rejectedPutReservationAndReleaseDoNotPublishAnyEventBeyondTheOriginalAdjustment` e variantes (pass) |
| 11 | Falha de publicação no SQS não derruba o `PUT` (continua 200, ajuste gravado), e uma linha ERROR identifica o evento perdido (D-30) | ✓ VERIFIED | `StockEventPublishFailureIT#publishFailureDoesNotFailThePutKeepsTheAdjustmentAndLogsErrorWithProductId` (pass); gate `grep 'Falha ao publicar'` presente |
| 12 | Nenhuma migração Flyway nova no `inventory-service` (D-29, sem outbox nesta fase) | ✓ VERIFIED | `ls` + `git log` de `inventory-service/src/main/resources/db/migration/` mostram só a migração da Fase 2 |
| 13 | `docker compose up -d --build --wait` sobe sete serviços saudáveis a partir de um `.env`, sem tag flutuante | ✓ VERIFIED | `docker compose ps` (reexecutado): 7/7 `running healthy`; nenhuma linha `image:` sem tag de patch fixa em `docker-compose.yml` |
| 14 | Suítes de integração já existentes (Fase 1/2) continuam verdes com o módulo novo e a assinatura interna alterada de `setStock`/`recoverSetStock` | ✓ VERIFIED | Log `mvn-verify.log` fornecido pelo orquestrador: `BUILD SUCCESS`, `inventory-service` 36/36, sem regressão em `StockReservationConcurrencyIT`/`InventoryRetryContentionIT` |
| 15 | Swagger UI do `notification-service` (porta 8084) abre sem token, declara `bearerAuth`, endpoint de negócio continua exigindo token | ✓ VERIFIED | `OpenApiDocsIT` (4/4 pass no log reexecutado); `OpenApiConfig.java` declara `SecurityRequirement`/`SecurityScheme bearerAuth` |

**Score:** 15/15 truths verificadas (0 present-behavior-unverified). Os dois itens abaixo são verificações humanas explicitamente deferidas pelo próprio plano (não contam contra o score, mas impedem `passed`).

## Artefatos Requeridos

| Artefato | Esperado | Status | Detalhes |
|----------|----------|--------|----------|
| `localstack-init/ready.d/01-create-notification-resources.sh` | Init hook cria fila+tabela | ✓ VERIFIED | Conteúdo lido integralmente; `sqs create-queue` idempotente, `dynamodb create-table` condicional a `describe-table` falhar, `--region us-east-1` em todo comando; modo `100755` no índice |
| `notification-service/src/main/java/.../NotificationRecord.java` | `@DynamoDbBean` com chave determinística | ✓ VERIFIED | Existe, classe (não record), campos conforme SUMMARY |
| `notification-service/src/main/java/.../NotificationRepository.java` | `putItem` sem condição + Query | ✓ VERIFIED | Existe |
| `notification-service/src/main/java/.../NotificationService.java` | Validação + mapeamento + ordenação | ✓ VERIFIED | Existe, 7128 bytes, validação confirmada por testes |
| `notification-service/src/main/java/.../messaging/NotificationEventListener.java` | Consumidor único `@SqsListener` | ✓ VERIFIED | Existe |
| `notification-service/src/main/java/.../NotificationController.java` | `GET /notifications/{productId}` restrito a SELLER_ADMIN | ✓ VERIFIED | Existe, `@PreAuthorize` confirmado por gate `grep` |
| `notification-service/src/main/java/.../config/SqsMessagingConfig.java` | Conversor que ignora `JavaType` | ✓ VERIFIED | Existe |
| `notification-service/src/main/java/.../config/GlobalExceptionHandler.java` | Corpo de erro uniforme | ✓ VERIFIED | Existe, handlers 400/401/403/503 confirmados por teste |
| `notification-service/src/main/java/.../config/OpenApiConfig.java` | Esquema `bearerAuth` | ✓ VERIFIED | Existe, criado em 03-03 |
| `inventory-service/src/main/java/.../messaging/StockEventPublisher.java` | Publicação pós-commit com falha capturada | ✓ VERIFIED | Existe, 2863 bytes |
| `inventory-service/src/main/java/.../stock/StockAdjustmentResult.java` | Retorno interno com quantidade anterior | ✓ VERIFIED | Existe |
| `inventory-service/src/main/java/.../config/SqsMessagingConfig.java` | `doNotSendPayloadTypeHeader` | ✓ VERIFIED | Existe, gate `grep doNotSendPayloadTypeHeader` confirmado no SUMMARY |
| `docker-compose.yml` | Bloco `notification-service`, healthcheck de recursos do LocalStack | ✓ VERIFIED | Lido integralmente; healthcheck verifica `sqs get-queue-url && dynamodb describe-table`, não apenas o processo |
| `gateway/src/main/resources/application.yml` | Rota `/api/notifications/**` | ✓ VERIFIED | Confirmado via grep |
| `scripts/smoke-notification-flow.sh` | Prova ponta a ponta na stack real | ✓ VERIFIED | Reexecutado nesta verificação, `SMOKE OK` |
| `README.md` | Endpoint, fluxo do evento, limitações conhecidas | ✓ VERIFIED (conteúdo) / verificação humana pendente para clareza | Seção "Limitações conhecidas (Fase 3)" lida integralmente — declara dual-write e ausência de DLQ com honestidade técnica |

## Verificação de Key Links

| De | Para | Via | Status | Detalhes |
|----|------|-----|--------|----------|
| `localstack-init/ready.d/01-create-notification-resources.sh` | `notification-service/.../application.yml` | Nome da fila idêntico | ✓ WIRED | `notification-events-queue` em ambos |
| `NotificationRecord.java` | init hook | Atributos de chave batem com key-schema | ✓ WIRED | `productId` HASH / `sortKey` RANGE em ambos |
| `InventoryController.java` | `StockEventPublisher.java` | Publicação depois do retorno de `setStock` | ✓ WIRED | Confirmado por leitura de código e por `WR-04`/`IN-01` do REVIEW (achados sobre comportamento, não sobre ausência de wiring) |
| `inventory-service/.../StockAdjustedEvent.java` | `notification-service/.../StockAdjustedEvent.java` | Mesmo contrato JSON sem módulo compartilhado | ✓ WIRED | Corpo observado em `03-02-SUMMARY.md` bate campo a campo com `NOTIFICATION_EVENT_CONTRACT` de `03-01-SUMMARY.md` |
| `gateway/.../application.yml` | `docker-compose.yml` | Host/porta da rota batem com o serviço | ✓ WIRED | `http://notification-service:8084` na rota, bloco `notification-service` expõe `8084` |
| `pom.xml` | 5 Dockerfiles | `COPY notification-service/pom.xml` em todos | ✓ WIRED | Confirmado por gate documentado no SUMMARY (`5` ocorrências) |

## Behavioral Spot-Checks (reexecutados por este verificador)

| Comportamento | Comando | Resultado | Status |
|---|---|---|---|
| Gates de anti-acoplamento (sem createQueue/createTable, sem REST client, InventoryService sem mensageria, sem endereço do notification-service) | 4 comandos `grep` isolados, ver seção "Achado geral" | Todos limpos | ✓ PASS |
| Stack real saudável | `docker compose ps --format '{{.Service}} {{.State}} {{.Health}}'` | 7/7 `running healthy` | ✓ PASS |
| Fluxo ponta a ponta pelo Gateway | `bash scripts/smoke-notification-flow.sh` | `SMOKE OK` — evento em 0s, reentrega 1 elemento, ajustes distintos 3 elementos | ✓ PASS |
| Build e testes completos (evidência do orquestrador, log lido integralmente por este verificador) | `./mvnw -B verify` (log `mvn-verify.log`) | `BUILD SUCCESS`, 5 módulos, notification-service 18/18, inventory-service 36/36 | ✓ PASS |
| Índice git registra bit de execução dos dois scripts shell | `git ls-files -s` | `100755` para ambos | ✓ PASS |

## Execução de Probes

Não aplicável — o projeto usa a convenção `scripts/smoke-*.sh` em vez de `scripts/*/tests/probe-*.sh`; o script de smoke já foi coberto na seção de Behavioral Spot-Checks acima.

## Cobertura de Requisitos

| Requisito | Plano de origem | Descrição | Status | Evidência |
|---|---|---|---|---|
| NOTF-01 | 03-01, 03-02, 03-03 | Notification-service consome eventos e grava no DynamoDB via LocalStack | ✓ SATISFIED | Truths 1, 3, 4, 6, 7, 9, 10, 11, 12, 13, 14 acima |
| NOTF-02 | 03-01, 03-03 | Histórico consultável por identificador | ✓ SATISFIED | Truths 2, 5, 8, 15 acima |

Nenhum requisito órfão: `REQUIREMENTS.md` mapeia exatamente NOTF-01 e NOTF-02 para a Fase 3, e ambos aparecem no campo `requirements` de pelo menos um plano. `DLQ-01` é explicitamente v2 (fora de escopo, citado corretamente nas limitações conhecidas do README e no `03-01-PLAN.md`).

## Anti-Patterns Encontrados (de `03-REVIEW.md`, 0 crítico / 7 warning / 7 info)

Nenhum é BLOCKER — o próprio review confirma 0 críticos e nenhum item invalida uma truth acima (todas as truths continuam comprovadas por teste passante). Listados por transparência, pois alguns merecem atenção antes da Fase 5/6:

| Arquivo | Severidade | Impacto |
|---|---|---|
| `NotificationStoreUnavailableIT.java` (WR-01) | ⚠️ Warning | Contexto `@MockitoBean` mantém listener SQS vivo que pode consumir mensagens de outras classes de teste — risco de flakiness futuro, não uma falha atual (suíte está 100% verde nesta verificação) |
| `NotificationService.java`/`NotificationEventListener.java` (WR-02) | ⚠️ Warning | Falha permanente do DynamoDB (ex.: item > 400KB) reentrega para sempre — comportamento é o desenhado pelo plano (sem DLQ, `DLQ-01` é v2), mas o gatilho concreto (payload grande) não está coberto por teste |
| `StockEventPublisher.java` (WR-03) | ⚠️ Warning | Envio síncrono ao SQS sem timeout explícito pode prender a thread HTTP sob degradação do LocalStack/SQS |
| `StockAdjustedEvent.java`/`InventoryController.java` (WR-04) | ⚠️ Warning | `occurredAt` capturado na publicação (não no commit) pode inverter a ordem cronológica do histórico sob dois `PUT` concorrentes no mesmo produto — não observado pelos testes atuais (que fixam valores) |
| `scripts/smoke-notification-flow.sh` (WR-05) | ⚠️ Warning | `pipefail` + `grep` sem correspondência pode abortar o script sem diagnóstico em cenários de erro — não afetou a execução reexecutada nesta verificação (`SMOKE OK`) |
| `NotificationService.java` (WR-06) | ⚠️ Warning | Sanitização de log cobre só `\r\n\t`, não todos os caracteres de controle Unicode |
| `SecurityConfig.java`/`application.yml` (WR-07) | ⚠️ Warning | Resource server de produção não valida `iss`, divergindo do `auth-service` e do decoder de teste |
| IN-01 a IN-07 | ℹ️ Info | Ver `03-REVIEW.md` — nenhum invalida uma truth desta fase |

Nenhum debt marker (`TBD`/`FIXME`/`XXX`) não referenciado foi encontrado nos arquivos revisados.

## Verificação Humana Necessária

Estes dois itens já estavam explicitamente deferidos pelo executor para o UAT de fim de fase (ver `03-03-PLAN.md` Task 2 `<human-check>` e `03-03-SUMMARY.md` itens D5/D6) e ainda não foram confirmados por um humano:

### 1. Navegação BUYER 403 pela Swagger UI

**Teste:** Autenticar como BUYER na stack real, abrir `http://localhost:8084/swagger-ui.html`, clicar em Authorize, colar o token do BUYER e executar `GET /notifications/{productId}` via Try it out.
**Esperado:** 403; o botão Authorize funciona para o esquema `bearerAuth`; a navegação é coerente para um avaliador que não abre o código.
**Por que humano:** O 403 já está provado por teste automatizado (`NotificationControllerIT`); a experiência visual da Swagger UI não é.

### 2. Clareza do README para um avaliador externo

**Teste:** Ler "Limitações conhecidas (Fase 3)" do ponto de vista de quem não vai abrir o código.
**Esperado:** Fica claro, sem jargão de implementação, que um evento pode se perder (dual-write, D-29/D-30), que não há DLQ, e que a Fase 5 resolve o primeiro ponto.
**Por que humano:** É um `must_have.prohibitions` de `verification: judgment` no `03-03-PLAN.md`; clareza de prosa é julgamento humano por definição.

Nota deste verificador: o texto lido em `README.md` (linhas 240-252) já é tecnicamente honesto e específico (cita `eventId`/`productId`/nome da fila, distingue a falha transitória do DynamoDB da mensagem venenosa descartável, e nomeia a Fase 5 como a que resolve o dual-write) — a confirmação humana é sobre legibilidade para um público não-técnico, não sobre a existência ou correção do conteúdo.

## Resumo de Gaps

Nenhum gap encontrado. Todas as 15 truths derivadas do ROADMAP e dos três planos foram verificadas com evidência reexecutada por este verificador (não apenas lida do SUMMARY.md): testes automatizados passando, gates de anti-acoplamento limpos, stack real saudável, e o script de smoke reexecutado com sucesso. O único motivo para o status `human_needed` são dois itens de confirmação humana que o próprio plano da fase já havia agendado para o UAT de fim de fase — não indicam trabalho pendente de implementação.

---

*Verificado em: 2026-09-23*
*Verificador: Claude (gsd-verifier)*
