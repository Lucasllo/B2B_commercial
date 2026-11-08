# Phase 5: Saga de Reserva de Estoque — Outbox, Compensação e Confirmação - Research

**Researched:** 2026-09-25
**Domain:** Transactional Outbox + orchestrated saga (Spring Cloud AWS SQS) between two existing Spring Boot 3.5/Java 21 microservices (order-service, inventory-service) already sharing Postgres-per-schema and a proven `SqsTemplate`/`@SqsListener` integration from Phase 3.
**Confidence:** MEDIUM-HIGH — the pattern itself (outbox + orchestrated saga) is well-established and every piece of plumbing (Spring Cloud AWS, Testcontainers LocalStack, Spring Retry ordering) is already proven working code in this exact repo from Phases 2–4; the two genuinely new risks (multi-item atomic reservation without breaking the existing retry/self-invocation contract, and a brand-new dual-Spring-Boot-context E2E module) are flagged below as needing an early spike, not assumed to "just work."

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Gatilho e estado intermediário**
- **D-48:** A saga é disparada em toda entrada em APPROVED — aprovação automática dentro do limite (D-45) e aprovação manual do vendedor (D-46). Um único ponto de entrada da saga, usado pelos dois caminhos.
- **D-49:** Novo status `RESERVING`: `CREATED → (PENDING_APPROVAL →) APPROVED → RESERVING → CONFIRMED | CANCELLED`. Exige migração V2 do `CHECK` de status e inclusão no enum `OrderStatus`. — Reversibility: costly — o status aparece no contrato da API (`OrderResponse.status`), nos filtros `?status=`, no smoke e na timeline da Fase 6.
- **D-50:** A decisão de aprovação grava direto `status = RESERVING` + a linha do outbox na mesma transação (sob a trava `company_credit_lock`, D-40). APPROVED é um passo lógico registrado em `decidedBy/decidedAt/reason`, não um estado persistido — nunca existe pedido "APPROVED sem comando". O enum mantém APPROVED (compatibilidade e semântica de decisão), mas nenhum pedido novo repousa nele.
- **D-51:** A migração V2 move pedidos legados em APPROVED → RESERVING e insere um comando de reserva no outbox para cada um — a saga os processa na subida, nenhum fica órfão.
- **D-52:** RESERVING consome crédito — entra em `OrderStatus.CREDIT_CONSUMING` (fonte única, D-37). CANCELLED libera; CONFIRMED continua consumindo.
- **D-53:** Resultado da saga em colunas próprias, separadas da decisão do vendedor: `cancellation_code`, `cancellation_reason` (mensagem legível), `cancelled_at`, `confirmed_at`. `decidedBy/decidedAt/reason` permanecem intactos. Expor no `OrderResponse`.
- **D-54:** `POST /orders` (dentro do limite) e `POST /orders/{id}/approve` devolvem o pedido já em RESERVING com os mesmos códigos HTTP da Fase 4 (201/200). O cliente acompanha por `GET /orders/{id}` — sem 202, sem requisição bloqueante.

**Reserva multi-item e falha**
- **D-55:** Um comando `ReserveStock` carrega todos os itens do pedido; o inventory-service reserva todos numa única transação local, tudo ou nada — se algum faltar, rollback e evento de falha. Não existe reserva parcial a compensar. `reservation_id = orderId` em cada produto (RESERVATION_ID_SCOPE=scope-per-product, D-11). — Reversibility: costly — o formato do comando/evento é contrato entre dois serviços e base do teste E2E.
- **D-56:** O evento de falha traz reason code + detalhe por produto: códigos `INSUFFICIENT_STOCK`, `PRODUCT_NOT_STOCKED` (e `RESERVATION_CANCELLED`, D-66), com lista `{productId, requested, available}`. O pedido grava o código em `cancellation_code` e uma mensagem legível em `cancellation_reason` (ex.: "Estoque insuficiente: produto X — disponível 2, solicitado 5").
- **D-57:** CONFIRMED mantém o estoque reservado (`quantity_reserved += qtd`); a baixa de `quantity_on_hand` (saída física) fica para a Fase 6, na expedição (SHIPPED). Isso substitui a indicação de D-09 de que o "confirmar saída" entraria nesta fase.
- **D-58:** Produto sem linha de estoque (hoje 404 no `reserve`, D-18) é falha de negócio `PRODUCT_NOT_STOCKED` → CANCELLED, nunca erro técnico em retry eterno.

**Mecânica do Outbox**
- **D-59:** Relay por polling `@Scheduled` (~1s, configurável): busca linhas não publicadas em lote com `SELECT ... FOR UPDATE SKIP LOCKED`, envia via `SqsTemplate` e marca `published_at`. Entrega pelo menos uma vez — por isso todo consumidor é idempotente (ORD-06). Sem CDC/Debezium.
- **D-60:** Outbox também no inventory-service: o evento de resultado (`StockReserved` / `StockReservationFailed`) é gravado na mesma transação da reserva, e o `STOCK_ADJUSTED` da Fase 3 migra para o mesmo outbox — fecha a limitação D-29/D-30 (atualizar README). O `StockEventPublisher` de dual-write deixa de existir no caminho síncrono.
- **D-61:** Uma fila por direção, cada uma com DLQ, provisionadas no `localstack-init/ready.d` (único lugar que cria recursos, `queue-not-found-strategy=fail`): `inventory-commands-queue` (order → inventory: `ReserveStock`, `ReleaseStock`) e `order-events-queue` (inventory → order: resultados). A `notification-events-queue` da Fase 3 continua como está. Nomes exatos a critério do planejador.
- **D-62:** Código do outbox (entidade `outbox_event`, repositório, relay) duplicado em cada serviço — sem módulo compartilhado, preservando a autonomia dos serviços (mesmo padrão do `TestJwt` copiado).

**Garantia de "nunca travado"**
- **D-63:** Timeout da saga: job `@Scheduled` no order-service cancela pedidos em RESERVING há mais de N (configurável) com `cancellation_code = RESERVATION_TIMEOUT`, e na mesma transação grava no outbox um `ReleaseStock` para compensar uma reserva que tenha acontecido tarde. A transição passa pela mesma guarda de estado (D-64).
- **D-64:** Idempotência do order-service por transição guardada pelo estado: o resultado só é aplicado se o pedido estiver em RESERVING; duplicata = no-op com log. Resultado "reservado" tardio para pedido já CANCELLED (timeout) → publica `ReleaseStock` pelo outbox. Sem tabela `processed_messages`.
- **D-65:** Idempotência do inventory-service pelo livro `stock_reservations` (D-11/D-14): `ReserveStock` repetido com o mesmo `reservation_id` não decrementa de novo e reemite o mesmo resultado. Critério 4 do roadmap comprovado por teste que republica o comando.
- **D-66:** Lápide (tombstone) no `ReleaseStock`: liberar um `reservation_id` inexistente grava a linha já `released = true`; um `ReserveStock` posterior com o mesmo id encontra a lápide e responde falha `RESERVATION_CANCELLED` sem reservar. Resolve a corrida da fila SQS padrão (sem ordem garantida) sem migrar para FIFO. Ajusta D-14 (liberação de inexistente deixa de ser no-op puro). Atenção: `stock_reservations.product_id` tem FK para `inventory(product_id)` — o planejador decide como tratar lápide para produto sem linha de estoque. — Reversibility: costly — muda a semântica do livro de idempotência compartilhado pela reserva REST e pela saga.
- **D-67:** No consumidor do inventory, falha de negócio vira evento de falha (mensagem consumida com sucesso); só erro técnico lança exceção e deixa o SQS reentregar até a DLQ.

