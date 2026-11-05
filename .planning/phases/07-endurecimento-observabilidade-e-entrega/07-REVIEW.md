---
phase: 07-endurecimento-observabilidade-e-entrega
reviewed: 2026-11-05T00:00:00Z
depth: standard
files_reviewed: 151
files_reviewed_list:
  - .github/workflows/ci.yml
  - auth-service/src/main/java/com/orderflow/auth/auth/AuthController.java
  - auth-service/src/main/java/com/orderflow/auth/auth/dto/CurrentUserResponse.java
  - auth-service/src/main/java/com/orderflow/auth/auth/dto/LoginRequest.java
  - auth-service/src/main/java/com/orderflow/auth/auth/dto/LoginResponse.java
  - auth-service/src/main/java/com/orderflow/auth/company/CompanyController.java
  - auth-service/src/main/java/com/orderflow/auth/company/dto/CompanyResponse.java
  - auth-service/src/main/java/com/orderflow/auth/company/dto/CreateCompanyRequest.java
  - auth-service/src/main/java/com/orderflow/auth/company/dto/CreditLimitResponse.java
  - auth-service/src/main/java/com/orderflow/auth/company/dto/UpdateCreditLimitRequest.java
  - auth-service/src/main/java/com/orderflow/auth/config/ErrorResponse.java
  - auth-service/src/main/java/com/orderflow/auth/config/JwksController.java
  - auth-service/src/main/java/com/orderflow/auth/config/OpenApiConfig.java
  - auth-service/src/main/java/com/orderflow/auth/observability/CorrelationContext.java
  - auth-service/src/main/java/com/orderflow/auth/observability/CorrelationIdFilter.java
  - auth-service/src/main/resources/application.yml
  - auth-service/src/test/java/com/orderflow/auth/auth/AuthControllerTest.java
  - auth-service/src/test/java/com/orderflow/auth/auth/TokenServiceTest.java
  - auth-service/src/test/java/com/orderflow/auth/company/CompanyGuardTest.java
  - auth-service/src/test/java/com/orderflow/auth/company/CompanyServiceTest.java
  - auth-service/src/test/java/com/orderflow/auth/CorrelationIdLoggingIT.java
  - auth-service/src/test/java/com/orderflow/auth/observability/CorrelationIdFilterTest.java
  - auth-service/src/test/java/com/orderflow/auth/OpenApiDocsIT.java
  - catalog-service/src/main/java/com/orderflow/catalog/config/ErrorResponse.java
  - catalog-service/src/main/java/com/orderflow/catalog/config/OpenApiConfig.java
  - catalog-service/src/main/java/com/orderflow/catalog/observability/CorrelationContext.java
  - catalog-service/src/main/java/com/orderflow/catalog/observability/CorrelationIdFilter.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/dto/CreateProductRequest.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/dto/ProductResponse.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/dto/UpdateProductRequest.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/dto/UpdateProductStatusRequest.java
  - catalog-service/src/main/java/com/orderflow/catalog/product/ProductController.java
  - catalog-service/src/main/resources/application.yml
  - catalog-service/src/test/java/com/orderflow/catalog/CorrelationIdLoggingIT.java
  - catalog-service/src/test/java/com/orderflow/catalog/observability/CorrelationIdFilterTest.java
  - catalog-service/src/test/java/com/orderflow/catalog/OpenApiDocsIT.java
  - catalog-service/src/test/java/com/orderflow/catalog/product/ProductServiceTest.java
  - docs/adr/0001-saga-por-orquestracao-no-order-service.md
  - docs/adr/0002-transactional-outbox-em-vez-de-publicacao-direta.md
  - docs/adr/0003-localstack-em-vez-de-aws-real.md
  - docs/adr/0004-sem-service-discovery-nem-config-server.md
  - docs/adr/0005-jwt-auto-emitido-e-validado-localmente.md
  - docs/adr/0006-dynamodb-para-o-historico-de-notificacoes.md
  - docs/adr/0007-credito-serializado-por-empresa.md
  - docs/adr/0008-correlation-id-proprio-em-vez-de-tracing-distribuido.md
  - docs/adr/0009-gateway-server-webmvc-em-vez-do-reativo.md
  - docs/adr/0010-falha-fechada-sem-resilience4j.md
  - docs/adr/0011-codigo-duplicado-por-servico-em-vez-de-modulo-comum.md
  - docs/adr/README.md
  - docs/API.md
  - docs/VISAO-GERAL.md
  - e2e-tests/src/test/java/com/orderflow/e2e/CorrelationIdE2EIT.java
  - e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java
  - e2e-tests/src/test/java/com/orderflow/e2e/support/E2eHttp.java
  - gateway/pom.xml
  - gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java
  - gateway/src/main/resources/application.yml
  - gateway/src/test/java/com/orderflow/gateway/CorrelationIdFilterTest.java
  - gateway/src/test/java/com/orderflow/gateway/GatewayRoutesTest.java
  - gateway/src/test/java/com/orderflow/gateway/GatewayRoutingIT.java
  - gateway/src/test/java/com/orderflow/gateway/support/UpstreamStubServer.java
  - inventory-service/src/main/java/com/orderflow/inventory/config/ErrorResponse.java
  - inventory-service/src/main/java/com/orderflow/inventory/config/OpenApiConfig.java
  - inventory-service/src/main/java/com/orderflow/inventory/observability/CorrelationContext.java
  - inventory-service/src/main/java/com/orderflow/inventory/observability/CorrelationIdFilter.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/InvalidShipStockException.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxEvent.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelay.java
  - inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxWriter.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/ReserveStockRequest.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/SetStockRequest.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockResponse.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java
  - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
  - inventory-service/src/main/resources/application.yml
  - inventory-service/src/main/resources/db/migration/V5__outbox_correlation_id.sql
  - inventory-service/src/test/java/com/orderflow/inventory/observability/CorrelationIdFilterTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/OpenApiDocsIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/ReservationCommandConsumptionIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/ReservationCommandListenerTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/saga/messaging/SagaCommandParserTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/saga/outbox/OutboxRelayTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/saga/outbox/OutboxWriterTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/ShipStockConsumptionIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/stock/InventoryServiceTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/stock/InventoryTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/stock/StockReservationTest.java
  - inventory-service/src/test/java/com/orderflow/inventory/StockAdjustedEventPublishingIT.java
  - inventory-service/src/test/java/com/orderflow/inventory/support/SagaQueues.java
  - notification-service/src/main/java/com/orderflow/notification/config/ErrorResponse.java
  - notification-service/src/main/java/com/orderflow/notification/config/OpenApiConfig.java
  - notification-service/src/main/java/com/orderflow/notification/history/dto/NotificationResponse.java
  - notification-service/src/main/java/com/orderflow/notification/history/InvalidNotificationEventException.java
  - notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationController.java
  - notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java
  - notification-service/src/main/java/com/orderflow/notification/observability/CorrelationContext.java
  - notification-service/src/main/java/com/orderflow/notification/observability/CorrelationIdFilter.java
  - notification-service/src/main/resources/application.yml
  - notification-service/src/test/java/com/orderflow/notification/CorrelationIdConsumptionIT.java
  - notification-service/src/test/java/com/orderflow/notification/history/messaging/NotificationEventListenerTest.java
  - notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java
  - notification-service/src/test/java/com/orderflow/notification/observability/CorrelationIdFilterTest.java
  - notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java
  - order-service/src/main/java/com/orderflow/order/config/ClientConfig.java
  - order-service/src/main/java/com/orderflow/order/config/ErrorResponse.java
  - order-service/src/main/java/com/orderflow/order/config/OpenApiConfig.java
  - order-service/src/main/java/com/orderflow/order/observability/CorrelationContext.java
  - order-service/src/main/java/com/orderflow/order/observability/CorrelationIdFilter.java
  - order-service/src/main/java/com/orderflow/order/order/dto/ApproveOrderRequest.java
  - order-service/src/main/java/com/orderflow/order/order/dto/CreateOrderRequest.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemRequest.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemResponse.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderResponse.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderSummaryResponse.java
  - order-service/src/main/java/com/orderflow/order/order/dto/RejectOrderRequest.java
  - order-service/src/main/java/com/orderflow/order/order/Order.java
  - order-service/src/main/java/com/orderflow/order/order/OrderController.java
  - order-service/src/main/java/com/orderflow/order/order/OrderDecisionController.java
  - order-service/src/main/java/com/orderflow/order/order/OrderService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderShipmentController.java
  - order-service/src/main/java/com/orderflow/order/saga/messaging/ReservationResultListener.java
  - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxEvent.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java
  - order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxWriter.java
  - order-service/src/main/resources/application.yml
  - order-service/src/main/resources/db/migration/V4__correlation_id.sql
  - order-service/src/test/java/com/orderflow/order/client/DownstreamClientsTest.java
  - order-service/src/test/java/com/orderflow/order/CorrelationIdPropagationIT.java
  - order-service/src/test/java/com/orderflow/order/observability/CorrelationIdFilterTest.java
  - order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java
  - order-service/src/test/java/com/orderflow/order/order/OrderDecisionServiceTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderServiceTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderShipmentServiceTest.java
  - order-service/src/test/java/com/orderflow/order/saga/OrderSagaServiceTest.java
  - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java
  - order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxWriterTest.java
  - order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java
  - order-service/src/test/java/com/orderflow/order/support/DownstreamStubServer.java
  - order-service/src/test/java/com/orderflow/order/support/OrderSagaQueues.java
  - pom.xml
  - README.md
  - scripts/check-adrs.sh
  - scripts/check-coverage-matrix.sh
  - scripts/check-no-skipped-tests.sh
  - scripts/ci-summary.sh
  - scripts/smoke-correlation-id.sh
