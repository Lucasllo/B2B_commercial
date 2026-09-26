---
gsd_state_version: "1.0"
current_phase: 05
current_phase_name: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação
status: executing
stopped_at: Completed 05-03-PLAN.md
last_updated: "2026-09-26T16:03:17.821Z"
last_activity: 2026-09-26
last_activity_desc: Phase 05 execution started
state_head: 506811b84f0d57dfaa6c462bb99d02c72a5e81fa
progress:
  total_phases: 7
  completed_phases: 4
  total_plans: 22
  completed_plans: 19
  percent: 57
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-25)

**Core value:** O fluxo de pedido — criação, aprovação condicional por limite de crédito, reserva de estoque e confirmação — funcionando de ponta a ponta entre microsserviços via orquestração por eventos (padrão saga).
**Current focus:** Phase 05 — Saga de Reserva de Estoque — Outbox, Compensação e Confirmação

## Current Position

Phase: 05 (Saga de Reserva de Estoque — Outbox, Compensação e Confirmação) — EXECUTING
Plan: 4 of 6
Status: Ready to execute
Last activity: 2026-09-26 — Phase 05 execution started

Progress: [██████░░░░] 57%

## Performance Metrics

**Velocity:**

- Total plans completed: 16
- Average duration: —
- Total execution time: 0.0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1 | 5 | - | - |
| 02 | 3 | - | - |
| 03 | 3 | - | - |
| 04 | 5 | - | - |
| 4 | 5 | - | - |

**Recent Trend:**

