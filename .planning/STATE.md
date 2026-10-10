---
gsd_state_version: "1.0"
current_phase: 07
current_phase_name: Endurecimento, Observabilidade e Entrega
status: verifying
stopped_at: Completed 07-11-PLAN.md
last_updated: "2026-10-03T17:05:19.924Z"
last_activity: 2026-10-03
last_activity_desc: Phase 07 execution started
state_head: f91149fa5ec64014fa468aeaffb58050bcac9fb4
progress:
  total_phases: 7
  completed_phases: 6
  total_plans: 40
  completed_plans: 40
  percent: 86
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-10-01)

**Core value:** O fluxo de pedido — criação, aprovação condicional por limite de crédito, reserva de estoque e confirmação — funcionando de ponta a ponta entre microsserviços via orquestração por eventos (padrão saga).
**Current focus:** Phase 07 — Endurecimento, Observabilidade e Entrega

## Current Position

Phase: 07 (Endurecimento, Observabilidade e Entrega) — EXECUTING
Plan: 11 of 11
Status: Phase complete — ready for verification
Last activity: 2026-10-03 — Phase 07 execution started

Progress: [█████████░] 86%

## Performance Metrics

**Velocity:**

- Total plans completed: 34
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
| 5 | 6 | - | - |
| 6 | 7 | - | - |

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
| Phase 05 P04 | 110min | 3 tasks | 27 files |
| Phase 05 P05 | 95min | 2 tasks | 18 files |
| Phase 05 P06 | 30min | 2 tasks | 5 files |
| Phase 06 P01 | 17min | 2 tasks | 17 files |
| Phase 06 P02 | 22 min | 2 tasks | 11 files |
| Phase 06 P03 | 45 min | 2 tasks | 9 files |
| Phase 06 P04 | 14 min | 3 tasks | 15 files |
| Phase 06 P05 | 11 min | 2 tasks | 15 files |
| Phase 06 P06 | 75 min | 2 tasks | 3 files |
| Phase 06 P07 | 40 min | 3 tasks | 4 files |
| Phase 07 P01 | 19min | 2 tasks | 9 files |
| Phase 07 P02 | 66min | 3 tasks | 21 files |
| Phase 07 P04 | 19min | 2 tasks | 16 files |
| Phase 07 P05 | 25min | 2 tasks | 14 files |
| Phase 07 P06 | 25min | 3 tasks | 14 files |
| Phase 07 P07 | 35min | 3 tasks | 15 files |
| Phase 07 P08 | 70min | 3 tasks | 20 files |
| Phase 07 P09 | 30min | 2 tasks | 7 files |
| Phase 07 P10 | 90min | 2 tasks | 13 files |
| Phase 07 P11 | 95min | 3 tasks | 10 files |

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
- [Phase 05]: TOMBSTONE_FK=dropped, REST_RESERVATION_ENDPOINTS=kept, SAGA_TIMEOUT_DEFAULTS=reservation-timeout 2m/timeout-check-interval 10000ms/timeout-batch-size 50 (05-04)
- [Phase 05]: [Rule 1 - Bug] StockReservationConcurrencyIT reaproveita o Postgres de AbstractIntegrationTest em vez de um container separado - evita listener zumbi gravando no banco errado sob a suite inteira
- [Phase 05]: [Phase 05]: E2E_CONFIG_STRATEGY/E2E_FLYWAY_LOCATIONS provados no módulo e2e-tests (05-05) — dois contextos Spring reais isolados via spring.config.location real + overrides + argumentos de linha de comando, sem depender da resolução ambígua de classpath:application.yml entre os dois jars
- [Phase 05]: [Phase 05]: [Rule 1 - Bug] Consulta a flyway_schema_history do E2eContextsSmokeIT ajustada para version IS NOT NULL — o Flyway grava uma linha adicional (type SCHEMA, version nulo) para o evento de criação do schema
- [Phase 05]: [Phase 05]: OUTBOX_RETENTION=none-this-phase - linhas publicadas do outbox nao sao apagadas nesta fase; documentado como limitacao conhecida
- [Phase 06]: CARRIER_ALGORITHM=sha256-orderId; CARRIER_LIST de cinco nomes ficticios sem acento
- [Phase 06]: TRACKING_CODE_UNIQUENESS=probabilistic; sem UNIQUE no banco para nao criar laco de reentrega no CONFIRMED
- [Phase 06]: LEGACY_TRACKING_BACKFILL; pedidos CONFIRMED legados recebem Transportadora Legada e codigo LG+9 digitos de md5
- [Phase 06]: ORDER_RESPONSE_CONTRACT inclui carrier,trackingCode,shippedAt,shippedBy,deliveredAt,deliveredBy entre cancelledAt e items; tabela unica de 9 transicoes em OrderStatus (D-83)
- [Phase 06]: SHIPMENT_LOCK=order-row: /ship and /deliver lock the order row only (no company_credit_lock); INVALID_TRANSITION_ERROR=409 invalid_order_transition; ShipStock command goes through the outbox to inventory-commands-queue (D-75)
- [Phase 06]: 06-03: SHIP_STOCK_ANOMALY=missing-or-released-throws; SHIP_STOCK_ADJUSTED_EVENT=none; RELEASE_AFTER_SHIP=ignored (shipAll baixa on_hand+reserved pela quantidade do livro, idempotente por stock_reservations.shipped) — D-75/D-57: baixa fisica adiada da Fase 5; livro e a fonte da quantidade; CHECK released+shipped e rede de seguranca
- [Phase 06]: NOTIFICATION_PK=entityId: particao generica da tabela notification-history (produto ou pedido); NotificationResponse.entityId
- [Phase 06]: PRODUCT_HISTORY_ROUTE=kept: GET /notifications/{productId} inalterado, so SELLER_ADMIN
- [Phase 06]: TIMELINE_ORDER=occurredAt,lifecycle-rank,sortKey nas duas rotas de historico
- [Phase 06]: TIMELINE_READ_RULE: SELLER_ADMIN lista (vazia sem eventos); BUYER 404 identico se vazia ou algum companyId != JWT; rota de pedido so devolve ORDER_*
- [Phase 06]: TIMELINE_OCCURRED_AT=transition-column; TIMELINE_ROUTING=explicit-list; TEST_LOCALSTACK_SERVICES=sqs,dynamodb (06-05) — occurredAt vem da coluna gravada pela transicao; o relay roteia ORDER_* por lista explicita; o LocalStack dos ITs do order-service copia os hooks 01 e 02
- [Phase 06]: 06-06: smoke afirma formato (carrier nao vazio, trackingCode ^[A-Z]{2}[0-9]{9}BR$), nao valor fixo — O rastreio depende do orderId gerado em cada execucao
- [Phase 06]: 06-06: E2E de expedicao cobre order+inventory; linha do tempo so e provada pelo smoke na stack real (D-86) — notification-service fora do E2E por custo e fragilidade
- [Phase 06]: 06-07: diagramas stateDiagram-v2 do README e da visao geral conferidos por OrderStatusDiagramConsistencyTest contra OrderStatus.transitions(); 'corresponde exatamente' = diagrama x tabela (este teste) + API x tabela (OrderLifecycleTransitionsIT) — Mantem documentacao e codigo travados pela mesma tabela unica (D-83)
- [Phase 07]: CORRELATION_HEADER=X-Correlation-Id; CORRELATION_ID_PATTERN=[A-Za-z0-9-]{1,64}; CORRELATION_LOG_PATTERN=[%X{correlationId:-}]
- [Phase 07]: GATEWAY_RESPONSE_HEADER_OWNER=gateway — um unico X-Correlation-Id na resposta, eco do servico ignorado
- [Phase 07]: DOCS_ROUTES=/docs/<svc>/v3/api-docs com SetPath=/v3/api-docs; SWAGGER_PRIMARY=order-service
- [Phase 07]: SQS_CORRELATION_ATTRIBUTE=correlationId
- [Phase 07]: CORRELATION_CODE_PLACEMENT=duplicated-per-service
- [Phase 07]: TRANSITION_CORRELATION_SOURCE=request-id
- [Phase 07]: LEGACY_CORRELATION=null
- [Phase 07]: ORDER_CORRELATION_SETTER=recordCorrelationId
- [Phase 07]: OPENAPI_SERVER_URL=/api; ERROR_SCHEMA=ErrorResponse; PUBLIC_OPERATION_MARK=SecurityRequirements vazio em POST /auth/login; JWKS_IN_SPEC=hidden — Contratos e erros de catalog e auth; o mesmo padrao segue em 07-07 e 07-08.
- [Phase 07]: E2E_CORRELATION_PROOF=output-capture
- [Phase 07]: LISTENER_COMPAT_OVERLOAD=onMessage String delegates with null
- [Phase 07]: 07-05: CorrelationContext/CorrelationIdFilter duplicated in auth and catalog; CompanyGuard/TokenService unit-tested with real Nimbus encoder and no Spring context
- [Phase 07]: [Phase 07-06]: 11 ADRs MADR em português em docs/adr/ (ADR_LIST) verificados por scripts/check-adrs.sh; API_COVERAGE_GATE=none
- [Phase 07]: 07-07: WR03_ORDER_ID_FALLBACK=? (orderId só se casar UUID; eventType via sanitizeForLog) e NOTIFICATION_INFO_LOG='Evento registrado eventType={} entityId={}' após cada save
- [Phase 07]: 07-08: OrderStatus no spec via @Schema(implementation = OrderStatus.class), travado por OpenApiDocsIT contra OrderStatus.values(); ErrorResponse documental com productIds (order) e available/requested (inventory)
- [Phase 07]: 07-09: ShipStock invalido -> InvalidShipStockException (ERROR + relanca -> DLQ); recover por nome em shipAll/releaseAll; IT baixa visibility para 1s so no caso da DLQ
- [Phase 07]: 07-10: matriz regra->teste (72 regras, 7 modulos) verificada por scripts/check-coverage-matrix.sh; sem LACUNA; E2eContextsSmokeIT atualizado para V4/V5
- [Phase 07]: 07-11: CI em ubuntu-24.04 com LocalStack em sequencia (max-parallel 1) e falha ::error:: sem o secret LOCALSTACK_AUTH_TOKEN; testcontainers.version removido do pom (1.21.4 vem do BOM do Spring Boot)

