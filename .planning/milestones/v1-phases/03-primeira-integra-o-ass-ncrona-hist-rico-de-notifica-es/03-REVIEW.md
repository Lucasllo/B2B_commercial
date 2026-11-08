---
phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
reviewed: 2026-09-23T00:00:00Z
depth: standard
files_reviewed: 51
files_reviewed_list:
  - .gitattributes
  - auth-service/Dockerfile
  - catalog-service/Dockerfile
  - docker-compose.yml
  - gateway/Dockerfile
  - gateway/src/main/resources/application.yml
  - inventory-service/Dockerfile
  - inventory-service/pom.xml
  - inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockAdjustedEvent.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/messaging/StockEventPublisher.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/StockAdjustmentResult.java
  - inventory-service/src/main/resources/application.yml
  - inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/StockEventPublishFailureIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/support/LocalStackProvisioningWaiter.java
  - inventory-service/src/test/java/com/orderflow/inventory/support/LocalStackTestSupport.java
  - localstack-init/ready.d/01-create-notification-resources.sh
  - notification-service/Dockerfile
  - notification-service/pom.xml
  - notification-service/src/main/java/com/orderflow/notification/config/GlobalExceptionHandler.java
  - notification-service/src/main/java/com/orderflow/notification/config/OpenApiConfig.java
  - notification-service/src/main/java/com/orderflow/notification/config/SecurityConfig.java
  - notification-service/src/main/java/com/orderflow/notification/config/SqsMessagingConfig.java
  - notification-service/src/main/java/com/orderflow/notification/history/dto/NotificationResponse.java
  - notification-service/src/main/java/com/orderflow/notification/history/dto/StockAdjustedEvent.java
  - notification-service/src/main/java/com/orderflow/notification/history/InvalidNotificationEventException.java
  - notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationController.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationRecord.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationRepository.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java
  - notification-service/src/main/java/com/orderflow/notification/NotificationServiceApplication.java
  - notification-service/src/main/resources/application.yml
  - notification-service/src/test/java/com/orderflow/notification/AbstractIntegrationTest.java
  - notification-service/src/test/java/com/orderflow/notification/history/messaging/NotificationEventListenerTest.java
  - notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java
  - notification-service/src/test/java/com/orderflow/notification/NotificationControllerIT.java
  - notification-service/src/test/java/com/orderflow/notification/NotificationDeliveryIT.java
  - notification-service/src/test/java/com/orderflow/notification/NotificationEventFlowIT.java
  - notification-service/src/test/java/com/orderflow/notification/NotificationStoreUnavailableIT.java
  - notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java
  - notification-service/src/test/java/com/orderflow/notification/support/LocalStackProvisioningWaiter.java
  - notification-service/src/test/java/com/orderflow/notification/support/LocalStackTestSupport.java
  - notification-service/src/test/java/com/orderflow/notification/support/TestJwt.java
  - pom.xml
  - scripts/smoke-notification-flow.sh
findings:
  critical: 0
  warning: 7
  info: 7
  total: 14
status: issues_found
---

# Fase 03: Relatório de Code Review

**Revisado em:** 2026-09-23T00:00:00Z
**Profundidade:** standard
**Arquivos revisados:** 51
**Status:** issues_found

## Resumo

Foram revisados o novo `notification-service` (listener SQS -> DynamoDB, endpoint de leitura, segurança, tratamento de erros), o publicador direto do `inventory-service` (`StockEventPublisher` + `StockAdjustmentResult`), o init hook do LocalStack, o `docker-compose.yml`, a rota nova do Gateway, os Dockerfiles, os poms e o smoke `scripts/smoke-notification-flow.sh`, além das suítes de teste que sustentam esses comportamentos.

O núcleo funcional está correto: a chave determinística `eventType#eventId` dá mesmo a idempotência de reentrega; o consumidor não confia no atributo `JavaType`; a publicação acontece depois do commit (a ordem `@EnableRetry(order = LOWEST_PRECEDENCE - 1)` garante que retry envolve a transação); e a validação do evento cobre campos ausentes e tipos inválidos. Não encontrei nenhum BLOCKER. O dual-write sem outbox (D-29/D-30) é uma dívida aceita e documentada para a Fase 5, então não entra como achado.