findings:
  critical: 0
  warning: 9
  info: 8
  total: 17
status: issues_found
---

# Phase 7: Relatório de Code Review

**Revisado em:** 2026-11-05T00:00:00Z
**Profundidade:** standard
**Arquivos revisados:** 151
**Status:** issues_found

## Resumo

Foram revisados o filtro de Correlation-ID do Gateway e dos cinco serviços, a propagação pelo outbox e pelo atributo SQS, o `RestClient` do order-service, a documentação OpenAPI dos controllers, o pipeline `ci.yml`, os cinco scripts de guarda/smoke e os testes adicionados na fase. O código de produção do Correlation-ID está coerente entre os serviços: os cinco `CorrelationContext`/`CorrelationIdFilter` diferem do order-service só em pacote e comentário (verificado por `diff`). A validação `[A-Za-z0-9-]{1,64}` é aplicada nas duas fronteiras de confiança, o MDC é restaurado no `finally`, e os dois wrappers do Gateway fecham de fato o header duplicado. A linha de acesso usa `getRequestURI()`, então não vaza query string nem `Authorization`.

Não encontrei bloqueadores. Os defeitos reais estão em quatro pontos:

- Os scripts de prova (smoke e guardas) têm uma armadilha de `pipefail` com `grep -q`.
- O pipeline não serializa a sessão do token LocalStack entre refs diferentes e não tem timeout de job.
- O `Location` dos `201` e a documentação dele apontam para um caminho que o Gateway não roteia.
- O parser de comandos aceita um inteiro de 64+ bits truncado como quantidade válida.