**Testes e demonstração**
- **D-68:** E2E do critério 5 num novo módulo Maven `e2e-tests` que sobe order-service e inventory-service como dois contextos Spring no mesmo JVM, com Postgres e LocalStack reais via Testcontainers; auth-service e catalog-service stubados (padrão `DownstreamStubServer` da Fase 4). Um comando: `./mvnw -pl e2e-tests verify` (ou equivalente). Cobre sucesso → CONFIRMED (reserved refletido no inventory) e falha → CANCELLED; teste do caminho de falha escrito antes do caminho feliz (critério 3).
- **D-69:** Demonstração na stack real com `scripts/smoke-order-saga.sh` (estilo dos smokes existentes): pedido com estoque suficiente → espera CONFIRMED e confere `reserved`; pedido sem estoque → espera CANCELLED com motivo. `scripts/smoke-order-flow.sh` da Fase 4 é ajustado para aceitar RESERVING/CONFIRMED no lugar de APPROVED.

### Claude's Discretion
- Valores padrão: intervalo do relay, timeout da saga (ex.: 2 min), `maxReceiveCount` antes da DLQ (ex.: 3), tamanho de lote — todos por propriedade, valores curtos nos testes.
- Formato do envelope dos eventos (eventId, type, occurredAt, payload), nomes exatos de filas, tipos de evento e colunas; uso de `doNotSendPayloadTypeHeader`/`setPayloadTypeMapper` já provados na Fase 3.
- Limpeza das linhas publicadas do outbox (job de retenção ou nenhuma nesta fase).
- Se o CANCELLED por falha precisa tomar a trava `company_credit_lock` (liberar crédito não arrisca estourar limite).
- Se os endpoints REST de reserva/liberação do inventory (SELLER_ADMIN, Fase 2) permanecem — a "identidade de serviço" prevista no javadoc do `InventoryController` deixa de ser necessária, pois order ↔ inventory conversam só por SQS.

### Deferred Ideas (OUT OF SCOPE)
- Baixa de `quantity_on_hand` na expedição — Fase 6.
- Publicar eventos da saga (reservando/confirmado/cancelado) para o notification-service — Fase 6.
- SQS FIFO / SNS fan-out — descartados agora (D-61, D-66).
- Módulo compartilhado de outbox — descartado (D-62).
- Cancelamento de pedido pelo comprador — capacidade nova, não prevista no roadmap.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| ORD-04 | Order-service publica evento de reserva de estoque via SQS usando o padrão Transactional Outbox (evento gravado na mesma transação da mudança de estado do pedido) | §Architecture Patterns Pattern 1 (Outbox table + `SELECT...FOR UPDATE SKIP LOCKED` relay), §Code Examples (outbox repository), existing `CompanyCreditLockRepository` native-query pattern reused |
| ORD-05 | Order-service consome o resultado da reserva de estoque e transiciona o pedido para CONFIRMED (sucesso) ou CANCELLED (falha) | §Architecture Patterns Pattern 2 (state-guarded idempotent consumer), §Common Pitfalls (self-invocation, retry-vs-transaction ordering) |
| ORD-06 | Consumidores SQS processam cada evento de forma idempotente, mesmo diante de entrega duplicada | §Architecture Patterns Pattern 2 and Pattern 3 (order-side state guard, inventory-side `stock_reservations` book + tombstone) |
| TEST-03 | Ao menos um teste E2E/contrato verifica o fluxo completo da saga (criação do pedido → reserva de estoque com sucesso ou falha → status final) | §Validation Architecture, §Common Pitfalls (dual-application.yml classpath collision risk), existing `LocalStackTestSupport`/`DownstreamStubServer` patterns to duplicate into `e2e-tests` |
| ORD-10 (referenced) | Status do pedido segue a máquina de estados — este fase acrescenta RESERVING | §Standard Stack / Order.java changes, D-49/D-50 |
</phase_requirements>

## Summary

This phase wires the project's Core Value: order-service and inventory-service exchange commands/results over SQS using the Transactional Outbox pattern in both services, with order-service acting as the saga orchestrator. Every piece of low-level plumbing needed here (Spring Cloud AWS `SqsTemplate`/`@SqsListener`, the `doNotSendPayloadTypeHeader` converter trick, Testcontainers `LocalStackContainer` with a shared singleton, `queue-not-found-strategy=fail`, Spring Retry aspect-ordering with `@Transactional`, `SELECT...FOR UPDATE` native queries with the `{h-schema}` marker) already exists as proven, working code from Phases 2–4 — this phase is primarily an exercise in *composing* those exact same techniques into a new bidirectional flow, not inventing new infrastructure.

Two things are genuinely new and risk-bearing, and this research flags both explicitly rather than assuming they will "just work": (1) the existing `InventoryService.reserve()` method cannot be called in a loop from another method of the same bean (self-invocation bypasses the Spring proxy, per its own javadoc) nor safely nested inside an outer `@Transactional` while retaining `@Retryable` semantics — the multi-item all-or-nothing reservation (D-55) needs a **new** method built the same way `reserve()` already is (retry-wraps-transaction on the *same* method, not composed from calls to the existing single-item method); (2) the new `e2e-tests` Maven module boots **two full Spring Boot applications in one JVM**, each shipping its own `application.yml` at the classpath root inside its own jar — Spring's classpath: resource resolution is documented (Spring Framework Resources reference) to return only the *first* match via `ClassLoader.getResource()`, not a merge, which risks one service's context silently loading the other's configuration. This is flagged as a Wave-0 spike item, not asserted as a known failure (the official Spring Boot docs are silent on the exact multi-jar scenario), but it materially changes how the e2e module's test config should be built (explicit `@DynamicPropertySource`/`@TestPropertySource` overrides for everything, never relying on the packaged `application.yml`).

**Primary recommendation:** Reuse the exact retry/transaction-ordering technique already proven in `InventoryService`/`RetryConfig`/`InventoryRetryContentionIT` for the new multi-item reservation method (retry aspect wrapping a single `@Transactional` method, never composed by calling the existing per-product `reserve()`/`release()` in a loop), reuse the exact outbox-relay technique from `CompanyCreditLockRepository`'s native-query `{h-schema}` pattern for `SELECT...FOR UPDATE SKIP LOCKED`, and treat the `e2e-tests` module's classpath-collision risk as a first task to verify empirically before writing the real saga test.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Order status transition to RESERVING + outbox write | API/Backend (order-service) | Database/Storage (Postgres `orders`/`outbox_event`) | Same transaction as the credit-lock-guarded status change (D-50) — no I/O inside the transaction (D-41 precedent) |
| Outbox relay (poll + publish) | API/Backend (order-service, inventory-service) | — | `@Scheduled` polling job inside each service process, not a separate infra component (D-59) |
| ReserveStock/ReleaseStock command exchange | API/Backend (order-service ⇄ inventory-service via SQS/LocalStack) | — | Async, no direct REST between the two services (ARCHITECTURE.md Anti-Pattern 1) |
| Multi-item atomic reservation | API/Backend (inventory-service) | Database/Storage (`inventory`, `stock_reservations` with `chk_inventory_not_oversold`) | All-or-nothing in one local transaction (D-55); DB constraint is the final safety net, same precedent as Phase 2 |
| Idempotent consumer guard (order side) | API/Backend (order-service) | Database/Storage (`orders.status` as the guard) | State-guarded transition, no new dedup table (D-64) |
| Idempotent consumer guard (inventory side) | API/Backend (inventory-service) | Database/Storage (`stock_reservations` unique constraint + tombstone) | Existing book reused, extended with tombstone semantics (D-65/D-66) |
| Saga timeout/compensation | API/Backend (order-service) | Database/Storage + messaging (outbox `ReleaseStock`) | `@Scheduled` job, same transactional-outbox discipline as the forward path (D-63) |
| E2E saga verification | Test infrastructure (new `e2e-tests` Maven module) | — | Not a runtime tier; two full Spring contexts + Testcontainers Postgres/LocalStack in one JVM (D-68) |

