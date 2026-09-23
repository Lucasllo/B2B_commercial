---
phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
plan: "01"
subsystem: notification
tags: [spring-cloud-aws, sqs, dynamodb, dynamodb-enhanced, localstack, testcontainers, spring-security, oauth2-resource-server, mockmvc, awaitility]

# Dependency graph
requires:
  - phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
    provides: "SecurityConfig/GlobalExceptionHandler/TestJwt pattern (JWT resource server, claim `role`), docker-compose com LocalStack ja configurado (SERVICES=sqs,dynamodb, LOCALSTACK_AUTH_TOKEN obrigatorio, imagem localstack/localstack:2026.08.3)"
provides:
  - "notification-service novo no reactor Maven — consumidor SQS unico (@SqsListener de fan-out), sink DynamoDB idempotente por chave deterministica, GET /notifications/{productId} restrito a SELLER_ADMIN"
  - "NOTF-01/NOTF-02: encanamento assincrono de ponta a ponta provado contra LocalStack real (Testcontainers) — evento na fila vira historico consultavel em segundos"
  - "NOTIFICATION_EVENT_CONTRACT=eventId:UUID,eventType:String,productId:UUID,previousQuantityOnHand:Integer,newQuantityOnHand:Integer,occurredAt:Instant(ISO-8601) — contrato JSON que o plano 03-02 (publicador no inventory-service) escreve contra"
  - "Porta 8084; fila notification-events-queue; tabela notification-history (partition key productId, sort key sortKey=eventType#eventId)"
affects: [03-02-inventory-service-publisher, 03-03-compose-gateway-docs, 06-order-timeline]

# Actuals (#2632)
actuals:
  tokens: 25293
  tasks: 3
  commits: 3

plan_head_before: 8b47023d606546586c71c904557905975f8c4ed0

# Tech tracking
tech-stack:
  added:
    - "io.awspring.cloud:spring-cloud-aws-dependencies:3.4.2 (BOM) — ultima linha compativel com Spring Boot 3.5.16/Spring Cloud 2025.0.3; 4.x exige Boot 4.0"
    - "io.awspring.cloud:spring-cloud-aws-starter-sqs — @SqsListener + SqsTemplate"
    - "io.awspring.cloud:spring-cloud-aws-starter-dynamodb — autoconfigura DynamoDbEnhancedClient a partir de spring.cloud.aws.*"
    - "org.testcontainers:localstack, org.awaitility:awaitility (escopo teste)"
  patterns:
    - "DynamoDbEnhancedClient.table(nome, TableSchema.fromBean(...)) usado diretamente em vez de DynamoDbTemplate — decisao Claude's Discretion de 03-CONTEXT.md, ver secao dedicada abaixo"
    - "Chave primaria deterministica (productId HASH + eventType#eventId RANGE) + putItem sem ConditionExpression — idempotencia de reentrega vem de graca do DynamoDB, sem checagem 'ja processei?' manual"
    - "MessagingMessageConverter<Message> com setPayloadTypeMapper(msg -> null) — o tipo de destino vem sempre do parametro do metodo do listener, nunca do atributo JavaType enviado pelo produtor"
    - "LocalStack init hook (ready.d) como unico ponto de provisionamento de fila/tabela — queue-not-found-strategy=fail no lado da aplicacao para que um init hook quebrado derrube a subida em vez de mascarar o problema"
    - "Espera de provisionamento do Testcontainers isolada numa classe auxiliar separada da que a inicia — evita deadlock de inicializacao de classe da JVM entre a thread do bloco static e a thread de background do Awaitility (ver Deviations)"

