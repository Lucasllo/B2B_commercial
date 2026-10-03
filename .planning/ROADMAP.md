# Roadmap: OrderFlow

## Overview

O projeto avança como um esqueleto vertical que engorda a cada fase: primeiro tudo sobe junto no `docker-compose` e um usuário real consegue se autenticar (Fase 1); depois o vendedor passa a ter catálogo e estoque de verdade, já protegidos contra concorrência (Fase 2); então a mensageria é provada no cenário mais simples possível — um evento no SQS virando histórico no DynamoDB (Fase 3) — antes de qualquer coisa difícil depender dela. Com o encanamento validado, o pedido nasce com sua regra de negócio central, a aprovação por limite de crédito (Fase 4), e só então a saga de reserva de estoque com Transactional Outbox e compensação é construída (Fase 5) — o Core Value do projeto, deliberadamente isolado numa fase própria porque é a parte de maior risco e maior valor demonstrativo. As duas últimas fases fecham o ciclo de vida do pedido até a entrega (Fase 6) e deixam o projeto apresentável a um avaliador externo com OpenAPI, Correlation-ID, testes completos, CI e ADRs (Fase 7).

Cada fase é uma fatia vertical: ao final dela, `docker-compose up` sobe o sistema e existe algo novo que pode ser exercitado por uma chamada REST — nunca uma fase que produz só código sem nada observável.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Esqueleto Vertical — Infraestrutura, Autenticação e Empresas** - Todo o sistema sobe com um comando e um usuário real se autentica pelo Gateway recebendo um JWT com papel e empresa (completed 2026-09-19)
- [x] **Phase 2: Catálogo e Estoque** - O vendedor mantém produtos e níveis de estoque, o comprador enxerga o catálogo, e a reserva já é atômica contra concorrência (completed 2026-09-20)
- [x] **Phase 3: Primeira Integração Assíncrona — Histórico de Notificações** - Um evento publicado no SQS vira registro consultável no DynamoDB, provando o encanamento antes da saga (completed 2026-09-23)
- [x] **Phase 4: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito** - O comprador cria pedidos do catálogo e a regra de aprovação por crédito funciona, ainda sem saga (completed 2026-09-25)
- [x] **Phase 5: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação** - O Core Value: reserva assíncrona com Transactional Outbox terminando sempre em CONFIRMED ou CANCELLED (completed 2026-09-30)
- [x] **Phase 6: Ciclo de Vida Completo — Expedição, Entrega e Histórico do Pedido** - Transportadora simulada, rastreio, SHIPPED/DELIVERED e a linha do tempo completa nas notificações (completed 2026-10-01)
- [ ] **Phase 7: Endurecimento, Observabilidade e Entrega** - OpenAPI por serviço, Correlation-ID nos logs, testes completos, pipeline de CI verde e ADRs em português

## Phase Details

### Phase 1: Esqueleto Vertical — Infraestrutura, Autenticação e Empresas

**Goal**: Todo o sistema sobe com um único `docker-compose up` e um usuário real (vendedor ou comprador) se autentica pelo API Gateway recebendo um JWT com papel e empresa, com as empresas compradoras e seus limites de crédito já persistidos — a base que todas as fases seguintes assumem pronta.
**Mode:** mvp
**Depends on**: Nothing (first phase)
**Requirements**: INFRA-01, AUTH-01, AUTH-02, AUTH-03, COMP-01, COMP-02, COMP-03
**Success Criteria** (what must be TRUE):

  1. A partir de um clone limpo, `docker-compose up` sobe API Gateway + auth-service + PostgreSQL + LocalStack (todas as imagens com versão fixada, sem `latest`) e todos respondem saudáveis no health check.
  2. O vendedor (SELLER_ADMIN) cria, via REST pelo Gateway, uma empresa compradora com nome e limite de crédito e um usuário BUYER vinculado a ela.
  3. Um usuário faz login com email/senha e recebe um JWT assinado contendo papel (BUYER/SELLER_ADMIN) e, para compradores, o ID da empresa; token inválido ou expirado é rejeitado com 401 sem nenhuma chamada em tempo de execução ao auth-service.
  4. O vendedor consulta e atualiza o limite de crédito de uma empresa, e um BUYER que tenta acessar dados de outra empresa recebe erro de autorização — isolamento por empresa comprovado por teste, não apenas por convenção.