## Standard Stack

No new external technology choices are needed — every library required by this phase is already pinned in the root `pom.xml` dependency management and already proven working in `inventory-service` or `notification-service`. This phase's stack work is *adding the already-approved dependencies to order-service's pom* and *creating a new pom for `e2e-tests`*, not evaluating new libraries.

### Core (all already pinned/proven — see Package Legitimacy Audit)

| Library | Version (verified in repo) | Purpose | Why Standard |
|---------|-----|---------|--------------|
| `io.awspring.cloud:spring-cloud-aws-starter-sqs` | 3.4.2 via `spring-cloud-aws-dependencies` BOM `[VERIFIED: pom.xml:58,98-104]` | `SqsTemplate` (producer) + `@SqsListener` (consumer) for both new queues | Already used by inventory-service (`StockEventPublisher`) and notification-service (`NotificationEventListener`); order-service currently lacks this dependency and must add it `[VERIFIED: order-service/pom.xml:1-118 — no io.awspring.cloud dependency present]` |
| `org.testcontainers:localstack` | managed by `testcontainers-bom` 1.20.4 `[VERIFIED: pom.xml:38-39,79-86]` | Real LocalStack container in order-service's new integration tests and in `e2e-tests` | Already used by inventory-service (`LocalStackTestSupport`) `[VERIFIED: inventory-service/pom.xml:134-138]`; order-service has no LocalStack test dependency yet |
| `org.awaitility:awaitility` | managed by `testcontainers-bom`/direct, already a test dep in inventory-service `[VERIFIED: inventory-service/pom.xml:139-144]` | Polling assertions for async saga outcomes (`GET /orders/{id}` eventually CONFIRMED/CANCELLED) | Same technique needed for order-service's new saga ITs and for `e2e-tests` |
| Flyway `V2__*.sql` per service | flyway-core/flyway-database-postgresql, already pinned | New `outbox_event` table (both services), `orders` status/columns migration, `stock_reservations` tombstone-related change if any | Same migration convention already used (`V1__init_order_schema.sql`, `V1__init_inventory_schema.sql`) `[VERIFIED: order-service/src/main/resources/db/migration/V1__init_order_schema.sql:1-56, inventory-service/src/main/resources/db/migration/V1__init_inventory_schema.sql:1-38]` |
| Spring Retry (`org.springframework.retry:spring-retry` + `spring-boot-starter-aop`) | already in inventory-service `[VERIFIED: inventory-service/pom.xml:59-71]`, `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)` `[VERIFIED: inventory-service/src/main/java/com/orderflow/inventory/config/RetryConfig.java:1-33]` | Multi-item reservation retry on optimistic-lock conflicts | Reuse the exact aspect-ordering fix already proven by `InventoryRetryContentionIT` — do not re-derive the ordering value from scratch |

### New Maven module: `e2e-tests`