Há ainda uma lacuna de observabilidade: o log de falha do relay perde o ID de correlação.

## Warnings

### WR-01: `grep -q` sob `pipefail` pode reprovar o smoke de forma intermitente

**Arquivo:** `scripts/smoke-correlation-id.sh:130`
**Problema:** `wait_for_id_in_logs` roda `docker compose logs --no-color "$svc" 2>&1 | grep -qF "[${cid}]"` com `set -euo pipefail` (linha 22). O `grep -q` sai no primeiro acerto. Se o `docker compose logs` ainda estiver escrevendo (log grande, que é o caso depois de uma saga completa), ele recebe SIGPIPE e termina com 141. Com `pipefail`, o pipeline inteiro devolve 141, o `if` conta como "não achou" e o laço de 15 s repete. O ID está no log, mas o script pode falhar com `Correlation-ID ... ausente nos logs de <svc>`. A falha depende do tamanho do log e do tempo, então some ao rodar de novo, que é o pior caso para uma prova de entrega. O mesmo padrão `printf '%s\n' "$content" | grep -q...` existe em `scripts/check-adrs.sh` (linhas 59-87). Lá o risco é baixo porque os ADRs têm cerca de 5 KB, menos que o buffer do pipe.
**Correção:**
```bash
wait_for_id_in_logs() {
    local svc="$1" cid="$2" logs
    local wait_start=$SECONDS elapsed=0
    while [ "$elapsed" -le 15 ]; do
        logs=$(docker compose logs --no-color "$svc" 2>&1 || true)
        if grep -qF -- "[${cid}]" <<<"$logs"; then
            return 0
        fi
        sleep 1
        elapsed=$((SECONDS - wait_start))
    done
    return 1
}
```

