# OrderFlow

## What This Is

OrderFlow é uma plataforma de gerenciamento de pedidos B2B/atacado: uma empresa vendedora ("seller") disponibiliza um catálogo de produtos para múltiplas empresas compradoras, que criam pedidos sujeitos a aprovação por limite de crédito, reserva de estoque, atribuição de transportadora e acompanhamento até a entrega. É um projeto de portfólio técnico voltado a demonstrar competências exigidas para uma vaga de Desenvolvedor Java Pleno.

## Core Value

O fluxo de pedido — criação, aprovação condicional por limite de crédito, reserva de estoque e confirmação — funcionando de ponta a ponta entre microsserviços via orquestração por eventos (padrão saga). Se isso não funcionar de forma confiável, o projeto não cumpre seu propósito de demonstrar arquitetura de microsserviços orientada a eventos.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Autenticação com papéis (BUYER, SELLER_ADMIN) via JWT
- [ ] Empresa compradora é uma entidade própria (nome, limite de crédito) com usuários vinculados — não é só um atributo do usuário
- [ ] Vendedor gerencia catálogo de produtos (criar/atualizar produtos e preços)
- [ ] Vendedor gerencia níveis de estoque por produto
- [ ] Empresa compradora cria pedido selecionando produtos do catálogo — *entregue na Fase 4 (order-service, itens validados e precificados pelo catalog-service, snapshot gravado)*
- [ ] Pedido acima do limite de crédito da empresa compradora entra em aprovação manual (PENDING_APPROVAL); abaixo do limite segue direto para confirmação — *regra de exposição acumulada entregue na Fase 4; na Fase 5 a aprovação (automática ou manual) entra na saga e termina em CONFIRMED ou CANCELLED*
- [ ] Vendedor aprova ou rejeita pedidos pendentes de aprovação — *entregue na Fase 4 (decidedBy/decidedAt/reason, 409 fora de PENDING_APPROVAL)*
- [ ] Comprador lista e visualiza detalhe dos próprios pedidos; vendedor lista e visualiza detalhe de todos os pedidos — *entregue na Fase 4 (escopo por `company_id` do JWT, 404 idêntico para pedido de outra empresa)*
- [ ] Order-service reserva estoque no inventory-service via evento assíncrono ao confirmar pedido (saga), publicando o evento através do padrão Transactional Outbox (grava o evento na mesma transação do banco, evitando inconsistência entre escrita e publicação) — *entregue na Fase 5 (RESERVING + `ReserveStock` no outbox na mesma transação, relay `SKIP LOCKED`, inventory responde pelo próprio outbox; idempotente por reentrega; E2E com os dois serviços reais)*
- [ ] Pedido falha/é cancelado se a reserva de estoque falhar por falta de disponibilidade — *entregue na Fase 5 (CANCELLED com `cancellationCode` e motivo legível; timeout cancela e compensa com `ReleaseStock`; nenhum pedido fica preso em RESERVING)*
- [ ] Pedido inclui atribuição de transportadora e código de rastreio (integração externa simulada)
- [ ] Fluxo de status do pedido: CREATED → PENDING_APPROVAL (condicional) → APPROVED/REJECTED → CONFIRMED → SHIPPED → DELIVERED (ou CANCELLED) — *na Fase 5 o estado intermediário RESERVING foi adicionado entre APPROVED e CONFIRMED/CANCELLED; SHIPPED/DELIVERED chegam na Fase 6*
- [ ] Notification-service registra histórico de notificações (pedido criado, aprovado, enviado, entregue) em NoSQL (DynamoDB via LocalStack), consumindo eventos via SQS — *encanamento SQS → DynamoDB → consulta provado na Fase 3 com o evento `STOCK_ADJUSTED`; na Fase 5 o `STOCK_ADJUSTED` passou a sair pelo outbox; falta ligar os eventos do ciclo de vida do pedido (Fase 6)*
- [ ] Todo o sistema sobe localmente via docker-compose (microsserviços + Postgres + LocalStack)
- [ ] Pipeline de CI/CD (GitHub Actions) builda e testa cada serviço a cada push
- [ ] Decisões arquiteturais documentadas como ADRs em português

### Out of Scope

