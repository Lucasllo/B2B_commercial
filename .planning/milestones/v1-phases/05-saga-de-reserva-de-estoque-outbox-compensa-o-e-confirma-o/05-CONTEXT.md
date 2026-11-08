# Phase 5: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação - Context

**Gathered:** 2026-09-25
**Status:** Ready for planning

<domain>
## Phase Boundary

Entrega o Core Value: quando um pedido é aprovado (automática ou manualmente), o order-service grava o comando de reserva numa tabela outbox na mesma transação da mudança de status; um relay publica o comando no SQS; o inventory-service reserva todos os itens de forma atômica e idempotente e devolve o resultado também via outbox; o order-service consome o resultado e leva o pedido a CONFIRMED ou CANCELLED — com timeout e compensação garantindo que nenhum pedido fique preso em RESERVING.

Requisitos: ORD-04, ORD-05, ORD-06, TEST-03.

**Fora desta fase:** baixa de `quantity_on_hand` (saída física) e transportadora/SHIPPED/DELIVERED (Fase 6); eventos de ciclo de vida do pedido no notification-service (Fase 6); cancelamento pelo comprador; Correlation-ID e ADRs formais (Fase 7).

</domain>

<decisions>
## Implementation Decisions

### Gatilho e estado intermediário
- **D-48:** A saga é disparada em **toda entrada em APPROVED** — aprovação automática dentro do limite (D-45) e aprovação manual do vendedor (D-46). Um único ponto de entrada da saga, usado pelos dois caminhos.
- **D-49:** Novo status **`RESERVING`**: `CREATED → (PENDING_APPROVAL →) APPROVED → RESERVING → CONFIRMED | CANCELLED`. Exige migração V2 do `CHECK` de status e inclusão no enum `OrderStatus`. — **Reversibility:** costly — o status aparece no contrato da API (`OrderResponse.status`), nos filtros `?status=`, no smoke e na timeline da Fase 6.
- **D-50:** A decisão de aprovação grava **direto `status = RESERVING` + a linha do outbox na mesma transação** (sob a trava `company_credit_lock`, D-40). APPROVED é um passo lógico registrado em `decidedBy/decidedAt/reason`, não um estado persistido — nunca existe pedido "APPROVED sem comando". O enum mantém APPROVED (compatibilidade e semântica de decisão), mas nenhum pedido novo repousa nele.
- **D-51:** A migração V2 move pedidos legados em **APPROVED → RESERVING** e insere um comando de reserva no outbox para cada um — a saga os processa na subida, nenhum fica órfão.
- **D-52:** **RESERVING consome crédito** — entra em `OrderStatus.CREDIT_CONSUMING` (fonte única, D-37). CANCELLED libera; CONFIRMED continua consumindo.
- **D-53:** Resultado da saga em **colunas próprias**, separadas da decisão do vendedor: `cancellation_code`, `cancellation_reason` (mensagem legível), `cancelled_at`, `confirmed_at`. `decidedBy/decidedAt/reason` permanecem intactos. Expor no `OrderResponse`.
- **D-54:** `POST /orders` (dentro do limite) e `POST /orders/{id}/approve` devolvem o pedido **já em RESERVING com os mesmos códigos HTTP da Fase 4** (201/200). O cliente acompanha por `GET /orders/{id}` — sem 202, sem requisição bloqueante.