| Property | Value |
|----------|-------|
| Packaging | `jar`, test-only (no `src/main`, only `src/test/java`) `[ASSUMED — no such test-only module exists yet in this repo to verify against; standard Maven shape]` |
| Dependencies | `order-service` and `inventory-service` as plain module dependencies (default scope) — resolvable because the parent's `spring-boot-maven-plugin` execution sets `<classifier>exec</classifier>` on `repackage`, which means each module's **main** artifact stays the plain (unshaded) jar `[VERIFIED: pom.xml:122-136 — comment explicitly states "classifier=exec mantém o jar plano (auth-service.jar) como artefato principal"]`; plus `spring-boot-starter-test`, `testcontainers` (`postgresql`, `localstack`), `awaitility` |
| Build plugins | `maven-failsafe-plugin` only (declare it in `e2e-tests/pom.xml` to inherit the parent's `pluginManagement` execution binding, exactly as `order-service`/`inventory-service` already do) `[VERIFIED: order-service/pom.xml:112-115, pom.xml:161-179]` — do **not** declare `spring-boot-maven-plugin` in this module (it has no runnable main class of its own) |
| Root pom | Add `<module>e2e-tests</module>` to `pom.xml`'s `<modules>` list `[VERIFIED: pom.xml:20-27]` |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `@Scheduled` polling outbox relay (D-59, locked) | Debezium/CDC | Already rejected by CONTEXT.md and by `ARCHITECTURE.md`/`PITFALLS.md` as overkill for this scale — not re-litigated here |
| Two Spring Boot contexts in one JVM for E2E (D-68, locked) | `docker compose` + shell script hitting the real gateway (like `scripts/smoke-order-flow.sh`) | The shell-script smoke (D-69) already covers "real stack" demonstration; the Testcontainers E2E module is what satisfies TEST-03's requirement for an automated, `mvn verify`-able test, not a manual demo |
| Retry-wraps-transaction on a *new* single method (recommended here) | Composing the new multi-item reservation from N calls to the existing `InventoryService.reserve()` | Self-invocation from within the same bean bypasses the Spring AOP proxy entirely — no retry, no per-call transaction boundary — this is explicitly documented as forbidden by `InventoryService`'s own javadoc |

**Installation (order-service `pom.xml` — add to `<dependencies>`, mirroring inventory-service exactly):**
```xml
<dependency>
    <groupId>io.awspring.cloud</groupId>
    <artifactId>spring-cloud-aws-starter-sqs</artifactId>
</dependency>
<!-- test scope -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>localstack</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

**Version verification:** All versions above come from the root `pom.xml` BOM imports already read this session (`spring-boot.version` 3.5.16, `spring-cloud-aws.version` 3.4.2, `testcontainers.version` 1.20.4) — no registry lookup needed since these are already resolved, building dependencies of this exact reactor, not new external claims.

## Package Legitimacy Audit

No new external packages are introduced by this phase. Every dependency order-service needs to add (`spring-cloud-aws-starter-sqs`, `testcontainers:localstack`, `awaitility`) is already present, resolved, and running in `inventory-service`'s `pom.xml` today, managed by BOMs already imported in the root `pom.xml` (`[VERIFIED: pom.xml:93-104]`, `[VERIFIED: inventory-service/pom.xml:94-102,129-144]`). The new `e2e-tests` module introduces no new third-party dependency either — only intra-reactor dependencies on `order-service`/`inventory-service` plus already-used test libraries.

| Package | Registry | Age (in this project) | Source Repo | Verdict | Disposition |
|---------|----------|------|-------------|---------|-------------|
| `io.awspring.cloud:spring-cloud-aws-starter-sqs` | Maven Central | In use since Phase 3 (03-01) | github.com/awspring/spring-cloud-aws | OK | Approved — add to order-service, no re-audit needed |
| `org.testcontainers:localstack` | Maven Central | In use since Phase 3 | github.com/testcontainers/testcontainers-java | OK | Approved — add to order-service and `e2e-tests` |
| `org.awaitility:awaitility` | Maven Central | In use since Phase 2/3 | github.com/awaitility/awaitility | OK | Approved — add to order-service and `e2e-tests` |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none.

## Architecture Patterns

### System Architecture Diagram

```
BUYER/SELLER (via Gateway)                order-service                         inventory-service
      │                                         │                                      │
      │ POST /orders (auto-approve)             │                                      │
      │ or POST /orders/{id}/approve            │                                      │
      ├────────────────────────────────────────►│                                      │
      │                                         │ @Transactional (company_credit_lock) │
      │                                         │  status = RESERVING                  │
      │                                         │  INSERT outbox_event(ReserveStock)   │
      │                                         │  COMMIT                              │
      │                                         │                                      │
      │◄──── 200/201 body: status=RESERVING ────┤                                      │
      │                                         │                                      │
      │                                         │ @Scheduled relay (~1s)               │
      │                                         │  SELECT...FOR UPDATE SKIP LOCKED      │
      │                                         │  sqsTemplate.send(inventory-commands-queue)
      │                                         ├─────────────────────────────────────►│
      │                                         │                                      │ @SqsListener ReserveStock
      │                                         │                                      │ @Transactional (all items,
      │                                         │                                      │  tudo-ou-nada, D-55)
      │                                         │                                      │  check stock_reservations
      │                                         │                                      │  (idempotent replay / tombstone)
      │                                         │                                      │  reserve or fail
      │                                         │                                      │  INSERT outbox_event(
      │                                         │                                      │   StockReserved|StockReservationFailed)
      │                                         │                                      │  COMMIT
      │                                         │                                      │
      │                                         │                                      │ @Scheduled relay (~1s)
      │                                         │◄─────────────────────────────────────┤ sqsTemplate.send(order-events-queue)
      │                                         │ @SqsListener (order-events-queue)    │
      │                                         │ guard: order.status == RESERVING?    │
      │                                         │  yes → CONFIRMED | CANCELLED+reason  │
      │                                         │  no  → no-op (dup) OR late-CONFIRMED │
      │                                         │        while CANCELLED → outbox      │
      │                                         │        ReleaseStock (compensation)   │
      │                                         │                                      │
      │              GET /orders/{id} ──────────►│  (poll — D-54, no 202/blocking)      │
      │◄──────── status=CONFIRMED|CANCELLED ────┤                                      │
      │                                         │                                      │
      │                                         │ @Scheduled timeout job (D-63)        │
      │                                         │  RESERVING older than N min          │
      │                                         │  → CANCELLED(RESERVATION_TIMEOUT)    │
      │                                         │  + outbox ReleaseStock (compensation)│
      │                                         ├─────────────────────────────────────►│ (may hit tombstone if
      │                                         │                                      │  reservation never happened)
```

### Recommended Project Structure

```
order-service/src/main/java/com/orderflow/order/
├── order/                      # existing — Order, OrderStatus, OrderRepository, services
│   └── OrderStatus.java        # + RESERVING
├── saga/                       # NEW — saga-specific classes, kept out of order/ to avoid
│   │                           #   file collisions with Phase 4 classes in the same wave
│   ├── outbox/
│   │   ├── OutboxEvent.java            # entity
│   │   ├── OutboxEventRepository.java  # native SELECT...FOR UPDATE SKIP LOCKED
│   │   └── OutboxRelay.java            # @Scheduled poller
│   ├── messaging/
│   │   ├── dto/ReserveStockCommand.java
│   │   ├── dto/ReleaseStockCommand.java
│   │   ├── dto/StockReservedEvent.java
│   │   ├── dto/StockReservationFailedEvent.java
│   │   └── ReservationResultListener.java   # @SqsListener(order-events-queue)
│   └── SagaTimeoutJob.java              # @Scheduled compensation job (D-63)
└── config/SqsMessagingConfig.java       # NEW — mirrors inventory-service's converter/timeout config

inventory-service/src/main/java/com/orderflow/inventory/
├── stock/                       # existing — Inventory, InventoryService, StockReservation
│   └── InventoryService.java    # + new reserveAll(...) all-or-nothing method (NOT built by
│                                 #   looping calls to the existing reserve())
├── saga/
│   ├── outbox/                  # same shape as order-service's (duplicated per D-62)
│   └── messaging/
│       ├── dto/ (mirrors order-service's command/event DTOs — JSON contract only)
│       └── ReservationCommandListener.java  # @SqsListener(inventory-commands-queue)

e2e-tests/                       # NEW top-level Maven module
├── pom.xml                      # depends on order-service + inventory-service jars
└── src/test/java/com/orderflow/e2e/
    ├── support/                 # duplicated LocalStackTestSupport/DownstreamStubServer-style
    │   #   helpers — cannot reuse order-service's test-jar without publishing one (see Pitfall)
    └── OrderReservationSagaE2EIT.java   # failure path written first (D-68/criterion 3)
```

### Pattern 1: Transactional Outbox with `SELECT...FOR UPDATE SKIP LOCKED` relay

**What:** Both services get an `outbox_event` table written in the *same* transaction as the business change. A `@Scheduled` method polls unpublished rows, locks a batch with `FOR UPDATE SKIP LOCKED` (never blocking on rows another poll tick might already be sending), publishes via `SqsTemplate`, and marks `published_at` in the same transaction as the lock — so a crash mid-publish leaves the row locked-but-unpublished for the next tick, never double-marked-sent-but-never-sent.

**When to use:** Every write in this phase that must be atomic with an SQS publish: order-service's RESERVING transition, order-service's CONFIRMED/CANCELLED transition when it also needs to emit a compensating `ReleaseStock` (timeout path, late-result path), and inventory-service's `StockReserved`/`StockReservationFailed` (and the migrated `STOCK_ADJUSTED`, D-60).

**Example (native query, following the exact `{h-schema}`-marker convention already used for `company_credit_lock`):**
```java
// Source: pattern generalized from CompanyCreditLockRepository (order-service, Phase 4)
// [VERIFIED: order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLockRepository.java:19-33]
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = "SELECT * FROM {h-schema}outbox_event "
            + "WHERE published_at IS NULL "
            + "ORDER BY created_at ASC "
            + "LIMIT :batchSize "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);
}
```
```java
// Relay — one @Transactional tick, batch lock + publish + mark, mirrors the "no I/O inside a
// business transaction" precedent (D-41) EXCEPT here the SQS send IS the point of the
// transaction (D-59) — unlike CompanyCreditLocker, which never does I/O under its lock.
@Scheduled(fixedDelayString = "${orderflow.outbox.relay-interval:1000}")
@Transactional
public void relay() {
    List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(batchSize);
    for (OutboxEvent event : batch) {
        sqsTemplate.send(to -> to.queue(resolveQueue(event.getEventType())).payload(event.getPayload()));
        event.markPublished(Instant.now());
    }
}
```

**Confirmed via WebSearch (MEDIUM confidence, cross-referenced):** the `FOR UPDATE SKIP LOCKED` pattern for a Postgres-backed outbox/job-queue relay, and the Hibernate `@QueryHints`/native-query alternatives, are the standard community approach for exactly this scenario `[CITED: vladmihalcea.com/database-job-queue-skip-locked, dev.to outbox-pattern article]`.

### Pattern 2: State-guarded idempotent consumer (order-service side, D-64)

**What:** No `processed_messages`/dedup table. The guard is the order's own `status` column: the result listener only applies a transition if `order.status == RESERVING`. A duplicate delivery of the same result event finds the order already `CONFIRMED`/`CANCELLED` and no-ops with a log line. A *late* success result arriving after the timeout job already cancelled the order is a special case: it must **not** silently no-op — it must publish a compensating `ReleaseStock` via the outbox, because inventory-service *did* actually reserve stock that order-service no longer wants.

```java
// Conceptual — the guard is read-modify-write under the row lock JPA already provides via
// findById + @Version-less entity update inside @Transactional; no new lock table needed since
// there is exactly one writer path (this listener) plus the timeout job, both @Transactional on
// the same Order row, standard JPA optimistic path already covers the race.
@Transactional
public void onReservationResult(ReservationResultEvent event) {
    Order order = orderRepository.findById(event.orderId()).orElseThrow(...);
    if (order.getStatus() != OrderStatus.RESERVING) {
        if (event.isSuccess() && order.getStatus() == OrderStatus.CANCELLED) {
            // late reservation success after a timeout cancellation — compensate (D-64)
            outboxEventRepository.save(OutboxEvent.releaseStock(order.getId(), event.reservationLines()));
        }
        log.info("Ignoring duplicate/late saga result for order {} (status={})", order.getId(), order.getStatus());
        return;
    }
    if (event.isSuccess()) {
        order.confirm(Instant.now());
    } else {
        order.cancel(event.reasonCode(), event.readableReason(), Instant.now());
    }
}
```

### Pattern 3: Multi-item all-or-nothing reservation WITHOUT breaking the existing retry contract (inventory-service, D-55)

**What goes wrong if built naively:** `InventoryService.reserve(productId, reservationId, quantity)` is `@Retryable` + `@Transactional` on the **same method**, and its own javadoc states reservation methods "são chamados apenas de fora deste bean" — calling `reserve()` in a loop from a *new* method on the *same* `InventoryService` bean bypasses the Spring AOP proxy (self-invocation), silently losing both the retry and the per-call transaction semantics `[VERIFIED: inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java:17-28 javadoc]`. Calling the existing `reserve()` from a *different* bean, inside an already-open outer `@Transactional`, is also unsafe: `@Transactional(REQUIRED)` (the default) would make each call **join** the caller's transaction rather than open its own, so a caught `ObjectOptimisticLockingFailureException` on item 3 of 5 marks the *whole* outer transaction rollback-only before Spring Retry's next attempt runs — the retry would then fail with `UnexpectedRollbackException` on attempt 2, not actually retry the row it needs to.

**Correct approach:** Build **one new method**, `InventoryService.reserveAll(List<ReservationLine> lines)`, with `@Retryable` + `@Transactional` on that *same new method* — exactly the same shape as the existing `reserve()` — that internally loops over `lines` doing the repository-level check/insert/reserve logic **directly against `InventoryRepository`/`StockReservationRepository`**, never by delegating to the public single-item `reserve()`. This reproduces the exact aspect-ordering already proven safe by `RetryConfig`'s `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)` and validated by `InventoryRetryContentionIT` `[VERIFIED: inventory-service/src/main/java/com/orderflow/inventory/config/RetryConfig.java:1-33]`: each retry attempt gets a genuinely fresh transaction because the previous attempt's transaction has already fully rolled back by the time the retry aspect (which wraps *outside* the transactional aspect) re-invokes the method.

```java
// Conceptual shape — same annotation pattern as the existing reserve(), generalized to N items,
// all inside ONE transaction (tudo ou nada, D-55). NOT calling the public reserve()/release().
@Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
        maxAttempts = RETRY_MAX_ATTEMPTS,
        backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
