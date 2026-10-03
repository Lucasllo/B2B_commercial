---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "07"
subsystem: observability
tags: [correlation-id, mdc, sqs, dynamodb, openapi, localstack, log-injection, wr-03]

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: CorrelationContext/CorrelationIdFilter e o atributo SQS correlationId publicado pelo OutboxRelay (07-02/07-04); padrão ErrorResponse/OpenApiDocsIT (07-03)
provides:
  - Último elo do Correlation-ID (critério 2): notification-service lê o atributo SQS, põe no MDC e loga recebimento e registro com [id]
  - WR-03 fechado: descarte de ORDER_* inválido loga orderId e eventType sanitizados
  - Spec OpenAPI do notification-service com contratos, erros reais e server /api
affects: [07-10, 07-11]

actuals:
  tokens: 30000
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - CorrelationContext e CorrelationIdFilter duplicados por serviço (mesma decisão de 07-02/07-05)
    - Listener SQS com sobrecarga sem atributo delegando com ID nulo, para manter os testes unitários existentes
    - Exceção de domínio carrega contexto já sanitizado para o log, nunca o payload

key-files:
  created:
    - notification-service/src/main/java/com/orderflow/notification/observability/CorrelationContext.java
    - notification-service/src/main/java/com/orderflow/notification/observability/CorrelationIdFilter.java
    - notification-service/src/main/java/com/orderflow/notification/config/ErrorResponse.java
    - notification-service/src/test/java/com/orderflow/notification/observability/CorrelationIdFilterTest.java
    - notification-service/src/test/java/com/orderflow/notification/CorrelationIdConsumptionIT.java
  modified:
    - notification-service/src/main/resources/application.yml
    - notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java
    - notification-service/src/main/java/com/orderflow/notification/history/InvalidNotificationEventException.java
    - notification-service/src/main/java/com/orderflow/notification/config/OpenApiConfig.java
    - notification-service/src/main/java/com/orderflow/notification/history/NotificationController.java
    - notification-service/src/main/java/com/orderflow/notification/history/dto/NotificationResponse.java
    - notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java
    - notification-service/src/test/java/com/orderflow/notification/history/messaging/NotificationEventListenerTest.java
    - notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java

key-decisions:
  - "WR03_ORDER_ID_FALLBACK=? — orderId extraído de forma tolerante: só texto que casa UUID vira valor; ausente ou fora do formato vira ?; eventType passa por sanitizeForLog"
  - "NOTIFICATION_INFO_LOG=Evento registrado eventType={} entityId={} depois de cada save, nos caminhos de produto e de pedido; nunca o payload"
  - "CORRELATION_CODE_PLACEMENT=duplicated-per-service"
  - "Tag OpenAPI 'Histórico de notificações'; ErrorResponse do notification só com error e message (o serviço não tem validation_failed)"

patterns-established:
  - "Escopo de MDC por mensagem SQS: try (var scope = CorrelationContext.open(correlationId)) cobre recebimento, processamento e descarte"

requirements-completed: [QUAL-01, QUAL-02, TEST-01, TEST-02]

coverage:
  - id: D1
    description: Evento ORDER_CREATED e STOCK_ADJUSTED com atributo correlationId é registrado e as linhas de recebimento e registro trazem [id]
    requirement: QUAL-02
    verification:
      - kind: integration
        ref: notification-service/src/test/java/com/orderflow/notification/CorrelationIdConsumptionIT.java#orderCreatedWithCorrelationAttributeLogsReceiptAndRecordingWithThatId
        status: pass
      - kind: integration
        ref: notification-service/src/test/java/com/orderflow/notification/CorrelationIdConsumptionIT.java#stockAdjustedWithCorrelationAttributeLogsRecordingWithThatId
        status: pass
    human_judgment: false
  - id: D2
    description: Sem atributo o evento é registrado com UUID no MDC; atributo inválido nunca chega ao log (T-07-23)
    requirement: QUAL-02
    verification:
      - kind: integration
        ref: notification-service/src/test/java/com/orderflow/notification/CorrelationIdConsumptionIT.java#eventWithoutAttributeIsRecordedAndTheLogLineCarriesAGeneratedUuid
        status: pass
      - kind: integration
        ref: notification-service/src/test/java/com/orderflow/notification/CorrelationIdConsumptionIT.java#invalidAttributeIsRecordedAndNeverReachesTheLog
        status: pass
    human_judgment: false
  - id: D3
    description: Filtro HTTP do notification abre o MDC, restaura ao fim e não ecoa o header (D-93)
    requirement: QUAL-02
    verification:
      - kind: unit
        ref: notification-service/src/test/java/com/orderflow/notification/observability/CorrelationIdFilterTest.java
        status: pass
    human_judgment: false
  - id: D4
    description: Descarte de ORDER_* inválido gera WARN com orderId e eventType sanitizados; orderId cru com CR/LF nunca chega à exceção; nada é salvo
    requirement: QUAL-01
    verification:
      - kind: unit
        ref: notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java#invalidOrderEventWithANonUuidOrderIdNeverCarriesTheRawValue
        status: pass
      - kind: unit
        ref: notification-service/src/test/java/com/orderflow/notification/history/messaging/NotificationEventListenerTest.java#discardedOrderEventIsLoggedWithOrderIdAndEventTypeAndNeverEscapes
        status: pass
    human_judgment: false
  - id: D5
    description: Spec do notification com server /api, summary/tag, erros 400/401/403/404/503 apontando para ErrorResponse e exemplos em NotificationResponse
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java#bothHistoryOperationsDocumentSummaryTagAndRealErrors
        status: pass
      - kind: integration
        ref: notification-service/src/test/java/com/orderflow/notification/OpenApiDocsIT.java#serverUrlIsApiPrefix
        status: pass
    human_judgment: false