Os principais riscos são de robustez e de confiabilidade dos testes:
1. Um contexto de teste com `@MockitoBean` mantém um listener SQS vivo que confirma e apaga mensagens sem gravar nada. Os testes seguintes podem ficar instáveis, dependendo da ordem de execução.
2. Falhas permanentes do DynamoDB, como um item acima de 400 KB, entram num laço infinito de reentrega.
3. A publicação síncrona roda na thread HTTP sem timeout.
4. O `occurredAt` é capturado depois do commit, o que pode inverter a ordem cronológica do histórico sob concorrência.
5. O smoke aborta sem diagnóstico por causa de `pipefail` + `grep`.

## Narrative Findings (AI reviewer)

## Warnings

### WR-01: Contexto de `NotificationStoreUnavailableIT` mantém um listener SQS ativo que consome e descarta mensagens de outras classes de teste

**Arquivo:** `notification-service/src/test/java/com/orderflow/notification/NotificationStoreUnavailableIT.java:23-26`
**Problema:** `@MockitoBean NotificationRepository` cria um segundo contexto Spring. O cache de contextos do Spring Test mantém esse contexto vivo até o fim da JVM, e o `@SqsListener` dele continua fazendo poll na mesma fila `notification-events-queue` usada pelo contexto principal. Nesse contexto, `NotificationService.record` chama `save` num mock que não faz nada, o método retorna normalmente e o Spring Cloud AWS confirma e **apaga** a mensagem. A partir daí, toda mensagem publicada por uma classe de IT que rode depois dessa tem cerca de 50% de chance de ser consumida pelo contexto mockado e sumir, e o `await()` de 15 s estoura. Hoje isso só não aparece porque, pela ordem em que as classes costumam rodar, só `OpenApiDocsIT` (que não usa a fila) vem depois. Só que o `runOrder` padrão do failsafe é `filesystem`, que no ext4 de um runner Linux não é alfabético. Basta também adicionar uma nova classe `*IT` com nome posterior para o problema aparecer.
**Correção:** fechar o contexto ao fim da classe (o que para o container do listener) ou impedir que o listener dele suba:
```java
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationStoreUnavailableIT extends AbstractIntegrationTest {
```
Outra opção, mais forte: mockar também o `NotificationEventListener` (`@MockitoBean NotificationEventListener`), para que nenhum `@SqsListener` real seja registrado nesse contexto.

### WR-02: Falha permanente do DynamoDB vira laço infinito de reentrega (sem limite de tamanho do payload)

**Arquivo:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java:43-82` e `notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java:38-45`
**Problema:** o listener só descarta `InvalidNotificationEventException`. Qualquer outra exceção é tratada como transitória, e a mensagem volta à fila depois do visibility timeout, para sempre (não há DLQ nem `maxReceiveCount`). O design supõe que toda falha do `putItem` seja transitória, mas existem falhas permanentes. A mais simples de provocar: `FAIL_ON_UNKNOWN_PROPERTIES` está desligado no `ObjectMapper` do Boot, e o `rawPayload` guarda o corpo inteiro, inclusive campos extras. Um evento válido com um campo extra grande (o SQS aceita até 256 KB/1 MiB, e o item do DynamoDB tem teto de 400 KB) passa pela validação, e o `putItem` lança `DynamoDbException` (ValidationException: item size exceeded). A mensagem é reentregue indefinidamente, gastando consumo e poluindo o log com ERROR a cada ciclo. O mesmo vale para outros erros 4xx não retentáveis do SDK.
**Correção:** limitar o tamanho do corpo antes do parse e classificar como inválido o que nunca vai caber:
```java
private static final int MAX_RAW_PAYLOAD_BYTES = 64 * 1024;

