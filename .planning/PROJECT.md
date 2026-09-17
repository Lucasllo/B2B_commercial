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
- [ ] Empresa compradora cria pedido selecionando produtos do catálogo
- [ ] Pedido acima do limite de crédito da empresa compradora entra em aprovação manual (PENDING_APPROVAL); abaixo do limite segue direto para confirmação
- [ ] Vendedor aprova ou rejeita pedidos pendentes de aprovação
- [ ] Comprador lista e visualiza detalhe dos próprios pedidos; vendedor lista e visualiza detalhe de todos os pedidos
- [ ] Order-service reserva estoque no inventory-service via evento assíncrono ao confirmar pedido (saga), publicando o evento através do padrão Transactional Outbox (grava o evento na mesma transação do banco, evitando inconsistência entre escrita e publicação)
- [ ] Pedido falha/é cancelado se a reserva de estoque falhar por falta de disponibilidade
- [ ] Pedido inclui atribuição de transportadora e código de rastreio (integração externa simulada)
- [ ] Fluxo de status do pedido: CREATED → PENDING_APPROVAL (condicional) → APPROVED/REJECTED → CONFIRMED → SHIPPED → DELIVERED (ou CANCELLED)
- [ ] Notification-service registra histórico de notificações (pedido criado, aprovado, enviado, entregue) em NoSQL (DynamoDB via LocalStack), consumindo eventos via SQS
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
| Padrão Transactional Outbox em vez de publicação direta (dual-write) | Evita divergência entre o estado salvo no banco e o evento publicado no SQS; achado de risco crítico da pesquisa de arquitetura/pitfalls | — Pending |

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
*Última atualização: 2026-09-16 após pesquisa de domínio (achados incorporados)*
