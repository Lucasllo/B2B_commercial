# Phase 6: Ciclo de Vida Completo — Expedição, Entrega e Histórico do Pedido - Context

**Gathered:** 2026-09-30
**Status:** Ready for planning

<domain>
## Phase Boundary

Fecha o ciclo do pedido de ponta a ponta: ao chegar em CONFIRMED, o pedido recebe transportadora e código de rastreio de um mock interno do order-service; o vendedor o leva a SHIPPED (o que dispara a baixa física de estoque no inventory-service) e depois a DELIVERED; transições inválidas são rejeitadas; e cada transição persistida do pedido vira um registro na linha do tempo do notification-service (DynamoDB), consultável pelo vendedor e pelo comprador da própria empresa. O fluxo de status é documentado com diagrama e provado igual ao comportamento real da API.

Requisitos: ORD-07, ORD-10.

**Fora desta fase:** cancelamento manual de pedido CONFIRMED/SHIPPED (pelo vendedor ou comprador); estados intermediários de trânsito (AWAITING_PICKUP, IN_TRANSIT — Out of Scope do PROJECT.md); integração real com transportadora; shipping-service dedicado; Correlation-ID, ADRs formais e CI (Fase 7).

</domain>

<decisions>
## Implementation Decisions

### Transportadora simulada (ORD-07)
- **D-70:** Transportadora e código de rastreio são atribuídos **no CONFIRMED, na mesma transação** em que o resultado `StockReserved` leva o pedido de RESERVING a CONFIRMED (`OrderSagaService`). Nunca existe pedido CONFIRMED sem transportadora. Sem I/O de rede, porque o mock é interno (D-41 continua respeitada).
- **D-71:** Mock como **costura de integração explícita**: interface `CarrierGateway` com implementação `SimulatedCarrierGateway` (nomes exatos a critério do planejador). Lista fixa de transportadoras fictícias brasileiras; a escolha é **determinística por pedido** (ex.: derivada do `orderId`), para que testes e reprocessamentos sejam reproduzíveis.
- **D-72:** O mock **nunca falha**. Sem Resilience4j e sem falha simulada nesta fase: não existe chamada externa dentro da transação que justifique circuit breaker.
- **D-73:** Código de rastreio no **padrão Correios / UPU S10**: 2 letras + 9 dígitos + `BR` (ex.: `AB123456789BR`), único por pedido. Colunas novas no pedido (ex.: `carrier`, `tracking_code`) expostas no `OrderResponse`. — **Reversibility:** costly — os campos entram no contrato da API (`ORDER_RESPONSE_CONTRACT`), no smoke e no payload do evento ORDER_CONFIRMED da timeline.

### Expedição, entrega e baixa de estoque
- **D-74:** Dois endpoints de ação, **só SELLER_ADMIN**, sem corpo, devolvendo 200 com o pedido: `POST /orders/{id}/ship` (CONFIRMED → SHIPPED) e `POST /orders/{id}/deliver` (SHIPPED → DELIVERED). Seguem o padrão de `/approve`/`/reject` (Fase 4, `OrderDecisionController`). Transição inválida → **409** com mensagem clara, no mesmo formato de erro `{"error","message","fields"}` (generalizar ou irmanar `OrderNotPendingException`). Pedido inexistente → 404.
- **D-75:** A baixa de `quantity_on_hand` (D-57) é um **comando assíncrono pelo outbox**: `/ship` grava `status = SHIPPED` e um comando (ex.: `ShipStock`, `reservationId = orderId`) no outbox do order-service **na mesma transação**. O inventory-service baixa `quantity_on_hand` e `quantity_reserved` juntos e marca a reserva como expedida no livro `stock_reservations`, com idempotência por `reservation_id` (reentrega não baixa de novo). O pedido **não espera resposta**, porque a reserva já garantiu o estoque. Não há estado intermediário (ex.: SHIPPING). — **Reversibility:** costly — novo comando no contrato order ↔ inventory (`SAGA_MESSAGE_CONTRACT`) e nova semântica no livro de reservas compartilhado pela saga e pela reserva REST.
- **D-76:** Cada transição registra **quando e quem**: colunas `shipped_at`/`shipped_by` e `delivered_at`/`delivered_by` (claim `sub` do JWT), expostas no `OrderResponse` ao lado de `confirmedAt`/`cancelledAt`. Migração Flyway V3 no order-service.
- **D-77:** **CANCELLED continua alcançável só pela saga** (falha de reserva ou timeout, Fase 5). Um pedido CONFIRMED, SHIPPED ou DELIVERED não pode ser cancelado nesta fase: qualquer tentativa de transição para fora do fluxo é 409. SHIPPED e DELIVERED continuam consumindo crédito (D-37).