- Last 5 plans: —
- Trend: —

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 02 P01 | 26min | 3 tasks | 25 files |
| Phase 02 P02 | 27min | 3 tasks | 29 files |
| Phase 03 P03 | 37min | 2 tasks | 8 files |
| Phase 04 P01 | 48min | 2 tasks | 48 files |
| Phase 04 P02 | 40min | 3 tasks | 13 files |
| Phase 04 P03 | 35min | 2 tasks | 5 files |
| Phase 04 P04 | 20min | 3 tasks | 10 files |
| Phase 04 P05 | 27min | 2 tasks | 8 files |
| Phase 05 P01 | 33min | 2 tasks | 32 files |
| Phase 05 P02 | 89min | 2 tasks | 28 files |
| Phase 05 P03 | 34min | 2 tasks | 21 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: Saga isolada na Fase 5, depois de auth, catálogo/estoque, prova assíncrona simples e núcleo do pedido — de-risking antes do Core Value (ARCHITECTURE.md build order).
- [Roadmap]: Notification-service + SQS + DynamoDB construídos na Fase 3 como a integração assíncrona mais simples possível, antes da saga, para depurar o encanamento isoladamente.
- [Roadmap]: Infraestrutura (docker-compose, Postgres, LocalStack, Gateway) fundida com autenticação na Fase 1 — modo `mvp` exige fatia vertical demonstrável, e infra sozinha não entrega valor observável.
- [Roadmap]: Testes unitários/integração e CI registrados formalmente na Fase 7, mas devem ser escritos em cada fase (PITFALLS.md #7 — não adiar testes para uma fase final).
- [Phase 02]: PRODUCT_STATUS_CONTRACT=status-two-values — dois estados ACTIVE/DISCONTINUED, retirada via PUT /products/{id}/status, sem remoção física (D-23)
- [Phase 02]: catalog-service: porta 8082, jwk-set-uri padrão http://localhost:8081/.well-known/jwks.json
- [Phase 02]: GET /products devolve o envelope padrão Page<T> do Spring Data (content/totalElements/...) — contrato consumido pela Fase 4
- [Phase 02]: RESERVATION_ID_SCOPE=scope-per-product — unicidade por par (productId, reservationId) para o reservationId da reserva de estoque
- [Phase 02]: inventory-service: porta 8083, jwk-set-uri padrão http://localhost:8081/.well-known/jwks.json
- [Phase 02]: Reexecução de conflito de lock otimista: maxAttempts=4, backoff delay=25ms multiplier=2 (Spring Retry, @EnableRetry order=LOWEST_PRECEDENCE)
- [Phase 03]: [Phase 3]: Healthcheck do LocalStack verifica fila e tabela via awslocal (não só o processo) — recurso de negócio precisa existir antes de qualquer serviço dependente subir saudável
- [Phase 03]: [Phase 3]: Reentrega provada na stack real enviando duas vezes o mesmo eventId direto na fila via awslocal, em vez de derrubar o consumidor no meio do processamento
- [Phase 04]: ORDER_RESPONSE_CONTRACT=id,companyId,status,total,createdBy,createdAt,decidedBy,decidedAt,reason,items[...] — Contrato consumido pelo smoke do plano 04-05 e pela documentação
- [Phase 04]: Cláusula de bloqueio Postgres/Hibernate para PESSIMISTIC_WRITE é 'for no key update', não 'for update' — Comportamento padrão do dialeto Postgres do Hibernate 6.6 para entidade sem FK-alvo; ambas as formas se excluem mutuamente, serialização continua válida (comprovado por 3 cenários de concorrência)
- [Phase 04]: DownstreamStubServer (JDK pura) em vez de MockRestServiceServer para stub de auth-service/catalog-service nos testes — MockRestServiceServer troca a fábrica de requisições do cliente — os timeouts de ClientConfig nunca seriam exercitados e o repasse do header Authorization não passaria por socket real
- [Phase 04]: [Phase 04]: MAX_ITEMS_PER_ORDER=50, MAX_QUANTITY_PER_ITEM=1000000 (Claude's Discretion) — defesa contra amplificacao de chamadas sincronas ao catalog-service e abuso de quantidade
- [Phase 04]: [Phase 04]: Guarda de total order_total_out_of_range usa precision()-scale() > 17, mesmo criterio do @Digits(integer=17,fraction=2) do auth-service, checada antes do limite de credito
- [Phase 04]: [Phase 04]: AuthenticationEntryPoint customizado em JSON adicionado a SecurityConfig (Rule 2) — rejeicao de JWT acontece no filtro de seguranca antes do GlobalExceptionHandler, corpo de 401 vazio quebraria o envelope uniforme
- [Phase 04]: GET /orders: ?status= vale para BUYER e SELLER_ADMIN (Open Question 2 resolvida) — Filtro adicional sobre escopo ja resolvido, nao enfraquece isolamento por empresa
- [Phase 04]: [Phase 04]: OrderDecisionService.decide privado parametrizado por Consumer<Order>, reaproveitado por approve/reject sem auto-invocacao de metodo @Transactional — Evita duplicar busca->trava->releitura->instante entre approve e reject
- [Phase 04]: [Phase 04]: OrderDecisionController e um segundo @RestController sobre /orders, nao um metodo a mais em OrderController — Evita disputa de arquivo com o plano 04-03 na mesma wave
- [Phase 04]: [Phase 04]: order-service sem localstack no depends_on do compose — nenhuma mensageria existe neste servico nesta fase (saga com SQS/Outbox chega na Fase 5)
- [Phase 04]: [Phase 04]: Script de smoke gera sufixo unico (UUID) dentro do container do Gateway para emails/SKUs — permite reexecutar na mesma base sem colidir com dados de execucoes anteriores
- [Phase 05]: [Phase 5]: SAGA_MESSAGE_CONTRACT - envelope plano eventId/eventType/occurredAt + campos do tipo; ReserveStock com reservationId=orderId.toString() e items na ordem de lineNumber
- [Phase 05]: [Phase 5]: SAGA_TIMEOUT_CLOCK=reservation_started_at - coluna propria gravada na entrada em RESERVING; o job de timeout (05-04) conta a partir dela, nao de decided_at
- [Phase 05]: [Phase 5]: OUTBOX_RELAY_DEFAULTS=relay-interval 1000ms (200ms em teste), batch-size 20, ordem attempts,created_at,id
- [Phase 05]: [Fase 5]: Livro parcialmente preenchido em stock_reservations e sempre anomalia tecnica (IllegalStateException), nunca reemitido como resultado de negocio
- [Phase 05]: [Fase 5]: spring.cloud.aws.sqs.listener.poll-timeout=0s no inventory-service - long polling padrao do SQS estourava o apiCallAttemptTimeout compartilhado com a chamada sincrona de PUT /inventory
- [Phase 05]: [Phase 05]: ORDER_RESPONSE_CONTRACT=id,companyId,status,total,createdBy,createdAt,decidedBy,decidedAt,reason,cancellationCode,cancellationReason,confirmedAt,cancelledAt,items[...] - campos novos entre reason e items
- [Phase 05]: [Phase 05]: SAGA_RESULT_LOCK=order-row - OrderSagaService trava a linha do pedido (findByIdForUpdate/PESSIMISTIC_WRITE), nao company_credit_lock, para serializar resultado x timeout (05-04) sobre o mesmo pedido
- [Phase 05]: [Phase 05]: CANCELLATION_REASON_TEMPLATE - texto de cancelamento montado no servidor a partir de modelo fixo por reasonCode, com sku do snapshot do item; truncado em 500 caracteres; nunca texto livre da mensagem
- [Phase 05]: [Phase 05]: STOCK_RESERVED_ITEMS_CHECK - StockReserved com itens diferentes dos do pedido e mensagem invalida e descartado; a fila e uma fronteira de confianca

### Pending Todos

[From .planning/todos/pending/ — ideas captured during sessions]

None yet.

### Blockers/Concerns

[Issues that affect future work]

- [Fase 1]: LocalStack exige `LOCALSTACK_AUTH_TOKEN` (tier Hobby gratuito) desde 2026.03.0 — precisa estar no docker-compose e no CI desde o primeiro dia.
- [Fase 1]: Fixar versões das imagens Docker (LocalStack, Postgres) — `latest` causa divergência silenciosa de comportamento (PITFALLS.md #6).
- [Fase 5]: Padrões do Spring Cloud AWS (`SqsTemplate`/`@SqsListener`, `doNotSendPayloadTypeHeader`, `setPayloadTypeMapper`) já provados na Fase 3 — reusar na saga; o desenho de tabela DynamoDB (partition por agregado + sort `TIPO#eventId`) serve de base para a timeline do pedido (Fase 6).
- [Ambiente]: sessão LocalStack Hobby é única por token — Testcontainers falha (exit 126) com a stack do compose de pé; derrubar o compose antes de `./mvnw verify`.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260920-g6c | Adicionar springdoc-openapi (Swagger UI) em auth-service, catalog-service e inventory-service para visualizacao e teste rapido dos endpoints no navegador | 2026-09-20 | 588785f | [260920-g6c-adicionar-springdoc-openapi-swagger-ui-e](./quick/260920-g6c-adicionar-springdoc-openapi-swagger-ui-e/) |
| 260923-tj9 | Validar issuer (iss) do JWT nos resource servers notification/catalog/inventory — fecha T-03-02 / WR-07; testes passam a usar o decoder de produção | 2026-09-24 | 805d5c2 | [260923-tj9-validar-issuer-do-jwt-nos-resource-serve](./quick/260923-tj9-validar-issuer-do-jwt-nos-resource-serve/) |
| 3 | README.md: incluir a porta 8085 (order-service) na lista de portas ligadas a 127.0.0.1 | 2026-09-26 | de4e384 | — |

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-09-26T16:03:17.641Z
Stopped at: Completed 05-03-PLAN.md
Resume file: None
