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
- [ ] **Phase 2: Catálogo e Estoque** - O vendedor mantém produtos e níveis de estoque, o comprador enxerga o catálogo, e a reserva já é atômica contra concorrência
- [ ] **Phase 3: Primeira Integração Assíncrona — Histórico de Notificações** - Um evento publicado no SQS vira registro consultável no DynamoDB, provando o encanamento antes da saga
- [ ] **Phase 4: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito** - O comprador cria pedidos do catálogo e a regra de aprovação por crédito funciona, ainda sem saga
- [ ] **Phase 5: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação** - O Core Value: reserva assíncrona com Transactional Outbox terminando sempre em CONFIRMED ou CANCELLED
- [ ] **Phase 6: Ciclo de Vida Completo — Expedição, Entrega e Histórico do Pedido** - Transportadora simulada, rastreio, SHIPPED/DELIVERED e a linha do tempo completa nas notificações
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

**Plans:** 2/3 plans executed

Plans:
**Wave 1**

- [x] 02-01-PLAN.md — catalog-service: o vendedor mantém o catálogo (cria, atualiza, descontinua por soft-delete) e o comprador lista produtos ativos paginados (CAT-01, CAT-02)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 02-02-PLAN.md — inventory-service: o vendedor define os níveis de estoque e a reserva nasce atômica por lock otimista e idempotente por identificador do chamador (INV-01, INV-02)

**Wave 3** *(blocked on Wave 2 completion)*

- [ ] 02-03-PLAN.md — catálogo e estoque no `docker compose up` e nas rotas do Gateway, mais a prova de atomicidade por HTTP real e concorrência real (INV-02, CAT-02)

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

**Plans**: TBD

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

**Plans**: TBD

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

**Plans**: TBD

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

**Plans**: TBD

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

**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Esqueleto Vertical — Infraestrutura, Autenticação e Empresas | 5/5 | Complete    | 2026-09-19 |
| 2. Catálogo e Estoque | 2/3 | In Progress|  |
| 3. Primeira Integração Assíncrona — Histórico de Notificações | 0/TBD | Not started | - |
| 4. Núcleo do Pedido — Criação e Aprovação por Limite de Crédito | 0/TBD | Not started | - |
| 5. Saga de Reserva de Estoque — Outbox, Compensação e Confirmação | 0/TBD | Not started | - |
| 6. Ciclo de Vida Completo — Expedição, Entrega e Histórico do Pedido | 0/TBD | Not started | - |
| 7. Endurecimento, Observabilidade e Entrega | 0/TBD | Not started | - |

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