### Linha do tempo no histórico
- **D-78:** **Toda transição persistida** do pedido gera um evento de timeline: `ORDER_CREATED`, `ORDER_PENDING_APPROVAL`, `ORDER_APPROVED` (automática ou manual, com `decidedBy`), `ORDER_REJECTED` (com motivo), `ORDER_CONFIRMED` (com transportadora e rastreio), `ORDER_CANCELLED` (com `cancellationCode` e motivo), `ORDER_SHIPPED` e `ORDER_DELIVERED`. A entrada em RESERVING fica coberta pelo `ORDER_APPROVED`, sem evento próprio. Cada evento carrega `orderId`, `companyId`, `eventId`, `eventType`, `occurredAt` e os campos do tipo. — **Reversibility:** costly — os tipos e o formato do evento são contrato entre order-service e notification-service.
- **D-79:** Os eventos saem pelo **outbox do order-service** na mesma transação da mudança de status, e o relay passa a rotear `eventType` `ORDER_*` para a `notification-events-queue` existente (Fase 3, desenhada como fan-out). Nenhum envio direto (Key Decision da Fase 5: relay é o único caminho de envio SQS). Sem fila nova.
- **D-80:** **Mesma tabela `notification-history`, PK renomeada para um nome genérico** (ex.: `entityId`/`aggregateId`) no lugar de `productId`. Eventos de produto (`STOCK_ADJUSTED`) e de pedido convivem, e a sort key `eventType#eventId` (D-33) não muda. O init hook `01-create-notification-resources.sh`, o `NotificationRecord`, o repositório e os testes da Fase 3 são ajustados juntos. O estado do LocalStack é recriado na subida, então não há dado real a migrar. Realiza a intenção de D-32. — **Reversibility:** costly — muda o key-schema da tabela e o bean `@DynamoDbBean`; uma nova troca depois exige recriar a tabela e reescrever o mapeamento.
- **D-81:** Consulta por **endpoint novo `GET /notifications/orders/{orderId}`**, lista em ordem cronológica (mesma ordenação de leitura da Fase 3). Cada registro de pedido guarda o `companyId`: **SELLER_ADMIN vê qualquer pedido; BUYER só vê se o `company_id` do JWT bater, senão recebe 404** (mesmo padrão de D-47, sem revelar a existência). É a "regra nova e explícita" prevista no javadoc do `NotificationController`.
- **D-82:** O `NotificationService` deixa de aceitar só `STOCK_ADJUSTED`: valida e monta a mensagem legível por tipo de evento (ex.: "Pedido confirmado — transportadora X, rastreio AB123456789BR"). Continua com o descarte com log para mensagem inválida, o limite de tamanho do payload e a idempotência por chave (`putItem` sem condição).

### Diagrama e prova do fluxo (ORD-10, critério 4)
- **D-83:** As transições permitidas vivem em **um único lugar do código** (ex.: `OrderStatus.canTransitionTo` ou uma tabela de transições), usado pelos endpoints de ação e pela saga. Um **teste parametrizado exaustivo** cobre todos os pares (de, para) de `OrderStatus` e compara com a tabela documentada. O diagrama é escrito a partir da mesma lista.
- **D-84:** Diagrama **Mermaid `stateDiagram-v2`** no `README.md` e versão detalhada em `docs/VISAO-GERAL.md`, com cada transição anotada pelo gatilho (endpoint, evento da saga, timeout). O diagrama mostra RESERVING e o APPROVED lógico da D-50 como são de fato.
- **D-85:** Demonstração na stack real com **`scripts/smoke-order-lifecycle.sh`** (estilo dos smokes existentes): cria o pedido, espera CONFIRMED com transportadora e rastreio, chama `ship` e `deliver`, confere a baixa de `on_hand`/`reserved` no inventory e a timeline completa em `/notifications/orders/{id}`, e prova uma transição inválida (409). `smoke-order-saga.sh` e `smoke-order-flow.sh` são ajustados se os novos campos ou a rota do histórico mudarem.
- **D-86:** **E2E estendido** no módulo `e2e-tests`: novo cenário CONFIRMED → `ship` → o inventory baixa `on_hand` e `reserved` (Awaitility). O notification-service **não** entra como terceiro contexto. A timeline é provada por testes de integração próprios (Testcontainers LocalStack), e o smoke cobre a junção dos três serviços.