- Pagamento real via gateway — fora do foco; o objetivo é demonstrar arquitetura de pedidos, não faturamento
- Integração real com APIs de transportadoras — simulada via mock, evitando dependência e custo externos
- Deploy contínuo na AWS real — AWS demonstrada via LocalStack para evitar custo de nuvem constante; deploy real fica como opção pontual sob demanda
- Modelo marketplace multi-vendedor — mantido um único vendedor com vários compradores, para não inflar a complexidade sem agregar ao objetivo do portfólio
- Estados intermediários de trânsito (ex: AWAITING_PICKUP, IN_TRANSIT) — fluxo de status do pedido mantido simples (SHIPPED → DELIVERED)
- Shipping-service dedicado — dados de transportadora ficam como atributos do pedido dentro do order-service

## Context

- Projeto de portfólio pessoal, não um produto comercial — a audiência principal são recrutadores e entrevistadores técnicos avaliando conhecimento para uma vaga de Desenvolvedor Java Pleno.
- Domínio: uma empresa vendedora vende para múltiplas empresas compradoras (B2B/atacado) — não é e-commerce direto ao consumidor.
- Preferência explícita do usuário: nenhuma tecnologia deve ser implementada antes de ele entender o motivo de seu uso — cada fase deve explicar o "porquê" de uma escolha técnica antes de implementá-la.
- Convenção de idiomas do projeto:
  - Documentação (requisitos, especificações, ADRs, planos de implementação, documentação técnica, README, critérios de aceitação) → português do Brasil.
  - Código-fonte, nomes de classes/métodos/variáveis, endpoints, nomes de arquivos, banco de dados e commits Git → inglês, seguindo convenções da comunidade Java/Spring.
  - Termos técnicos amplamente usados em inglês (REST API, Microservices, Dependency Injection, Repository, Controller, Service, Docker, CI/CD, Clean Architecture) permanecem em inglês, com explicação em português quando necessário.
  - Comentários de código podem ser em português ou em inglês, conforme a necessidade.
  - Nota estrutural: os cabeçalhos de seção dos documentos de planejamento do GSD (ex.: "## Requirements", "### Active", "## Traceability", "### Phase N") permanecem em inglês porque fazem parte do contrato estrutural das ferramentas de automação do GSD; todo o conteúdo textual dentro dessas seções é escrito em português.

## Constraints

