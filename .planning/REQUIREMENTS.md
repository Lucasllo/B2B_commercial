# Requirements: OrderFlow

**Defined:** 2026-09-16
**Core Value:** O fluxo de pedido — criação, aprovação condicional por limite de crédito, reserva de estoque e confirmação — funcionando de ponta a ponta entre microsserviços via orquestração por eventos (padrão saga).

## v1 Requirements

Requisitos da primeira versão. Cada um será mapeado para uma fase do roadmap.

### Authentication & Companies

- [ ] **AUTH-01**: Vendedor (seller admin) cria contas de empresas compradoras com credenciais de usuário
- [ ] **AUTH-02**: Usuário faz login com email/senha e recebe um JWT contendo papel (BUYER/SELLER_ADMIN) e, se comprador, o ID da empresa
- [ ] **AUTH-03**: Cada serviço valida o JWT localmente (stateless), rejeitando tokens inválidos/expirados
- [ ] **COMP-01**: Empresa compradora é armazenada com nome e limite de crédito
- [ ] **COMP-02**: Vendedor pode visualizar/atualizar o limite de crédito de uma empresa compradora
- [ ] **COMP-03**: Usuários compradores são restritos aos dados da própria empresa (não veem dados de outras empresas)

### Catalog

- [ ] **CAT-01**: Vendedor cria/atualiza produtos com nome, preço e descrição
- [ ] **CAT-02**: Comprador lista e visualiza produtos e preços disponíveis

### Inventory

- [ ] **INV-01**: Vendedor define/atualiza a quantidade em estoque por produto
- [ ] **INV-02**: Reserva de estoque usa atualização atômica/lock otimista para evitar overselling sob concorrência

### Orders

- [ ] **ORD-01**: Comprador cria pedido selecionando produtos/quantidades do catálogo
- [ ] **ORD-02**: Pedido acima do limite de crédito da empresa compradora entra em PENDING_APPROVAL; abaixo do limite segue direto rumo à confirmação
- [ ] **ORD-03**: Vendedor aprova ou rejeita pedidos pendentes de aprovação
- [ ] **ORD-04**: Order-service publica evento de reserva de estoque via SQS usando o padrão Transactional Outbox (evento gravado na mesma transação da mudança de estado do pedido)
- [ ] **ORD-05**: Order-service consome o resultado da reserva de estoque e transiciona o pedido para CONFIRMED (sucesso) ou CANCELLED (falha)
- [ ] **ORD-06**: Consumidores SQS processam cada evento de forma idempotente, mesmo diante de entrega duplicada
- [ ] **ORD-07**: Pedido confirmado recebe atribuição de transportadora simulada e código de rastreio
- [ ] **ORD-08**: Comprador lista e visualiza detalhe dos próprios pedidos
- [ ] **ORD-09**: Vendedor lista e visualiza detalhe de todos os pedidos
- [ ] **ORD-10**: Status do pedido segue CREATED → PENDING_APPROVAL (condicional) → APPROVED/REJECTED → CONFIRMED → SHIPPED → DELIVERED (ou CANCELLED)

### Notifications

- [ ] **NOTF-01**: Notification-service consome eventos do ciclo de vida do pedido e os grava no DynamoDB (via LocalStack)
- [ ] **NOTF-02**: Histórico de notificações de um pedido pode ser consultado

### Quality & Observability

- [ ] **QUAL-01**: Documentação OpenAPI/Swagger disponível para cada serviço
- [ ] **QUAL-02**: Correlation-ID é gerado no API Gateway e propagado nos logs de todos os serviços envolvidos em uma requisição

### Infrastructure

- [ ] **INFRA-01**: Todo o sistema (microsserviços + Postgres + LocalStack) sobe localmente com um único comando `docker-compose up`
- [ ] **INFRA-02**: Pipeline de CI (GitHub Actions) builda e testa cada serviço a cada push
- [ ] **INFRA-03**: Decisões arquiteturais são documentadas como ADRs em português

### Testing

