---
status: testing
phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
source: [03-VERIFICATION.md]
started: 2026-09-23T23:56:08Z
updated: 2026-09-23T23:56:08Z
---

## Current Test

number: 1
name: Navegação BUYER 403 pela Swagger UI do notification-service
expected: |
  Autenticado como BUYER, em http://localhost:8084/swagger-ui.html, Authorize com o token do BUYER
  e Try it out em GET /notifications/{productId} devolve 403; o botão Authorize funciona para o
  esquema bearerAuth e a navegação é coerente para um avaliador que nunca abriu o código.
awaiting: user response

## Tests

### 1. Navegação BUYER 403 pela Swagger UI do notification-service
expected: Try it out com token de BUYER em GET /notifications/{productId} devolve 403; Authorize (bearerAuth) funciona; navegação coerente para um avaliador externo.
result: [pending]

### 2. Clareza da seção "Limitações conhecidas (Fase 3)" do README
expected: Sem jargão de implementação, deixa claro que (1) um evento de ajuste pode se perder se o SQS falhar logo após o commit (dual-write sem Outbox, D-29/D-30), (2) não há fila de mensagens mortas nesta fase, e (3) a Fase 5 resolve o primeiro ponto com Transactional Outbox — sem prometer entrega garantida.
result: [pending]

## Summary

total: 2
passed: 0
issues: 0
pending: 2
skipped: 0
blocked: 0

## Gaps