@Transactional
public ReservationOutcome reserveAll(String reservationId, List<ReservationLine> lines) {
    // idempotent replay: if EVERY line already has a stock_reservations row for reservationId,
    // return the prior outcome without touching stock again (D-65).
    // tombstone check: if any reservation_id row for these products is already `released=true`
    // (D-66), fail RESERVATION_CANCELLED without reserving anything.
    // else: for each line, check inventory row exists (else PRODUCT_NOT_STOCKED, D-58),
    // check availability (else INSUFFICIENT_STOCK, D-56), and only after ALL lines pass,
    // apply every reserve()+saveAndFlush() write — a single failing line throws, which rolls
    // back the WHOLE method's transaction (no partial reservation ever committed).
}
```

**Why this is the single most valuable non-obvious finding of this research:** the CONTEXT.md decisions (D-55) state *what* must happen ("tudo ou nada numa única transação") but not *how*, given the existing code's explicit self-invocation warning. Getting this wrong produces a subtle bug that will not surface in a simple happy-path manual test — exactly the class of failure `PITFALLS.md` Pitfall 7 warns about (Mockito-only confidence masking real transactional/proxy behavior).

### Pattern 4: Tombstone semantics for `ReleaseStock` (D-66) and the FK constraint question

`stock_reservations.product_id` has `REFERENCES inventory(product_id)` `[VERIFIED: inventory-service/src/main/resources/db/migration/V1__init_inventory_schema.sql:29]`. A tombstone for a `PRODUCT_NOT_STOCKED` product (D-58: no inventory row exists at all) cannot be inserted while that FK is enforced. CONTEXT.md explicitly defers this to the planner ("o planejador decide como tratar lápide para produto sem linha de estoque" — D-66). This research surfaces two concrete options rather than picking one, since it is a locked-as-open decision:

- **Option A (schema change):** Drop the FK on `stock_reservations.product_id` in the V2 migration, treating `product_id` as an opaque reference like `company_id`/`product_id` already are treated everywhere else cross-service (`D-15`'s "referência opaca" convention, already the norm in this codebase). This is the most consistent with existing conventions but is exactly the kind of change CONTEXT.md marks costly to reverse (D-66's own reversibility note).
- **Option B (no schema change):** Auto-create a zero-stock `inventory` row the first time a tombstone is needed for an unknown product, satisfying the FK without changing it. Simpler migration, but creates inventory rows for products that were never legitimately stocked (a `PRODUCT_NOT_STOCKED` failure would then look, in the `inventory` table, indistinguishable from a legitimately-zero-stock product on subsequent queries).

Recommend the planner pick explicitly and document the choice as a new decision (not left implicit in code) — this is exactly the kind of thing an ADR/checkpoint should capture given the phase's stated reversibility concern.

### Anti-Patterns to Avoid
- **Composing `reserveAll` from calls to the existing `reserve()`:** see Pattern 3 — self-invocation bypass, or unsafe nested-transaction-plus-retry.
- **A `processed_messages`/dedup table:** explicitly rejected by D-64/D-65 — the existing `orders.status` and `stock_reservations` book are already the idempotency source of truth; adding a third table duplicates a source of truth this project has deliberately kept singular (same "single source" philosophy already applied to `CREDIT_CONSUMING`, D-37).
- **SQS FIFO to fix the tombstone race:** D-66 explicitly resolves the standard-queue ordering race with the tombstone technique instead of migrating to FIFO — do not reintroduce FIFO as a "simpler" fix.
- **Publishing `STOCK_ADJUSTED` via the old direct `StockEventPublisher` path once the inventory outbox exists (D-60):** the whole point of this phase is closing the D-29/D-30 dual-write gap; leaving `STOCK_ADJUSTED` on the old direct-publish path while adding a new outbox for saga events would be an inconsistent half-migration.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SQS message send/receive | Raw `SqsAsyncClient` calls | `SqsTemplate` (producer) / `@SqsListener` (consumer) | Already the established pattern in this repo (Phase 3); consistent with `spring-cloud-aws-starter-sqs` already pinned |
| Outbox row locking for the relay | Application-level in-memory mutex / `synchronized` | Postgres `SELECT...FOR UPDATE SKIP LOCKED` via native query | DB-level locking is correct even if this project only ever runs one instance per service — it is also what makes the pattern honestly explainable as "would scale to N pollers" in an interview |
| Duplicate-delivery dedup | A new `processed_messages` table | The existing state columns (`orders.status`, `stock_reservations`) | D-64/D-65 — this project already has a stated "single source of truth" philosophy (D-37 `CREDIT_CONSUMING`); do not add a second idempotency mechanism alongside it |
| Two-service E2E test harness | A brand-new test framework/tool | Two `@SpringBootTest` contexts in one JUnit 5 run, reusing the exact singleton-container pattern already proven in `OrderTestInfrastructure`/`LocalStackTestSupport` | Consistent with the project's existing test infrastructure conventions; avoids introducing e.g. a separate BDD/contract-testing tool this late in the project for one test |

**Key insight:** almost nothing in this phase is a "new pattern" — it is the same five techniques (SqsTemplate, `@SqsListener`, native `{h-schema}` locking queries, `@Retryable`+`@Transactional` co-location, singleton Testcontainers) already proven in Phases 2–4, recombined into a new bidirectional flow. The risk in this phase is composition mistakes (self-invocation, transaction/retry ordering, classpath collisions), not missing infrastructure.

## Runtime State Inventory

> Included because D-51 requires migrating **existing** `orders` rows (any left in `APPROVED` from Phase 4 usage/tests against a persisted dev database) into `RESERVING` plus a backfilled outbox row — this is a real data migration, not just a schema change.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `orders` rows already `APPROVED` under the current `V1__init_order_schema.sql` CHECK constraint, if any dev/demo database has been used since Phase 4 `[VERIFIED: order-service/src/main/resources/db/migration/V1__init_order_schema.sql:17-19]` | V2 migration must both widen the CHECK to include `RESERVING` **and** `UPDATE orders SET status='RESERVING' WHERE status='APPROVED'` plus `INSERT INTO outbox_event (...)` one row per migrated order (D-51) — a Flyway SQL migration, not a Java backfill job, since it must run before the app (and its saga listeners) starts |
| Live service config | None — `inventory-commands-queue`/`order-events-queue` are new resources created by the `localstack-init/ready.d` script (D-61), not pre-existing external config to migrate | Add a new numbered script (e.g. `02-create-order-saga-resources.sh`) alongside the existing `01-create-notification-resources.sh` `[VERIFIED: localstack-init/ready.d/01-create-notification-resources.sh:1-34]` |
| OS-registered state | None — no Task Scheduler/systemd/pm2 registrations anywhere in this repo | Nothing to do |
| Secrets/env vars | None new — `SPRING_CLOUD_AWS_ENDPOINT` env var already exists for inventory-service/notification-service in `docker-compose.yml`; order-service needs the same var added `[VERIFIED: docker-compose.yml:108-109,130]` | Add `SPRING_CLOUD_AWS_ENDPOINT` to order-service's compose block, and add `localstack: condition: service_healthy` to its `depends_on` (currently absent `[VERIFIED: docker-compose.yml:140-151]`) |
| Build artifacts | None affected — no renamed packages/artifacts this phase | Nothing to do |

## Common Pitfalls

### Pitfall 1: Self-invocation / retry-transaction ordering breaks the "tudo ou nada" reservation
**What goes wrong:** Building the multi-item reservation by looping calls to the existing `InventoryService.reserve()` — either from inside the same bean (proxy bypass, silent) or from a different bean nested in an outer transaction (retry/transaction interaction failure, `UnexpectedRollbackException`).
**Why it happens:** It looks like the most natural way to reuse "already tested" code.
**How to avoid:** New method (`reserveAll`), same annotation shape as the existing `reserve()`, internal logic against repositories directly. See Pattern 3.
**Warning signs:** A code review finds `inventoryService.reserve(...)` called in a loop from anywhere other than `InventoryController`.

### Pitfall 2: `e2e-tests` module's two packaged `application.yml` files collide on the classpath
**What goes wrong:** Both `order-service` and `inventory-service` jars ship `application.yml` at the classpath root. Spring's classpath: resource resolution (singular prefix) is documented Spring Framework behavior to use `ClassLoader.getResource()` — first match only, not a merge — when multiple JARs on the classpath contain the same resource path `[CITED: docs.spring.io/spring-framework/reference/core/resources.html; Baeldung "Access a File from the Classpath using Spring"]`. Applied to Spring Boot's own default `classpath:/` config-data search location, this **could** mean one service's `@SpringBootTest` context silently loads the *other* service's `application.yml` (wrong schema name, wrong queue name, wrong port default) when both jars are on the same test classpath in `e2e-tests`. The official Spring Boot docs do not explicitly describe this exact multi-jar-dependency scenario, so this is flagged as a real risk grounded in general Spring resource-resolution mechanics, **not asserted as a confirmed failure** — no positive falsification was run in this session (absent-evidence rule).
**Why it happens:** Multi-module test harnesses booting more than one packaged Spring Boot app in the same JVM are rare enough that this scenario is undocumented territory.
**How to avoid:** Treat this as a Wave 0 spike: write a trivial test in `e2e-tests` that starts only `OrderServiceApplication`'s context and asserts a property known to be order-service-specific (e.g., `server.port` default or `spring.flyway.schemas=order`) resolves correctly with **both** jars on the classpath; if it does not, override **every** needed property explicitly via `@DynamicPropertySource`/`@TestPropertySource` per context in `e2e-tests` (mirroring the project's existing convention of never trusting classpath `application.yml` alone for `@DynamicPropertySource`-driven values — see `OrderTestInfrastructure.register`).
**Warning signs:** `e2e-tests`' order-service context reports `inventory` as its Flyway schema, or vice versa; wrong server port assumptions; Flyway trying to run inventory-service's migrations against order-service's schema.

### Pitfall 3: Saga rollback treated like a `@Transactional` rollback (project-level pitfall, directly applicable)
Already documented at project inception `[CITED: .planning/research/PITFALLS.md Pitfall 1]` — restated here because this is the phase where it becomes concrete: order-service must never mark an order `CONFIRMED` before the success event arrives, and the failure/timeout compensation path (D-63/D-64) must be written and tested **before** the happy path (explicitly required by roadmap Success Criterion 3 and D-68).

### Pitfall 4: Dual-write reintroduced for `STOCK_ADJUSTED` if the migration to the inventory outbox is incomplete
D-60 requires `STOCK_ADJUSTED` to move into the same outbox as the new saga events — if the planner only builds the outbox for the *new* events and leaves `InventoryController`'s existing post-commit `StockEventPublisher.publishStockAdjusted(...)` call in place `[VERIFIED: inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java:46-51]`, the project ships with **two** competing publish mechanisms and the D-29/D-30 known limitation stays open despite the phase claiming to close it.
**How to avoid:** `InventoryController.setStock` must write an `outbox_event` row inside `InventoryService.setStock`'s existing `@Transactional` boundary instead of calling `stockEventPublisher.publishStockAdjusted(...)` after the method returns; `StockEventPublisher` (the direct-publish class) should be deleted, not left dead in the codebase.

### Pitfall 5: LocalStack quirks around DLQ/redrive timing (project-level pitfall, applicable to the new queues)
`[CITED: .planning/research/PITFALLS.md Pitfall 6]` — DLQ redrive on LocalStack has documented timing quirks (redrive only takes effect on the *next* `ReceiveMessage` call). This phase introduces the project's first real DLQ usage (D-61: "cada uma com DLQ"). Design consumer idempotency around documented AWS at-least-once semantics, not around whatever LocalStack's exact redrive timing happens to do — the tombstone (D-66) and state-guard (D-64) patterns already make the business logic redrive-timing-agnostic, which is the correct posture per this project's own prior research.

## Code Examples

### Outbox entity shape (both services, duplicated per D-62)
```java
// Source: pattern generalized from StockAdjustedEvent's envelope shape already used in this repo
// [VERIFIED: inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockAdjustedEvent.java:17-24]
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private String eventType;       // e.g. "ReserveStock", "StockReserved", "STOCK_ADJUSTED"
    @Column(nullable = false, columnDefinition = "text")
    private String payload;         // JSON — the event/command DTO serialized once at write time
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private OffsetDateTime publishedAt; // null until the relay sends it
}
```

### Order status enum change (D-49)
```java
// Existing enum — [VERIFIED: order-service/src/main/java/com/orderflow/order/order/OrderStatus.java:12-20]
// Add RESERVING between APPROVED and CONFIRMED, keep CREDIT_CONSUMING set updated per D-52:
public enum OrderStatus {
    CREATED, PENDING_APPROVAL, APPROVED, REJECTED,
    RESERVING,               // NEW
    CONFIRMED, CANCELLED, SHIPPED, DELIVERED;