### WR-02: A `concurrency` do CI é por ref, mas o token do LocalStack é global

**Arquivo:** `.github/workflows/ci.yml:29-31, 84-93, 122-127`
**Problema:** O comentário e a D-101 justificam `max-parallel: 1` e o `cancel-in-progress` pelo fato de a exclusividade de sessão do token Hobby não estar confirmada. Mas `group: ${{ github.workflow }}-${{ github.ref }}` só serializa runs da mesma ref. Um push em `main` e outro em uma branch de feature, ou dois pushes em branches diferentes, rodam `com-localstack` e `e2e` ao mesmo tempo com o mesmo `LOCALSTACK_AUTH_TOKEN`. Isso reproduz a disputa de sessão que o desenho quer evitar. O `cancel-in-progress` também derruba o run de `main` quando chega outro push nela, e um run cancelado no meio de um teste pode deixar a sessão do LocalStack ocupada até expirar no servidor.
**Correção:** Adicionar um grupo global só para os jobs que usam LocalStack, sem cancelar:
```yaml
  com-localstack:
    concurrency:
      group: localstack-session
      cancel-in-progress: false
  e2e:
    concurrency:
      group: localstack-session
      cancel-in-progress: false
```
Outra opção é parar de cancelar runs de `main` (`cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}`).

### WR-03: Nenhum job do pipeline tem `timeout-minutes`

**Arquivo:** `.github/workflows/ci.yml:38, 56, 84, 122`
**Problema:** O padrão do GitHub Actions é 360 minutos por job. Os jobs rodam Testcontainers e LocalStack, e um `docker pull` ou healthcheck travado prende o runner por 6 horas. Em `com-localstack` a matriz roda com `max-parallel: 1`, então um job preso bloqueia os outros dois módulos e o `e2e`, que depende deles.
**Correção:** Definir um teto por job, por exemplo `timeout-minutes: 10` em `guardas`, `20` em `sem-localstack` e `30` em `com-localstack` e `e2e`.

### WR-04: `Location` do `201` (e a documentação dele) aponta para um caminho que o Gateway não roteia

**Arquivo:** `order-service/.../OrderController.java:81`, `catalog-service/.../ProductController.java:72`, `auth-service/.../CompanyController.java:63`; `docs/API.md:289, 390, 869`
**Problema:** Os controllers devolvem `Location: /orders/{id}`, `/products/{id}` e `/companies/{id}`. O contrato público do projeto é o Gateway, que expõe `/api/orders/...` e usa `StripPrefix=1`. Um cliente que siga o `Location` recebido pelo Gateway chega em `/orders/{id}` na porta 8080, que não casa nenhuma rota e dá 404. O `@ApiResponse` da fase (`OrderController`, resposta 201) agora publica "o header Location aponta para GET /orders/{orderId}", o que deixa o erro como contrato documentado. O OpenAPI declara `servers: /api`, então o Swagger UI do Gateway mostra o caminho certo e o `Location` real o contradiz.
**Correção:** Escolher um dos dois caminhos e registrar a escolha:
- Construir o `Location` com o prefixo público: `URI.create("/api/orders/" + id)`, lendo o prefixo da mesma propriedade `orderflow.openapi.server-url`.
- Reescrever o header no Gateway com o filtro `RewriteLocationResponseHeader`, ou, no mínimo, corrigir `docs/API.md` e o texto do `@ApiResponse` para dizer que o `Location` é relativo ao serviço, sem o prefixo `/api`.

