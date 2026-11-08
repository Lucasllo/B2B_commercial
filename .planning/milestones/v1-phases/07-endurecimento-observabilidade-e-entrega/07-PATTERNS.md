# Phase 7: Endurecimento, Observabilidade e Entrega - Pattern Map

**Mapped:** 2026-10-01
**Files analyzed:** 27 (grupos de arquivos novos/modificados)
**Analogs found:** 23 / 27 (todos os analogos verificados como git-tracked)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `<svc>/observability/CorrelationContext.java` (5x, novo) | utility | transform | RESEARCH Pattern 3 (sem analogo no codigo) | no-analog |
| `<svc>/observability/CorrelationIdFilter.java` (5x, novo) | middleware | request-response | RESEARCH Pattern 2 (sem filtro servlet no repo) | no-analog |
| `gateway/.../CorrelationIdFilter.java` (novo) | middleware | request-response | RESEARCH Pattern 2 | no-analog |
| `order-service/.../saga/outbox/OutboxWriter.java` (mod) | service | CRUD/event-driven | si mesmo; espelho `inventory-service/.../saga/outbox/OutboxWriter.java` | exact |
| `order-service/.../saga/outbox/OutboxEvent.java` (mod, +`correlationId`) | model | CRUD | si mesmo; espelho em inventory | exact |
| `order-service/.../saga/outbox/OutboxRelay.java` (mod) | service | event-driven | si mesmo; espelho em inventory | exact |
| `order-service/.../db/migration/V4__correlation_id.sql` (novo) | migration | batch | `order-service/.../db/migration/V3__order_fulfillment.sql` | role-match |
| `inventory-service/.../db/migration/V5__outbox_correlation_id.sql` (novo) | migration | batch | `inventory-service/.../db/migration/V4__stock_reservation_shipped.sql` | role-match |
| `ReservationCommandListener` / `ReservationResultListener` / `NotificationEventListener` (mod) | listener | event-driven | si mesmos (padrao identico entre os 3) | exact |
| `order-service/.../config/ClientConfig.java` (mod, interceptor) | config | request-response | si mesmo (`buildRestClient`) | exact |
| `order-service/.../saga/OrderSagaService.java` (`expireReservation`, mod) | service | event-driven | si mesmo | exact |
| `*/config/OpenApiConfig.java` (5x, mod: `servers`) | config | request-response | `order-service/.../config/OpenApiConfig.java` | exact |
| `*/config/SecurityConfig.java` (5x) | config | request-response | `order-service/.../config/SecurityConfig.java:85-86` (ja tem permitAll; so manter) | exact |
| `*/OpenApiDocsIT.java` (5x, mod) | test | request-response | `order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java` | exact |
| `*Controller` / DTOs (anotacoes `@Tag`, `@Operation`, `@Schema`) | controller | request-response | sem analogo (nenhuma anotacao existe) | no-analog |
| `<svc>/ErrorResponse.java` (novo, record doc-only) | model | transform | sem analogo | no-analog |
| `gateway/src/main/resources/application.yml` (mod) | config | request-response | si mesmo (rotas) | exact |
| `gateway/pom.xml` (mod: springdoc, test, failsafe) | config | n/a | `order-service/pom.xml` (failsafe) | role-match |
| `gateway/src/test/.../CorrelationIdFilterTest.java` (novo) | test | request-response | `order-service/.../saga/outbox/OutboxRelayTest.java` (estilo unit) | partial |
| `gateway/src/test/.../GatewayRoutingIT.java` + stub JDK (novo) | test | request-response | `e2e-tests/.../support/DownstreamStubServer.java` | role-match |
| `inventory-service/.../InventoryService.java` (WR-02) | service | CRUD | si mesmo (linhas 497-562) | exact |
| `inventory-service/.../SagaCommandParser` + listener (WR-01) | service/listener | event-driven | `ReservationCommandListener` | exact |
| `notification-service/.../NotificationService.record` (WR-03) | service | event-driven | si mesmo (`sanitizeForLog`, linha ~270) | exact |
| `OutboxRelayTest` order+inventory (mod, captor `Message<String>`) | test | event-driven | `order-service/.../OutboxRelayTest.java` | exact |
| `scripts/smoke-correlation-id.sh` (novo) | script | request-response | `scripts/smoke-order-lifecycle.sh` | exact |
| `.github/workflows/ci.yml` (novo) | config | batch | RESEARCH "Workflow CI" (sem analogo) | no-analog |
| `docs/adr/*.md`, `07-COVERAGE.md`, README, `estudos/27-29` | doc | n/a | sem analogo (COVERAGE.md antigos tem 1 linha) | no-analog |

