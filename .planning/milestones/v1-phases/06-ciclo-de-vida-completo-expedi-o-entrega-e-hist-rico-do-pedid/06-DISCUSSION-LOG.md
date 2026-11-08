# Phase 6: Ciclo de Vida Completo — Expedição, Entrega e Histórico do Pedido - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-30
**Phase:** 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedido
**Areas discussed:** Transportadora simulada, Expedição/entrega/baixa, Linha do tempo no histórico, Diagrama e prova do fluxo

---

## Transportadora simulada

| Option | Description | Selected |
|--------|-------------|----------|
| No CONFIRMED | Mesma transação do StockReserved → CONFIRMED | ✓ |
| No SHIPPED | Atribuída na expedição | |
| Passo assíncrono depois do CONFIRMED | Job/evento separado; janela sem transportadora | |

| Option | Description | Selected |
|--------|-------------|----------|
| Interface + implementação fake | CarrierGateway + SimulatedCarrierGateway, determinística | ✓ |
| Classe utilitária simples | Um componente só | |
| Stub HTTP separado | Contêiner/endpoint fingindo a API | |

| Option | Description | Selected |
|--------|-------------|----------|
| Não, sempre funciona | Sem Resilience4j nesta fase | ✓ |
| Falha configurável | Estado CONFIRMED sem transportadora + retry | |

| Option | Description | Selected |
|--------|-------------|----------|
| Você decide | Prefixo + sufixo aleatório | |
| Estilo Correios | AA123456789BR, transportadoras fictícias brasileiras | ✓ |

**User's choice:** CONFIRMED, interface + fake, sem falha, estilo Correios.

---

## Expedição, entrega e baixa

| Option | Description | Selected |
|--------|-------------|----------|
| Dois endpoints de ação | POST /ship e /deliver, SELLER_ADMIN | ✓ |
| PATCH genérico de status | PATCH /orders/{id}/status | |

| Option | Description | Selected |
|--------|-------------|----------|
| Comando assíncrono pelo outbox | ShipStock na mesma transação; pedido não espera | ✓ |
| SHIPPED espera o inventory | Estado intermediário SHIPPING | |
| Sem baixa nesta fase | Contradiz D-57 | |

| Option | Description | Selected |
|--------|-------------|----------|
| Quando e quem | shipped_at/by, delivered_at/by | ✓ |
| Só o quando | shipped_at, delivered_at | |

| Option | Description | Selected |
|--------|-------------|----------|
| Não, fica para depois | CANCELLED só pela saga | ✓ |
| Sim, CONFIRMED → CANCELLED | POST /cancel com ReleaseStock | |

**User's choice:** todas as opções recomendadas.

---

## Linha do tempo no histórico

| Option | Description | Selected |
|--------|-------------|----------|
| Todas as transições persistidas | Inclui PENDING_APPROVAL e REJECTED | ✓ |
| Só as do critério 3 | Criado, aprovado, confirmado/cancelado, enviado, entregue | |

| Option | Description | Selected |
|--------|-------------|----------|
| Mesma tabela, PK genérica | entityId/aggregateId no lugar de productId | ✓ |
| Tabela nova só para pedidos | order-timeline | |
| Mesma tabela, sem renomear | orderId gravado em productId | |

| Option | Description | Selected |
|--------|-------------|----------|
| Endpoint novo + BUYER da empresa | /notifications/orders/{orderId}, 404 fora da empresa | ✓ |
| Endpoint novo, só SELLER_ADMIN | BUYER usa GET /orders/{id} | |
| Reaproveitar /notifications/{id} | Regra de acesso por tipo de entidade | |

| Option | Description | Selected |
|--------|-------------|----------|
| Outbox → notification-events-queue | Relay roteia ORDER_* para a fila existente | ✓ |
| Fila nova só para pedidos | order-notification-queue | |

**User's choice:** todas as opções recomendadas.

---

## Diagrama e prova do fluxo

| Option | Description | Selected |
|--------|-------------|----------|
| Mermaid no README + docs | stateDiagram-v2 | ✓ |
| Só em docs/ | README aponta | |
| Imagem exportada | PNG/SVG | |

| Option | Description | Selected |
|--------|-------------|----------|
| Tabela de transições + teste exaustivo | Fonte única + teste parametrizado | ✓ |
| Tabela + teste que lê o Mermaid | Parse do README | |
| Só testes de endpoint | Sem tabela central | |

| Option | Description | Selected |
|--------|-------------|----------|
| Novo smoke do ciclo de vida | scripts/smoke-order-lifecycle.sh | ✓ |
| Estender smoke-order-saga.sh | Um script só | |

| Option | Description | Selected |
|--------|-------------|----------|
| Sim, estender com ship → baixa | Timeline em IT próprio | ✓ |
| Sim, com os três serviços | notification-service no E2E | |
| Não, só integração por serviço | Smoke cobre a junção | |

**User's choice:** todas as opções recomendadas.

---

## Claude's Discretion

- Nomes (gateway, transportadoras, comando de baixa, PK genérica, colunas); algoritmo determinístico.
- Semântica do ShipStock para reserva inexistente/liberada/já expedida.
- Se a baixa publica STOCK_ADJUSTED; destino da rota de produto do histórico.
- Texto das mensagens legíveis; como o companyId chega ao notification-service.
- Estratégia de trava em /ship e /deliver; formato do 409.

## Deferred Ideas

- Cancelamento manual de pedido CONFIRMED/SHIPPED.
- Falha simulada da transportadora + Resilience4j (candidato à Fase 7).
- notification-service como terceiro contexto no E2E.
- Tabela DynamoDB separada para pedidos.
