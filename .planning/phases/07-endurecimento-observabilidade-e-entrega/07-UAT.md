---
status: complete
phase: 07-endurecimento-observabilidade-e-entrega
source: [07-VERIFICATION.md]
started: 2026-11-05T23:15:45Z
updated: 2026-11-05T23:40:22Z
---

## Current Test

[testing complete]

## Tests

### 1. Smoke de Correlation-ID na stack real
expected: Com o Docker ligado e a stack de pé, `bash scripts/smoke-correlation-id.sh` termina com sucesso e o mesmo Correlation-ID aparece nos logs dos 6 serviços. Guardar a saída como evidência. Falha intermitente com o ID presente nos logs aponta para o WR-01 do 07-REVIEW.md.
result: pass

### 2. Prova de falha visível do CI
expected: Na branch descartável `ci/prova-falha-visivel`, com uma asserção invertida no `CorrelationIdFilterTest` do gateway, o push gera um run vermelho no GitHub Actions apontando o teste quebrado. Depois a branch é apagada.
result: pass

### 3. Try it out da Swagger UI pelo Gateway
expected: Em `http://localhost:8080/swagger-ui.html`, o fluxo de login seguido de `POST /orders` funciona pelo Try it out, sem erro de CORS e usando o server relativo `/api`.
result: pass

## Summary

total: 3
passed: 3
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none yet]