## Pattern Assignments

### `OutboxWriter.java` + `OutboxEvent.java` (order e inventory) - correlation_id (D-94)

**Analog:** `order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxWriter.java` (inventory e duplicata, D-62)

**Core pattern** (OutboxWriter linhas 31-43): `@Transactional(propagation = MANDATORY)`; hoje termina com
```java
OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
outboxEventRepository.save(OutboxEvent.pending(eventId, eventType, aggregateId, json, now));
```
Mudar para `OutboxEvent.pending(..., now, CorrelationContext.current())`.

**Entidade** (OutboxEvent linhas 36-67): colunas por `@Column(name = "...")`, factory estatica `pending(...)` com Lombok `@Getter`/`@NoArgsConstructor(PROTECTED)`. Adicionar:
```java
@Column(name = "correlation_id", length = 64)   // nullable (linhas legadas)
private String correlationId;
```
e um parametro novo em `pending(...)`. `ddl-auto: validate` exige a coluna na migracao.

### `OutboxRelay.java` (order e inventory) (D-94)

**Analog:** si mesmo, `order-service/.../saga/outbox/OutboxRelay.java` linhas 55-74.

**Core pattern a alterar** (linhas 59-71):
```java
for (OutboxEvent event : batch) {
    try {
        String queueName = resolveQueue(event.getEventType());
        sqsOperations.send(queueName, event.getPayload());
        event.markPublished(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        published++;
    } catch (RuntimeException e) {
        log.warn("Falha ao publicar evento outbox eventId={} eventType={} na fila '{}' — ...", ..., e);
        event.recordFailure(e.getMessage());
    }
}
```
Embrulhar o corpo do `for` em `try (var ignored = CorrelationContext.open(event.getCorrelationId()))`, trocar `send(queue, payload)` por `send(queue, MessageBuilder.withPayload(payload).setHeader("correlationId", id).build())` e adicionar INFO "Evento outbox publicado eventId eventType fila" (Pitfall 1). Preservar o isolamento de falha por evento (T-05-02) e nunca logar payload (T-05-03). Gate de grep T-05-01: so este arquivo importa `io.awspring.cloud.sqs.operations.`.

### `OutboxRelayTest.java` (order e inventory)

**Analog:** `order-service/src/test/java/com/orderflow/order/saga/outbox/OutboxRelayTest.java`

Linhas 48-51 usam `send(eq(QUEUE_NAME), eq(first.getPayload()))`, que quebram com `send(String, Message)`. Trocar por `ArgumentCaptor<Message<String>>` / matcher em `getPayload()`; estrutura `@ExtendWith(MockitoExtension.class)`, `@Mock OutboxEventRepository/SqsOperations`, `newRelay()`, `pendingEvent(type)` mantida. Cobrir o caso `correlationId == null` (header ausente).

### Listeners SQS (3x) - atributo para MDC (D-94)

**Analogs:** `inventory-service/.../saga/messaging/ReservationCommandListener.java` (linhas 46-67), `order-service/.../saga/messaging/ReservationResultListener.java` (linhas 39-51), `notification-service/.../history/messaging/NotificationEventListener.java` (linhas 38-45).