### WR-05: `SagaCommandParser` aceita inteiro de 64+ bits truncado como quantidade válida

**Arquivo:** `inventory-service/.../saga/messaging/SagaCommandParser.java:173-183`
**Problema:** `requireQuantity` checa `isIntegralNumber()` e depois `asLong()`. Para um `BigIntegerNode` (literal maior que `Long.MAX_VALUE`), `asLong()` devolve `BigInteger.longValue()`, que descarta os bits altos. Exemplo: `18446744073709551621` (2^64 + 5) vira `5`, passa pela faixa `1..1_000_000` e o item é reservado com quantidade 5. O parser existe justamente para validar entrada não confiável (ASVS V5, comentário da classe), e o limite superior declarado é contornável.
**Correção:**
```java
if (quantityNode == null || quantityNode.isNull() || !quantityNode.canConvertToLong()) {
    throw new InvalidSagaMessageException("Campo quantity ausente ou nao e um numero inteiro");
}
```
`canConvertToLong()` é falso para valores fora do intervalo de `long`. Vale o mesmo teste em qualquer parser irmão que use `asLong()` depois de `isIntegralNumber()`, como o `SagaEventParser` do order-service.

### WR-06: O log de falha do relay perde o Correlation-ID

**Arquivo:** `order-service/.../saga/outbox/OutboxRelay.java:67-85`; `inventory-service/.../saga/outbox/OutboxRelay.java:84-90`
**Problema:** O `try (var scope = CorrelationContext.open(...))` fica dentro do `try` externo, e o `catch (RuntimeException e)` fica fora do escopo. O `try-with-resources` fecha o escopo antes do `catch`, então o `log.warn("Falha ao publicar evento outbox ...")` sai sem `[id]`. O log de sucesso tem o ID e o de falha não. Esse é justamente o log que alguém procura ao seguir uma requisição que travou no SQS. A prova por `grep` do ID, que é o objetivo da fase, não mostra a falha do relay.
**Correção:** Abrir o escopo em volta do `try`/`catch` inteiro de cada evento:
```java
for (OutboxEvent event : batch) {
    try (var scope = CorrelationContext.open(event.getCorrelationId())) {
        try {
            String queueName = resolveQueue(event.getEventType());
            // ... send, log.info, markPublished
        } catch (RuntimeException e) {
            log.warn("Falha ao publicar ...", ..., e);
            event.recordFailure(e.getMessage());
        }
    }
}
```

### WR-07: O Gateway não configura timeout de conexão nem de leitura para os upstreams

**Arquivo:** `gateway/src/main/resources/application.yml:9-79`
**Problema:** As dez rotas não definem timeout, e o arquivo não habilita threads virtuais (`spring.threads.virtual.enabled`). O order-service tem `connect-timeout: 2s` e `read-timeout: 3s` para as chamadas de saída (D-41), mas a borda, que atende todos os clientes, não tem teto. Um serviço que aceita a conexão e não responde prende uma thread do Tomcat (200 por padrão) por requisição. Poucas requisições lentas esgotam o pool e derrubam também as rotas saudáveis e o `/actuator/health` usado pelo healthcheck do compose. O ADR 0010 defende "falha fechada", mas a borda não aplica o princípio.
**Correção:** Configurar `spring.cloud.gateway.server.webmvc.http-client.connect-timeout` e `read-timeout` (conferir os nomes exatos na documentação da linha 2025.0.x) e/ou ligar `spring.threads.virtual.enabled: true`, coerente com o argumento de threads virtuais do CLAUDE.md. Acrescentar um teste de `GatewayRoutingIT` com um stub que demora.

### WR-08: A guarda de matriz não distingue teste unitário de teste de integração