### Reserva multi-item e falha
- **D-55:** Um comando `ReserveStock` carrega **todos os itens** do pedido; o inventory-service reserva todos **numa única transação local, tudo ou nada** — se algum faltar, rollback e evento de falha. Não existe reserva parcial a compensar. `reservation_id = orderId` em cada produto (RESERVATION_ID_SCOPE=scope-per-product, D-11). — **Reversibility:** costly — o formato do comando/evento é contrato entre dois serviços e base do teste E2E.
- **D-56:** O evento de falha traz **reason code + detalhe por produto**: códigos `INSUFFICIENT_STOCK`, `PRODUCT_NOT_STOCKED` (e `RESERVATION_CANCELLED`, D-66), com lista `{productId, requested, available}`. O pedido grava o código em `cancellation_code` e uma mensagem legível em `cancellation_reason` (ex.: "Estoque insuficiente: produto X — disponível 2, solicitado 5").
- **D-57:** **CONFIRMED mantém o estoque reservado** (`quantity_reserved += qtd`); a baixa de `quantity_on_hand` (saída física) fica para a Fase 6, na expedição (SHIPPED). Isso substitui a indicação de D-09 de que o "confirmar saída" entraria nesta fase.
- **D-58:** Produto sem linha de estoque (hoje 404 no `reserve`, D-18) é **falha de negócio** `PRODUCT_NOT_STOCKED` → CANCELLED, nunca erro técnico em retry eterno.

### Mecânica do Outbox
- **D-59:** Relay por **polling `@Scheduled`** (~1s, configurável): busca linhas não publicadas em lote com `SELECT ... FOR UPDATE SKIP LOCKED`, envia via `SqsTemplate` e marca `published_at`. Entrega **pelo menos uma vez** — por isso todo consumidor é idempotente (ORD-06). Sem CDC/Debezium.
- **D-60:** **Outbox também no inventory-service**: o evento de resultado (`StockReserved` / `StockReservationFailed`) é gravado na mesma transação da reserva, e o `STOCK_ADJUSTED` da Fase 3 **migra para o mesmo outbox** — fecha a limitação D-29/D-30 (atualizar README). O `StockEventPublisher` de dual-write deixa de existir no caminho síncrono.
- **D-61:** **Uma fila por direção**, cada uma com DLQ, provisionadas no `localstack-init/ready.d` (único lugar que cria recursos, `queue-not-found-strategy=fail`): `inventory-commands-queue` (order → inventory: `ReserveStock`, `ReleaseStock`) e `order-events-queue` (inventory → order: resultados). A `notification-events-queue` da Fase 3 continua como está. Nomes exatos a critério do planejador.
- **D-62:** Código do outbox (entidade `outbox_event`, repositório, relay) **duplicado em cada serviço** — sem módulo compartilhado, preservando a autonomia dos serviços (mesmo padrão do `TestJwt` copiado).

### Garantia de "nunca travado"
- **D-63:** **Timeout da saga**: job `@Scheduled` no order-service cancela pedidos em RESERVING há mais de N (configurável) com `cancellation_code = RESERVATION_TIMEOUT`, e na mesma transação grava no outbox um **`ReleaseStock`** para compensar uma reserva que tenha acontecido tarde. A transição passa pela mesma guarda de estado (D-64).
- **D-64:** Idempotência do order-service por **transição guardada pelo estado**: o resultado só é aplicado se o pedido estiver em RESERVING; duplicata = no-op com log. Resultado **"reservado" tardio** para pedido já CANCELLED (timeout) → publica `ReleaseStock` pelo outbox. Sem tabela `processed_messages`.
- **D-65:** Idempotência do inventory-service pelo livro `stock_reservations` (D-11/D-14): `ReserveStock` repetido com o mesmo `reservation_id` não decrementa de novo e reemite o mesmo resultado. Critério 4 do roadmap comprovado por teste que republica o comando.
- **D-66:** **Lápide (tombstone) no `ReleaseStock`**: liberar um `reservation_id` inexistente grava a linha já `released = true`; um `ReserveStock` posterior com o mesmo id encontra a lápide e responde falha `RESERVATION_CANCELLED` sem reservar. Resolve a corrida da fila SQS padrão (sem ordem garantida) sem migrar para FIFO. Ajusta D-14 (liberação de inexistente deixa de ser no-op puro). Atenção: `stock_reservations.product_id` tem FK para `inventory(product_id)` — o planejador decide como tratar lápide para produto sem linha de estoque. — **Reversibility:** costly — muda a semântica do livro de idempotência compartilhado pela reserva REST e pela saga.
- **D-67:** No consumidor do inventory, **falha de negócio vira evento de falha** (mensagem consumida com sucesso); **só erro técnico** lança exceção e deixa o SQS reentregar até a DLQ.