public void record(String rawPayload) {
    if (rawPayload.getBytes(StandardCharsets.UTF_8).length > MAX_RAW_PAYLOAD_BYTES) {
        throw new InvalidNotificationEventException("Corpo da mensagem excede o tamanho maximo");
    }
    ...
```
Opcionalmente, converter `DynamoDbException` com `statusCode()` 400 (não retentável) em `InvalidNotificationEventException` no listener, deixando propagar apenas as falhas 5xx/de rede.

### WR-03: Publicação síncrona no SQS roda na thread da requisição HTTP sem timeout

**Arquivo:** `inventory-service/src/main/java/com/orderflow/inventory/stock/messaging/StockEventPublisher.java:43-54` (chamado em `InventoryController.java:47-50`)
**Problema:** `sqsTemplate.send(...)` resolve internamente um `CompletableFuture` com `join()` sem prazo. O `SqsAsyncClient` padrão (Netty) usa read timeout de 30 s e a política de retry standard (3 tentativas). Se o LocalStack/SQS aceitar a conexão mas não responder, cada `PUT /inventory/{id}`, que **já commitou**, prende a thread do Tomcat por até cerca de 1–2 minutos antes de cair no `catch` e devolver 200. Sob carga, o pool de threads do servidor se esgota por causa de um efeito colateral que o próprio design declara como "melhor esforço". O `StockEventPublishFailureIT` só cobre a falha rápida (fila inexistente), não a lentidão.
**Correção:** limitar o tempo total da chamada ao SDK com um customizer do cliente, ou usar o envio assíncrono com prazo:
```java
@Bean
SqsAsyncClientCustomizer sqsTimeouts() {
    return builder -> builder.overrideConfiguration(c -> c
            .apiCallTimeout(Duration.ofSeconds(3))
            .apiCallAttemptTimeout(Duration.ofSeconds(1)));
}
```
Ou então: `sqsTemplate.sendAsync(...).orTimeout(3, SECONDS).exceptionally(e -> { log.error(...); return null; })`, sem bloquear a resposta.

### WR-04: `occurredAt` é gerado depois do commit, na publicação, e pode inverter a ordem cronológica do histórico sob concorrência

**Arquivo:** `inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockAdjustedEvent.java:27-30` e `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java:47-49`
**Problema:** `StockAdjustedEvent.of` chama `Instant.now()` no momento da publicação, fora da transação e depois do retorno do proxy. Com dois `PUT` concorrentes no mesmo produto, A commita primeiro (0 -> 5) e B commita depois (5 -> 9). Se a thread de B chegar antes à linha de publicação, o evento de B recebe um `occurredAt` menor. `NotificationService.history` (linhas 139-140) ordena por `occurredAt`, então o histórico mostra "5 -> 9" antes de "0 -> 5". O resultado contradiz o próprio par `previousQuantityOnHand`/`newQuantityOnHand` e a promessa de "ordem cronológica restaurada na leitura". O campo também diz "occurredAt", mas mede o instante do envio, não o do ajuste.
**Correção:** capturar o instante dentro da tentativa transacional que gravou o ajuste e levá-lo em `StockAdjustmentResult`:
```java
public record StockAdjustmentResult(StockResponse stock, int previousQuantityOnHand, Instant adjustedAt) {}
// em setStock: new StockAdjustmentResult(StockResponse.from(inventory), previousQuantityOnHand, Instant.now());
// no publisher: StockAdjustedEvent.of(productId, previous, next, result.adjustedAt())
```
Para uma ordem estrita, o ideal seria usar a coluna de versão/`updated_at` da linha, e não o relógio da JVM.

### WR-05: Smoke aborta em silêncio (sem mensagem `SMOKE FALHOU`) quando `grep` não encontra nada, por causa de `set -o pipefail`

**Arquivo:** `scripts/smoke-notification-flow.sh:12, 44-46, 136, 158`
**Problema:** `count_history_entries` faz `grep -o '"recordedAt"' | wc -l`. Com `pipefail`, se o corpo não tiver nenhum `recordedAt` (lista vazia `[]`, ou um corpo de erro como `{"error":"notification_store_unavailable",...}`), o `grep` sai com 1, o pipeline sai com 1, a atribuição `REDELIVERY_COUNT=$(...)` falha e o `set -e` encerra o script com exit 1 **sem imprimir nenhum diagnóstico**. As mensagens `fail "reentrega duplicou..."`/`"ajustes distintos nao acumularam..."` nunca aparecem. No laço do passo 7 (linha 158), uma resposta transitória sem elementos aborta o script em vez de tentar de novo no próximo segundo, o que anula o propósito do laço de 15 s. O mesmo vale para `exec_gateway curl` dentro dos laços: um erro de conexão do curl (exit 7) atravessa o pipe e mata o script.
**Correção:**
```bash
count_history_entries() {
    { grep -o '"recordedAt"' || true; } | wc -l | tr -d ' '
}
```
Nos laços de polling, proteger as chamadas: `HISTORY_BODY=$(exec_gateway curl -s ... || true)`.

### WR-06: `sanitizeForLog` não neutraliza "qualquer caractere de controle", como o javadoc afirma

**Arquivo:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java:110-123`
**Problema:** o regex `[\r\n\t]` cobre só três caracteres. Um `eventType` vindo da fila com `\u001b[...` (sequência ANSI de terminal), `\u0085` (NEL), `\u2028`/`\u2029` (separadores de linha que vários agregadores de log tratam como quebra) ou `\u0000` passa intacto para a linha WARN do listener (`NotificationEventListener.java:43`). A defesa contra injeção de log fica parcial, embora o comentário diga o contrário, e o teste `unsupportedEventTypeWithNewlineProducesSanitizedMessage` cobre apenas `\n`/`\r`.
**Correção:**
```java
String sanitized = value.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", "_");
```
Também vale acrescentar um caso com `\u001b` e `\u2028` ao teste parametrizado.

### WR-07: O resource server de produção não valida o emissor (`iss`), enquanto o `JwtDecoder` de teste valida, e os testes dão falsa garantia

**Arquivo:** `notification-service/src/main/resources/application.yml:24-30`, `notification-service/src/main/java/com/orderflow/notification/config/SecurityConfig.java:63` vs `notification-service/src/test/java/com/orderflow/notification/support/TestJwt.java:94-101`
**Problema:** em produção só existe `jwk-set-uri`, e o decoder autoconfigurado valida apenas assinatura e `exp`/`nbf`. Não há `issuer-uri`, e nenhum bean `JwtDecoder` usa `JwtValidators.createDefaultWithIssuer`. O `TestJwt.Config` de teste, por outro lado, aplica `createDefaultWithIssuer("orderflow-auth-service")`. Nenhum teste de IT consegue detectar a ausência dessa checagem em produção: um token com `iss` diferente seria rejeitado no teste e aceito em produção. O `auth-service` (`JwtIssuerConfig.java:67`) valida o emissor, então o comportamento diverge entre serviços. O impacto prático hoje é baixo (só o auth-service tem a chave privada), mas a paridade teste/produção está quebrada e o padrão vai ser herdado pelos próximos resource servers.
**Correção:** declarar o `JwtDecoder` de produção com o mesmo validador:
```java
@Bean
JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("orderflow-auth-service"));
    return decoder;
}
```
Outra forma é padronizar o issuer via propriedade no `application.yml` e usar a mesma propriedade no `TestJwt`.

## Info

### IN-01: `PUT` com a mesma quantidade publica um evento "ajustado de N para N"

**Arquivo:** `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java:47-50`
**Problema:** o evento é publicado sempre que o `PUT` dá certo, mesmo quando `previousQuantityOnHand == quantityOnHand` (por exemplo, um retry idempotente do cliente ou do Gateway). Cada repetição cria uma linha nova no histórico, com `eventId` novo, dizendo "de 7 para 7 unidades".
**Correção:** pular a publicação quando `result.previousQuantityOnHand() == result.stock().quantityOnHand()`, a menos que um ajuste sem mudança seja explicitamente desejado no histórico. Se for, documentar isso.

### IN-02: `GlobalExceptionHandler` não cumpre o contrato "sempre `error`/`message`" que o javadoc declara

**Arquivo:** `notification-service/src/main/java/com/orderflow/notification/config/GlobalExceptionHandler.java:17-21`
**Problema:** não há handler genérico. O `IllegalStateException` de `NotificationService.parsePayload` (linha 153), ou qualquer outra exceção não mapeada, cai no `/error` padrão do Boot (`timestamp/status/error/path`). O 401 do `BearerTokenAuthenticationEntryPoint` sai sem corpo e nunca passa pelo `@ExceptionHandler(AuthenticationException)`, que na prática é código morto. Um único item corrompido também torna todo o histórico do produto ilegível (500).
**Correção:** adicionar `@ExceptionHandler(Exception.class)` com log e corpo `{"error":"internal_error",...}`, e corrigir o javadoc. Se quiser o corpo uniforme também no 401, configurar um `authenticationEntryPoint` próprio no `SecurityConfig`.

### IN-03: A validação do evento aceita coerções silenciosas do Jackson

**Arquivo:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java:55-60`
**Problema:** com o `ObjectMapper` do Boot, `"previousQuantityOnHand":"5"` (string) e `"newQuantityOnHand":1.9` (float, truncado para 1 por `ACCEPT_FLOAT_AS_INT`) passam como válidos. A `message` gravada diz "para 1 unidade", enquanto o `rawPayload` guarda `1.9`, uma inconsistência dentro do mesmo item. O comentário de `StockAdjustedEvent` ("o consumidor não confia no produtor") sugere uma validação mais estrita.
**Correção:** usar um reader dedicado com `.without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)` e `MapperFeature.ALLOW_COERCION_OF_SCALARS` desligado (ou `coercionConfigFor(LogicalType.Integer)` com `CoercionAction.Fail`), ou então checar `tree.get("newQuantityOnHand").isInt()`.

### IN-04: `testcontainers.version` 1.20.4 no pom raiz é configuração morta, e há comentários desatualizados sobre o Spring Cloud AWS

**Arquivo:** `pom.xml:38, 51-57, 78-85, 92-96`
**Problema:** o `spring-boot-dependencies` 3.5.16 é importado antes e já gerencia o Testcontainers (`testcontainers.version` 1.21.4 dentro do BOM). Como o primeiro BOM importado vence, a resolução efetiva é 1.21.4 (confirmado em `~/.m2`), e o `testcontainers-bom` 1.20.4 declarado não tem efeito. Os comentários também dizem que o Spring Cloud AWS é "usado pelo notification-service", mas desde o 03-02 o `inventory-service` também o usa.
**Correção:** remover o import redundante (ou movê-lo para antes do BOM do Boot, se a versão 1.20.4 for realmente a pretendida) e atualizar os comentários.

### IN-05: A busca de `.env` nos testes sobe além da raiz do repositório

**Arquivo:** `notification-service/src/test/java/com/orderflow/notification/support/LocalStackTestSupport.java:83-96` (idem `inventory-service/.../LocalStackTestSupport.java:83-96`)
**Problema:** `readFromDotEnvUpwards` sobe até a raiz do sistema de arquivos e pode pegar um `LOCALSTACK_AUTH_TOKEN` de um `.env` não relacionado, por exemplo no diretório home. As duas classes são cópias literais uma da outra.
**Correção:** parar no primeiro diretório que contém `pom.xml` com `<artifactId>orderflow-parent</artifactId>` (ou em `.git`). Se possível, extrair o código duplicado para um utilitário de teste compartilhado.

### IN-06: No smoke, token no argv, credencial fixa e regex só compatível com GNU sed

**Arquivo:** `scripts/smoke-notification-flow.sh:17-18, 70-73, 123-124`
**Problema:** o JWT é passado como argumento do `curl` via `docker compose exec`, e fica visível em `ps` dentro do container. A senha `ChangeMe!123` do admin semeado está fixa no script, e o Gateway publica `8080` em todas as interfaces (`docker-compose.yml:154`), então qualquer máquina da rede local consegue logar como SELLER_ADMIN. `\+` no `sed` é extensão GNU: no BSD/macOS, `VISIBLE`/`NOT_VISIBLE` ficam vazios e o passo 6 falha por timeout, com um diagnóstico enganoso.
**Correção:** passar o header via `-H @-`/arquivo temporário dentro do container; permitir sobrescrever `DEMO_PASSWORD` por variável de ambiente; trocar `\+` por `[0-9][0-9]*` ou usar `--query ... --output text` do awslocal no lugar do parse com sed.

### IN-07: Histórico sem paginação, com campos arbitrários do produtor devolvidos em `payload`

**Arquivo:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationRepository.java:41-45` e `notification-service/src/main/java/com/orderflow/notification/history/dto/NotificationResponse.java:13-21`
**Problema:** `findByProductId` materializa todas as páginas, e o endpoint devolve a lista inteira. O `payload` ecoa verbatim qualquer campo extra que um produtor com acesso à fila tenha incluído, sem limite (ver WR-02). Quando a Fase 6 exibir isso numa UI, esse conteúdo vira um vetor de conteúdo não confiável.
**Correção:** adicionar `limit` ou cursor (`exclusiveStartKey`) ao endpoint e, se o objetivo for mostrar o evento de contrato, devolver apenas os campos conhecidos do `StockAdjustedEvent` em vez do corpo cru.

---

_Revisado em: 2026-09-23T00:00:00Z_
_Revisor: Claude (gsd-code-reviewer)_
_Profundidade: standard_