### Claude's Discretion
- Nomes exatos: `CarrierGateway`/`SimulatedCarrierGateway`, lista de transportadoras, algoritmo determinístico, nome do comando de baixa (`ShipStock` ou outro), nome da PK genérica, nomes das colunas novas.
- Se o `ShipStock` para uma reserva inexistente, liberada ou já expedida é no-op idempotente com log ou anomalia técnica (lançar exceção → DLQ). Recomendação: expedida de novo = no-op, e inexistente ou liberada = anomalia técnica, seguindo o padrão "livro parcialmente preenchido é anomalia" da Fase 5.
- Se a baixa de estoque na expedição também publica `STOCK_ADJUSTED` para o histórico do produto.
- Se a rota de produto atual `GET /notifications/{productId}` vira `/notifications/products/{productId}` ou fica como está. Se mudar, atualizar o smoke da Fase 3 e a documentação.
- Texto das mensagens legíveis por tipo de evento, e como o `companyId` chega ao notification-service (no envelope do evento).
- Se `/ship` e `/deliver` travam a linha do pedido (`PESSIMISTIC_WRITE`, padrão `SAGA_RESULT_LOCK`) ou usam guarda de estado com lock otimista. A trava de empresa não é necessária, porque SHIPPED e DELIVERED não mudam a exposição de crédito.
- Formato exato do código 409 e da mensagem de transição inválida.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Escopo e requisitos
- `.planning/ROADMAP.md` §Phase 6 — goal e 4 critérios de sucesso (transportadora no CONFIRMED, SHIPPED/DELIVERED com transições inválidas rejeitadas, timeline completa, diagrama igual à API)
- `.planning/REQUIREMENTS.md` — ORD-07, ORD-10 (e NOTIF para o histórico)
- `.planning/PROJECT.md` §Constraints (Outbox), §Out of Scope (sem estados de trânsito, sem shipping-service, transportadora mockada) e §Key Decisions

### Decisões anteriores que se aplicam
- `.planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-CONTEXT.md` — D-49/D-50 (RESERVING, APPROVED lógico), D-53 (colunas próprias de resultado), D-57 (baixa de on_hand no SHIPPED), D-59/D-60/D-62 (outbox e relay por serviço), D-64/D-65 (idempotência por estado e pelo livro), D-68 (módulo e2e-tests), D-69 (estilo de smoke)
- `.planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-CONTEXT.md` — D-37 (CREDIT_CONSUMING inclui SHIPPED/DELIVERED), D-46 (padrão de endpoints de ação, 409), D-47 (BUYER → 404 para pedido de outra empresa)
- `.planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-CONTEXT.md` — D-31/D-32/D-33 (Query por entidade, PK por entidade, sort key `eventType#eventId`), D-34 (payload bruto + mensagem legível)
- `.planning/phases/02-cat-logo-e-estoque/02-CONTEXT.md` — D-08/D-09 (on_hand/reserved, confirmar saída), D-11 (reservation_id)

### Pesquisa e estado
- `.planning/research/ARCHITECTURE.md` — saga orquestrada no order-service; logística como atributos do pedido
- `.planning/research/PITFALLS.md` — Pitfall 2 (dual-write/outbox), Pitfall 3 (consumidores idempotentes), Pitfall 5 (quirks do LocalStack)
- `.planning/STATE.md` §Accumulated Context — `SAGA_MESSAGE_CONTRACT`, `ORDER_RESPONSE_CONTRACT`, `SAGA_RESULT_LOCK`, `OUTBOX_RELAY_DEFAULTS`; §Blockers — sessão LocalStack Hobby única (derrubar o compose antes de `./mvnw verify`)