### Testes e demonstração
- **D-68:** E2E do critério 5 num **novo módulo Maven `e2e-tests`** que sobe order-service e inventory-service como dois contextos Spring no mesmo JVM, com Postgres e LocalStack reais via Testcontainers; auth-service e catalog-service stubados (padrão `DownstreamStubServer` da Fase 4). Um comando: `./mvnw -pl e2e-tests verify` (ou equivalente). Cobre sucesso → CONFIRMED (reserved refletido no inventory) e falha → CANCELLED; **teste do caminho de falha escrito antes do caminho feliz** (critério 3).
- **D-69:** Demonstração na stack real com **`scripts/smoke-order-saga.sh`** (estilo dos smokes existentes): pedido com estoque suficiente → espera CONFIRMED e confere `reserved`; pedido sem estoque → espera CANCELLED com motivo. `scripts/smoke-order-flow.sh` da Fase 4 é ajustado para aceitar RESERVING/CONFIRMED no lugar de APPROVED.

### Claude's Discretion
- Valores padrão: intervalo do relay, timeout da saga (ex.: 2 min), `maxReceiveCount` antes da DLQ (ex.: 3), tamanho de lote — todos por propriedade, valores curtos nos testes.
- Formato do envelope dos eventos (eventId, type, occurredAt, payload), nomes exatos de filas, tipos de evento e colunas; uso de `doNotSendPayloadTypeHeader`/`setPayloadTypeMapper` já provados na Fase 3.
- Limpeza das linhas publicadas do outbox (job de retenção ou nenhuma nesta fase).
- Se o CANCELLED por falha precisa tomar a trava `company_credit_lock` (liberar crédito não arrisca estourar limite).
- Se os endpoints REST de reserva/liberação do inventory (SELLER_ADMIN, Fase 2) permanecem — a "identidade de serviço" prevista no javadoc do `InventoryController` deixa de ser necessária, pois order ↔ inventory conversam só por SQS.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Escopo e requisitos
- `.planning/ROADMAP.md` §Phase 5 — goal e 5 critérios de sucesso (outbox atômico, CONFIRMED com reserved refletido, falha → CANCELLED com teste de falha primeiro, idempotência por republicação, E2E em um comando)
- `.planning/REQUIREMENTS.md` — ORD-04, ORD-05, ORD-06, TEST-03 (e ORD-10 para o fluxo de status)
- `.planning/PROJECT.md` §Constraints (Transactional Outbox em order-service e inventory-service) e §Key Decisions

### Decisões anteriores que se aplicam
- `.planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-CONTEXT.md` — D-37 (CREDIT_CONSUMING), D-40/D-41 (trava por empresa, sem I/O dentro da transação), D-45/D-46 (aprovação automática e manual — pontos de entrada da saga)
- `.planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-CONTEXT.md` — D-28 (reserva/liberação passam a publicar na Fase 5), D-29/D-30 (dual-write do STOCK_ADJUSTED a ser resolvido aqui)
- `.planning/phases/02-cat-logo-e-estoque/02-CONTEXT.md` — D-08 (on_hand/reserved), D-09 (confirmar saída — ajustado por D-57), D-11 (reservation_id do chamador), D-13 (sem TTL de reservas), D-14 (liberação idempotente — ajustado por D-66), D-18 (404 sem linha de estoque), D-20/D-21 (retry de lock otimista)