    public static final Set<OrderStatus> CREDIT_CONSUMING =
            Set.of(APPROVED, RESERVING, CONFIRMED, SHIPPED, DELIVERED); // RESERVING added, D-52
}
```
Note the existing `V1__init_order_schema.sql` CHECK constraint enumerates all 8 values already `[VERIFIED: order-service/src/main/resources/db/migration/V1__init_order_schema.sql:17-19]` — a **V2 migration is mandatory** to widen this CHECK to 9 values (`RESERVING` added) before the enum change can be exercised against a real Postgres instance, exactly as D-49 states.

## State of the Art

| Old Approach (Phase 3, D-29) | Current Approach (this phase, D-60) | When Changed | Impact |
|--------------------------|------------------|--------------|--------|
| `StockEventPublisher` direct-publish after commit, dual-write risk documented as a known limitation | Transactional Outbox in inventory-service, same table pattern as order-service | This phase | Closes D-29/D-30; `StockEventPublisher` class is deleted, not deprecated-in-place |
| Order status flow ends at APPROVED/REJECTED (Phase 4 scope) | RESERVING intermediate state, CONFIRMED/CANCELLED terminal via saga | This phase | `OrderResponse.status` gains a new possible value — any external consumer/smoke script hardcoding the Phase-4 status set needs updating (`scripts/smoke-order-flow.sh`, per D-69) |

**Deprecated/outdated:**
- `StockEventPublisher` (inventory-service) — replaced entirely by the outbox relay; do not keep it "just for STOCK_ADJUSTED" (Pitfall 4 above).
- The `InventoryController` javadoc's stated plan for a Fase-5 "identidade de serviço" for REST reservation calls `[VERIFIED: inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java:20-31]` is superseded by the locked decision that order↔inventory now communicate only via SQS (Claude's Discretion item: whether the REST reservation endpoints stay at all).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The `e2e-tests` module should be `src/test/java`-only with no `src/main` (no such test-only module exists yet in this repo to confirm the exact shape against) | Standard Stack — New Maven module `e2e-tests` | Low — this is a standard, low-risk Maven convention; worst case is an empty unused `src/main/java` directory |
| A2 | Spring's documented "classpath: = first match, not merged" resource-resolution behavior (general Spring Framework docs) applies identically to Spring Boot's `ConfigDataLocation` default `classpath:/` search when two dependency jars each ship an `application.yml` | Common Pitfalls Pitfall 2 | Medium — if wrong (Boot actually merges via `classpath*:` semantics for config data), the recommended mitigation (explicit per-context property overrides) is still safe and harmless to apply even if the risk doesn't materialize, so the downside of over-mitigating is low; the downside of NOT mitigating if the risk is real is a silently misconfigured E2E test that could pass or fail for the wrong reasons |
| A3 | Option A vs Option B for the `stock_reservations.product_id` FK + tombstone-for-unstocked-product question (Pattern 4) — neither option is confirmed against the actual codebase; both are proposals for the planner to choose between | Architecture Patterns Pattern 4 | Medium — CONTEXT.md's own D-66 flags this as "reversibility: costly," so picking the less-consistent option (B) now could require a harder migration later if it turns out to leak `PRODUCT_NOT_STOCKED` failures into legitimate-inventory-row territory |

**If this table is empty:** N/A — see rows above.

## Open Questions

1. **Do the inventory REST reservation/release endpoints (`POST/DELETE /inventory/{productId}/reservations...`) stay in this phase?**
   - What we know: CONTEXT.md explicitly leaves this to Claude's Discretion; the endpoints are `SELLER_ADMIN`-only today and the javadoc's stated rationale for keeping them (a future "identidade de serviço" for order-service to call them synchronously) is superseded by the SQS-only saga design.
   - What's unclear: whether any existing test (`InventoryControllerIT`, `StockAdjustedEventPublishingIT`) depends on these endpoints in a way that would need updating either way.
   - Recommendation: keep them (lowest churn, no test breakage) unless the planner finds a concrete reason to remove — removing dead-but-harmless REST endpoints is not required by any of the phase's success criteria.

2. **FK on `stock_reservations.product_id` vs. tombstone-for-unstocked-product (D-66)** — see Assumption A3 / Pattern 4. Needs an explicit planner decision, ideally recorded as a new D-number in the plan's SUMMARY, given CONTEXT.md's own "reversibility: costly" flag on this exact area.

3. **Exact relay interval / saga timeout / DLQ `maxReceiveCount` values** — CONTEXT.md defers these to Claude's Discretion with example values (~1s relay, ~2min timeout, maxReceiveCount≈3). No further research needed; these are tuning knobs, not correctness questions, and should be short in the test profile (mirroring the existing `application-test.yml` pattern of shortened timeouts, e.g. `[VERIFIED: order-service/src/test/resources/application-test.yml:14-23]`).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker Engine | Testcontainers (Postgres + LocalStack) for order-service's new ITs and `e2e-tests` | ✓ | `docker info` succeeded in this session | — |
| Java | Build/run all services | ✓ | 21.0.10 LTS (Temurin-compatible) | — |
| Maven | Build | Wrapper present (`./mvnw`), no globally-installed `mvn` on PATH | — | Use `./mvnw`/`./mvnw.cmd` exclusively, exactly as the existing project already does — no action needed |
| `.env` (`LOCALSTACK_AUTH_TOKEN`) | LocalStack container (docker-compose and Testcontainers, `LocalStackTestSupport.resolveAuthToken`) | ✓ (file present; contents not read per secret-handling policy) | — | — |

**Missing dependencies with no fallback:** none identified.
**Missing dependencies with fallback:** `mvn` not globally installed — no action needed, the project already exclusively uses the Maven Wrapper.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito (`spring-boot-starter-test`, unit) + Testcontainers 1.20.4 + Awaitility (integration) `[VERIFIED: root pom.xml properties + inventory-service/pom.xml test dependencies]` |
| Config file | `order-service/src/test/resources/application-test.yml`, `inventory-service/src/test/resources/application-test.yml` (both exist; extend, don't replace) |
| Quick run command | `./mvnw -pl order-service,inventory-service test` (Surefire, `*Test.java` only) |
| Full suite command | `./mvnw verify` (Failsafe adds `*IT.java`, including the new saga ITs and, once added, `e2e-tests`) — **must run with `docker compose down` first** per the project's own documented LocalStack Hobby single-session constraint `[VERIFIED: .planning/STATE.md §Blockers/Concerns — "sessão LocalStack Hobby é única por token — Testcontainers falha (exit 126) com a stack do compose de pé"]` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| ORD-04 | Outbox row written atomically with RESERVING transition | unit + integration | `./mvnw -pl order-service test` (unit: transition logic) + new `OutboxRelayIT` | ❌ Wave 0 — new test file |
| ORD-05 | Result listener transitions to CONFIRMED/CANCELLED | integration | new `ReservationResultListenerIT` (order-service, real LocalStack) | ❌ Wave 0 |
| ORD-06 | Idempotent consumers under redelivery | integration | new `IdempotentReservationIT` (inventory-service) — republish same command, assert single decrement (roadmap criterion 4) | ❌ Wave 0 |
| TEST-03 | Full saga E2E, success + failure paths | E2E | `./mvnw -pl e2e-tests verify` — `OrderReservationSagaE2EIT`, **failure path test method written first** (D-68) | ❌ Wave 0 — new module |

### Sampling Rate
- **Per task commit:** `./mvnw -pl order-service,inventory-service test` (fast unit tests only)
- **Per wave merge:** `./mvnw -pl order-service,inventory-service verify` (adds Testcontainers ITs)
- **Phase gate:** `docker compose down` then `./mvnw verify` (whole reactor, including `e2e-tests`) green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `order-service` `pom.xml` — add `spring-cloud-aws-starter-sqs`, `testcontainers:localstack`, `awaitility` (currently absent)
- [ ] `order-service` — new `SqsMessagingConfig` mirroring inventory-service's converter + timeout customizer beans
- [ ] `e2e-tests` module scaffold — `pom.xml`, added to root `<modules>`, a trivial context-load smoke test proving the classpath-collision risk (Pitfall 2) is or isn't real, **before** the real saga test is written
- [ ] `localstack-init/ready.d/02-create-order-saga-resources.sh` — provisions `inventory-commands-queue`, `order-events-queue`, and their DLQs
- [ ] `docker-compose.yml` — order-service gains `SPRING_CLOUD_AWS_ENDPOINT` env var and `localstack: condition: service_healthy` in `depends_on`; localstack healthcheck extended to also probe the two new queues

*(All items above are net-new for this phase; nothing pre-existing covers them.)*

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | No (new work) | No new auth surface — SQS consumers are not HTTP endpoints; existing JWT validation on order-service/inventory-service REST endpoints is unchanged |
| V3 Session Management | No | N/A — stateless JWT already covers this, unchanged |
| V4 Access Control | No (new work) | Unchanged — existing `@PreAuthorize`/role checks on REST endpoints are untouched by this phase |
| V5 Input Validation | Yes | SQS message payloads (`ReserveStockCommand`, result events) must be validated/deserialized defensively — mirror the existing pattern of `String payload` + service-layer parsing with a discard-and-log path for malformed input, same as `NotificationEventListener` `[VERIFIED: notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java:38-46]`; never let a malformed SQS body crash the listener into an infinite DLQ loop for a message that could never succeed |
| V6 Cryptography | No | No new cryptographic material — LocalStack `test`/`test` credentials, same as existing services |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| Poison-pill SQS message (malformed JSON, unknown event type) looping until DLQ | Denial of Service (against the consumer's processing capacity) | D-67 already locked: business-logic failure → consume-and-emit-failure-event (message deleted); only *technical* errors (e.g., DB unavailable) rethrow for SQS redelivery — this is the correct posture and should extend to "cannot even parse the payload" (treat as a technical/malformed-message case, log and drop rather than infinite-retry, matching `NotificationEventListener`'s existing precedent) |
| Cross-service trust of SQS payload without validating event shape | Tampering (in a real-AWS deployment, a queue is an IAM-governed trust boundary; here it's same-account LocalStack, but the code should not assume it) | Deserialize into a typed DTO record with Bean Validation-style required-field checks before acting on it, same discipline already applied to REST DTOs (`@Valid @RequestBody`) |
| `cancellation_reason` free-text field echoing internal reason data back through the API (D-53) | Information Disclosure (low severity here — B2B seller-facing field, not attacker-facing, per existing `GlobalExceptionHandler` policy of never leaking stack traces/downstream detail) | Compose the reason message server-side from a fixed template + safe values (product id, quantities) as D-56 already specifies — never interpolate a raw exception message or downstream response body into `cancellation_reason` |

## Sources

### Primary (HIGH confidence — read directly this session)
- `order-service/`, `inventory-service/`, `notification-service/` source trees (all files cited inline with `[VERIFIED: path:lines]` above)
- `pom.xml` (root reactor), `order-service/pom.xml`, `inventory-service/pom.xml`
- `docker-compose.yml`, `localstack-init/ready.d/01-create-notification-resources.sh`
- `.planning/ROADMAP.md` §Phase 5, `.planning/REQUIREMENTS.md`, `.planning/STATE.md`
- `.planning/phases/05-.../05-CONTEXT.md` (this phase's locked decisions)

### Secondary (MEDIUM confidence — WebSearch cross-checked against official framework docs)
- [Database Job Queue Using SKIP LOCKED — Vlad Mihalcea](https://vladmihalcea.com/database-job-queue-skip-locked/) — outbox/job-queue `FOR UPDATE SKIP LOCKED` pattern, and the `@QueryHints` alternative
- [Don't Publish Events Inside Your Transaction: The Transactional Outbox Pattern in Spring Boot — DEV Community](https://dev.to/thellu/dont-publish-events-inside-your-transaction-the-transactional-outbox-pattern-in-spring-boot-1p2j)
- [Resources :: Spring Framework Reference](https://docs.spring.io/spring-framework/reference/core/resources.html) — `classpath:` (singular, first-match via `getResource()`) vs `classpath*:` (all matches via `getResources()`) semantics
- [Access a File from the Classpath using Spring — Baeldung](https://www.baeldung.com/spring-classpath-file-access)
- [Java: Loading same-name resource from multiple jars — riptutorial](https://riptutorial.com/java/example/19290/loading-same-name-resource-from-multiple-jars)
- [Externalized Configuration :: Spring Boot Reference](https://docs.spring.io/spring-boot/reference/features/external-config.html) — confirms `classpath:/` and `classpath:/config/` as default search locations but does **not** explicitly resolve the multi-jar-dependency collision scenario (see Pitfall 2 / Assumption A2)
- [Introduction to Spring Cloud AWS 3.0 – SQS Integration — Baeldung](https://www.baeldung.com/java-spring-cloud-aws-v3-intro)
- [Testing AWS service integrations using LocalStack — Testcontainers official guide](https://testcontainers.com/guides/testing-aws-service-integrations-using-localstack/)

### Tertiary (LOW confidence — general web, not independently re-verified)
- [Combine Testcontainers and Spring Boot with multiple containers — Wim Deblauwe](https://www.wimdeblauwe.com/blog/2025/05/14/combine-testcontainers-and-spring-boot-with-multiple-containers/) — general multi-container Testcontainers patterns, not the specific two-application-contexts-in-one-JVM scenario this phase needs

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every dependency is already resolved and running elsewhere in this exact repo, verified by reading the actual poms.
- Architecture (outbox, saga state machine): HIGH — directly specified by CONTEXT.md's locked decisions plus this repo's own proven Phase 2-4 techniques for the retry/transaction/locking mechanics.
- e2e-tests module classpath-collision risk: MEDIUM — grounded in documented general Spring Framework resource-resolution behavior, but the exact Spring Boot config-data multi-jar scenario is not explicitly confirmed by official docs and was not falsified in this session (flagged as a Wave 0 spike, not asserted as fact).
- Pitfalls: HIGH for the ones grounded in this repo's own code (self-invocation, dual-write), MEDIUM for the LocalStack DLQ-timing carryover from prior-phase research.

**Research date:** 2026-09-25
**Valid until:** 30 days (stable — no fast-moving external dependency; all versions are already pinned in this repo's own `pom.xml`)