key-files:
  created:
    - localstack-init/ready.d/01-create-notification-resources.sh
    - notification-service/Dockerfile
    - notification-service/pom.xml
    - notification-service/src/main/resources/application.yml
    - notification-service/src/main/java/com/orderflow/notification/NotificationServiceApplication.java
    - notification-service/src/main/java/com/orderflow/notification/config/SecurityConfig.java
    - notification-service/src/main/java/com/orderflow/notification/config/SqsMessagingConfig.java
    - notification-service/src/main/java/com/orderflow/notification/config/GlobalExceptionHandler.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationRecord.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationRepository.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationController.java
    - notification-service/src/main/java/com/orderflow/notification/history/InvalidNotificationEventException.java
    - notification-service/src/main/java/com/orderflow/notification/history/dto/StockAdjustedEvent.java
    - notification-service/src/main/java/com/orderflow/notification/history/dto/NotificationResponse.java
    - notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java
    - notification-service/src/test/java/com/orderflow/notification/AbstractIntegrationTest.java
    - notification-service/src/test/java/com/orderflow/notification/support/TestJwt.java
    - notification-service/src/test/java/com/orderflow/notification/support/LocalStackTestSupport.java
    - notification-service/src/test/java/com/orderflow/notification/support/LocalStackProvisioningWaiter.java
    - notification-service/src/test/java/com/orderflow/notification/NotificationEventFlowIT.java
    - notification-service/src/test/java/com/orderflow/notification/NotificationDeliveryIT.java
    - notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java
    - notification-service/src/test/java/com/orderflow/notification/NotificationStoreUnavailableIT.java
    - notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java
    - notification-service/src/test/java/com/orderflow/notification/history/messaging/NotificationEventListenerTest.java
  modified:
    - pom.xml
    - .gitattributes
    - auth-service/Dockerfile
    - gateway/Dockerfile
    - catalog-service/Dockerfile
    - inventory-service/Dockerfile

key-decisions:
  - "NOTIFICATION_EVENT_CONTRACT: eventId (UUID), eventType (String, 'STOCK_ADJUSTED'), productId (UUID), previousQuantityOnHand (Integer), newQuantityOnHand (Integer), occurredAt (Instant ISO-8601) — o plano 03-02 publica exatamente este shape"
  - "notification-service: porta 8084, fila notification-events-queue, tabela notification-history"
  - "DynamoDbEnhancedClient usado diretamente em vez de DynamoDbTemplate do starter — a assinatura de query do template nao foi confirmada pela pesquisa (03-RESEARCH.md Assumptions Log A2) e o resolvedor de nome de tabela padrao dele derivaria da classe em vez do nome explicito da configuracao"
  - "GET /notifications/{productId} restrito a SELLER_ADMIN — diverge da recomendacao da pesquisa/mapa de padroes (qualquer autenticado); negar por padrao e mais barato de relaxar depois do que de apertar quando a Fase 6 reaproveitar o endpoint para a timeline do pedido"

patterns-established:
  - "Pattern: espera de provisionamento de container Testcontainers isolada numa classe auxiliar separada, nunca dentro do bloco static da classe que inicia o container — evita deadlock de inicializacao de classe da JVM quando a biblioteca de espera (Awaitility) avalia a condicao numa thread de background"
  - "Pattern: consumidor SQS de fan-out com parametro String no listener + MessagingMessageConverter com payloadTypeMapper nulo — desacopla o consumidor do atributo de tipo que o produtor decidir enviar"

requirements-completed: [NOTF-01, NOTF-02]