**Arquivo:** `scripts/check-coverage-matrix.sh:63-90, 157-164`
**Problema:** O cabeçalho promete "coluna 4 = teste unitário, coluna 5 = teste de integração contra dependência real", mas `check_field` aceita qualquer nome que termine em `Test` ou `IT` em qualquer das colunas. Uma linha que cita o mesmo `*IT` nas duas colunas, ou só `*Test` na coluna de integração, passa como "coberta". O CI avisa com `COVERAGE CHECK OK` sem que a regra tenha um teste unitário ou um de integração de verdade. A checagem de método (`grep -qE "void $method\("`) também casa um método de qualquer classe aninhada ou um nome que só aparece em comentário.
**Correção:** Passar o sufixo esperado a `check_field` e contar só os nomes que o respeitam: `*Test` na coluna 4 e `*IT` na coluna 5 (e `*IT` em `e2e-tests`). Para o método, exigir a anotação `@Test`/`@ParameterizedTest` nas linhas anteriores, ou aceitar a limitação e documentá-la.

### WR-09: Evento sem Correlation-ID gravado é logado com um UUID novo e enviado sem atributo

**Arquivo:** `order-service/.../saga/outbox/OutboxRelay.java:69-72`; `inventory-service/.../saga/outbox/OutboxRelay.java:167-170`
**Problema:** Para linhas legadas com `correlation_id` nulo, `CorrelationContext.open(null)` gera um UUID para o escopo do relay, e o log "Evento outbox publicado" sai com esse ID. Mas o header SQS é montado a partir de `event.getCorrelationId()` (nulo), e `MessageBuilder.setHeader(nome, null)` remove o header: a mensagem vai sem atributo. O consumidor gera outro UUID. O log do produtor mostra um ID que nenhum outro serviço jamais verá, o que sugere uma correlação que não existe. É um caso de borda (só linhas anteriores à migração), mas o efeito é um ID inventado em log de "prova".
**Correção:** Usar o ID do escopo nas duas pontas, `.setHeader(SQS_ATTRIBUTE, CorrelationContext.current())`, ou, para linhas sem ID, não abrir escopo novo e logar o `eventId`. Em qualquer caso, registrar no código por que um ID gerado no relay é aceitável.

## Info

### IN-01: O comentário do `logging.pattern.correlation` contradiz o padrão configurado

**Arquivo:** `order-service/src/main/resources/application.yml:6` (e nos outros quatro serviços); `gateway/src/main/resources/application.yml:81-85`
**Problema:** `order-service`, `auth-service`, `catalog-service`, `inventory-service` e `notification-service` dizem "O traço entra quando a thread não tem ID". O Gateway diz "e nada quando não está". O padrão é `"[%X{correlationId:-}] "`, e `:-` sem valor padrão resolve para string vazia, então a thread sem ID imprime `[] ` (colchetes vazios). Nenhum dos dois comentários está certo.
**Correção:** Corrigir os comentários para "imprime `[]` quando não há ID" ou usar `"[%X{correlationId:--}] "` se o traço for o desejado.

### IN-02: O token do LocalStack entra no ambiente de todos os passos do job

**Arquivo:** `.github/workflows/ci.yml:92-93, 126-127`
**Problema:** `LOCALSTACK_AUTH_TOKEN` é definido em `env:` no nível do job, então todos os passos o enxergam, inclusive os de `actions/checkout`, `setup-java` e `upload-artifact`. O cabeçalho do arquivo diz que o secret entra "apenas por `env:` dos jobs com LocalStack", o que é verdade, mas só os passos `./mvnw` precisam dele. As ações também usam tags de versão (`@v7`, `@v6`) em vez de SHA de commit, e eu não consegui confirmar que essas tags existem nesta data. Uma tag móvel de ação comprometida teria acesso ao secret.
**Correção:** Mover o `env:` para os passos `./mvnw ... verify` e fixar as ações por SHA (`uses: actions/checkout@<sha> # v7`). Conferir no marketplace que as versões citadas existem.

### IN-03: O exemplo do OpenAPI e o smoke publicam a senha padrão do vendedor