**Padrao atual (identico nos 3):**
```java
@SqsListener("${orderflow.messaging.order-events-queue}")
public void onMessage(String payload) {
    try { ... } catch (InvalidSagaMessageException e) {
        log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
    }
}
```
**Alterar para** `onMessage(String payload, @Header(name = "correlationId", required = false) String correlationId)` com `try (var ignored = CorrelationContext.open(correlationId)) { log.info("Mensagem recebida da fila '{}'", queueName); ... }`. `required = false` e obrigatorio (Anti-Pattern; ITs postam sem atributo). Manter overload `onMessage(String)` para `NotificationEventListenerTest` (Pitfall 5). Importar `org.springframework.messaging.handler.annotation.Header`.

**WR-01 (inventory):** no `ReservationCommandListener` linhas 49-54, o `catch (InvalidSagaMessageException)` descarta `ShipStock` invalido. Inserir antes um `catch (InvalidShipStockException e) { log.error(...); throw e; }` (nova subclasse lancada por `SagaCommandParser.parseShipStock`) para reentrega ate a DLQ. Testes: `SagaCommandParserTest`, novo teste do listener, IT `ShipStockConsumptionIT`.

**WR-03 (notification):** o `log.warn` da linha 43 deve incluir `orderId=` e `eventType=`; extrair `orderId` tolerante em `NotificationService.record` e reaproveitar `sanitizeForLog` (`NotificationService.java:~270`).

### `ClientConfig.java` (order-service) - interceptor HTTP (D-96)

**Analog:** si mesmo, `order-service/src/main/java/com/orderflow/order/config/ClientConfig.java` linhas 42-55.

```java
return RestClient.builder()
        .baseUrl(downstream.baseUrl().toString())
        .requestFactory(requestFactory)
        .build();
```
Adicionar `.requestInterceptor((req, body, exec) -> { String id = CorrelationContext.current(); if (id != null) req.getHeaders().set("X-Correlation-Id", id); return exec.execute(req, body); })` no builder. `buildRestClient` e `public static` usado por `DownstreamClientsTest`, entao o interceptor entra ali para ser testado por socket real.

### `OrderSagaService.expireReservation` + `SagaTimeoutJob` (D-95)

**Analog:** `order-service/.../saga/OrderSagaService.java` (nao lido em detalhe; ler `expireReservation` antes de editar) e teste `order-service/src/test/java/com/orderflow/order/saga/SagaTimeoutJobTest.java`. Abrir `CorrelationContext.open(order.getCorrelationId())` depois de travar o pedido, antes de `orderTimelineEvents.cancelled(order)` e de `outboxWriter.enqueue(ReleaseStock...)`. `/approve`, `/ship`, `/deliver` usam o ID da requisicao (recomendacao da pesquisa; sem codigo extra).

### Migracoes Flyway (D-94, D-95)

**Analog:** `order-service/src/main/resources/db/migration/V3__order_fulfillment.sql` (estilo: comentario de cabecalho em portugues citando fase/decisao e IDs, `ALTER TABLE ... ADD COLUMN`).
```sql
ALTER TABLE orders ADD COLUMN carrier VARCHAR(64);
```
Novos: `order-service` `V4__correlation_id.sql` (`ALTER TABLE orders ADD COLUMN correlation_id VARCHAR(64); ALTER TABLE outbox_event ADD COLUMN correlation_id VARCHAR(64);`) e `inventory-service` `V5__outbox_correlation_id.sql` (so `outbox_event`). Nullable, sem default, sem backfill (volume `postgres-data` persiste). Atualizar a entidade `Order` com a coluna (validate).

### `OpenApiConfig.java` (5x) - `servers` (D-88/D-90)

