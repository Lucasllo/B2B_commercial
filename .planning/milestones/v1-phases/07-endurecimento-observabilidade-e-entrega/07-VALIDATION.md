---
phase: "7"
slug: "endurecimento-observabilidade-e-entrega"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: validated
nyquist_compliant: true
wave_0_complete: true
created: "2026-10-01"
---

# Phase 7 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Fonte: `07-RESEARCH.md` § Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito + AssertJ + Awaitility + Spring Boot Test (MockMvc / RANDOM_PORT) + Testcontainers 1.21.4 (efetivo) |
| **Config file** | `pom.xml` raiz (surefire `*Test`, failsafe `*IT`); `application-test.yml` por serviço |
| **Quick run command** | `./mvnw -B -pl <svc> test` |
| **Full suite command** | `./mvnw -B -pl <svc> verify` por serviço; e2e: `./mvnw -B -pl order-service,inventory-service -am install -DskipTests && ./mvnw -B -pl e2e-tests verify` (compose derrubado) |
| **Estimated runtime** | ~30 s (unit por módulo); vários minutos (verify com Testcontainers/LocalStack) |

---

## Sampling Rate

- **After every task commit:** `./mvnw -B -pl <módulo tocado> test`; em tarefas de outbox/listener, `-Dit.test=<IT específico>` com o compose derrubado
- **After every plan wave:** `./mvnw -B -pl <módulos da wave> verify` (módulos com LocalStack em sequência)
- **Before `/gsd-verify-work`:** todos os serviços `verify` + e2e + `scripts/smoke-correlation-id.sh` + `scripts/check-adrs.sh` + CI verde no GitHub
- **Max feedback latency:** 30 s (unit)

---

## Per-Task Verification Map

Preenchido pelo planner/executor a partir do mapa requisito→teste abaixo.