**Arquivo:** `auth-service/src/main/java/com/orderflow/auth/auth/dto/LoginRequest.java:15`; `scripts/smoke-correlation-id.sh:27-28`
**Problema:** O `@Schema(example = "ChangeMe!123")` coloca a senha do `SELLER_ADMIN` semeado no spec público, que não exige token (D-91). O texto diz "usuário de demonstração publicado no README", então é uma decisão consciente para o avaliador. Mesmo assim, quem subir a stack fora do ambiente local sem trocar a senha semeada entrega o administrador por um campo de exemplo do Swagger.
**Correção:** Se a conveniência for mantida, avisar no README e no `description` do schema que a senha semeada deve ser trocada fora do ambiente de demonstração.

### IN-04: `check-no-skipped-tests.sh` tem lacunas e os ITs usam espera fixa para provar "nada acontece"

**Arquivo:** `scripts/check-no-skipped-tests.sh:16`; `order-service/src/test/java/com/orderflow/order/SagaTimeoutIT.java:157,178`; `inventory-service/src/test/java/com/orderflow/inventory/ShipStockConsumptionIT.java:242,317`
**Problema:** O padrão não cobre `assumingThat` (JUnit) nem `@Tag`/`-DskipITs`/`<skipTests>` em `pom.xml`, que também silenciam testes. As asserções negativas de `Thread.sleep(3000)` são aceitáveis para "nenhum efeito acontece", mas deixam o CI 3 s mais lento por caso e dependem do relógio do runner.
**Correção:** Incluir `assumingThat` e uma busca por `skipTests|skipITs|<skip>` nos `pom.xml`; trocar as esperas por `Awaitility.during(...)`, que torna a janela explícita.

### IN-05: A checagem de decisões do ADR gera falsos positivos

**Arquivo:** `scripts/check-adrs.sh:107`
**Problema:** `grep -oE 'D-[0-9]+'` também casa o final de palavras como `ID-12` ou `AD-1`, e a guarda passa a exigir que essa "decisão" exista em algum `CONTEXT.md`. Não encontrei ocorrência hoje, mas basta citar `ID-7` num ADR para o job `guardas` falhar.
**Correção:** Ancorar na fronteira de palavra: `grep -oE '\bD-[0-9]+\b'`.

### IN-06: Falha do `curl` dentro do smoke encerra o script sem a mensagem `SMOKE FALHOU`

**Arquivo:** `scripts/smoke-correlation-id.sh:44-46, 140-143`
**Problema:** `exec_gateway` termina em `| strip_cr`. Com `pipefail`, uma falha do `docker compose exec` (contêiner parado, `curl` ausente) faz a atribuição `X=$(exec_gateway ...)` retornar não zero, e o `set -e` sai em silêncio. O cabeçalho promete que a falha sempre termina com `SMOKE FALHOU: ...`.
**Correção:** `trap 'echo "SMOKE FALHOU: erro na linha $LINENO" >&2' ERR`, ou envolver as chamadas críticas com `|| fail "..."`.

### IN-07: Mensagem enganosa em `SagaCommandParser.parse(null)`

**Arquivo:** `inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/SagaCommandParser.java:58-62`
**Problema:** Um corpo nulo cai na mesma exceção do corpo grande demais e a mensagem diz "excede o tamanho máximo permitido". Quem investiga o WARN procura um payload grande.
**Correção:** Separar os dois casos: `rawPayload == null` → "Corpo da mensagem ausente".

### IN-08: A resposta 200 das listagens não declara schema no OpenAPI

**Arquivo:** `order-service/src/main/java/com/orderflow/order/order/OrderController.java` (método `list`, `@ApiResponse` 200); `catalog-service/.../ProductController.java:140-155`
**Problema:** A listagem de pedidos declara `200 "Página de resumos de pedido"` sem `content`, e a de produtos nem declara o 200. O schema depende da inferência de `Page<...>` pelo springdoc. O `OpenApiDocsIT` do order-service confere as respostas de erro e o enum de status, mas nenhum teste confere que o schema do 200 das listagens contém `content[]`, `totalElements` etc., ou seja, o que o avaliador usa no "Try it out".
**Correção:** Declarar o `content` do 200 com o schema de página, ou acrescentar uma asserção no `OpenApiDocsIT` sobre o schema de `GET /orders` e `GET /products`.

---

_Revisado em: 2026-11-05T00:00:00Z_
_Revisor: Claude (gsd-code-reviewer)_
_Profundidade: standard_
