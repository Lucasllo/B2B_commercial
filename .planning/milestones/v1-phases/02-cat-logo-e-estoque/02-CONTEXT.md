# Phase 2: Catálogo e Estoque - Context

**Gathered:** 2026-09-19
**Status:** Ready for planning

<domain>
## Phase Boundary

O vendedor (SELLER_ADMIN) mantém o catálogo de produtos (criar/atualizar nome, preço, descrição, SKU, status) e os níveis de estoque por produto. O comprador (BUYER) autenticado enxerga o catálogo de produtos ativos e consulta a disponibilidade de estoque. A reserva de estoque nasce protegida contra concorrência via lock otimista com retry — provada por um teste de concorrência real contra as últimas unidades de um produto. Catalog-service e inventory-service são dois microsserviços novos, cada um com seu próprio schema Postgres, subindo no mesmo `docker-compose up` das fases anteriores, protegidos por JWT (papel correto por operação). Nenhuma mensageria (SQS/saga) entra nesta fase — isso é Fase 3 (mensageria isolada) e Fase 5 (saga).

</domain>

<decisions>
## Implementation Decisions

### Semântica de Reserva de Estoque
- **D-08:** Estoque modelado com duas colunas — `quantity_on_hand` e `quantity_reserved` — em vez de uma quantidade única. Disponível = `on_hand - reserved`. — **Reversibility:** costly — migrar de campo único para essas duas colunas depois de haver dados reais exigiria migração de schema e recálculo de disponibilidade.
- **D-09:** O inventory-service expõe endpoints de **reservar** (incrementa `reserved`) e **liberar** (decrementa `reserved`) nesta fase. Não há endpoint de "confirmar saída" (decrementar `on_hand`) — isso só entra na Fase 5, quando o pedido de fato sai do estoque via saga. — **Reversibility:** reversible — adicionar o endpoint de confirmação depois é aditivo.
- **D-10:** Reservar mais do que o disponível (`on_hand - reserved`) retorna **409 Conflict** com corpo explicando a insuficiência (disponível X, solicitado Y) — distinto semanticamente de erro de validação de payload (400). — **Reversibility:** reversible — mudar o código de erro depois é uma alteração de contrato pequena, ainda sem consumidores externos além dos testes desta fase.
- **D-11:** O endpoint de reservar aceita um `reservation_id` **fornecido pelo chamador**, não gerado internamente. A operação de reservar (e de liberar) é idempotente por esse ID: reenviar a mesma reserva não duplica o decremento de `reserved`. — **Reversibility:** one-way — este ID vai ser o mesmo mecanismo de idempotência que a saga da Fase 5 (TEST-03, consumidor idempotente da reentrega de evento SQS) vai depender; mudar para geração interna depois quebraria esse contrato já em uso por outro serviço.
- **D-12:** Estoque modelado por **produto único**, sem conceito de depósito/local/armazém (múltiplos depósitos está fora do escopo desta fase). — **Reversibility:** costly — introduzir uma dimensão de local depois exigiria migração de schema (chave composta produto+local) em uma tabela já populada.
- **D-13:** Sem TTL/expiração automática de reservas órfãs nesta fase — reserva fica pendente até liberação explícita. Mecanismos de limpeza fazem sentido quando a Fase 5 introduzir falhas reais de rede/timeout via SQS. — **Reversibility:** reversible — adicionar um job de expiração depois é aditivo.
- **D-14:** Liberar uma reserva já liberada (ou inexistente) é **idempotente** — retorna sucesso, no-op silencioso. Mesmo princípio de idempotência aplicado à reserva (D-11). — **Reversibility:** reversible.