**Plans**: TBD

### Phase 2: Catálogo e Estoque

**Goal**: O vendedor mantém o catálogo de produtos e os níveis de estoque, o comprador autenticado enxerga o catálogo disponível, e a reserva de estoque já nasce protegida contra concorrência — domínio e persistência sólidos antes de qualquer mensageria entrar em cena.
**Mode:** mvp
**Depends on**: Phase 1
**Requirements**: CAT-01, CAT-02, INV-01, INV-02
**Success Criteria** (what must be TRUE):

  1. O SELLER_ADMIN cria e atualiza produtos (nome, preço, descrição) e o BUYER lista e consulta produtos e preços pelo Gateway — cada operação restrita ao papel correto pelo JWT da Fase 1.
  2. O vendedor define e atualiza a quantidade em estoque por produto e consulta a disponibilidade atual de qualquer item.
  3. A operação de reserva de estoque é atômica: um teste de concorrência disparando requisições paralelas contra as últimas unidades de um produto nunca reserva mais do que o disponível.
  4. Catalog-service e inventory-service sobem no mesmo `docker-compose up` das fases anteriores, cada um com seu próprio banco, acessíveis somente com JWT válido.

**Plans:** 3/3 plans complete

Plans:
**Wave 1**

- [x] 02-01-PLAN.md — catalog-service: o vendedor mantém o catálogo (cria, atualiza, descontinua por soft-delete) e o comprador lista produtos ativos paginados (CAT-01, CAT-02)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 02-02-PLAN.md — inventory-service: o vendedor define os níveis de estoque e a reserva nasce atômica por lock otimista e idempotente por identificador do chamador (INV-01, INV-02)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 02-03-PLAN.md — catálogo e estoque no `docker compose up` e nas rotas do Gateway, mais a prova de atomicidade por HTTP real e concorrência real (INV-02, CAT-02)

### Phase 3: Primeira Integração Assíncrona — Histórico de Notificações

**Goal**: Provar a mensageria ponta a ponta no cenário mais simples possível — um serviço publica um evento no SQS (LocalStack), o notification-service consome e grava um registro consultável no DynamoDB — de modo que, quando a saga depender desse encanamento, ele já esteja validado e depurado isoladamente.
**Mode:** mvp
**Depends on**: Phase 2
**Requirements**: NOTF-01, NOTF-02
**Success Criteria** (what must be TRUE):

  1. Um evento de ciclo de vida publicado por um serviço já existente (ex.: ajuste de estoque) aparece como registro no DynamoDB do notification-service em segundos, sem nenhuma chamada REST entre os dois serviços.
  2. O histórico de notificações pode ser consultado por identificador através de um endpoint do notification-service acessado pelo Gateway.
  3. O registro é gravado com chave determinística (ex.: `id#tipoDeEvento`), de modo que uma reentrega do mesmo evento sobrescreve o registro em vez de duplicar o histórico.
  4. Filas SQS e tabela DynamoDB são criadas automaticamente na subida do LocalStack, sem passo manual, e um teste de integração com Testcontainers + LocalStack exercita o fluxo real de publicação e consumo.

**Plans:** 3/3 plans complete

Plans:
**Wave 1**

- [x] 03-01-PLAN.md — notification-service: evento na fila do LocalStack vira histórico no DynamoDB com chave determinística (reentrega sobrescreve, mensagem venenosa descartada com log), consulta `GET /notifications/{productId}` restrita a SELLER_ADMIN, init hook do LocalStack e módulo no reactor (NOTF-01, NOTF-02)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 03-02-PLAN.md — inventory-service: o ajuste de estoque publica `STOCK_ADJUSTED` direto no SQS depois do commit, sem outbox (D-29), com a quantidade anterior capturada na transação e falha de publicação registrada em log sem derrubar o ajuste (D-30) (NOTF-01)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 03-03-PLAN.md — notification-service no `docker compose up` e atrás do Gateway, LocalStack saudável só com fila e tabela provisionadas, smoke ponta a ponta na stack real (fluxo, reentrega, ajustes distintos), Swagger do serviço novo e README com a limitação de dual-write (NOTF-01, NOTF-02)

### Phase 4: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito

**Goal**: O comprador cria pedidos reais a partir do catálogo e a regra de negócio central do B2B funciona: acima do limite de crédito da empresa o pedido para em PENDING_APPROVAL e espera o vendedor, abaixo do limite segue adiante — tudo ainda sem saga, para que a lógica de aprovação seja verificável sozinha.
**Mode:** mvp
**Depends on**: Phase 3
**Requirements**: ORD-01, ORD-02, ORD-03, ORD-08, ORD-09
**Success Criteria** (what must be TRUE):

  1. Um BUYER cria um pedido selecionando produtos e quantidades do catálogo; o order-service consulta o catalog-service de forma síncrona para validar itens e calcular o total, e o pedido nasce em CREATED.
  2. Pedido cujo total ultrapassa o limite de crédito da empresa entra em PENDING_APPROVAL, e pedido abaixo do limite segue automaticamente rumo à confirmação — comprovado por dois pedidos de demonstração com totais diferentes.
  3. O SELLER_ADMIN aprova ou rejeita um pedido pendente pelo Gateway, e o pedido passa a registrar quem decidiu, quando e o motivo (APPROVED/REJECTED).
  4. O comprador lista e abre o detalhe apenas dos pedidos da própria empresa; o vendedor lista e abre o detalhe de todos os pedidos.
  5. Dois pedidos concorrentes do mesmo comprador na fronteira do limite de crédito não passam ambos na verificação — checagem transacional com bloqueio comprovada por teste.

**Plans:** 5/5 plans complete

Plans:
**Wave 1**

- [x] 04-01-PLAN.md — order-service nasce: o BUYER cria pedido validado por item no catalog-service e decidido contra o limite lido no auth-service (JWT repassado, I/O antes da transação), APPROVED ou PENDING_APPROVAL sob a trava `company_credit_lock`, snapshot dos itens, `GET /orders/{id}` com escopo de empresa, e a prova por socket real do Success Criteria 5 (ORD-01, ORD-02, ORD-08, ORD-09)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 04-02-PLAN.md — endurecimento da criação: tudo ou nada (repetido 400, itens inválidos 422), falha fechada 503 com vizinho quebrado/lento/inalcançável, limites de entrada, total fora da faixa, tokens adversariais, snapshot congelado e testes unitários da regra de crédito (ORD-01, ORD-02)
- [x] 04-03-PLAN.md — `GET /orders` paginado: BUYER só a própria empresa, SELLER_ADMIN todas, `createdAt` decrescente imposto pelo servidor, `?status=` como fila de aprovação (ORD-08, ORD-09)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 04-04-PLAN.md — o SELLER_ADMIN aprova (motivo opcional) ou rejeita (motivo obrigatório) pedido pendente pela mesma trava, com `decidedBy`/`decidedAt`/`reason`, 409 fora de PENDING_APPROVAL e corridas de decisão provadas (ORD-03, ORD-02)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 04-05-PLAN.md — order-service no `docker compose up` e atrás do Gateway, smoke ponta a ponta na stack real (dois pedidos de demonstração, decisão do vendedor, produto descontinuado recusado pelo catálogo real, isolamento), Swagger e documentação da fase (ORD-01, ORD-02, ORD-03, ORD-08, ORD-09)

### Phase 5: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação

**Goal**: Entregar o Core Value do projeto — ao seguir para confirmação, o order-service publica o comando de reserva pelo padrão Transactional Outbox, o inventory-service reserva o estoque de forma idempotente e devolve o resultado, e o pedido termina sempre em CONFIRMED ou CANCELLED, nunca preso num estado intermediário.
**Mode:** mvp
**Depends on**: Phase 4
**Requirements**: ORD-04, ORD-05, ORD-06, TEST-03
**Success Criteria** (what must be TRUE):

  1. Ao confirmar um pedido, o evento de reserva é gravado numa tabela outbox dentro da mesma transação da mudança de status, e só é publicado no SQS depois do commit — nunca existe pedido avançado sem evento correspondente, nem evento sem a mudança de estado que o originou.
  2. Com estoque suficiente, o pedido percorre o estado intermediário de reserva até CONFIRMED automaticamente, e a quantidade reservada aparece refletida no inventory-service.
  3. Com estoque insuficiente, o inventory-service devolve o evento de falha e o pedido termina em CANCELLED com motivo registrado — o teste do caminho de falha é escrito antes do caminho feliz, e forçar "estoque insuficiente" nunca deixa o pedido travado.
  4. Reentregar o mesmo comando de reserva duas vezes decrementa o estoque uma única vez — consumidor idempotente comprovado por teste que republica o evento.
  5. Um teste E2E com Testcontainers (PostgreSQL + LocalStack reais) percorre o fluxo completo criar → reservar → confirmar e também o caminho de falha → cancelar, executável por um único comando.

