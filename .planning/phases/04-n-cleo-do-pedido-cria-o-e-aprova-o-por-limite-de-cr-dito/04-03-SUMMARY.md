---
phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
plan: "03"
subsystem: orders
tags: [spring-boot, spring-data-jpa, pageable, jdbctemplate, postgresql]

# Dependency graph
requires:
  - phase: 04-01
    provides: order-service (porta 8085, schema order), Order/OrderStatus/OrderRepository, OrderController/OrderService com getById escopado por empresa (requireCompanyId/isSellerAdmin), TestJwt/DownstreamStubServer/AbstractIntegrationTest
  - phase: 02-cat-logo-e-estoque
    provides: precedente de Page<T> do Spring Data em ProductController.list/ProductService.list (sellerView boolean derivado das authorities, nunca de query/body)
provides:
  - "ORD-08: GET /orders paginado — BUYER vê só os pedidos da própria empresa (company_id do JWT), inclusive nos totais (totalElements/totalPages)"
  - "ORD-09: GET /orders — SELLER_ADMIN vê pedidos de todas as empresas e filtra a fila de aprovação por ?status=PENDING_APPROVAL"
  - "Ordenação sempre createdAt/id decrescentes, imposta pelo servidor — o Sort do cliente (?sort=) é descartado antes da consulta"
  - "Limites de paginação providos: tamanho padrão 20, size acima de 100 reduzido a 100, página além do fim devolve content vazio com totalElements real"
  - "OrderSummaryResponse — resumo sem a coleção de itens, reutilizável pelos planos 04-04/04-05"
affects: [04-04-aprovacao-rejeicao, 04-05-compose-gateway-smoke]

# Actuals (#2632)
actuals:
  tokens: 6021
  tasks: 2
  commits: 2
plan_head_before: 01ebcfe50c41af992161e9c12a61ba19663893e8

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Ordenação montada no servidor via PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(...)) — o Sort do Pageable recebido do cliente nunca chega ao Spring Data, fechando a superfície de propriedade arbitrária em ?sort= (T-04-17)"
    - "Resumo de listagem (OrderSummaryResponse) sem a coleção de itens — evita N+1 e mantém a listagem paginada leve; o detalhe completo (com itens) continua isolado em GET /orders/{id}"
    - "Escopo por empresa e visão de vendedor decididos no controller (requireCompanyId/isSellerAdmin), nunca por parâmetro de query — mesmo padrão de ProductController.list herdado da Fase 2"

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderSummaryResponse.java
    - order-service/src/test/java/com/orderflow/order/OrderListIT.java
  modified:
    - order-service/src/main/java/com/orderflow/order/order/OrderController.java
    - order-service/src/main/java/com/orderflow/order/order/OrderService.java
    - order-service/src/main/java/com/orderflow/order/order/OrderRepository.java

key-decisions:
  - "?status= vale para os dois papéis (BUYER e SELLER_ADMIN) — 04-RESEARCH.md Open Question 2 resolvida a favor de permitir o filtro ao comprador também, sem enfraquecer o isolamento por empresa (é só uma cláusula a mais sobre o escopo já resolvido)"
  - "SELLER_ADMIN não tem filtro por empresa nesta fase — D-47 só pede ?status=, nenhum parâmetro de empresa é aceito em nenhum papel"
  - "OrderSummaryResponse.from nunca acessa order.getItems() — a listagem não paga consulta extra por pedido, ao contrário do detalhe (OrderResponse.from)"

requirements-completed: [ORD-08, ORD-09]