coverage:
  - id: D1
    description: "Evento STOCK_ADJUSTED publicado na fila real vira, em segundos, item no DynamoDB com chave deterministica productId/eventType#eventId, e o vendedor le de volta por GET /notifications/{productId}"
    requirement: "NOTF-01"
    verification:
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationEventFlowIT.java#publishedStockAdjustedEventBecomesQueryableHistoryWithinFifteenSeconds"
        status: pass
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationEventFlowIT.java#repositoryHoldsExactlyOneItemWithDeterministicKeyAfterPublish"
        status: pass
    human_judgment: false
  - id: D2
    description: "Reentrega da mesma mensagem sobrescreve (recordedAt avanca, lista continua com 1 elemento); dois ajustes distintos do mesmo produto geram duas linhas em ordem cronologica"
    requirement: "NOTF-01"
    verification:
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationDeliveryIT.java#redeliveringSameMessageOverwritesInsteadOfDuplicating"
        status: pass
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationDeliveryIT.java#distinctAdjustmentsOfSameProductAccumulateInChronologicalOrder"
        status: pass
    human_judgment: false
  - id: D3
    description: "Mensagem invalida (JSON malformado, campo ausente, quantidade negativa, tipo nao suportado) e descartada com log WARN sem travar a fila; atributo de tipo estrangeiro do produtor e ignorado"
    requirement: "NOTF-01"
    verification:
      - kind: unit
        ref: "notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java#invalidBodiesThrowAndNeverCallRepository"
        status: pass
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationDeliveryIT.java#poisonMessageIsDiscardedWithWarnLogAndConsumptionStaysAlive"
        status: pass
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationDeliveryIT.java#foreignTypeAttributeIsIgnoredAndEventIsRecordedNormally"
        status: pass
    human_judgment: false
  - id: D4
    description: "Consulta do historico fechada: SELLER_ADMIN le, BUYER e 403, token invalido/expirado e 401, identificador nao-UUID e 400, DynamoDB fora do ar e 503 — nenhum corpo vaza detalhe da AWS/implementacao"
    requirement: "NOTF-02"
    verification:
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java#buyerTokenReturns403WithoutLeakingEventData"
        status: pass
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java#errorBodiesNeverLeakExceptionOrStackTraceDetails"
        status: pass
      - kind: integration
        ref: "notification-service/src/test/java/com/orderflow/notification/NotificationStoreUnavailableIT.java#dynamoDbFailureReturns503WithoutLeakingSdkDetails"
        status: pass
    human_judgment: false
  - id: D5
    description: "Provisionamento exclusivo pelo init hook do LocalStack (Success Criteria 4) — nenhum codigo Java cria fila/tabela; reactor Maven e as cinco imagens Docker continuam construindo com o modulo novo"
    verification:
      - kind: automated
        ref: "! grep -rEq 'createQueue|createTable' notification-service/src/main"
        status: pass
      - kind: automated
        ref: "docker build -f notification-service/Dockerfile e -f inventory-service/Dockerfile"
        status: pass
    human_judgment: false

duration: 95min
completed: 2026-09-22
status: complete
---

# Phase 3 Plan 1: Notification-service tracer — SQS to DynamoDB Summary

**Primeiro consumidor SQS do projeto: um evento de ajuste de estoque publicado num LocalStack real vira, em segundos, um item idempotente no DynamoDB, consultavel pelo vendedor por `GET /notifications/{productId}`, com provisionamento de infraestrutura exclusivamente pelo init hook do LocalStack.**

## Performance

- **Duration:** ~95 min (Task 1 tracer incluiu a maior parte do tempo — diagnostico de um deadlock de inicializacao de classe da JVM levou a maior parte disso; Tasks 2 e 3 rodaram sem incidentes)
- **Tasks:** 3 (todas `type="auto"`/`tracer`, `tdd="true"`)
- **Files:** 32 (24 no commit da Task 1, 7 na Task 2, 4 na Task 3, com sobreposicao de arquivos ja existentes)

## NOTIFICATION_EVENT_CONTRACT

`NOTIFICATION_EVENT_CONTRACT=eventId:UUID,eventType:String,productId:UUID,previousQuantityOnHand:Integer,newQuantityOnHand:Integer,occurredAt:Instant(ISO-8601)`

