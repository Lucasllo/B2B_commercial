# Phase 4: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito - Context

**Gathered:** 2026-09-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Nasce o `order-service` (porta 8085, schema `order` no Postgres compartilhado, rota `/api/orders/**` no Gateway, módulo no reactor e no `docker compose up`). O BUYER cria pedidos a partir do catálogo; o order-service valida os itens no catalog-service e lê o limite de crédito no auth-service, ambos de forma síncrona; o pedido é aprovado automaticamente dentro do limite ou fica em PENDING_APPROVAL para o SELLER_ADMIN aprovar/rejeitar. Listagem e detalhe respeitam o isolamento por empresa.

Requisitos: ORD-01, ORD-02, ORD-03, ORD-08, ORD-09.

**Fora desta fase:** reserva de estoque, Transactional Outbox e CONFIRMED/CANCELLED (Fase 5); transportadora, SHIPPED/DELIVERED e eventos de pedido no notification-service (Fase 6); identidade de serviço entre microsserviços (Fase 5, conforme javadoc do `InventoryController`).

</domain>

<decisions>
## Implementation Decisions

### Semântica do crédito
- **D-36:** O limite é de **exposição acumulada**: `soma dos pedidos que consomem crédito da empresa + total do novo pedido > credit_limit` → PENDING_APPROVAL; caso contrário (`<=`) → aprovado automaticamente. É o que dá sentido ao critério 5 do roadmap (concorrência na fronteira).
- **D-37:** Consomem crédito os pedidos em **APPROVED, CONFIRMED, SHIPPED e DELIVERED**. PENDING_APPROVAL **não** consome enquanto espera; REJECTED e CANCELLED não consomem (liberam). Sem pagamento modelado, DELIVERED continua consumindo. O cálculo deve ser uma consulta de soma (fonte única de verdade nos próprios pedidos), não um saldo desnormalizado. — **Reversibility:** costly — mudar a regra altera o cálculo, os testes de fronteira e o comportamento que a Fase 5/6 herdam.
- **D-38:** Pedido acima do limite **aprovado pelo vendedor passa a consumir crédito**, mesmo estourando o limite (o vendedor assume o risco). A aprovação manual **não** reavalia o limite nem altera o `credit_limit` no auth-service.

### Origem do limite e concorrência
- **D-39:** O limite é lido por **REST no auth-service**: `GET /companies/{companyId}/credit-limit` repassando o JWT do próprio BUYER (o `companyGuard.isSelfOrSeller` já permite). Nenhuma cópia local do limite e nenhum claim novo no JWT. Auth-service fora do ar → a criação falha com erro 503 claro, sem criar pedido.
- **D-40:** A checagem é serializada por empresa com uma **linha de trava**: tabela `company_credit_lock(company_id PK)` no schema `order`; a transação de criação faz upsert/`SELECT ... FOR UPDATE` nessa linha, soma a exposição, decide o status e insere o pedido — tudo na mesma transação. Empresas diferentes não se bloqueiam. Um teste de concorrência (pedidos paralelos da mesma empresa na fronteira) prova que no máximo um é aprovado automaticamente. — **Reversibility:** costly — o mecanismo de trava vira o padrão que a Fase 5 segue ao mudar status que afetam crédito.
- **D-41:** As chamadas HTTP (auth-service e catalog-service) acontecem **antes** de abrir a transação com trava — nunca segurar o lock durante I/O de rede.

### Validação no catálogo
- **D-42:** Um `GET /products/{id}` **por item**, repassando o JWT do BUYER. Como o catalog-service devolve 404 para produto DISCONTINUED quando quem pede é BUYER (D-24), isso cobre a D-19 sem lógica extra. Nenhum endpoint novo no catalog-service.
- **D-43:** Cada `order_item` grava um **snapshot**: `productId`, `sku`, `name`, `unitPrice` (BigDecimal 19,2 — D-06), `quantity` e `subtotal`; o pedido grava `total`. Preço/nome consultados no momento da criação; nunca recalculados depois. — **Reversibility:** one-way — o snapshot é a base contábil do pedido e do cálculo de crédito; remover depois exige migração e reconstrução de totais.
- **D-44:** **Tudo ou nada.** Pedido sem itens, `quantity <= 0` ou `productId` repetido → 400 de validação (formato `{"error","message","fields"}` já usado). Produto inexistente/descontinuado → erro de negócio (422, ou 409 a critério do planejador) listando os ids problemáticos; nenhum pedido é criado.

### Estados e decisão
- **D-45:** Dentro do limite, o pedido vai **CREATED → APPROVED na mesma transação**, com decisão registrada como automática (`decidedBy = SYSTEM`, `decidedAt`, motivo "dentro do limite de crédito"). Acima do limite: CREATED → PENDING_APPROVAL. A Fase 5 parte de APPROVED para disparar a reserva. Os status existentes nesta fase: CREATED, PENDING_APPROVAL, APPROVED, REJECTED (o enum pode já prever os demais da ORD-10, sem transições para eles).
- **D-46:** Decisão do vendedor por **dois endpoints**: `POST /orders/{id}/approve` (motivo opcional) e `POST /orders/{id}/reject` (motivo **obrigatório**). Só SELLER_ADMIN. Decidir pedido que não está em PENDING_APPROVAL → 409. Grava `decidedBy` (claim `sub` do JWT), `decidedAt` e `reason`. A aprovação manual também passa pela trava da empresa (D-40), porque muda a exposição.
- **D-47:** `GET /orders` paginado no envelope `Page<T>` do Spring Data (mesmo contrato do catálogo), ordenado por `createdAt desc`, com `?status=` opcional (fila de aprovação do vendedor). BUYER vê apenas pedidos da própria empresa (`company_id` do JWT); SELLER_ADMIN vê todos. `GET /orders/{id}` de outra empresa para um BUYER → **404** (não 403), para não revelar existência. Só BUYER cria pedidos; SELLER_ADMIN não cria.