### Pesquisa de arquitetura
- `.planning/research/ARCHITECTURE.md` — saga orquestrada no order-service; estoque só por SQS
- `.planning/research/PITFALLS.md` — Pitfall 2 (dual-write/outbox), Pitfall 3 (consumidores idempotentes), Pitfall 5 (quirks do LocalStack), Pitfall 7 (Testcontainers real, não adiar testes)
- `.planning/research/STACK.md` — Spring Cloud AWS SQS, Testcontainers LocalStack, Awaitility
- `.planning/STATE.md` §Blockers/Concerns — sessão LocalStack Hobby única por token (derrubar o compose antes de `./mvnw verify`); padrões do Spring Cloud AWS já provados na Fase 3

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `order-service/.../order/OrderStatus.java` — enum com os 8 estados e `CREDIT_CONSUMING`; ganha `RESERVING`.
- `order-service/.../order/OrderCreationService.java` e `OrderDecisionService.java` — pontos onde APPROVED é decidido (automático e manual); passam a gravar RESERVING + outbox.
- `order-service/.../credit/CompanyCreditLocker.java` — trava `PESSIMISTIC_WRITE` por empresa (D-40).
- `inventory-service/.../stock/InventoryService.java` — `reserve`/`release` por produto com `@Retryable` + `@Transactional`; base para a reserva multi-item atômica.
- `inventory-service/.../stock/StockReservation*.java` + `V1__init_inventory_schema.sql` — livro `stock_reservations` com `UNIQUE (product_id, reservation_id)` e `released`.
- `inventory-service/.../stock/messaging/StockEventPublisher.java` + `config/SqsMessagingConfig.java` — publicação SQS atual (dual-write) a migrar para o outbox.
- `notification-service` — modelo de `@SqsListener` e configuração do Spring Cloud AWS (Fase 3).
- `localstack-init/ready.d/01-create-notification-resources.sh` — padrão de provisionamento de filas.
- `DownstreamStubServer` (testes do order-service, Fase 4) e `AbstractIntegrationTest` — base para o módulo `e2e-tests`.
- `scripts/smoke-order-flow.sh`, `scripts/smoke-notification-flow.sh` — estilo do novo smoke.

### Established Patterns
- Flyway `V<n>__*.sql` por serviço, `ddl-auto: validate`; migração V2 no order-service (status, colunas da saga, outbox) e no inventory-service (outbox).
- Erro `{"error","message","fields"}`; pacotes `com.orderflow.<svc>.<domain>` com `dto/` em records.
- Chamadas HTTP fora da transação com trava (D-41) — o relay também não segura transação de negócio durante envio ao SQS.
- Docker compose com `depends_on: service_healthy`; order-service passa a depender do localstack (hoje não depende).

### Integration Points
- `pom.xml` raiz: dependência `spring-cloud-aws-starter-sqs` no order-service; novo módulo `e2e-tests`.
- `docker-compose.yml`: env de SQS no order-service, healthcheck do localstack passa a checar as filas novas.
- `README.md`: remover a limitação de dual-write (D-30) e a de "APPROVED não reserva" (Fase 4); documentar o fluxo da saga.

</code_context>

<specifics>
## Specific Ideas

- O "nunca travado" é uma garantia de código (timeout + compensação + lápide), não só retry do SQS.
- A saga não precisa de compensação item a item porque a reserva multi-item é atômica no banco do inventory — a compensação real é o `ReleaseStock` do timeout/resultado tardio.
- Mensagem legível no `cancellation_reason` pensando no avaliador que abre o Swagger.

</specifics>

<deferred>
## Deferred Ideas

- Baixa de `quantity_on_hand` na expedição — Fase 6.
- Publicar eventos da saga (reservando/confirmado/cancelado) para o notification-service — Fase 6.
- SQS FIFO / SNS fan-out — descartados agora (D-61, D-66).
- Módulo compartilhado de outbox — descartado (D-62).
- Cancelamento de pedido pelo comprador — capacidade nova, não prevista no roadmap.

</deferred>

---

*Phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o*
*Context gathered: 2026-09-25*