**Plans:** 6/6 plans complete

Plans:
*Todos em sequência — a sessão do LocalStack Hobby é única por token e todo plano tem testes com LocalStack.*

**Wave 1**

- [x] 05-01-PLAN.md — order-service: aprovação automática e manual entram na saga pelo mesmo ponto — RESERVING + `ReserveStock` no outbox na mesma transação, relay `SKIP LOCKED` até a `inventory-commands-queue`, filas com DLQ no init hook, pedidos APPROVED legados migrados, compose ligado ao LocalStack (ORD-04)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 05-02-PLAN.md — inventory-service: consome `ReserveStock`, reserva tudo ou nada com `reserveAll` (`@Retryable` + `@Transactional` no próprio método), idempotente pelo livro `stock_reservations`, e responde `StockReserved`/`StockReservationFailed` pelo próprio outbox — falha provada antes do sucesso (ORD-06, ORD-05)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 05-03-PLAN.md — order-service: consome o resultado e leva RESERVING a CANCELLED (código + motivo legível, primeiro) ou CONFIRMED, guardado pelo estado, com `ReleaseStock` para sucesso tardio (ORD-05, ORD-06)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 05-04-PLAN.md — "nunca preso" e fim do dual-write: job de timeout cancela e compensa com `ReleaseStock`, liberação idempotente com lápide contra a corrida da fila padrão (FK removida), `STOCK_ADJUSTED` pelo outbox do inventory (ORD-05, ORD-06, ORD-04)

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 05-05-PLAN.md — módulo `e2e-tests`: os dois serviços reais no mesmo JVM contra Postgres e LocalStack reais, spike de configuração, falha → CANCELLED antes de criar → reservar → confirmar, republicação idempotente e aprovação manual, em `./mvnw -B -pl e2e-tests -am verify` (TEST-03)

**Wave 6** *(blocked on Wave 5 completion)*

- [x] 05-06-PLAN.md — stack real: `scripts/smoke-order-saga.sh` pelo Gateway, `smoke-order-flow.sh` ajustado a RESERVING, e README/docs explicando a saga e as limitações da fase (ORD-04, ORD-05, TEST-03)

### Phase 6: Ciclo de Vida Completo — Expedição, Entrega e Histórico do Pedido

**Goal**: Fechar o ciclo do pedido de ponta a ponta — o pedido confirmado recebe transportadora simulada e código de rastreio, avança para SHIPPED e DELIVERED, e cada transição vira um registro consultável no histórico de notificações, tornando toda a jornada visível para quem estiver avaliando o projeto.
**Mode:** mvp
**Depends on**: Phase 5
**Requirements**: ORD-07, ORD-10
**Success Criteria** (what must be TRUE):

  1. Ao ser confirmado, o pedido recebe automaticamente transportadora e código de rastreio de um mock interno do order-service, ambos visíveis no detalhe do pedido.
  2. O vendedor marca o pedido como SHIPPED e depois DELIVERED, e transições inválidas (ex.: pular direto de CREATED para DELIVERED, ou alterar um pedido CANCELLED) são rejeitadas com erro claro.
  3. Consultando o histórico de notificações de um pedido, aparece a linha do tempo completa: criado, aprovado (quando houve aprovação), confirmado ou cancelado, enviado e entregue.
  4. O fluxo de status CREATED → PENDING_APPROVAL → APPROVED/REJECTED → CONFIRMED → SHIPPED → DELIVERED (ou CANCELLED) está documentado com diagrama e corresponde exatamente ao comportamento real da API.

**Plans:** 7/7 plans complete

Plans:
*Todos em sequência — a sessão do LocalStack Hobby é única por token e todo plano com IT usa LocalStack; 06-07 vem por último para documentar o que já foi demonstrado.*

**Wave 1**

