# Phase 2: Catálogo e Estoque - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-19
**Phase:** 2-Catálogo e Estoque
**Areas discussed:** Semântica de reserva de estoque, Acoplamento catalog-inventory, Conflito de lock otimista, Modelo de produto e visibilidade de estoque

---

## Semântica de reserva de estoque

| Question | Selected |
|---|---|
| Campo único de quantidade ou total separado de reservado? | **Total + Reservado** (`quantity_on_hand` / `quantity_reserved`) |
| Quais operações de reserva expor nesta fase? | **Reservar + Liberar** (confirmar saída fica para a Fase 5) |
| O que fazer quando a reserva pede mais do que o disponível? | **409 Conflict** com corpo explicando insuficiência |
| Endpoint de reservar aceita reservation_id do chamador ou gera internamente? | **Chamador fornece reservation_id** |
| Estoque por produto único ou produto + depósito/local? | **Produto único, sem local** |
| Reservas órfãs expiram (TTL) ou ficam pendentes? | **Sem TTL nesta fase** |
| Liberar reserva já liberada deve ser idempotente ou falhar? | **Idempotente — no-op silencioso** |

**Notes:** A decisão de `on_hand`/`reserved` + `reservation_id` do chamador foi tomada pensando explicitamente na saga da Fase 5 (Transactional Outbox + consumidor idempotente, TEST-03) — evita redesenhar a API de reserva depois.

---

## Acoplamento catalog-inventory

| Question | Selected |
|---|---|
| Inventory-service valida sincronamente o product_id no catalog-service? | **Sem validação cruzada** |
| Listagem de catálogo já vem enriquecida com disponibilidade? | **Duas chamadas separadas** |
| Criar produto cria estoque zerado automaticamente? | **Totalmente aparte** |
| O que acontece se operação chega para product_id sem linha de estoque? | **Definir estoque é upsert; reservar em ID inexistente é 404** |
| Inativar produto propaga para o inventory? | **Totalmente independentes nesta fase** |

**Notes:** Todas as decisões desta área reforçam o mesmo princípio — zero chamada síncrona de escrita entre catalog-service e inventory-service nesta fase, mantendo os dois serviços donos dos seus próprios dados.

---

## Conflito de lock otimista

| Question | Selected |
|---|---|
| O que fazer em conflito de versão do lock otimista? | **Retry interno automático com limite curto** |
| O que retornar se os retries se esgotarem? | **503/409 com mensagem de tente novamente** |
| Teste de concorrência via HTTP real ou chamada direta ao service? | **HTTP real via Testcontainers + virtual threads** |
| Retry via Spring Retry declarativo ou loop manual? | **Spring Retry (@Retryable)** |

**Notes:** A escolha de testar via HTTP real (não chamada direta ao repository) foi justificada explicitamente como maior força probatória para um avaliador externo da vaga-alvo.

---

## Modelo de produto e visibilidade de estoque

| Question | Selected |
|---|---|
| Produto precisa de SKU e status ativo/descontinuado? | **SKU + status ativo/descontinuado (soft-delete)** |
| BUYER vê produtos inativos na listagem? | **Só produtos ativos para o BUYER** |
| BUYER vê quantidade exata ou só disponível/indisponível? | **Quantidade exata disponível** |
| Listagem precisa de paginação desde já? | **Paginação simples (page/size)** |

**Notes:** Soft-delete via status foi justificado por preservar integridade referencial quando pedidos futuros (Fase 4+) referenciarem o produto.

---

## Claude's Discretion

- Nome exato dos endpoints REST.
- Estrutura exata de pacotes Java dentro de `com.orderflow.catalog` / `com.orderflow.inventory`.
- Formato exato do corpo de erro 409.
- Valor exato do limite de tentativas de retry do Spring Retry e backoff.

## Deferred Ideas

None — discussion stayed within phase scope.