coverage:
  - id: D1
    description: "GET /orders com token de BUYER devolve, no envelope Page<T>, só os pedidos da própria empresa — nenhum elemento de content nem o totalElements revelam pedidos de outra empresa"
    requirement: "ORD-08"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderListIT.java#buyerListsOnlyOwnCompanyOrdersAndSellerAdminListsAllInDescendingCreatedAtOrder"
        status: pass
    human_judgment: false
  - id: D2
    description: "GET /orders com token de SELLER_ADMIN devolve pedidos de todas as empresas"
    requirement: "ORD-09"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderListIT.java#buyerListsOnlyOwnCompanyOrdersAndSellerAdminListsAllInDescendingCreatedAtOrder"
        status: pass
    human_judgment: false
  - id: D3
    description: "Ordem sempre createdAt decrescente com id decrescente como desempate; um sort=total,asc enviado pelo cliente é ignorado"
    requirement: "ORD-08"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderListIT.java#statusFilterActsAsApprovalQueueAndOrderingAndPaginationAreServerControlled"
        status: pass
    human_judgment: false
  - id: D4
    description: "?status=PENDING_APPROVAL devolve só pedidos pendentes (a fila de aprovação do vendedor), mesmo filtro funciona para o BUYER; status inválido (inclusive em minúsculas) devolve 400 invalid_parameter"
    requirement: "ORD-09"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderListIT.java#statusFilterActsAsApprovalQueueAndOrderingAndPaginationAreServerControlled"
        status: pass
    human_judgment: false
  - id: D5
    description: "Tamanho de página padrão 20, size acima de 100 reduzido a 100, página além do fim devolve content vazio com totalElements real"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderListIT.java#statusFilterActsAsApprovalQueueAndOrderingAndPaginationAreServerControlled"
        status: pass
    human_judgment: false
  - id: D6
    description: "Cada elemento da listagem é um resumo sem itens (id, companyId, status, total, createdBy, createdAt, decidedBy, decidedAt); o detalhe completo continua em GET /orders/{id}"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderListIT.java#buyerListsOnlyOwnCompanyOrdersAndSellerAdminListsAllInDescendingCreatedAtOrder"
        status: pass
    human_judgment: false
  - id: D7
    description: "BUYER sem o claim company_id recebe 403 forbidden na listagem; requisição sem token recebe 401"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderListIT.java#statusFilterActsAsApprovalQueueAndOrderingAndPaginationAreServerControlled"
        status: pass
    human_judgment: false

duration: 35min
completed: 2026-09-25
status: complete
---

# Phase 4 Plan 3: Listagem Paginada de Pedidos Summary

**`GET /orders` no mesmo envelope `Page<T>` do catálogo: BUYER vê só os pedidos da própria empresa, SELLER_ADMIN vê todos, sempre ordenado por `createdAt`/`id` decrescentes com o `sort` do cliente descartado, `?status=` como fila de aprovação para os dois papéis, e limites de paginação (20 padrão, 100 máximo) provados por teste.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-09-25T22:26:00Z (aproximado)
- **Completed:** 2026-09-25T23:01:46Z
- **Tasks:** 2 (Task 1: tracer — escopo por empresa/ordenação/envelope; Task 2: filtro de status/ordenação imposta/limites de paginação)
- **Files modified:** 5 (2 novos, 3 modificados)

## Accomplishments
- ORD-08: `GET /orders` com token de BUYER devolve, no envelope `Page<T>` do Spring Data, só os pedidos da própria empresa — provado com duas empresas (A com 3 pedidos, B com 2) no mesmo banco, sem vazamento em `content` nem em `totalElements`
- ORD-09: `GET /orders` com token de SELLER_ADMIN devolve pedidos de todas as empresas; `?status=PENDING_APPROVAL` funciona como fila de aprovação para o vendedor e igualmente para o comprador dentro da própria empresa (04-RESEARCH.md Open Question 2 resolvida)
- Ordenação sempre `createdAt`/`id` decrescentes, montada no servidor — um `?sort=total,asc` do cliente é descartado antes da consulta ao Spring Data, provado com 5 pedidos de `created_at` controlado via `JdbcTemplate` (T-04-17)
- Limites de paginação provados: tamanho padrão 20, `size=500` reduzido a 100, `size=2&page=1` devolve exatamente o 3º e o 4º mais recentes, `page=9` além do fim devolve `content` vazio com `totalElements` real (T-04-18)
- `?status=FOO`/`?status=pending_approval` (minúsculo) devolvem 400 `invalid_parameter` pelo mesmo handler do `04-01` (`MethodArgumentTypeMismatchException`) — `GlobalExceptionHandler.java` não foi tocado por este plano, sem conflito com o `04-02` na mesma onda
- `OrderSummaryResponse` — resumo sem a coleção de itens (`id`, `companyId`, `status`, `total`, `createdBy`, `createdAt`, `decidedBy`, `decidedAt`), sem consulta extra por pedido; o detalhe completo continua em `GET /orders/{id}`
- BUYER sem o claim `company_id` recebe 403 `forbidden` (T-04-19); requisição sem token recebe 401

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — comprador lista só os pedidos da própria empresa e vendedor lista todos, do mais recente ao mais antigo** - `d0c6e26` (feat)
2. **Task 2: Filtro por status como fila de aprovação, ordenação imposta pelo servidor e limites de paginação** - `e3e1f1d` (feat) — nenhuma mudança de produção necessária, o comportamento já estava correto desde a Task 1