Este e o corpo JSON exato que `com.orderflow.notification.history.dto.StockAdjustedEvent` desserializa, e e o contrato que o plano `03-02` (publicador no `inventory-service`) precisa escrever. `previousQuantityOnHand`/`newQuantityOnHand` sao `Integer` (nao `int`) de proposito — um campo ausente no JSON vira `null` detectavel, nao zero em silencio. Porta do servico: `8084`. Fila: `notification-events-queue`. Tabela: `notification-history` (partition key `productId`, sort key `sortKey` = `eventType#eventId`).

## Accomplishments

- `notification-service` entra no reactor Maven como quinto modulo, com Dockerfile proprio e o BOM `spring-cloud-aws-dependencies:3.4.2` importado no pom raiz; os quatro Dockerfiles existentes ganham a linha `COPY notification-service/pom.xml` sem a qual o build quebraria no `docker compose up` do plano `03-03`
- Init hook `localstack-init/ready.d/01-create-notification-resources.sh` cria a fila `notification-events-queue` e a tabela `notification-history` (idempotente, `--region us-east-1` explicito em todo comando); registrado no indice git com modo `100755` e sem CRLF (`.gitattributes` ganha `*.sh text eol=lf`)
- `NotificationEventListener` (`@SqsListener`, parametro `String`) delega a `NotificationService.record`, que valida o evento, monta a chave deterministica `productId` + `eventType#eventId` (D-32/D-33) e grava via `NotificationRepository.save` (`putItem` sem condicao — sobrescrita em reentrega de graca)
- `SqsMessagingConfig` neutraliza o atributo `JavaType` que o produtor poderia enviar — o tipo de destino vem sempre do parametro do metodo do listener, nunca do produtor; provado com um evento enviado com o atributo apontando para uma classe inexistente neste classpath
- Mensagens invalidas (JSON malformado, campo ausente, quantidade negativa, `eventType` nao suportado) sao descartadas com log WARN (motivo sanitizado contra injecao de linha de log, nunca o corpo completo) e a mensagem e confirmada; qualquer outra falha (ex.: DynamoDB fora do ar) propaga sem ser capturada, garantindo reentrega
- `GET /notifications/{productId}` restrito a `SELLER_ADMIN`, devolve lista ordenada por `occurredAt` (D-31), 200 com lista vazia para produto sem eventos, 400/401/403/503 com corpo uniforme `{error, message}` sem nenhum detalhe do SDK da AWS, nome de tabela ou stack trace
- 33 testes (19 unitarios JUnit+Mockito, 14 de integracao contra LocalStack real via Testcontainers) verdes; reactor e as cinco imagens Docker continuam construindo

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — evento na fila vira historico no DynamoDB, lido de volta pelo vendedor** — `578b055` (feat) — RED (4 testes de `NotificationEventFlowIT`) → GREEN (4/4), depois de resolver o deadlock descrito em Deviations
2. **Task 2: Entrega confiavel — reentrega sobrescreve, ajustes distintos acumulam, mensagem venenosa nao trava a fila** — `7cbfde1` (feat) — RED (19 unitarios + 4 integracao) → GREEN (23/23 novos, 27/27 no total do modulo)
3. **Task 3: Consulta vira contrato — so o vendedor le, erro nunca vaza detalhe da AWS** — `d7c199f` (feat) — RED (6 testes) → GREEN (6/6 novos, 33/33 no total do modulo)

**Plan metadata:** commit de documentacao final a ser criado logo apos este SUMMARY.

## Files Created/Modified