### Pending Todos

[From .planning/todos/pending/ — ideas captured during sessions]

None yet.

### Blockers/Concerns

[Issues that affect future work]

- [Fase 1]: LocalStack exige `LOCALSTACK_AUTH_TOKEN` (tier Hobby gratuito) desde 2026.03.0 — precisa estar no docker-compose e no CI desde o primeiro dia.
- [Fase 1]: Fixar versões das imagens Docker (LocalStack, Postgres) — `latest` causa divergência silenciosa de comportamento (PITFALLS.md #6).
- [Fase 5]: WR-03 do review não corrigido — `poll-timeout: 0s` desliga o long polling no `SqsAsyncClient` compartilhado (order e inventory); só afeta custo/latência no SQS real. Separar um cliente dedicado para os listeners faz a auto-configuração do Spring Cloud AWS recuar — tratar como backlog (candidato natural à Fase 7).
- [Fase 5]: Outbox sem retenção (OUTBOX_RETENTION=none-this-phase) e lápides de reserva sem limpeza (AR-05-02) — documentados como limitações conhecidas.
- [Fase 6]: Code review (06-REVIEW.md) com 3 warnings não corrigidos — WR-01 `ShipStock` inválido é descartado (ack) com o pedido já SHIPPED, perdendo a baixa sem DLQ; WR-02 `@Recover` de `shipAll`/`releaseAll` com assinaturas idênticas; WR-03 evento `ORDER_*` inválido descartado sem log com `orderId`. Candidatos naturais à Fase 7 (endurecimento) ou a `/gsd-code-review 6 --fix`.
- [Fase 6]: `workflow.security_enforcement` ligado e `06-SECURITY.md` ainda não existe — rodar `/gsd-secure-phase 6` antes de avançar.
- [Ambiente]: sessão LocalStack Hobby é única por token — Testcontainers falha (exit 126) com a stack do compose de pé; derrubar o compose antes de `./mvnw verify`.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260920-g6c | Adicionar springdoc-openapi (Swagger UI) em auth-service, catalog-service e inventory-service para visualizacao e teste rapido dos endpoints no navegador | 2026-09-20 | 94792e6 | [260920-g6c-adicionar-springdoc-openapi-swagger-ui-e](./quick/260920-g6c-adicionar-springdoc-openapi-swagger-ui-e/) |
| 260923-tj9 | Validar issuer (iss) do JWT nos resource servers notification/catalog/inventory — fecha T-03-02 / WR-07; testes passam a usar o decoder de produção | 2026-09-24 | eac39a8 | [260923-tj9-validar-issuer-do-jwt-nos-resource-serve](./quick/260923-tj9-validar-issuer-do-jwt-nos-resource-serve/) |
| 3 | README.md: incluir a porta 8085 (order-service) na lista de portas ligadas a 127.0.0.1 | 2026-09-26 | 4a9cff1 | — |

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-10-03T17:05:19.482Z
Stopped at: Completed 07-11-PLAN.md
Resume file: None