**Plan metadata:** commit de documentação a ser criado logo após este SUMMARY.

_Nota: as duas tasks carregavam `tdd="true"`; `workflow.tdd_mode` está desativado neste projeto (mesma configuração dos planos anteriores da fase), então o gate rígido de commits separados `test(...)`/`feat(...)` não se aplicava. RED/GREEN: Task 1 — RED foi falha de compilação (`OrderListIT` referenciando `OrderSummaryResponse`/`OrderService.list`/`OrderController.list` inexistentes), GREEN após a implementação completa (1/1 teste). Task 2 — RED foi o segundo teste ainda não escrito no mesmo arquivo; depois de escrito, GREEN de primeira (2/2 testes em `OrderListIT`, nenhuma correção de produção)._

## Files Created/Modified
- `order-service/src/main/java/com/orderflow/order/order/dto/OrderSummaryResponse.java` - resumo sem itens, fábrica `from(Order)`
- `order-service/src/main/java/com/orderflow/order/order/OrderRepository.java` - `findByCompanyId`, `findByCompanyIdAndStatus`, `findByStatus` paginados
- `order-service/src/main/java/com/orderflow/order/order/OrderService.java` - `list(callerCompanyId, sellerView, status, pageable)` com ordenação fixa e escolha de consulta por papel/filtro
- `order-service/src/main/java/com/orderflow/order/order/OrderController.java` - `GET /orders`, escopo derivado de `requireCompanyId`/`isSellerAdmin`, sem parâmetro de empresa
- `order-service/src/test/java/com/orderflow/order/OrderListIT.java` - 2 testes de integração (escopo/ordenação/envelope; filtro/ordenação imposta/paginação)

## Decisions Made
- **`?status=` vale para os dois papéis** — 04-RESEARCH.md Open Question 2 resolvida a favor de permitir o filtro ao BUYER também, registrado no frontmatter `key-decisions`.
- **SELLER_ADMIN sem filtro por empresa nesta fase** — D-47 só pede `?status=`; nenhum parâmetro de query escolhe empresa em nenhum papel.
- **`OrderSummaryResponse.from` nunca acessa `order.getItems()`** — a listagem não paga consulta extra por pedido.

## Deviations from Plan

None - plan executed exactly as written. Nenhum ajuste de Rules 1-4 foi necessário: os testes de Task 1 e Task 2 passaram na primeira execução completa depois da implementação, e nenhuma acceptance criteria exigiu correção.

## Issues Encountered

Nenhum bloqueante. Toda a implementação (Task 1 e Task 2) passou na primeira execução completa dos testes, sem necessidade de reexecuções de correção. A suíte inteira do módulo (`./mvnw -pl order-service verify`) foi reexecutada duas vezes durante este plano (após a Task 1 e após a Task 2) e permaneceu verde nas duas vezes.

## User Setup Required

None - nenhuma configuração de serviço externo é necessária.

## Next Phase Readiness
- `GET /orders?status=PENDING_APPROVAL` está pronto para o plano `04-04` (aprovação/rejeição manual) usar como a fila de aprovação do vendedor.
- `ORDER_RESPONSE_CONTRACT` e o novo resumo (`OrderSummaryResponse`) ficam disponíveis para o smoke do plano `04-05`.
- Nenhum bloqueio conhecido. A reserva de estoque, o Transactional Outbox e a saga permanecem fora de escopo desta fase (Fase 5), como planejado.

---
*Phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito*
*Completed: 2026-09-25*

## Self-Check: PASSED

All key files verified present on disk (`OrderSummaryResponse.java`, `OrderListIT.java`, `OrderController.java`, `OrderService.java`, `OrderRepository.java`). Both task commits (`d0c6e26`, `e3e1f1d`) verified present in `git log --oneline --all`. Full module suite re-verified green (`./mvnw -pl order-service verify` — `BUILD SUCCESS`, 0 failures/errors across all 8 test classes including `OrderListIT` 2/2). All plan-level `<verification>` commands re-run and passing; `GlobalExceptionHandler.java` confirmed untouched by this plan (`git status --short` on the file shows no change).