- `pom.xml` — adiciona `notification-service` a `<modules>`, propriedade `spring-cloud-aws.version=3.4.2`, import do BOM `spring-cloud-aws-dependencies`
- `.gitattributes` — regra `*.sh text eol=lf`
- `auth-service/Dockerfile`, `gateway/Dockerfile`, `catalog-service/Dockerfile`, `inventory-service/Dockerfile` — ganham `COPY notification-service/pom.xml notification-service/pom.xml`
- `localstack-init/ready.d/01-create-notification-resources.sh` — unico ponto de criacao da fila/tabela (modo `100755` no indice)
- `notification-service/pom.xml`, `notification-service/Dockerfile` — modulo novo, sem JPA/Flyway/Postgres (sink puro sobre DynamoDB)
- `notification-service/.../history/NotificationRecord.java` — `@DynamoDbBean` (classe, nao `record`), `@DynamoDbPartitionKey`/`@DynamoDbSortKey` escritos a mao
- `notification-service/.../history/NotificationRepository.java` — `DynamoDbEnhancedClient` direto; `save` via `putItem` sem condicao, `findByProductId` via `QueryConditional.keyEqualTo`
- `notification-service/.../history/NotificationService.java` — validacao do evento, mensagem legivel (singular/plural), ordenacao da leitura por `occurredAt`+`sortKey`
- `notification-service/.../history/InvalidNotificationEventException.java` — excecao de descarte controlado
- `notification-service/.../history/NotificationController.java` — `GET /notifications/{productId}`, `@PreAuthorize("hasRole('SELLER_ADMIN')")`
- `notification-service/.../history/messaging/NotificationEventListener.java` — unico ponto de entrada de dados do servico
- `notification-service/.../config/{SecurityConfig,SqsMessagingConfig,GlobalExceptionHandler}.java`
- `notification-service/.../history/dto/{StockAdjustedEvent,NotificationResponse}.java`
- `notification-service/src/test/.../support/{TestJwt,LocalStackTestSupport,LocalStackProvisioningWaiter}.java`, `AbstractIntegrationTest.java` — infraestrutura de teste com LocalStack real
- `notification-service/src/test/.../{NotificationEventFlowIT,NotificationDeliveryIT,NotificationControllerIT,NotificationStoreUnavailableIT}.java`, `history/{NotificationServiceTest,messaging/NotificationEventListenerTest}.java` — 33 testes

## Decisions Made

