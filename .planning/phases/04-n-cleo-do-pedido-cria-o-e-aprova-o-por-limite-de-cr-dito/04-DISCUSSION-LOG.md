# Phase 4: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-24
**Phase:** 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
**Areas discussed:** Semântica do crédito, Origem do limite, Validação no catálogo, Estados e decisão

---

## Semântica do crédito

| Option | Description | Selected |
|--------|-------------|----------|
| Exposição acumulada | Soma dos pedidos em aberto + novo > limite → pendente | ✓ |
| Só o valor do pedido | Cada pedido comparado sozinho com o limite | |

| Option | Description | Selected |
|--------|-------------|----------|
| Aprovados e além | APPROVED/CONFIRMED/SHIPPED/DELIVERED consomem; pendente não | ✓ |
| Inclui pendentes | PENDING_APPROVAL também reserva crédito | |
| Até entregar | DELIVERED libera (pagamento na entrega) | |

| Option | Description | Selected |
|--------|-------------|----------|
| Passa a consumir | Aprovado acima do limite entra na soma | ✓ |
| Aprovação aumenta o limite | Ajusta credit_limit no auth-service | |

**User's choice:** todas as recomendadas.

---

## Origem do limite

| Option | Description | Selected |
|--------|-------------|----------|
| REST ao auth-service | GET credit-limit repassando o JWT do BUYER | ✓ |
| Cópia local no order | Tabela replicada via evento | |
| Limite dentro do JWT | Claim credit_limit | |

| Option | Description | Selected |
|--------|-------------|----------|
| Linha de trava por empresa | company_credit_lock + SELECT FOR UPDATE | ✓ |
| Advisory lock do Postgres | pg_advisory_xact_lock | |
| Você decide | Planejador escolhe | |

**Notes:** Claude acrescentou que as chamadas HTTP devem acontecer antes da transação com trava.

---

## Validação no catálogo

| Option | Description | Selected |
|--------|-------------|----------|
| GET por item, token do BUYER | N chamadas; DISCONTINUED já vira 404 | ✓ |
| Endpoint em lote novo | GET /products?ids= no catalog-service | |

| Option | Description | Selected |
|--------|-------------|----------|
| Snapshot de preço e nome | order_item guarda preço/nome/sku do momento | ✓ |
| Só productId e quantidade | Preço reconsultado ao exibir | |

| Option | Description | Selected |
|--------|-------------|----------|
| Rejeitar o pedido inteiro | 400 validação; 422/409 produto inválido; tudo ou nada | ✓ |
| Mesclar duplicados | productId repetido somado | |
| Você decide | | |

---

## Estados e decisão

| Option | Description | Selected |
|--------|-------------|----------|
| APPROVED automático | CREATED → APPROVED na mesma transação, decidedBy SYSTEM | ✓ |
| Fica em CREATED | Espera a saga | |

| Option | Description | Selected |
|--------|-------------|----------|
| Dois endpoints, motivo na rejeição | /approve e /reject; 409 fora de PENDING_APPROVAL | ✓ |
| Um endpoint com ação | /decision com APPROVE/REJECT | |

| Option | Description | Selected |
|--------|-------------|----------|
| Paginada + filtro de status | Page<T>, createdAt desc, ?status=, 404 entre empresas | ✓ |
| Paginada sem filtros | | |

---

## Claude's Discretion

- Timeouts/Resilience4j nas chamadas síncronas, formato dos DTOs de resposta, código HTTP de produto inválido, dados de demonstração.

## Deferred Ideas

- Endpoint em lote no catálogo; cópia local do limite; circuit breaker completo (Fase 7); eventos de pedido (Fase 6).