**Analog:** `order-service/src/main/java/com/orderflow/order/config/OpenApiConfig.java` (linhas 22-33, ja declara `bearerAuth`):
```java
return new OpenAPI()
        .info(new Info().title(title).description(description).version("1.0.0"))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
```
Acrescentar `.servers(List.of(new Server().url(serverUrl)))` com `@Value("${orderflow.openapi.server-url:/api}")`. O `@Value("${orderflow.openapi.title}")` ja vem do `application.yml` de cada servico.

### `OpenApiDocsIT.java` (5x)

**Analog:** `order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java` (extends `AbstractIntegrationTest`, MockMvc, `jsonPath`). Estender com asserts de `servers`, schema `ErrorResponse`, `OrderResponse.properties.status.enum == OrderStatus.values()`, e a guarda de regressao `get("/orders")` -> 401 (linhas 46-51). Nota: o IT de gateway nao tem `OpenApiDocsIT`; o analogo vale para criar guardas equivalentes. `JwksController` (auth) recebe `@Hidden`.

### `SecurityConfig.java` (5x) - D-91

**Analog:** `order-service/.../config/SecurityConfig.java` linhas 85-87. Ja contem:
```java
.requestMatchers("/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**",
        "/v3/api-docs", "/v3/api-docs/**").permitAll()
.anyRequest().authenticated())
```
Nada a criar, apenas nao alterar e manter o teste-guarda. Gateway nao tem Spring Security (D-05).

### `gateway/src/main/resources/application.yml` (rotas de docs, URIs parametrizadas)