### Claude's Discretion
- Timeouts das chamadas síncronas (RestClient) e se usa Resilience4j nesta fase — PITFALLS.md trata circuit breaker como nice-to-have; timeout explícito é obrigatório (lição do WR-03 da Fase 3).
- Formato exato de `OrderResponse`/`OrderSummary`, nomes de colunas, código HTTP do erro de produto inválido (422 vs 409).
- Dados/script de demonstração dos dois pedidos (abaixo e acima do limite) para o critério 2 — seguir o estilo do `scripts/smoke-notification-flow.sh`.
- Se o `companyId` do pedido vem só do JWT (recomendado: sim, nunca do corpo).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Escopo e requisitos
- `.planning/ROADMAP.md` §Phase 4 — goal e 5 critérios de sucesso (critério 5 = teste de concorrência com trava)
- `.planning/REQUIREMENTS.md` — ORD-01, ORD-02, ORD-03, ORD-08, ORD-09 (e ORD-10 para o enum de status)
- `.planning/PROJECT.md` §Key Decisions

### Decisões anteriores que se aplicam
- `.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-CONTEXT.md` — D-01 (schema `order` no Postgres compartilhado), D-02/D-03 (JWKS, validação local), D-05 (Gateway só roteia), D-06 (BigDecimal 19,2 inclusive totais de pedido)
- `.planning/phases/02-cat-logo-e-estoque/02-CONTEXT.md` — D-11 (reservationId do chamador, usado na Fase 5), D-16, D-19 (bloquear produto inativo é do order-service), D-23/D-24 (soft-delete; BUYER vê só ACTIVE)
- `.planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-CONTEXT.md` — D-28/D-29/D-30 (eventos e outbox na Fase 5), D-32 (orderId como partition key para eventos de pedido na Fase 6)

### Pesquisa de arquitetura
- `.planning/research/ARCHITECTURE.md` — catálogo por REST síncrono; estoque só por SQS/saga (reserva por REST direto é anti-padrão)
- `.planning/research/PITFALLS.md` — circuit breaker opcional; não adiar testes
- `.planning/research/STACK.md` — Resilience4j, RestClient, Testcontainers

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `auth-service/.../company/` — `GET /companies/{companyId}/credit-limit` → `CreditLimitResponse(UUID companyId, BigDecimal creditLimit)`; guard `@companyGuard.isSelfOrSeller`.
- `catalog-service/.../product/` — `GET /products/{id}` → `ProductResponse(id, sku, name, description, price, status, createdAt)`; 404 `product_not_found` para BUYER em DISCONTINUED.
- `*/support/TestJwt.java` (catalog/inventory/notification) — `sellerAdminToken()`, `buyerToken(UUID)` e customizer com chave em memória; copiar para o order-service.
- `*/AbstractIntegrationTest.java` — Postgres 16.15 singleton com `@ServiceConnection`, `@SpringBootTest(RANDOM_PORT)`, perfil `test`.
- `inventory-service/.../StockReservationConcurrencyIT.java` — modelo de teste de concorrência para o critério 5.

### Established Patterns
- Pacotes `com.orderflow.<svc>` com `config/` (GlobalExceptionHandler, SecurityConfig, OpenApiConfig), `<domain>/`, `<domain>/dto/` (records).
- Erro: `{"error": "snake_code", "message": "...", "fields"?: {...}}`.
- Security: `@EnableMethodSecurity`, claim `role` → `ROLE_<role>`, claim `company_id` para BUYER, `sub` = UUID do usuário, `iss = orderflow-auth-service` (validado via `issuer-uri` + `jwk-set-uri`).
- Flyway `db/migration/V1__init_order_schema.sql`, `ddl-auto: validate`, `currentSchema=order` / `default_schema`.
- Swagger via springdoc em cada serviço (quick 260920-g6c).

### Integration Points
- `pom.xml` raiz (novo módulo `order-service`), `docker-compose.yml` (serviço em `127.0.0.1:8085`, env de datasource/JWK/URLs de auth e catalog, healthcheck curl, `depends_on` do gateway), `gateway/src/main/resources/application.yml` (rota `/api/orders/**` com `StripPrefix=1`).
- Chamadas de saída: `http://auth-service:8081` e `http://catalog-service:8082` (configuráveis por propriedade; WireMock ou MockRestServiceServer nos testes).

</code_context>

<specifics>
## Specific Ideas

- Critério 2 do roadmap: dois pedidos de demonstração da mesma empresa, um abaixo e outro acima do limite restante, mostrando APPROVED e PENDING_APPROVAL.
- Critério 5: o teste deve disparar pedidos paralelos cuja soma ultrapassa o limite mas cada um cabe sozinho — no máximo um sai APPROVED, o(s) outro(s) PENDING_APPROVAL.

</specifics>

<deferred>
## Deferred Ideas

- Endpoint em lote no catalog-service (`GET /products?ids=`) — só se o número de chamadas por pedido virar problema.
- Evento de alteração de limite / cópia local do limite no order-service — descartado agora (D-39).
- Circuit breaker completo (Resilience4j) nas chamadas síncronas — pode entrar na Fase 7 (endurecimento).
- Eventos de ciclo de vida do pedido no notification-service — Fase 6.

</deferred>

---

*Phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito*
*Context gathered: 2026-09-24*