| Req ID | Behavior | Test Type | Automated Command | Test File(s) | Status |
|--------|----------|-----------|-------------------|--------------|--------|
| QUAL-01 | Spec de cada serviço com `servers`, tags, `bearerAuth`, enum de status, schema de erro; docs públicos, negócio protegido | IT | `./mvnw -B -pl <svc> verify -Dit.test=OpenApiDocsIT` | `OpenApiDocsIT` (auth, catalog, inventory, notification, order — este compara o enum com `OrderStatus.values()`) | ✅ green |
| QUAL-01 | Todo `operation` com `summary` e respostas de erro; `JwksController` fora do spec | IT | idem | `OpenApiDocsIT#everyOperationDocumentsSummaryTagAndRealErrors`, `#serverUrlIsApiPrefixAndJwksIsHidden` | ✅ green |
| QUAL-01 | Gateway serve `swagger-config` com 5 `urls`; `/docs/<svc>/v3/api-docs` → upstream `/v3/api-docs` | IT | `./mvnw -B -pl gateway verify` | `GatewayRoutingIT#swaggerConfigListsExactlyTheFiveServiceSpecs`, `#eachDocsRouteFetchesOnlyThatServiceSpec` | ✅ green |
| QUAL-02 | Filtro do Gateway gera/reaproveita/rejeita; header único | unit + IT | `./mvnw -B -pl gateway verify` | `gateway/CorrelationIdFilterTest`, `GatewayRoutingIT` | ✅ green |
| QUAL-02 | Filtro por serviço: MDC durante requisição, limpo depois, não ecoa header | unit + IT | `./mvnw -B -pl <svc> test` | `observability/CorrelationIdFilterTest` (5 serviços); `CorrelationIdLoggingIT` (auth, catalog) | ✅ green |
| QUAL-02 | `OutboxWriter` grava `correlation_id` do MDC | unit | `./mvnw -B -pl <svc> test -Dtest=OutboxWriterTest` | `OutboxWriterTest` (order, inventory) | ✅ green |
| QUAL-02 | `OutboxRelay` envia atributo `correlationId` | unit | `./mvnw -B -pl <svc> test -Dtest=OutboxRelayTest` | `OutboxRelayTest` (order, inventory) | ✅ green |
| QUAL-02 | Atributo SQS → `@Header` → MDC nos listeners, logs com `[cid]` | IT | `./mvnw -B -pl <svc> verify` | `CorrelationIdPropagationIT` (order), `ReservationCommandConsumptionIT` (inventory), `CorrelationIdConsumptionIT` (notification) | ✅ green |
| QUAL-02 | Mesmo ID em order e inventory no E2E | E2E | `./mvnw -B -pl e2e-tests verify -Dit.test=CorrelationIdE2EIT` | `CorrelationIdE2EIT` | ✅ green |
| QUAL-02 | `SagaTimeoutJob` herda `orders.correlation_id` | IT | `./mvnw -B -pl order-service verify -Dit.test=SagaTimeoutIT` | `SagaTimeoutIT` | ✅ green |
| QUAL-02 | Stack real Gateway→order→inventory→notification | smoke | `bash scripts/smoke-correlation-id.sh` | `scripts/smoke-correlation-id.sh` — evidência `07-SMOKE-EVIDENCE.txt` (`SMOKE OK`) | ✅ green |
| INFRA-02 | Workflow sintaticamente válido | lint | `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:<tag>` | `.github/workflows/ci.yml` — aceito pelo GitHub em todos os runs (último master: 42134491828 success) | ✅ green |
| INFRA-02 | Nenhum teste desabilitado em silêncio | script | `bash scripts/check-no-skipped-tests.sh` (job `guardas`) | `scripts/check-no-skipped-tests.sh` | ✅ green |
| INFRA-02 | Teste quebrado deixa o run vermelho | CI | push em branch descartável | run 42426572364 (`failure`) — evidência `07-CI-FAILURE-EVIDENCE.txt` | ✅ green |
| INFRA-03 | ADRs com seções, alternativa rejeitada, índice, links | script | `bash scripts/check-adrs.sh` (job `guardas`) | `scripts/check-adrs.sh` | ✅ green |
| TEST-01 | Unitários nas regras da matriz por serviço | unit + script | `./mvnw -B -pl <svc> test`; `bash scripts/check-coverage-matrix.sh` | `07-COVERAGE.md` (72 regras, 0 `LACUNA`); `CompanyServiceTest`, `TokenServiceTest`, `CompanyGuardTest`, `ProductServiceTest`, `InventoryTest`, `StockReservationTest`, `OutboxWriterTest` | ✅ green |
| TEST-02 | ≥1 IT com dependência real por serviço | IT | `./mvnw -B -pl <svc> verify` | ITs Testcontainers em todos os serviços; gateway com upstream HTTP real (`GatewayRoutingIT`) | ✅ green |
| WR-01 | `ShipStock` inválido vai à DLQ | IT | `./mvnw -B -pl inventory-service verify -Dit.test=ShipStockConsumptionIT` | `ShipStockConsumptionIT` (bloco 07-09 Task 1, `inventory-commands-dlq`) | ✅ green |
| WR-02 | Recover correto por nome em `shipAll`/`releaseAll` | IT | idem | `ShipStockConsumptionIT#shipAllForReservationWithoutBookRow…`, `#releaseAllForLiveReservationWithoutInventoryRow…` | ✅ green |
| WR-03 | Evento `ORDER_*` inválido loga `orderId`/`eventType` | unit | `./mvnw -B -pl notification-service test -Dtest=NotificationEventListenerTest` | `NotificationEventListenerTest` | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] `gateway/pom.xml` — `spring-boot-starter-test`, failsafe; `CorrelationIdFilterTest`, `GatewayRoutingIT`, stub JDK
- [x] `<svc>/observability/CorrelationContext` + filtro + teste (5 serviços)
- [x] Atualizar os dois `OutboxRelayTest` para `send(String, Message)` antes de mexer no relay
- [x] ITs de atributo SQS→MDC (inventory, order, notification) com `OutputCaptureExtension`
- [x] `scripts/smoke-correlation-id.sh`, `scripts/check-adrs.sh`
- [x] Unitários: `CompanyService`, `TokenService`, `CompanyGuard`, `ProductService`, `Inventory`, `StockReservation`, `OutboxWriter`
- [x] `07-COVERAGE.md` (matriz regra→teste) — primeira tarefa da frente de testes

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Try it out pelo Gateway na Swagger UI | QUAL-01 | JS do Swagger UI não é testável sem navegador | Abrir `http://localhost:8080/swagger-ui.html`, login, Authorize, executar `POST /orders` — **aprovado** no `07-UAT.md` (`07-SWAGGER-EVIDENCE.txt`) |
| Push dispara CI; teste quebrado deixa o run vermelho | INFRA-02 | Depende do GitHub e do secret `LOCALSTACK_AUTH_TOKEN` | Push de branch com teste quebrado deliberado, conferir run vermelho, reverter, conferir verde — **executado**: run 42426572364, `07-CI-FAILURE-EVIDENCE.txt` |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 30s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-11-07

---

## Validation Audit 2026-11-07

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |

Todas as linhas do mapa têm teste/script existente e verde: CI do master verde (run 42134491828, commit 561b129; commits posteriores só de docs), `check-no-skipped-tests.sh`, `check-adrs.sh` e `check-coverage-matrix.sh` no job `guardas`. Os itens que dependiam de execução real (smoke de Correlation-ID, run vermelho do CI, Swagger UI) já têm evidência gravada na fase. Nenhum teste novo foi necessário.