**Analog:** si mesmo (linhas 13-43): cada rota `id`/`uri`/`predicates: Path=`/`filters: StripPrefix=1`. Alterar `uri: http://auth-service:8081` para `${orderflow.gateway.upstream.auth:http://auth-service:8081}` (Pitfall 4: o IT nao pode sobrescrever `routes[N].uri`). Adicionar 5 rotas `Path=/docs/<svc>/v3/api-docs` + `SetPath=/v3/api-docs` e `springdoc.swagger-ui.urls` (RESEARCH Pattern 1). Predicados so com `Path` (issue springdoc #2506).

### `gateway/pom.xml`

**Analog:** o proprio (linhas 18-44) para estrutura; para failsafe/test copiar a declaracao de `maven-failsafe-plugin` de `order-service/pom.xml` (o `pluginManagement` raiz so define a versao 3.2.5). Adicionar `springdoc-openapi-starter-webmvc-ui` e `spring-boot-starter-test` (test).

### Gateway `CorrelationIdFilter` + testes (D-92, D-106)

Sem analogo de filtro servlet no repo: usar RESEARCH Pattern 2 (case-insensitive `TreeSet` em `getHeaderNames`, obrigatorio; nao ecoar header nos servicos). Estilo de teste unitario: `OutboxRelayTest` (JUnit5 + AssertJ, classe de pacote privado, Javadoc em portugues explicando o que e provado). Para o IT: stub JDK com `com.sun.net.httpserver.HttpServer`, copiar de `e2e-tests/.../support/DownstreamStubServer.java` linhas 52-63:
```java
this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
httpServer.createContext("/products", this::handleProducts);
httpServer.start();
```
O IT define `orderflow.gateway.upstream.*` via `@DynamicPropertySource`.

### `InventoryService` WR-02 (@Recover)

**Analog:** si mesmo, `inventory-service/.../stock/InventoryService.java`: `releaseAll` (`@Retryable` linha 407, `recoverReleaseAll` 449-452, `recoverReleaseAllInconsistentBook` 465-468) e `shipAll` (`@Retryable` 497, `recoverShipAll` 535-538, `recoverShipAllInconsistentBook` 559-562, hoje nunca invocado). Fix: `@Retryable(..., recover = "recoverShipAll")` / `recover = "recoverReleaseAll"` e renomear para que cada nome tenha as duas sobrecargas (`DataAccessException` e `IllegalStateException`). Teste: IT com `OutputCaptureExtension` provando o WARN do recover correto.

### `scripts/smoke-correlation-id.sh`

**Analog:** `scripts/smoke-order-lifecycle.sh` (linhas 17-69): `set -euo pipefail`, `export MSYS_NO_PATHCONV=1`, `cd "$(dirname "${BASH_SOURCE[0]}")/.."`, `fail()` imprimindo `SMOKE FALHOU: ...`, `strip_cr`, `exec_gateway` (`docker compose exec -T gateway`), `do_request` preenchendo `REQ_STATUS`/`REQ_BODY`, `extract_string`/`extract_first_string`/`extract_raw`. Ultima linha `SMOKE OK ...`. Nunca imprimir token/segredo. Reaproveitar login/criacao de empresa/produto/estoque de `smoke-order-saga.sh`/`smoke-order-lifecycle.sh` e adicionar `docker compose logs --no-color "$svc" | grep -F "[${CID}]"` para gateway, order, inventory, notification.

## Shared Patterns

### Correlation-ID (CorrelationContext + filtro + try/finally)
**Source:** RESEARCH Pattern 3 (nao existe no codigo). **Apply to:** os 5 servicos + gateway (copia duplicada, sem modulo `common`, decisao D-62/ADR 0011). Sempre `try (var ignored = CorrelationContext.open(raw))` que restaura o valor anterior; validar com `[A-Za-z0-9-]{1,64}` (fila e fronteira de confianca); logging `logging.pattern.correlation: "[%X{correlationId:-}] "` em cada `application.yml`; INFO de caminho feliz nos filtros/listeners/relay/saga (Pitfall 1).

### Log sem payload nem token
**Source:** `OutboxRelay.java` Javadoc (T-05-03) e `NotificationService.sanitizeForLog`. **Apply to:** todo novo log.

### Outbox como unico caminho SQS
**Source:** `OutboxRelay.java` Javadoc (T-05-01). **Apply to:** qualquer codigo novo (smoke/teste nao publica direto na fila).

### Teste unitario Mockito
**Source:** `OutboxRelayTest.java` linhas 28-39 (`@ExtendWith(MockitoExtension.class)`, `@Mock`, AssertJ). **Apply to:** testes unitarios novos (CompanyService, TokenService, CompanyGuard, ProductService, Inventory, StockReservation, OutboxWriter, filtro).

### Estilo de comentarios
Javadoc/comentarios em portugues explicando o "porque" e citando decisoes D-xx / ameacas T-xx; identificadores em ingles.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `CorrelationContext` / `CorrelationIdFilter` (6x) | utility/middleware | request-response | Nenhum filtro servlet nem uso de MDC no repo; usar RESEARCH Patterns 2 e 3 |
| Anotacoes `@Tag`/`@Operation`/`@Schema`, `ErrorResponse` | controller/model | request-response | Nenhuma anotacao OpenAPI existe; erros hoje sao `ResponseEntity<Map<String,Object>>` nos `GlobalExceptionHandler` |
| `.github/workflows/ci.yml`, `scripts/ci-summary.sh` | config | batch | `.github/` nao existe; usar skeleton do RESEARCH (runner `ubuntu-24.04`, `max-parallel: 1` para LocalStack, e2e via `install -DskipTests`) |
| `docs/adr/*`, `07-COVERAGE.md`, `scripts/check-adrs.sh`, `estudos/27-29` | doc/script | n/a | COVERAGE.md das fases 1-6 sao notas de uma linha, nao matriz; definir formato (Servico / Regra / Unit / IT / Status) |

## Metadata

**Analog search scope:** `*/src/main`, `*/src/test`, `scripts/`, `e2e-tests/`, `gateway/`, `.planning/phases/*/COVERAGE.md`
**Files scanned:** ~20 lidos, ~70 listados via `git ls-files` (todos os analogos citados estao tracked)
**Pattern extraction date:** 2026-10-01
**Nota:** `OrderSagaService`, `NotificationService`, `SagaCommandParser` e `order-service/pom.xml` nao foram lidos nesta passagem; o executor deve le-los antes de editar.