- **NOTIFICATION_EVENT_CONTRACT, porta 8084, nomes de fila/tabela** — ver secao dedicada acima; registrado para o plano `03-02` escrever o publicador contra este contrato.
- **`DynamoDbEnhancedClient` em vez de `DynamoDbTemplate`** (Claude's Discretion de `03-CONTEXT.md`) — a assinatura de consulta do template nao foi confirmada pela pesquisa (03-RESEARCH.md Assumptions Log A2), e o resolvedor de nome de tabela padrao do template derivaria um nome a partir da classe em vez de usar o nome explicito de `orderflow.notifications.table-name`.
- **`GET /notifications/{productId}` restrito a SELLER_ADMIN** (Claude's Discretion) — diverge deliberadamente da recomendacao da pesquisa e do mapa de padroes (`03-PATTERNS.md`), que sugeriam liberar para qualquer autenticado. O historico desta fase e dado operacional do vendedor sobre o proprio catalogo; negar por padrao e mais barato de relaxar depois do que de apertar. Quando a Fase 6 reaproveitar este endpoint para a timeline do pedido, o BUYER vai precisar de acesso restrito aos pedidos da propria empresa — uma regra nova e explicita, nao uma liberacao herdada daqui.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Deadlock de inicializacao de classe da JVM entre o bloco `static` do container Testcontainers e a thread de background do Awaitility**
- **Found during:** Task 1, ao rodar `NotificationEventFlowIT` pela primeira vez contra LocalStack real
- **Issue:** `LocalStackTestSupport` iniciava o container e chamava a logica de espera (`Awaitility.await(...).untilAsserted(...)`) dentro do proprio bloco `static { ... }` da classe. O lambda passado a `untilAsserted` e compilado como um metodo estatico sintetico da MESMA classe (`LocalStackTestSupport`) e e avaliado por uma thread de background do Awaitility, nao pela thread principal que executa o `<clinit>`. Invocar esse metodo estatico exige que a JVM confirme que a classe ja terminou de inicializar (JLS 12.4.1/12.4.2); como a classe ainda esta em inicializacao pela thread principal, a thread de background bloqueia esperando — e a thread principal esta bloqueada esperando o resultado da thread de background. Deadlock classico de inicializacao de classe, que so se manifestava como um timeout silencioso de 60s sem nenhuma tentativa de conexao chegando a rede (confirmado isolando a chamada AWS SDK num programa Java standalone, que funcionava em 304ms fora desse contexto).
- **Fix:** Extraida a logica de espera para uma classe auxiliar nova e separada (`LocalStackProvisioningWaiter`), ja totalmente inicializada antes do lambda comecar a rodar — o metodo estatico invocado pela thread de background do Awaitility passa a pertencer a uma classe sem inicializacao pendente, eliminando o ciclo.
- **Files modified:** `notification-service/src/test/java/com/orderflow/notification/support/LocalStackTestSupport.java` (simplificada), `notification-service/src/test/java/com/orderflow/notification/support/LocalStackProvisioningWaiter.java` (nova)
- **Verification:** `NotificationEventFlowIT` (4/4), depois toda a suite de integracao (14/14), verdes contra LocalStack real apos o fix.
- **Commit:** `578b055`

---

**Total deviations:** 1 auto-fixed (Rule 1 — bug de infraestrutura de teste, nao de codigo de producao)
**Impact on plan:** Nenhum scope creep. O fix e puramente na infraestrutura de teste (`support/`); nenhuma classe de producao foi alterada por causa dele.

## Issues Encountered

Nenhum problema bloqueante alem do deadlock documentado acima. O diagnostico exigiu isolar a chamada do AWS SDK v2 num programa Java standalone fora do Spring/Awaitility/JUnit para confirmar que a rede e as credenciais estavam corretas antes de suspeitar do padrao de concorrencia.

## User Setup Required

Nenhuma configuracao nova alem da ja registrada na Fase 1: `LOCALSTACK_AUTH_TOKEN` precisa estar definido como variavel de ambiente ou no arquivo `.env` da raiz do repositorio para os testes de integracao rodarem contra LocalStack real.

## Threat Flags

Nenhuma superficie nova alem das ja registradas no `<threat_model>` do plano (T-03-01 a T-03-10, T-03-SC) — todas com disposicao `mitigate`/`accept` implementadas e cobertas por teste (restricao de papel, validacao de JWT, descarte controlado de mensagem invalida, chave deterministica, corpo de erro sem vazamento, sanitizacao de log, `queue-not-found-strategy=fail`, auditoria de dependencias novas).

## Known Stubs

Nenhum. O fluxo evento-na-fila -> historico-consultavel esta implementado e testado de ponta a ponta contra LocalStack real, sem dado mock ou placeholder.

## Next Phase Readiness

- O plano `03-02` (publicador no `inventory-service`) escreve contra `NOTIFICATION_EVENT_CONTRACT` registrado acima — nenhum bloqueio conhecido.
- O plano `03-03` (compose, rota do Gateway, script de smoke, `OpenApiConfig`, documentacao) consome a porta `8084` e os nomes de fila/tabela registrados acima.
- A Fase 6 (timeline do pedido) reaproveita o padrao de Query-por-partition-key e precisa introduzir uma regra explicita de acesso do BUYER aos proprios pedidos — a restricao a SELLER_ADMIN desta fase nao e uma liberacao herdada.

---
*Phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es*
*Completed: 2026-09-22*

## Self-Check: PASSED

All key files and commit hashes verified present:
- `localstack-init/ready.d/01-create-notification-resources.sh`, `notification-service/pom.xml`,
  `NotificationRecord.java`, `NotificationRepository.java`, `SqsMessagingConfig.java`,
  `GlobalExceptionHandler.java`, `LocalStackProvisioningWaiter.java`, `NotificationControllerIT.java`,
  `NotificationStoreUnavailableIT.java` — all found.
- Commits `578b055`, `7cbfde1`, `d7c199f` — all found in `git log --oneline --all`.