- [x] 06-01-PLAN.md — order-service: CONFIRMED recebe transportadora simulada (`CarrierGateway`) e rastreio S10 na mesma transação, V3 com colunas de expedição/entrega, backfill e CHECK, `OrderResponse` com seis campos novos, e tabela única de 9 transições em `OrderStatus` consultada pelo domínio e pela saga (ORD-07, ORD-10)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 06-02-PLAN.md — order-service: `POST /orders/{id}/ship` (com `ShipStock` no outbox) e `/deliver` só para o vendedor, 409 `invalid_order_transition`, e prova de que cada status × ação da API segue a tabela (ORD-10)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 06-03-PLAN.md — inventory-service: consome `ShipStock`, baixa `quantity_on_hand` e `quantity_reserved` pelo livro uma única vez (V4), anomalias como erro técnico e liberações que ignoram reservas expedidas (ORD-10)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 06-04-PLAN.md — notification-service: partição genérica `entityId`, os oito eventos `ORDER_*` validados com mensagem legível, ordem de ciclo de vida e `GET /notifications/orders/{orderId}` com regra SELLER/BUYER (404 para outra empresa) (ORD-10)

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 06-05-PLAN.md — order-service: toda transição persistida grava seu evento `ORDER_*` no outbox e o relay os entrega na `notification-events-queue` existente (ORD-10)

**Wave 6** *(blocked on Wave 5 completion)*

- [x] 06-06-PLAN.md — stack real: `scripts/smoke-order-lifecycle.sh` pelo Gateway (jornada completa, caminhos tristes e recusas) e E2E `OrderShipmentE2EIT` (expedição baixa o estoque), reactor inteiro verde (ORD-07, ORD-10)

**Wave 7** *(blocked on Wave 6 completion)*

- [x] 06-07-PLAN.md — diagrama Mermaid no README e na visão geral conferido por teste contra a tabela, README e `docs/API.md` com endpoints, contratos, smoke e limitações da Fase 6 (ORD-07, ORD-10)

### Phase 7: Endurecimento, Observabilidade e Entrega

**Goal**: Deixar o projeto apresentável e verificável por um avaliador externo — documentação OpenAPI de cada serviço, rastreabilidade por Correlation-ID nos logs, cobertura de testes unitários e de integração sem lacunas, pipeline de CI verde a cada push e as decisões arquiteturais registradas como ADRs em português explicando o "porquê" de cada escolha.
**Mode:** mvp
**Depends on**: Phase 6
**Requirements**: QUAL-01, QUAL-02, INFRA-02, INFRA-03, TEST-01, TEST-02
**Success Criteria** (what must be TRUE):

  1. Cada serviço expõe documentação OpenAPI/Swagger navegável pelo Gateway, com os status do pedido e os contratos de requisição/resposta descritos.
  2. Uma requisição que atravessa Gateway → order-service → SQS → inventory-service → notification-service pode ser seguida pelo mesmo Correlation-ID em todos os logs envolvidos.
  3. Um push no GitHub dispara o pipeline que builda e testa todos os serviços — incluindo os testes com Testcontainers/LocalStack — e falha visivelmente quando algum teste quebra.
  4. Todo serviço tem testes unitários cobrindo suas regras de negócio centrais e ao menos um teste de integração contra dependência real (PostgreSQL ou LocalStack), sem lacunas herdadas das fases anteriores.
  5. As decisões-chave (saga por orquestração em vez de coreografia, outbox em vez de publicação direta, LocalStack em vez de AWS real, ausência deliberada de service discovery/config server) estão registradas como ADRs em português, cada uma com ao menos uma alternativa rejeitada e o motivo.

**Plans:** 11/11 plans executed

Plans:
*No máximo um plano com Testcontainers LocalStack por onda (sessão única do LocalStack Hobby por token, D-101); planos sem LocalStack rodam em paralelo, sem módulo Maven em comum na mesma onda.*

**Wave 1**

