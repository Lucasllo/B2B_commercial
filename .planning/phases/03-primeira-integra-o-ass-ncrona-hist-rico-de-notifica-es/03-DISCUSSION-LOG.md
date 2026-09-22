# Phase 3: Primeira Integração Assíncrona — Histórico de Notificações - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-22
**Phase:** 3-Primeira Integração Assíncrona — Histórico de Notificações
**Areas discussed:** Evento-gatilho da prova de conceito, Outbox agora ou só na Fase 5, Formato de consulta do histórico, Conteúdo do registro de notificação

---

## Evento-gatilho da prova de conceito

| Option | Description | Selected |
|--------|-------------|----------|
| Ajuste de estoque (PUT stock) | O SELLER_ADMIN define/atualiza a quantidade em estoque de um produto (INV-01). Exemplo já citado literalmente no ROADMAP.md. | ✓ |
| Reserva de estoque (POST reserve) | Já tem reservation_id fornecido pelo chamador e idempotência nativa (D-11 da Fase 2). | |
| Criação/atualização de produto | Evento no catalog-service (CAT-01) em vez do inventory-service. | |

**User's choice:** Ajuste de estoque (PUT stock)

| Option | Description | Selected |
|--------|-------------|----------|
| Só ajuste de quantidade (recomendado) | Mantém a fase no menor escopo possível — uma única ação publicando. | ✓ |
| Também reservar e liberar | Instrumenta os três endpoints do inventory-service agora. | |

**User's choice:** Só ajuste de quantidade (recomendado)
**Notes:** Reservar/liberar como fonte de evento fica mais natural na saga da Fase 5.

---

## Outbox agora ou só na Fase 5

| Option | Description | Selected |
|--------|-------------|----------|
| Publicar direto, sem outbox (recomendado) | PITFALLS.md recomenda introduzir outbox na Fase 5, quando order-service↔inventory-service trocam a saga. | ✓ |
| Já implementar outbox no inventory-service | Constrói a tabela outbox + poller agora, Fase 5 reaproveita/estende. | |

**User's choice:** Publicar direto, sem outbox (recomendado)

| Option | Description | Selected |
|--------|-------------|----------|
| Aceitar o risco, documentar como limitação conhecida (recomendado) | PITFALLS.md sugere isso explicitamente quando outbox completo é adiado. | ✓ |
| Publicar antes de commitar a mudança de estoque | Inverte a ordem — não resolve o problema, só desloca o risco. | |

**User's choice:** Aceitar o risco, documentar como limitação conhecida (recomendado)
**Notes:** A Fase 5 resolve isso corretamente onde a consistência realmente importa (a saga).

---

## Formato de consulta do histórico

| Option | Description | Selected |
|--------|-------------|----------|
| Lista por identificador (recomendado) | GET /notifications/{entityId} devolve todos os eventos daquele produto/pedido, ordenados — a Fase 6 vai precisar exatamente disso. | ✓ |
| Registro único por chave exata | GET /notifications/{entityId}/{eventType} devolve exatamente um item (GetItem simples). | |

**User's choice:** Lista por identificador (recomendado)

| Option | Description | Selected |
|--------|-------------|----------|
| productId (recomendado) | O evento é sobre um produto específico — partition key = productId. Fase 6 troca por orderId, mesmo padrão. | ✓ |
| Um identificador genérico de entidade | Campo abstrato tipo entityId. | |

**User's choice:** productId (recomendado)

---

## Conteúdo do registro de notificação

| Option | Description | Selected |
|--------|-------------|----------|
| Ambos (recomendado) | Payload bruto + mensagem legível — pouco custo extra, alto valor demonstrativo. | ✓ |
| Só payload bruto | Só dados estruturados. | |
| Só mensagem legível | Só texto human-readable, perde rastreabilidade. | |

**User's choice:** Ambos (recomendado)

| Option | Description | Selected |
|--------|-------------|----------|
| Guardar os dois (recomendado) | Sort key = eventType + identificador único do evento. Só reentrega da mesma mensagem sobrescreve. | ✓ |
| Só o mais recente por tipo | Sort key = só eventType — cada novo ajuste apaga o histórico do anterior. | |

**User's choice:** Guardar os dois (recomendado)
**Notes:** Isso é o que faz sentido para um "histórico de notificações" de verdade, e é o que a Fase 6 vai precisar para a timeline completa do pedido.

---

## Claude's Discretion

- Nome exato do evento/tipo e do payload DTO compartilhado entre inventory-service e notification-service.
- Nome exato da fila SQS e da tabela DynamoDB, e mecanismo de provisionamento automático no LocalStack.
- `dynamodb-enhanced` vs cliente SDK direto para o repositório do notification-service.
- Papel/JWT exigido para consultar o endpoint de histórico.
- Formato exato da mensagem legível.

## Deferred Ideas

None — discussion stayed within phase scope.
