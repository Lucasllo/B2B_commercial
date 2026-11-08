---
milestone: v1
audited: 2026-11-07T00:00:00Z
status: tech_debt
scores:
  requirements: 30/30
  phases: 7/7
  integration: 13/13
  flows: 5/5
gaps:
  requirements: []
  integration: []
  flows: []
gate_gaps:
  - phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
    item: "06-SECURITY.md ausente com workflow.security_enforcement=true"
    action: "/gsd-secure-phase 6"
  - phase: 01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas
    item: "01-SECURITY.md ausente (fase anterior à rotina de secure-phase)"
    action: "/gsd-secure-phase 1 (opcional)"
nyquist:
  compliant_phases: [2, 3, 5, 7]
  partial_phases: [4]
  not_validated_phases: [1, 6]
  missing_phases: []
  overall: partial
tech_debt:
  - phase: integration
    items:
      - "WARNING: hop do notification-service (ORDER_* timeline e STOCK_ADJUSTED) não exercitado por nenhum teste E2E — NotificationEventListener.java:49 (ORD-10, NOTF-01, NOTF-02, QUAL-02)"
      - "WARNING: sem testes de contrato entre serviços; e2e usa stubs manuais (DownstreamStubServer.java:59-61) para /products e /companies (TEST-02, TEST-03, restrição do CLAUDE.md)"
      - "WARNING: nenhum teste em runtime liga auth-service real a um resource server (E2eJwt usa JWKS stub) (AUTH-03)"
      - "WARNING: POST /inventory/{productId}/reservations (InventoryController.java:95) ainda roteado pelo Gateway, sem outbox — caminho de reserva paralelo à saga (INV-02, ORD-04)"
      - "WARNING: CorrelationIdE2EIT.java:49-50 não verifica o log do NotificationEventListener (QUAL-02)"
  - phase: 02-cat-logo-e-estoque
    items:
      - "WR-01 Location de POST /products não sobrevive ao strip do Gateway"
      - "WR-02 handler de AuthenticationException inalcançável para 401 reais"
      - "WR-03 catch-all DataIntegrityViolation sempre diz 'SKU already in use'"
      - "WR-04 replay idempotente de reserva não compara quantidade"
      - "WR-05 SecurityConfig/GlobalExceptionHandler/TestJwt duplicados entre catalog e inventory"
  - phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
    items:
      - "WR-01..WR-06 do 03-REVIEW (WR-07 resolvido por quick 260923-tj9); destaque WR-02 falha permanente do DynamoDB vira laço de reentrega (sem DLQ — DLQ-01 é v2)"
  - phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
    items:
      - "WR-01 GET /orders serializa Page<T> cru"
      - "WR-02 AuthenticationEntryPoint sem charset explícito"
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    items:
      - "WR-03 poll-timeout: 0s desliga long polling no SqsAsyncClient compartilhado (adiado)"
      - "Outbox sem retenção e lápides de reserva sem limpeza (AR-05-02, limitação documentada)"
  - phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
    items:
      - "(corrigido) WR-01/WR-02 fechados em 07-09 (ShipStock inválido → ERROR + DLQ; @Recover por nome); WR-03 fechado em 07-07"
      - "IN-01..IN-07 do 06-REVIEW (informativos) seguem abertos"
  - phase: 07-endurecimento-observabilidade-e-entrega
    items:
      - "WR-01 grep -q sob pipefail no smoke (intermitência)"
      - "WR-02 concurrency do CI por ref × token LocalStack global"
      - "WR-03 jobs sem timeout-minutes"
      - "WR-04 Location do 201 sem /api"
      - "WR-05 SagaCommandParser aceita inteiro 64+ bits truncado"
      - "WR-06 log de falha do relay sem Correlation-ID"
      - "WR-07 Gateway sem timeout de upstream"
      - "WR-08 guarda de matriz não distingue unitário de integração"
      - "WR-09 evento sem Correlation-ID logado com UUID novo e enviado sem atributo"
  - phase: ambiente
    items:
      - "Partida a frio do docker compose up --wait pode estourar healthcheck (inventory ~80 s; order desiste se SQS > 2 s)"
      - "Sessão LocalStack Hobby única por token — Testcontainers falha com o compose de pé"
---

# Auditoria do Milestone v1 — OrderFlow

**Status:** ⚡ `tech_debt` — todos os requisitos satisfeitos, nenhum bloqueador; dívida técnica e lacunas de gate a revisar.