### Acoplamento Catalog-Inventory
- **D-15:** O inventory-service **não valida sincronamente** (via HTTP) que um `product_id` existe no catalog-service. `product_id` é tratado como referência opaca. Zero chamada síncrona de escrita entre os dois serviços nesta fase. — **Reversibility:** reversible — adicionar validação síncrona depois (com Resilience4j) é aditivo, não quebra o que já existe.
- **D-16:** A listagem de catálogo do BUYER e a consulta de disponibilidade de estoque são **duas chamadas HTTP separadas** pelo Gateway (`GET /catalog/products` e `GET /inventory/{productId}`) — sem enriquecimento síncrono de um serviço pelo outro. — **Reversibility:** reversible — compor os dois numa única resposta depois (ex.: no order-service da Fase 4) é aditivo.
- **D-17:** Criar um produto no catalog-service **não cria** automaticamente um registro de estoque no inventory-service. O SELLER_ADMIN define a quantidade em estoque manualmente, numa chamada separada (INV-01). — **Reversibility:** reversible.
- **D-18:** Definir/atualizar a quantidade de estoque de um produto é **upsert** (cria a linha de inventário na primeira definição). Reservar contra um `product_id` sem linha de estoque retorna **404**. — **Reversibility:** reversible.
- **D-19:** Inativar/descontinuar um produto no catalog-service **não propaga** automaticamente para o inventory-service (nenhum bloqueio automático de reservas). Bloquear pedidos de produtos inativos é responsabilidade do order-service na Fase 4, que já vai validar itens contra o catalog-service. — **Reversibility:** reversible.

### Conflito de Lock Otimista
- **D-20:** Conflito de lock otimista (JPA `@Version`) em reservar/liberar dispara **retry automático interno** (Spring Retry, `@Retryable`, limite curto de 3–5 tentativas) — o chamador não precisa tratar o conflito de lock como erro de negócio. — **Reversibility:** reversible — trocar a estratégia de retry depois não afeta contratos externos.
- **D-21:** Se todas as tentativas de retry se esgotarem, a API retorna **503/409 com mensagem de "tente novamente"**, distinto do 409 de estoque insuficiente (D-10). — **Reversibility:** reversible.
- **D-22:** O teste de concorrência do Success Criteria 3 (última unidade, requisições paralelas) usa **HTTP real concorrente via Testcontainers** com virtual threads do Java 21, exercitando a pilha completa (controller, filtros de segurança, transação) — não uma chamada direta ao service/repository em threads. — **Reversibility:** reversible — decisão de estratégia de teste, não de produção.

### Modelo de Produto e Visibilidade de Estoque
- **D-23:** Produto tem, além de nome/preço/descrição (mínimo de CAT-01), um **SKU** (código definido pelo vendedor) e um **status** `active`/`discontinued`. Remoção de produto é sempre soft-delete via status — nunca `DELETE` físico, para preservar histórico quando pedidos (Fase 4+) referenciarem o produto. — **Reversibility:** one-way — remover o campo status ou trocar soft-delete por hard-delete depois de haver pedidos reais referenciando produtos quebraria a integridade referencial desses pedidos.
- **D-24:** A listagem de catálogo do BUYER filtra `status=active` por padrão. O SELLER_ADMIN vê todos os produtos (ativos e descontinuados) para gerenciar o catálogo. — **Reversibility:** reversible.
- **D-25:** O BUYER vê a **quantidade exata disponível** (`on_hand - reserved`) ao consultar estoque — não apenas um indicador disponível/indisponível. Cenário B2B/atacado: o comprador precisa do número para dimensionar a compra. — **Reversibility:** reversible.
- **D-26:** A listagem de catálogo aceita paginação (`page`/`size`, Spring Data `Pageable`) desde esta fase. — **Reversibility:** reversible.

### Herdado da Fase 1 (aplica-se aqui)
- Preço do produto usa `BigDecimal` com escala fixa de 2 casas, mapeado para `NUMERIC(19,2)` no Postgres (mesma convenção de D-06 da Fase 1, aplicada agora a `price` em vez de apenas ao limite de crédito).
- Catalog-service e inventory-service usam a mesma instância Postgres compartilhada, cada um com seu próprio schema (`catalog`, `inventory`) — convenção D-01 da Fase 1.
- Cada serviço valida o JWT localmente (OAuth2 Resource Server, JWKS do auth-service) — nenhuma chamada síncrona ao auth-service por requisição, conforme D-02/D-05 da Fase 1.

### Claude's Discretion
- Nome exato dos endpoints REST (ex.: `POST /products`, `PUT /products/{id}/stock`) — decisão de design de API, não afeta arquitetura.
- Estrutura exata de pacotes Java dentro de `com.orderflow.catalog` / `com.orderflow.inventory` — seguir a convenção já usada em `com.orderflow.auth` (domínio + `dto` por domínio, `config`).
- Formato exato da mensagem de erro 409 (JSON body com `available`/`requested`) — definir no planejamento.
- Valor exato do limite de tentativas de retry do Spring Retry (3 vs 5) e backoff — definir no planejamento com base em quão agressivo o teste de concorrência precisa ser.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Stack e decisões técnicas travadas
- `.claude/CLAUDE.md` §Technology Stack — Java 21, Spring Boot 3.5.x, Resilience4j (circuit breaker/retry para chamadas síncronas), Flyway, Testcontainers, Spring Data JPA.

