---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "08"
subsystem: api-docs
tags: [openapi, springdoc, order-service, inventory-service, error-response, order-status]

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: padrão ErrorResponse/servers/OpenApiDocsIT de contrato (07-03) e do notification (07-07)
provides:
  - Spec OpenAPI do order-service com o enum real de OrderStatus, as 7 operações do pedido, erros reais e server /api
  - Spec OpenAPI do inventory-service com as 4 operações de estoque, erros reais e server /api
  - OpenApiDocsIT de contrato inteiro nos dois serviços (critério 1 da fase fechado para order e inventory)
affects: [07-10, 07-11]

actuals:
  tokens: 60000
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "@Schema(implementation = OrderStatus.class) em campo String para o spec trazer o enum sem mudar o DTO"
    - ErrorResponse documental por serviço, com os campos extras opcionais que o handler real devolve
    - OpenApiDocsIT percorre paths, exige summary, tag, 401 e $ref para ErrorResponse em todo erro 4xx/5xx

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/config/ErrorResponse.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/ErrorResponse.java
  modified:
    - order-service/src/main/java/com/orderflow/order/config/OpenApiConfig.java
    - order-service/src/main/java/com/orderflow/order/order/OrderController.java
    - order-service/src/main/java/com/orderflow/order/order/OrderDecisionController.java
    - order-service/src/main/java/com/orderflow/order/order/OrderShipmentController.java
    - order-service/src/main/java/com/orderflow/order/order/dto/CreateOrderRequest.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemRequest.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderResponse.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemResponse.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderSummaryResponse.java
    - order-service/src/main/java/com/orderflow/order/order/dto/ApproveOrderRequest.java
    - order-service/src/main/java/com/orderflow/order/order/dto/RejectOrderRequest.java
    - order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/OpenApiConfig.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/SetStockRequest.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/ReserveStockRequest.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockResponse.java
    - inventory-service/src/test/java/com/orderflow/inventory/OpenApiDocsIT.java

key-decisions:
  - "ORDER_STATUS_IN_SPEC=implementation-enum — @Schema(implementation = OrderStatus.class) nos campos status de OrderResponse e OrderSummaryResponse e no parâmetro ?status= de GET /orders; o teste compara com OrderStatus.values() na mesma ordem, então um status novo sem documentação quebra o build"
  - "ERROR_SCHEMA_EXTRAS=order: productIds (List<UUID>, só em invalid_order_items); inventory: available e requested (Integer, só em insufficient_stock); fields (Map) nos dois"
  - "Tags Pedidos, Decisão do vendedor, Expedição e entrega, Estoque"
  - "GET /inventory/{productId} e DELETE de reserva não documentam 400 de UUID inválido: o inventory não tem handler de MethodArgumentTypeMismatch, e a documentação só descreve o que o serviço devolve de fato"

requirements-completed: [QUAL-01]

coverage:
  - id: D1
    description: OrderResponse.status e OrderSummaryResponse.status listam exatamente OrderStatus.values() na mesma ordem
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#orderStatusEnumInBothResponsesMatchesOrderStatusValues
        status: pass
    human_judgment: false
  - id: D2
    description: As 7 operações do pedido têm summary, tag, 401 e erros reais apontando para ErrorResponse; ErrorResponse declara productIds
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#specHasExactlySevenOperationsEachWithSummaryTagAndErrors
        status: pass
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#createListAndGetOperationsDocumentRealErrors
        status: pass
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#decisionAndShipmentOperationsDocumentRealErrors
        status: pass
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#errorResponseSchemaDeclaresErrorMessageFieldsAndProductIds
        status: pass
    human_judgment: false
  - id: D3
    description: As 4 operações de estoque têm contratos e erros reais; StockResponse traz exemplo em todos os campos; ErrorResponse declara available e requested
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: inventory-service/src/test/java/com/orderflow/inventory/OpenApiDocsIT.java#specHasExactlyFourOperationsEachWithSummaryTagAndErrors
        status: pass
      - kind: integration
        ref: inventory-service/src/test/java/com/orderflow/inventory/OpenApiDocsIT.java#operationsDocumentRealErrors
        status: pass
      - kind: integration
        ref: inventory-service/src/test/java/com/orderflow/inventory/OpenApiDocsIT.java#stockResponseDocumentsEveryFieldWithExample
        status: pass
    human_judgment: false
  - id: D4
    description: Os dois specs declaram servers[0].url = /api e os caminhos de negócio continuam exigindo token (D-89, D-90, D-91)
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#serverUrlIsApiPrefix
        status: pass
      - kind: integration
        ref: inventory-service/src/test/java/com/orderflow/inventory/OpenApiDocsIT.java#serverUrlIsApiPrefix
        status: pass
      - kind: integration
        ref: order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java#businessEndpointStillRequiresTokenGuardAgainstRegression
        status: pass
    human_judgment: false
  - id: D5
    description: Nenhuma anotação mudou comportamento — a suíte inteira de cada serviço continua verde
    requirement: QUAL-01
    verification:
      - kind: integration
        ref: ./mvnw -B -pl order-service verify (146 ITs) e ./mvnw -B -pl inventory-service verify (70 testes, 52 unitários)
        status: pass
    human_judgment: false

