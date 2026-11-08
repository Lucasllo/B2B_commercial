---
phase: "03"
slug: "primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: validated
nyquist_compliant: true
wave_0_complete: true
created: "2026-09-22"
validated: "2026-09-23"
---

# Phase 03 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito (`spring-boot-starter-test`), Testcontainers (PostgreSQL + LocalStack), Awaitility; smoke em bash contra a stack do docker compose |
| **Config file** | `pom.xml` (reactor) + `*/pom.xml` (surefire `*Test`, failsafe `*IT`) |
| **Quick run command** | `./mvnw -B -pl notification-service verify -Dit.test=<IT>` / `./mvnw -B -pl inventory-service verify -Dit.test=<IT>` |
| **Full suite command** | `./mvnw -B verify` + `bash scripts/smoke-notification-flow.sh` (stack no ar) |
| **Estimated runtime** | ~215 s (reactor completo) + ~30 s (smoke) |

---

## Sampling Rate

- **After every task commit:** o IT alvo da tarefa (`-Dit.test=...`)
- **After every plan wave:** `./mvnw -B -pl <módulo> verify`
- **Before `/gsd-verify-work`:** `./mvnw -B verify` verde (156 testes, 0 falhas em 2026-09-23) + smoke `SMOKE OK`
- **Max feedback latency:** ~80 s por módulo

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 03-01-01 | 01 | 1 | NOTF-01, NOTF-02 | T-03-10 | Serviço não cria fila/tabela sozinho (`queue-not-found-strategy: fail`) | integration | `./mvnw -B -pl notification-service verify -Dit.test=NotificationEventFlowIT` | ✅ | ✅ green |
| 03-01-02 | 01 | 1 | NOTF-01 | T-03-03, T-03-04, T-03-07 | Reentrega idempotente; mensagem venenosa descartada; log sanitizado | unit + integration | `./mvnw -B -pl notification-service test -Dtest=NotificationServiceTest,NotificationEventListenerTest` + `verify -Dit.test=NotificationDeliveryIT` | ✅ | ✅ green |
| 03-01-03 | 01 | 1 | NOTF-02 | T-03-01, T-03-02, T-03-05, T-03-06 | Só SELLER_ADMIN lê; token validado; UUID obrigatório; erro sem vazar AWS | integration | `./mvnw -B -pl notification-service verify -Dit.test=NotificationControllerIT,NotificationStoreUnavailableIT` | ✅ | ⚠️ green (ver WR-01) |
| 03-02-01 | 02 | 2 | NOTF-01 | T-03-11, T-03-13 | Publica só após commit; sem atributo `JavaType` | integration | `./mvnw -B -pl inventory-service verify -Dit.test=StockAdjustedEventPublishingIT` | ✅ | ✅ green |
| 03-02-02 | 02 | 2 | NOTF-01 | T-03-14 | Falha de publicação não derruba o PUT; reserva/liberação/recusa não publicam | integration | `./mvnw -B -pl inventory-service verify -Dit.test=StockAdjustedEventPublishingIT,StockEventPublishFailureIT` | ✅ | ✅ green |
| 03-03-01 | 03 | 3 | NOTF-01, NOTF-02 | T-03-20, T-03-24 | Gateway exige token (401); LocalStack só saudável com fila+tabela | e2e (stack real) | `docker compose up -d --build --wait` + `bash scripts/smoke-notification-flow.sh` | ✅ | ✅ green |
| 03-03-02 | 03 | 3 | NOTF-01, NOTF-02 | T-03-25 | Swagger sem token, endpoint de negócio com token; smoke não imprime token | integration + e2e | `./mvnw -B -pl notification-service verify -Dit.test=OpenApiDocsIT` + `bash scripts/smoke-notification-flow.sh` | ✅ | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Existing infrastructure covers all phase requirements.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Navegação BUYER 403 pela Swagger UI (Authorize + Try it out) | NOTF-02 | Experiência visual; o 403 em si é coberto por `NotificationControllerIT` | `03-UAT.md` teste 1 — **pass** (2026-09-23) |
| Clareza da seção "Limitações conhecidas (Fase 3)" do README | NOTF-01 | Julgamento de prosa para leitor não-técnico | `03-UAT.md` teste 2 — **pass** (2026-09-23) |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-09-23

---

## Validation Audit 2026-09-23

| Metric | Count |
|--------|-------|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |

Nota: 03-01-03 está verde, mas `NotificationStoreUnavailableIT` depende da ordem de execução (03-REVIEW.md WR-01 — segundo contexto Spring com listener SQS ativo consumindo a fila compartilhada). Não é lacuna de cobertura; recomenda-se `/gsd-code-review 03 --fix` antes da Fase 5.