### Documentação existente a atualizar
- `README.md` — diagrama de estados, novos endpoints, limitações
- `docs/VISAO-GERAL.md` e `docs/API.md` — fluxo detalhado e contratos novos

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `order-service/.../order/OrderStatus.java`: já tem SHIPPED/DELIVERED e o `CHECK` da V2 já os aceita. Ganha a tabela de transições (D-83).
- `order-service/.../saga/OrderSagaService.java`: ponto do CONFIRMED, onde entra a atribuição da transportadora (D-70) e o evento ORDER_CONFIRMED.
- `order-service/.../order/OrderDecisionController.java` + `OrderDecisionService.java`: modelo de endpoint de ação com 409 para `/ship` e `/deliver`.
- `order-service/.../saga/outbox/OutboxWriter.java` (`Propagation.MANDATORY`) e `OutboxRelay.java` (`resolveQueue` por `eventType`, hoje só `inventory-commands-queue`): ganha a rota `ORDER_*` → `notification-events-queue` e o comando de baixa.
- `order-service/.../order/OrderCreationService.java`, `OrderDecisionService.java`, `saga/OrderSagaService.java`, `saga/SagaTimeoutJob.java`: todos os pontos de transição que passam a gravar eventos de timeline.
- `inventory-service/.../saga/messaging/SagaCommandParser.java` + DTOs `ReserveStockCommand`/`ReleaseStockCommand`: padrão para o novo comando de baixa.
- `inventory-service/.../stock/StockReservation*` e `InventoryService`: livro de reservas, onde entra a marcação de expedida.
- `notification-service/.../history/NotificationService.java`, `NotificationRecord.java`, `NotificationController.java`, `messaging/NotificationEventListener.java`: listener `String` de fan-out, validação, sanitização de log e limite de payload. A PK muda (D-80), a validação passa a ser por tipo (D-82) e entra a rota nova (D-81).
- `localstack-init/ready.d/01-create-notification-resources.sh`: key-schema da tabela a renomear.
- `e2e-tests/` (Fase 5), `DownstreamStubServer`, `AbstractIntegrationTest`: base para o cenário novo e os testes de integração.
- `scripts/smoke-order-saga.sh`, `smoke-order-flow.sh`, `smoke-notification-flow.sh`: estilo do smoke novo.

### Established Patterns
- Flyway `V<n>__*.sql` por serviço com `ddl-auto: validate`. V3 no order-service (transportadora, rastreio, shipped/delivered) e possivelmente V3 no inventory-service (estado de expedida no livro).
- Envelope plano `eventId`/`eventType`/`occurredAt` + campos do tipo; `doNotSendPayloadTypeHeader`/`setPayloadTypeMapper` já provados.
- Idempotência por estado no order-service e pelo livro no inventory. No notification-service, pela chave determinística.
- Erro `{"error","message","fields"}`; BUYER recebe 404 para recurso de outra empresa.
- A trava da linha do pedido (`findByIdForUpdate`) serializa transições concorrentes sobre o mesmo pedido.

### Integration Points
- `docker-compose.yml`: o order-service passa a publicar também na `notification-events-queue` (env de fila). O healthcheck do LocalStack confere a tabela com o novo key-schema.
- `ORDER_RESPONSE_CONTRACT` ganha `carrier`, `trackingCode`, `shippedAt`, `shippedBy`, `deliveredAt`, `deliveredBy`.
- `SAGA_MESSAGE_CONTRACT` ganha o comando de baixa.

</code_context>

<specifics>
## Specific Ideas

- O avaliador deve conseguir abrir o Swagger, seguir um pedido do começo ao fim e ver a jornada inteira em `/notifications/orders/{id}`, inclusive o caminho triste (rejeitado ou cancelado).
- Código de rastreio "com cara de Brasil" (padrão Correios) reforça o contexto de vaga no Brasil.
- A costura `CarrierGateway` deixa explícito onde uma API real de transportadora entraria. É um bom ponto para falar em entrevista, sem inflar a infraestrutura.
- "Corresponde exatamente" do critério 4 vira uma garantia de teste (tabela única + teste exaustivo), não uma promessa de documentação.

</specifics>

<deferred>
## Deferred Ideas

- Cancelamento manual de pedido CONFIRMED/SHIPPED pelo vendedor (com `ReleaseStock` e liberação de crédito): capacidade nova, backlog.
- Falha simulada da transportadora com retry e Resilience4j: candidato à Fase 7 (endurecimento), se fizer sentido.
- notification-service como terceiro contexto no E2E: descartado agora por custo e fragilidade (D-86).
- Tabela DynamoDB separada para pedidos: descartada (D-80).

</deferred>

---

*Phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedido*
*Context gathered: 2026-09-30*
