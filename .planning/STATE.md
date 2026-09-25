---
gsd_state_version: "1.0"
current_phase: 04
current_phase_name: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito
status: executing
stopped_at: Phase 4 context gathered
last_updated: "2026-09-25T01:03:26.305Z"
last_activity: 2026-09-23
last_activity_desc: Phase 03 complete, transitioned to Phase 4
state_head: a86e308e76885c433a84050538dca271ea28b807
progress:
  total_phases: 7
  completed_phases: 3
  total_plans: 16
  completed_plans: 11
  percent: 43
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-24)

**Core value:** O fluxo de pedido — criação, aprovação condicional por limite de crédito, reserva de estoque e confirmação — funcionando de ponta a ponta entre microsserviços via orquestração por eventos (padrão saga).
**Current focus:** Phase 4 — Núcleo do Pedido — Criação e Aprovação por Limite de Crédito

## Current Position

Phase: 04 (Núcleo do Pedido — Criação e Aprovação por Limite de Crédito) — READY TO EXECUTE
Plan: Not started
Status: Ready to execute
Last activity: 2026-09-23 — Phase 03 complete, transitioned to Phase 4

Progress: [████░░░░░░] 43%

## Performance Metrics

**Velocity:**

- Total plans completed: 11
- Average duration: —
- Total execution time: 0.0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1 | 5 | - | - |
| 02 | 3 | - | - |
| 03 | 3 | - | - |

**Recent Trend:**

- Last 5 plans: —
- Trend: —

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 02 P01 | 26min | 3 tasks | 25 files |
| Phase 02 P02 | 27min | 3 tasks | 29 files |
| Phase 03 P03 | 37min | 2 tasks | 8 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: Saga isolada na Fase 5, depois de auth, catálogo/estoque, prova assíncrona simples e núcleo do pedido — de-risking antes do Core Value (ARCHITECTURE.md build order).
- [Roadmap]: Notification-service + SQS + DynamoDB construídos na Fase 3 como a integração assíncrona mais simples possível, antes da saga, para depurar o encanamento isoladamente.
- [Roadmap]: Infraestrutura (docker-compose, Postgres, LocalStack, Gateway) fundida com autenticação na Fase 1 — modo `mvp` exige fatia vertical demonstrável, e infra sozinha não entrega valor observável.
- [Roadmap]: Testes unitários/integração e CI registrados formalmente na Fase 7, mas devem ser escritos em cada fase (PITFALLS.md #7 — não adiar testes para uma fase final).
- [Phase 02]: PRODUCT_STATUS_CONTRACT=status-two-values — dois estados ACTIVE/DISCONTINUED, retirada via PUT /products/{id}/status, sem remoção física (D-23)
- [Phase 02]: catalog-service: porta 8082, jwk-set-uri padrão http://localhost:8081/.well-known/jwks.json
- [Phase 02]: GET /products devolve o envelope padrão Page<T> do Spring Data (content/totalElements/...) — contrato consumido pela Fase 4
- [Phase 02]: RESERVATION_ID_SCOPE=scope-per-product — unicidade por par (productId, reservationId) para o reservationId da reserva de estoque
- [Phase 02]: inventory-service: porta 8083, jwk-set-uri padrão http://localhost:8081/.well-known/jwks.json
- [Phase 02]: Reexecução de conflito de lock otimista: maxAttempts=4, backoff delay=25ms multiplier=2 (Spring Retry, @EnableRetry order=LOWEST_PRECEDENCE)
- [Phase 03]: [Phase 3]: Healthcheck do LocalStack verifica fila e tabela via awslocal (não só o processo) — recurso de negócio precisa existir antes de qualquer serviço dependente subir saudável
- [Phase 03]: [Phase 3]: Reentrega provada na stack real enviando duas vezes o mesmo eventId direto na fila via awslocal, em vez de derrubar o consumidor no meio do processamento

### Pending Todos

[From .planning/todos/pending/ — ideas captured during sessions]

None yet.

### Blockers/Concerns

[Issues that affect future work]

- [Fase 1]: LocalStack exige `LOCALSTACK_AUTH_TOKEN` (tier Hobby gratuito) desde 2026.03.0 — precisa estar no docker-compose e no CI desde o primeiro dia.
- [Fase 1]: Fixar versões das imagens Docker (LocalStack, Postgres) — `latest` causa divergência silenciosa de comportamento (PITFALLS.md #6).
- [Fase 5]: Padrões do Spring Cloud AWS (`SqsTemplate`/`@SqsListener`, `doNotSendPayloadTypeHeader`, `setPayloadTypeMapper`) já provados na Fase 3 — reusar na saga; o desenho de tabela DynamoDB (partition por agregado + sort `TIPO#eventId`) serve de base para a timeline do pedido (Fase 6).
- [Ambiente]: sessão LocalStack Hobby é única por token — Testcontainers falha (exit 126) com a stack do compose de pé; derrubar o compose antes de `./mvnw verify`.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260920-g6c | Adicionar springdoc-openapi (Swagger UI) em auth-service, catalog-service e inventory-service para visualizacao e teste rapido dos endpoints no navegador | 2026-09-20 | 588785f | [260920-g6c-adicionar-springdoc-openapi-swagger-ui-e](./quick/260920-g6c-adicionar-springdoc-openapi-swagger-ui-e/) |
| 260923-tj9 | Validar issuer (iss) do JWT nos resource servers notification/catalog/inventory — fecha T-03-02 / WR-07; testes passam a usar o decoder de produção | 2026-09-24 | 805d5c2 | [260923-tj9-validar-issuer-do-jwt-nos-resource-serve](./quick/260923-tj9-validar-issuer-do-jwt-nos-resource-serve/) |

## Deferred Items

Items acknowledged and deferred at milestone close, most recent first:

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| *(none)* | | | | |

## Session Continuity

Last session: 2026-09-25T00:06:55.509Z
Stopped at: Phase 4 context gathered
Resume file: .planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-CONTEXT.md