- [ ] **TEST-01**: Cada serviço possui testes unitários cobrindo as regras de negócio centrais (JUnit + Mockito)
- [ ] **TEST-02**: Cada serviço possui testes de integração contra dependências reais via Testcontainers
- [ ] **TEST-03**: Ao menos um teste E2E/contrato verifica o fluxo completo da saga (criação do pedido → reserva de estoque com sucesso ou falha → status final)

## v2 Requirements

Adiados para uma versão futura. Rastreados, mas fora do roadmap atual.

### Reliability & Observability

- **DLQ-01**: Dead-letter queue para eventos que falham repetidamente no processamento
- **TRACE-01**: Distributed tracing (Zipkin/Jaeger) entre os microsserviços

### Commerce

- **PRICE-01**: Desconto percentual simples configurável por empresa compradora

### Platform

- **GATE-01**: Rate limiting no API Gateway
- **CLOUD-01**: Deploy real na AWS sob demanda, fora do fluxo padrão de desenvolvimento local

## Out of Scope

Explicitamente excluído. Documentado para evitar scope creep.

| Feature | Reason |
|---------|--------|
| Pagamento real via gateway | Foco do projeto é a arquitetura de pedidos, não faturamento |
| Integração real com APIs de transportadoras | Simulada via mock, evita dependência e custo externos |
| Deploy contínuo na AWS real | AWS demonstrada via LocalStack para evitar custo de nuvem constante |
| Marketplace multi-vendedor | Mantido um único vendedor com vários compradores, evita inflar complexidade sem novo aprendizado arquitetural |
| Estados intermediários de trânsito (AWAITING_PICKUP, IN_TRANSIT) | Transportadora é simulada; estados intermediários não adicionam credibilidade real |
| Shipping-service dedicado | Dados de transportadora ficam como atributos do pedido dentro do order-service |
| Aprovação de crédito multi-nível (comitês, múltiplos aprovadores) | Um único limite por empresa já demonstra o padrão; múltiplos níveis são regra de negócio, não lição de arquitetura |
| Integração com ERP/CRM/contabilidade | Não há sistema externo real para integrar; construir um ERP falso só para integrar é inflação de escopo |
| Preços customizados/tiered por empresa compradora | Preço único de catálogo é suficiente para demonstrar o fluxo de pedidos |
| Fluxo de cotação (RFQ / negociação antes do pedido) | O gate de aprovação por crédito já cobre a narrativa de "nem todo pedido é aceito automaticamente" |
| Múltiplos armazéns / alocação multi-nó de estoque | Um único pool de estoque por produto é suficiente para a lição de arquitetura pretendida |

## Traceability

Preenchida durante a criação do roadmap.

| Requirement | Phase | Status |
|-------------|-------|--------|
| AUTH-01 | TBD | Pending |
| AUTH-02 | TBD | Pending |
| AUTH-03 | TBD | Pending |
| COMP-01 | TBD | Pending |
| COMP-02 | TBD | Pending |
| COMP-03 | TBD | Pending |
| CAT-01 | TBD | Pending |
| CAT-02 | TBD | Pending |
| INV-01 | TBD | Pending |
| INV-02 | TBD | Pending |
| ORD-01 | TBD | Pending |
| ORD-02 | TBD | Pending |
| ORD-03 | TBD | Pending |
| ORD-04 | TBD | Pending |
| ORD-05 | TBD | Pending |
| ORD-06 | TBD | Pending |
| ORD-07 | TBD | Pending |
| ORD-08 | TBD | Pending |
| ORD-09 | TBD | Pending |
| ORD-10 | TBD | Pending |
| NOTF-01 | TBD | Pending |
| NOTF-02 | TBD | Pending |
| QUAL-01 | TBD | Pending |
| QUAL-02 | TBD | Pending |
| INFRA-01 | TBD | Pending |
| INFRA-02 | TBD | Pending |
| INFRA-03 | TBD | Pending |
| TEST-01 | TBD | Pending |
| TEST-02 | TBD | Pending |
| TEST-03 | TBD | Pending |

**Coverage:**
- v1 requirements: 30 total
- Mapped to phases: 0
- Unmapped: 30 ⚠

---
*Requirements defined: 2026-09-16*
*Last updated: 2026-09-16 after initial definition*