- **Stack**: Java 17+, Spring Boot — exigido pela vaga-alvo de Desenvolvedor Java Pleno
- **Persistência**: PostgreSQL para dados transacionais dos serviços; DynamoDB via LocalStack para o histórico de notificações (NoSQL) — demonstra SQL e NoSQL lado a lado
- **Mensageria**: SQS via LocalStack para comunicação assíncrona entre order-service, inventory-service e notification-service (padrão saga)
- **Containerização**: Docker + docker-compose — todo o sistema deve subir localmente sem custo de nuvem
- **Cloud**: AWS demonstrada via LocalStack (S3, SQS, DynamoDB simulados) — sem exigência de deploy real ativo continuamente
- **CI/CD**: pipeline automatizado (GitHub Actions) validando build e testes a cada push
- **Testes**: unitários (JUnit + Mockito), integração (Testcontainers), contrato/E2E entre microsserviços
- **Confiabilidade de eventos**: padrão Transactional Outbox em order-service e inventory-service — evita o problema de dual-write (gravar no banco e publicar no SQS como operações separadas e não atômicas)
- **Processo**: desenvolvimento incremental, fase a fase; nenhuma tecnologia implementada sem explicação prévia do motivo de seu uso

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Domínio B2B/atacado: um vendedor, vários compradores | Mais simples que um marketplace, mas ainda rico o suficiente para justificar aprovação de pedido, limite de crédito e controle de estoque | — Pending |
| Decomposição em 5 microsserviços (auth, catalog, inventory, order, notification) + API Gateway | Cobre autenticação, domínio de catálogo/estoque, orquestração de pedido via saga e mensageria/NoSQL, sem inflar demais o escopo do portfólio | — Pending |
| Aprovação de pedido disparada por limite de valor/crédito da empresa compradora | Regra de negócio realista de B2B e motor natural para o fluxo PENDING_APPROVAL | — Pending |
| Pagamento simplificado/mockado | Foco do projeto é a arquitetura de pedidos, não faturamento | — Pending |
| Logística como atributos do order-service, com transportadora simulada via mock | Evita inflar em outro microsserviço, mas ainda demonstra integração externa simulada | — Pending |
| AWS demonstrada via LocalStack, sem deploy real contínuo | Evita custo de nuvem constante, mas ainda demonstra uso de serviços AWS (S3, SQS, DynamoDB) | — Pending |
| Documentação em português, código em inglês | Alinhado às convenções da comunidade Java/Spring e ao objetivo de portfólio para uma vaga no Brasil | — Pending |
| Empresa compradora como entidade própria com limite de crédito | A regra de aprovação por limite de crédito não tem contra o que checar sem isso; achado da pesquisa de domínio | — Pending |
| Padrão Transactional Outbox em vez de publicação direta (dual-write) | Evita divergência entre o estado salvo no banco e o evento publicado no SQS; achado de risco crítico da pesquisa de arquitetura/pitfalls | ✓ Good — Fase 5 (outbox nos dois serviços; relay é o único caminho de envio SQS) |
| Fase 3 publica direto (dual-write) do inventory-service, após o commit, e adia o Outbox para a Fase 5 (D-29/D-30) | Isola o encanamento SQS/DynamoDB antes da saga; a perda de evento fica observável em log ERROR e declarada no README | ✓ Resolvido na Fase 5 — `STOCK_ADJUSTED` passou a sair pelo outbox do inventory-service |
| Idempotência do histórico pela chave `productId` + `STOCK_ADJUSTED#<eventId>` com `putItem` sem condição | Reentrega do SQS sobrescreve em vez de duplicar, sem precisar de tabela de deduplicação | ✓ Good — Fase 3 (IT + smoke na stack real) |
| Resource servers validam o `iss` do JWT (`issuer-uri` junto de `jwk-set-uri`) e os testes usam o decoder de produção | Fecha a ameaça T-03-02; testes que trocam o decoder inteiro escondiam a falta da checagem | ✓ Good — quick 260923-tj9 |
| Decisão de crédito serializada por empresa: linha `company_credit_lock` com `PESSIMISTIC_WRITE` antes da soma da exposição, na mesma transação do INSERT/decisão; toda chamada HTTP a vizinhos acontece antes da transação | Evita dois pedidos simultâneos aprovados além do limite sem segurar a trava durante I/O de rede | ✓ Good — Fase 4 (concorrência por socket real) |
| order-service falha fechado (503) quando catálogo ou auth-service não respondem, e não aprova por omissão | Sem limite confiável não há decisão; aprovação fail-open seria elevação de privilégio | ✓ Good — Fase 4 |
| Estado APPROVED não reserva estoque nem confirma o pedido nesta fase | Reserva assíncrona com Outbox é o escopo isolado da Fase 5; limitação declarada no README | ✓ Resolvido na Fase 5 — APPROVED entra na saga (RESERVING → CONFIRMED/CANCELLED) |
| Saga orquestrada pelo order-service, com mensagens de envelope plano (`eventId`/`eventType`/`occurredAt`) e `reservationId = orderId` | Máquina de estados dentro do agregado Order, sem motor de workflow; um id natural por pedido torna a reserva idempotente sem tabela extra | ✓ Good — Fase 5 (E2E + smoke na stack real) |
| Resultado da saga e timeout serializados pela trava da linha do pedido (`PESSIMISTIC_WRITE`), com guarda por estado | Duas fontes de transição sobre o mesmo pedido; a trava por linha evita corrida sem bloquear a empresa inteira | ✓ Good — Fase 5 (`SagaTimeoutIT` cobre resultado tardio) |
| `ReleaseStock` antes do `ReserveStock` grava lápide no livro de reservas (FK removida) | A fila padrão do SQS não garante ordem; sem lápide, a compensação chegando primeiro deixaria estoque preso | ✓ Good — Fase 5 (`TombstoneReleaseIT`, 5 rodadas concorrentes) |
| `poll-timeout: 0s` nos listeners SQS (WR-03 do review, não corrigido) | Long polling estourava o timeout compartilhado com a chamada síncrona; separar o cliente SQS exige mudança estrutural | ⚠ Revisitar — custo/latência só no SQS real |

## Evolution

Este documento evolui a cada transição de fase e a cada marco (milestone) do projeto.

**Após cada transição de fase** (via `/gsd-transition`):
1. Requisito invalidado? → Mover para Out of Scope com o motivo
2. Requisito validado? → Mover para Validated com referência da fase
3. Novo requisito surgiu? → Adicionar em Active
4. Decisão a registrar? → Adicionar em Key Decisions
5. "What This Is" ainda está correto? → Atualizar se algo mudou

**Após cada marco** (via `/gsd-complete-milestone`):
1. Revisão completa de todas as seções
2. Core Value ainda é a prioridade certa?
3. Auditar Out of Scope — os motivos ainda são válidos?
4. Atualizar Context com o estado atual

---
*Última atualização: 2026-09-30 após a Fase 5*