### Escopo e critérios de aceitação da fase
- `.planning/ROADMAP.md` §Phase 2: Catálogo e Estoque — Goal, Requirements (CAT-01, CAT-02, INV-01, INV-02) e os 4 Success Criteria (inclui o teste de concorrência do critério 3).
- `.planning/REQUIREMENTS.md` §Catalog / §Inventory — texto completo de CAT-01, CAT-02, INV-01, INV-02 (INV-02 já trava "lock otimista" como mecanismo).

### Contexto e restrições do projeto
- `.planning/PROJECT.md` §Constraints — stack, persistência, processo incremental.
- `.planning/PROJECT.md` §Key Decisions — decomposição em 5 microsserviços; catalog-service e inventory-service são serviços distintos (não fundidos).
- `.planning/STATE.md` §Blockers/Concerns — fixar versões de imagem Docker (sem `latest`).

### Decisões herdadas da Fase 1 (base assumida pronta)
- `.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-CONTEXT.md` — D-01 (Postgres único, schema por serviço), D-02/D-03/D-05 (validação de JWT local, sem chamada ao auth-service), D-06 (BigDecimal/NUMERIC(19,2) para valores monetários).
- `docker-compose.yml` (raiz do repo) — padrão de serviço: `build.dockerfile`, `depends_on` com `condition: service_healthy`, `SPRING_DATASOURCE_URL` com `currentSchema=<schema>`, healthcheck via `/actuator/health`.
- `auth-service/src/main/java/com/orderflow/auth/` — convenção de pacotes por domínio (`auth`, `company`, `user`) com subpasta `dto` e `config` — seguir o mesmo padrão em `catalog-service` e `inventory-service`.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- Reactor Maven multi-módulo já configurado (`pom.xml` raiz, `mvnw` commitado) — novos módulos `catalog-service` e `inventory-service` entram como módulos irmãos de `auth-service` e `gateway`.
- Padrão de Dockerfile fixado em `eclipse-temurin:21.x.x` já estabelecido em `auth-service/Dockerfile` e `gateway/Dockerfile` — replicar para os dois novos serviços.
- Padrão de migração Flyway (`V1__init_*_schema.sql`) já estabelecido em `auth-service` — cada novo serviço tem sua própria pasta `db/migration`.

### Established Patterns
- Pacotes por domínio com `dto` aninhado (`com.orderflow.auth.company.dto.CreateCompanyRequest`) — replicar em `com.orderflow.catalog.product.dto.*` e `com.orderflow.inventory.stock.dto.*` (nomes exatos a definir no planejamento).
- Validação de JWT via Spring Security OAuth2 Resource Server, sem chamada síncrona ao auth-service — cada novo serviço replica a configuração de `auth-service`/`gateway`.
- `docker-compose.yml` usa `depends_on` + `service_healthy` + healthcheck via `/actuator/health` em todo serviço novo.

### Integration Points
- Gateway precisa de novas rotas estáticas para `catalog-service` e `inventory-service` (mesmo padrão de rota estática por serviço já usado para `auth-service`).
- README.md precisa ser atualizado com os novos endpoints de demonstração (padrão já estabelecido na Fase 1: credenciais + comandos de verificação).

</code_context>

<specifics>
## Specific Ideas

- Estoque com `quantity_on_hand` + `quantity_reserved`, nunca campo único — decisão explícita pensando na saga da Fase 5.
- `reservation_id` fornecido pelo chamador desde já, para nascer idempotente.
- SKU + status `active`/`discontinued` no produto — soft-delete sempre, nunca `DELETE` físico.
- Teste de concorrência da Fase 2 deve ser HTTP real via Testcontainers + virtual threads, não uma chamada direta ao service em threads — força probatória maior para o avaliador externo.
- BUYER vê quantidade exata de estoque (não booleano disponível/indisponível) — cenário B2B/atacado.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 2-Catálogo e Estoque*
*Context gathered: 2026-09-19*