## Fases

| Fase | VERIFICATION | UAT | SECURITY | VALIDATION (Nyquist) |
|------|--------------|-----|----------|----------------------|
| 1 Esqueleto vertical | passed (11/11, humano confirmado) | — | **ausente** | draft → NOT-VALIDATED |
| 2 Catálogo e estoque | passed (4/4) | 12/12 | verified | COMPLIANT |
| 3 Notificações | passed (15/15) | 2/2 | verified | COMPLIANT |
| 4 Núcleo do pedido | passed (5/5) | 1/1 | verified | PARTIAL (`nyquist_compliant: false`) |
| 5 Saga | passed (5/5 + 4/4 req) | 43/43 | verified | COMPLIANT |
| 6 Ciclo de vida | passed (4/4) | 40/40 | **ausente** (enforcement ligado) | draft → NOT-VALIDATED |
| 7 Endurecimento | passed (3/5 + 2 itens comportamentais provados no 07-UAT com evidência) | 3/3 | verified (41/41) | COMPLIANT |

## Cobertura de requisitos (3 fontes)

Todos os 30 REQ-IDs v1 aparecem em: tabela de rastreabilidade (`[x]`), tabela de requisitos da VERIFICATION da fase atribuída (SATISFIED) e `requirements-completed` de algum SUMMARY. **30/30 satisfied, 0 órfãos.**

| Grupo | REQ-IDs | Fase | Status |
|-------|---------|------|--------|
| Auth/Empresas | AUTH-01..03, COMP-01..03, INFRA-01 | 1 | satisfied |
| Catálogo/Estoque | CAT-01..02, INV-01..02 | 2 | satisfied |
| Notificações | NOTF-01..02 | 3 | satisfied |
| Pedido | ORD-01..03, ORD-08..09 | 4 | satisfied |
| Saga | ORD-04..06, TEST-03 | 5 | satisfied |
| Ciclo de vida | ORD-07, ORD-10 | 6 | satisfied |
| Qualidade/Infra | QUAL-01..02, INFRA-02..03, TEST-01..02 | 7 | satisfied (QUAL-02 "por partes" na VERIFICATION, fechado pelo 07-UAT/07-SMOKE-EVIDENCE.txt) |

## Integração entre fases (gsd-integration-checker, rastreio estático)

- **13/13 links conectados**: JWKS + claims (`role`, `company_id`, issuer) consistentes nos 4 resource servers; 23/23 endpoints roteados pelo Gateway; order→auth (limite de crédito) e order→catalog (produto); filas `inventory-commands-queue`, `order-events-queue`, `notification-events-queue` com nomes iguais em application.yml, init do LocalStack e healthcheck; DTOs e reason codes da saga batem dos dois lados; Correlation-ID gateway→serviços→outbox→atributo SQS→listeners; CI cobre 6 serviços + e2e.
- **1 rota órfã**: `POST /inventory/{productId}/reservations` (só testes chamam).
- **Fluxos (5/5 completos estaticamente, 0 quebrados)**: login→JWT; criação→crédito→saga→CONFIRMED→ship→deliver; acima do limite→aprovação/rejeição; estoque insuficiente→CANCELLED; Correlation-ID. Ressalva: o último hop (notification-service) dos fluxos 2 e 5 só é coberto pelos testes do próprio serviço e pelo smoke manual, não por E2E automatizado.
- Observação: AUTH-02 — papel e empresa vão nas claims do JWT, não no corpo de `/auth/login` (compatível com o texto do requisito).

## Dívida técnica

Ver `tech_debt` no frontmatter: 5 warnings de integração + ~28 warnings de code review abertos (fases 2–5 e 7; os 3 da fase 6 foram fechados na fase 7) + 2 itens de ambiente. Prioridade sugerida:
1. Rota de reserva REST paralela à saga (desync possível).
2. Teste E2E do hop de notificação + checagem de Correlation-ID no notification listener.
3. Fase 7 WR-02/WR-03 (robustez do CI) e WR-05 (truncamento 64 bits no `SagaCommandParser`).
4. Fase 5 WR-03 (`poll-timeout: 0s`, só custo/latência no SQS real).

## Lacunas de gate

- `/gsd-secure-phase 6` — obrigatório pela config (`security_enforcement: true`); STATE.md já registrava.
- `/gsd-validate-phase 1`, `/gsd-validate-phase 6` (draft) e `/gsd-validate-phase 4` (partial).
