---
phase: "7"
slug: "endurecimento-observabilidade-e-entrega"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: draft
nyquist_compliant: false
wave_0_complete: false
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

| Req ID | Behavior | Test Type | Automated Command | File Exists |
|--------|----------|-----------|-------------------|-------------|
| QUAL-01 | Spec de cada serviço com `servers`, tags, `bearerAuth`, enum de status, schema de erro; docs públicos, negócio protegido | IT | `./mvnw -B -pl <svc> verify -Dit.test=OpenApiDocsIT` | ✅ estender |
| QUAL-01 | Todo `operation` com `summary` e respostas de erro; `JwksController` fora do spec | IT | idem | ❌ W0 |
| QUAL-01 | Gateway serve `swagger-config` com 5 `urls`; `/docs/<svc>/v3/api-docs` → upstream `/v3/api-docs` | IT | `./mvnw -B -pl gateway verify` | ❌ W0 |
| QUAL-02 | Filtro do Gateway gera/reaproveita/rejeita; header único | unit + IT | `./mvnw -B -pl gateway verify` | ❌ W0 |
| QUAL-02 | Filtro por serviço: MDC durante requisição, limpo depois, não ecoa header | unit | `./mvnw -B -pl <svc> test` | ❌ W0 (5x) |
| QUAL-02 | `OutboxWriter` grava `correlation_id` do MDC | IT | `./mvnw -B -pl order-service verify` | ❌ W0 (2 serviços) |
| QUAL-02 | `OutboxRelay` envia atributo `correlationId` | unit | `./mvnw -B -pl order-service test -Dtest=OutboxRelayTest` | ✅ atualizar |
| QUAL-02 | Atributo SQS → `@Header` → MDC nos listeners, logs com `[cid]` | IT | `./mvnw -B -pl <svc> verify` | ❌ W0 (3x) |
| QUAL-02 | Mesmo ID em order e inventory no E2E | E2E | `./mvnw -B -pl e2e-tests verify -Dit.test=CorrelationIdE2EIT` | ❌ W0 |
| QUAL-02 | `SagaTimeoutJob` herda `orders.correlation_id` | IT | `./mvnw -B -pl order-service verify -Dit.test=SagaTimeoutIT` | ✅ estender |
| QUAL-02 | Stack real Gateway→order→inventory→notification | smoke | `bash scripts/smoke-correlation-id.sh` | ❌ W0 |
| INFRA-02 | Workflow sintaticamente válido | lint | `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:<tag>` | ❌ W0 |
| INFRA-02 | Nenhum teste desabilitado em silêncio | script | `! grep -rEn "@Disabled|assumeTrue|@EnabledIf|@DisabledIf" */src/test --include=*.java` | ❌ W0 |
| INFRA-03 | ADRs com seções, alternativa rejeitada, índice, links | script | `bash scripts/check-adrs.sh` | ❌ W0 |
| TEST-01 | Unitários nas regras da matriz por serviço | unit | `./mvnw -B -pl <svc> test` | ❌ lacunas auth/catalog/inventory/gateway |
| TEST-02 | ≥1 IT com dependência real por serviço | IT | `./mvnw -B -pl <svc> verify` | ✅ domínio; ❌ gateway |
| WR-01 | `ShipStock` inválido vai à DLQ | unit + IT | `./mvnw -B -pl inventory-service verify -Dit.test=ShipStockConsumptionIT` | ✅ estender |
| WR-02 | Recover correto por nome em `shipAll`/`releaseAll` | IT | idem | ✅ estender |
| WR-03 | Evento `ORDER_*` inválido loga `orderId`/`eventType` | unit | `./mvnw -B -pl notification-service test` | ✅ estender |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `gateway/pom.xml` — `spring-boot-starter-test`, failsafe; `CorrelationIdFilterTest`, `GatewayRoutingIT`, stub JDK
- [ ] `<svc>/observability/CorrelationContext` + filtro + teste (5 serviços)
- [ ] Atualizar os dois `OutboxRelayTest` para `send(String, Message)` antes de mexer no relay
- [ ] ITs de atributo SQS→MDC (inventory, order, notification) com `OutputCaptureExtension`
- [ ] `scripts/smoke-correlation-id.sh`, `scripts/check-adrs.sh`
- [ ] Unitários: `CompanyService`, `TokenService`, `CompanyGuard`, `ProductService`, `Inventory`, `StockReservation`, `OutboxWriter`
- [ ] `07-COVERAGE.md` (matriz regra→teste) — primeira tarefa da frente de testes

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Try it out pelo Gateway na Swagger UI | QUAL-01 | JS do Swagger UI não é testável sem navegador | Abrir `http://localhost:8080/swagger-ui.html`, login, Authorize, executar `POST /orders` |
| Push dispara CI; teste quebrado deixa o run vermelho | INFRA-02 | Depende do GitHub e do secret `LOCALSTACK_AUTH_TOKEN` | Push de branch com teste quebrado deliberado, conferir run vermelho, reverter, conferir verde |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