- [x] 07-01-PLAN.md — gateway: Correlation-ID nasce/valida no Gateway (header único no pedido e na resposta, MDC, log de acesso), Swagger UI única com rotas `/docs/<svc>/v3/api-docs`, URIs parametrizadas e primeiros testes do gateway (unitário + IT); estudos 27 e 28 (QUAL-01, QUAL-02, TEST-01, TEST-02)
- [x] 07-02-PLAN.md — order-service: Correlation-ID HTTP → MDC → coluna no outbox (V4) → atributo SQS `correlationId`, listener de resultados com MDC, timeout herda `orders.correlation_id`, interceptor do `RestClient` (QUAL-02, TEST-01, TEST-02)
- [x] 07-03-PLAN.md — catalog e auth: OpenAPI "contratos + erros" com `ErrorResponse`, `server` `/api`, login público e JWKS oculto, travado por `OpenApiDocsIT` (QUAL-01)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 07-04-PLAN.md — inventory-service: atributo SQS → MDC no consumidor, outbox com Correlation-ID (V5), filtro HTTP e E2E `CorrelationIdE2EIT` provando o mesmo ID em order e inventory (QUAL-02, TEST-01, TEST-02)
- [x] 07-05-PLAN.md — catalog e auth: filtro de Correlation-ID recebendo o ID repassado pelo order-service e testes unitários das regras centrais (`ProductService`, `CompanyService`, `CompanyGuard`, `TokenService`) (QUAL-02, TEST-01, TEST-02)
- [x] 07-06-PLAN.md — 11 ADRs MADR em português (os 4 do critério 5 primeiro), índice e `scripts/check-adrs.sh`; gate de API externa (INFRA-03)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 07-07-PLAN.md — notification-service: Correlation-ID do atributo SQS nos logs de recebimento/registro, filtro HTTP, WR-03 (descarte com `orderId`/`eventType`) e OpenAPI (QUAL-01, QUAL-02, TEST-01, TEST-02)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 07-08-PLAN.md — order e inventory: OpenAPI "contratos + erros" com o enum real de `OrderStatus` e as 7 + 4 operações travadas por teste (QUAL-01)

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 07-09-PLAN.md — inventory-service: WR-01 (`ShipStock` inválido vai à DLQ) e WR-02 (`@Recover` por nome em `shipAll`/`releaseAll`) com testes (TEST-01, TEST-02)

**Wave 6** *(blocked on Wave 5 completion)*

- [x] 07-10-PLAN.md — matriz regra → teste (`07-COVERAGE.md`) com `scripts/check-coverage-matrix.sh`, lacunas fechadas (estoque e orquestração do pedido) e reactor inteiro verde (TEST-01, TEST-02)

**Wave 7** *(blocked on Wave 6 completion)*

- [x] 07-11-PLAN.md — entrega: `scripts/smoke-correlation-id.sh` na stack real, pipeline `.github/workflows/ci.yml` (LocalStack em sequência, e2e no fim, falha visível sem token, relatórios), README "Para avaliadores" e docs (INFRA-02, QUAL-01, QUAL-02)

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Esqueleto Vertical — Infraestrutura, Autenticação e Empresas | 5/5 | Complete    | 2026-09-19 |
| 2. Catálogo e Estoque | 3/3 | Complete    | 2026-09-20 |
| 3. Primeira Integração Assíncrona — Histórico de Notificações | 3/3 | Complete    | 2026-09-23 |
| 4. Núcleo do Pedido — Criação e Aprovação por Limite de Crédito | 5/5 | Complete    | 2026-09-25 |
| 5. Saga de Reserva de Estoque — Outbox, Compensação e Confirmação | 6/6 | Complete    | 2026-09-30 |
| 6. Ciclo de Vida Completo — Expedição, Entrega e Histórico do Pedido | 7/7 | Complete    | 2026-10-01 |
| 7. Endurecimento, Observabilidade e Entrega | 11/11 | In Progress|  |

## Coverage

| Phase | Requirements | Count |
|-------|--------------|-------|
| 1 | INFRA-01, AUTH-01, AUTH-02, AUTH-03, COMP-01, COMP-02, COMP-03 | 7 |
| 2 | CAT-01, CAT-02, INV-01, INV-02 | 4 |
| 3 | NOTF-01, NOTF-02 | 2 |
| 4 | ORD-01, ORD-02, ORD-03, ORD-08, ORD-09 | 5 |
| 5 | ORD-04, ORD-05, ORD-06, TEST-03 | 4 |
| 6 | ORD-07, ORD-10 | 2 |
| 7 | QUAL-01, QUAL-02, INFRA-02, INFRA-03, TEST-01, TEST-02 | 6 |
| **Total** | | **30 / 30** |

Todos os 30 requisitos v1 estão mapeados para exatamente uma fase. Nenhum requisito órfão, nenhum duplicado.

---
*Roadmap criado: 2026-09-16 — granularidade `standard`, modo `mvp` (fatias verticais demonstráveis via docker-compose)*
