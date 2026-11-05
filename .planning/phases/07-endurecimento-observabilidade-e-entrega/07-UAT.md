---
status: testing
phase: 07-endurecimento-observabilidade-e-entrega
source: [07-VERIFICATION.md]
started: 2026-11-05T23:15:45Z
updated: 2026-11-05T23:15:45Z
---

## Current Test

number: 1
name: Smoke de Correlation-ID na stack real
expected: |
  Com o Docker ligado e a stack de pé, `bash scripts/smoke-correlation-id.sh` termina com sucesso e o mesmo Correlation-ID aparece nos logs dos 6 serviços. Guardar a saída como evidência. Falha intermitente com o ID presente nos logs aponta para o WR-01 do 07-REVIEW.md.
awaiting: user response

## Tests

### 1. Smoke de Correlation-ID na stack real
expected: Com o Docker ligado e a stack de pé, `bash scripts/smoke-correlation-id.sh` termina com sucesso e o mesmo Correlation-ID aparece nos logs dos 6 serviços. Guardar a saída como evidência. Falha intermitente com o ID presente nos logs aponta para o WR-01 do 07-REVIEW.md.
result: [pending]

### 2. Prova de falha visível do CI
expected: Na branch descartável `ci/prova-falha-visivel`, com uma asserção invertida no `CorrelationIdFilterTest` do gateway, o push gera um run vermelho no GitHub Actions apontando o teste quebrado. Depois a branch é apagada.
result: [pending]

### 3. Try it out da Swagger UI pelo Gateway
expected: Em `http://localhost:8080/swagger-ui.html`, o fluxo de login seguido de `POST /orders` funciona pelo Try it out, sem erro de CORS e usando o server relativo `/api`.
result: [pending]

## Summary

total: 3
passed: 0
issues: 0
pending: 3
skipped: 0
blocked: 0

## Gaps

[none yet]