duration: 70min
completed: 2026-10-03
status: complete
plan_head_before: 3d6e45c92b14b94b94bb4470390d09ed86215a2e
plan_head_after: 0fa9215
---

# Phase 07 Plan 08: OpenAPI do order-service e do inventory-service Summary

**Os dois serviços da saga passam a publicar spec OpenAPI no nível "contratos + erros": o enum real de OrderStatus (9 valores) nos dois DTOs de pedido, as 7 operações do pedido e as 4 do estoque com papel exigido e erros reais apontando para ErrorResponse, e server /api.**

## Performance

- **Duration:** 70 min
- **Tasks:** 3
- **Files modified:** 20 (2 criados, 18 alterados)

## Accomplishments

- `ErrorResponse` documental em cada serviço (order com `productIds`; inventory com `available` e `requested`), sem participar do runtime: os `GlobalExceptionHandler` continuam devolvendo `Map`.
- `OpenApiConfig` dos dois com `servers` lendo `orderflow.openapi.server-url` (default `/api`).
- `OrderController`, `OrderDecisionController` e `OrderShipmentController` com 7 `@Operation`; `InventoryController` com 4. Cada uma cita o papel, a regra de negócio relevante (aprovação automática por crédito e entrada em RESERVING; approve/reject só em PENDING_APPROVAL; ship só de CONFIRMED e deliver só de SHIPPED; reserva idempotente por `reservationId`; liberação idempotente) e as respostas de erro reais.
- `OrderResponse.status` e `OrderSummaryResponse.status` descrevem a tabela de transições e, via `implementation = OrderStatus.class`, expõem os 9 valores no spec; o parâmetro `?status=` de `GET /orders` também.
- `OpenApiDocsIT` do order (9 casos, 5 novos além do server) e do inventory (9 casos) travam o contrato inteiro; um status novo no enum sem documentação, uma operação a mais ou a menos, ou um erro sem `$ref` para `ErrorResponse` quebram o build.

## Task Commits

1. **Task 1 (tracer): spec do order-service com enum de status, erros reais e server /api** - `2e6242a` (feat)
2. **Task 2: decisão do vendedor e expedição/entrega documentadas, 7 operações travadas** - `65be29a` (feat)
3. **Task 3: spec do inventory-service com 4 operações, erros reais e server /api** - `0fa9215` (feat)

## Classes de teste novas ou ampliadas (insumo da matriz de 07-10)

| Classe | Casos novos |
|---|---|
| `order-service/.../OpenApiDocsIT` | 7 (de 11 no total: server, enum, erros de criar/consultar/listar, ErrorResponse, exemplos de CreateOrderRequest, 7 operações, decisão e expedição) |
| `inventory-service/.../OpenApiDocsIT` | 5 (de 9 no total: server, 4 operações, erros por operação, ErrorResponse, exemplos de StockResponse) |

Resultado: `./mvnw -B -pl order-service verify` BUILD SUCCESS (146 ITs); `./mvnw -B -pl inventory-service verify` BUILD SUCCESS (52 unitários, 70 testes no total). Os dois sobem Postgres e LocalStack reais por Testcontainers; o token do LocalStack foi resolvido pelo suporte de teste a partir do `.env` e não foi lido nem impresso.

## Deviations from Plan

None - plan executed exactly as written. Duas observações de escopo, sem alteração de comportamento:

- O inventory não documenta `400 invalid_parameter` em `GET /inventory/{productId}` nem no `DELETE` de reserva, porque o serviço não tem handler de `MethodArgumentTypeMismatchException`; a documentação descreve só o que o serviço devolve hoje. Adicionar esse handler seria mudança de comportamento, fora do plano (proibição de transparência).
- A tag do `POST /orders/{orderId}/approve` aceita corpo opcional; o spec reflete `@RequestBody(required = false)`.

### TDD sequencing

- Task 1: RED observado antes da implementação (5 falhas em `OpenApiDocsIT`), depois GREEN (9 casos).
- Task 2: os testes de contrato das 7 operações foram escritos antes dos controllers, mas a execução RED separada não foi feita; as anotações foram escritas em seguida e o `verify` completo ficou verde.
- Task 3: mesma situação da Task 2 — testes escritos primeiro, sem execução RED isolada, depois implementação e `verify` completo verde.

## Issues Encountered

- O shell não tinha `python`, e heredocs/`sed` do Bash estragaram escapes de regex; as edições em lote foram feitas com scripts Node escritos pela ferramenta Write. Os arquivos do inventory estavam com CRLF no working copy (o índice guarda LF); os scripts normalizaram para LF antes de substituir.
- `JAVA_HOME` apontado para `C:\Program Files\Java\jdk-21.0.10`.

## Threat Flags

None. A única superfície nova é o campo `servers` do spec, já aceita em D-88/D-90. `permitAll` continua com contagem 1 nos dois `SecurityConfig`, e nenhum deles está em `files_modified`.

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: order-service/src/main/java/com/orderflow/order/config/ErrorResponse.java
- FOUND: inventory-service/src/main/java/com/orderflow/inventory/config/ErrorResponse.java
- FOUND: 2e6242a
- FOUND: 65be29a
- FOUND: 0fa9215