duration: 35min
completed: 2026-10-03
status: complete
plan_head_before: df9ccd5d9573e77ba9a6cf2ae2bd80879daf58d4
plan_head_after: 5143cdf5c12474f5ef8dcf2ca4a8b9f56df2852b
---

# Phase 07 Plan 07: Correlation-ID, WR-03 e OpenAPI no notification-service Summary

**O notification-service fecha a cadeia do Correlation-ID (atributo SQS para MDC para as linhas de recebimento e de registro), o descarte de evento ORDER_* inválido passa a logar orderId e eventType sanitizados, e o spec OpenAPI ganha contratos, erros reais e server /api.**

## Performance

- **Duration:** 35 min
- **Tasks:** 3
- **Files modified:** 15 (5 criados, 10 alterados)

## Accomplishments

- `CorrelationContext` e `CorrelationIdFilter` copiados do order-service para `com.orderflow.notification.observability`; `logging.pattern.correlation` no `application.yml`.
- `NotificationEventListener#onMessage(String, @Header correlationId)` abre o escopo de MDC por mensagem; a sobrecarga `onMessage(String)` sem `@SqsListener` delega com `null`, e os testes antigos continuam passando.
- `NotificationService` loga `Evento registrado eventType=... entityId=...` depois de cada `save` (produto e pedido), sem payload.
- `InvalidNotificationEventException` ganha o construtor `(reason, orderId, eventType)` e os getters; o ramo `ORDER_*` relança com contexto, e o WARN do listener mostra `orderId=` e `eventType=` (`-` quando nulos).
- `OpenApiConfig` com `servers`, `ErrorResponse` documental, `@Tag`/`@Operation`/`@ApiResponses`/`@Parameter` nas duas consultas e `@Schema` com exemplos em `NotificationResponse`. `SecurityConfig` não foi tocado (`permitAll` continua 1).

## Task Commits

1. **Task 1 (tracer): filtro, padrão de log, listener com atributo, INFO de registro e IT com LocalStack** - `c546989` (feat)
2. **Task 2: WR-03, WARN de descarte com orderId e eventType** - `11950e4` (fix)
3. **Task 3: spec OpenAPI com contratos, erros e server /api** - `5143cdf` (feat)

## Classes de teste novas ou ampliadas (insumo da matriz de 07-10)

| Classe | Casos novos |
|---|---|
| `observability/CorrelationIdFilterTest` | 4 |
| `CorrelationIdConsumptionIT` (LocalStack real) | 4 |
| `history/NotificationServiceTest` | 4 (WR-03) |
| `history/messaging/NotificationEventListenerTest` | 2 (de 4 no total) |
| `OpenApiDocsIT` | 4 (de 8 no total) |

Resultado de `./mvnw -B -pl notification-service verify`: BUILD SUCCESS. 61 unitários e 40 ITs, todos verdes contra LocalStack real via Testcontainers. O token do LocalStack foi resolvido pelo próprio `LocalStackTestSupport` a partir do `.env`; o valor não foi lido nem impresso.

## Deviations from Plan

None - plan executed exactly as written.

### TDD sequencing

- Task 1: os testes do filtro são cópia de código já provado em 07-02, então nasceram verdes; o `CorrelationIdConsumptionIT` foi escrito junto da implementação e não houve execução RED separada dele.
- Task 2 e Task 3: RED observado antes da implementação (Task 2: 24 erros de compilação e 1 falha; Task 3: 4 falhas em `OpenApiDocsIT`), depois GREEN.

## Issues Encountered

- O shell não tinha `python`, e heredocs do Bash reduziram barras invertidas pela metade; os padrões regex do IT e do teste do listener foram corrigidos com a ferramenta Edit antes do primeiro commit.
- `JAVA_HOME` apontado para `C:\Program Files\Java\jdk-21.0.10`.

## Threat Flags

None. A única superfície nova é o campo `servers` do spec, já aceita em D-88/D-90.

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: notification-service/src/main/java/com/orderflow/notification/observability/CorrelationIdFilter.java
- FOUND: notification-service/src/main/java/com/orderflow/notification/config/ErrorResponse.java
- FOUND: notification-service/src/test/java/com/orderflow/notification/CorrelationIdConsumptionIT.java
- FOUND: c546989
- FOUND: 11950e4
- FOUND: 5143cdf
