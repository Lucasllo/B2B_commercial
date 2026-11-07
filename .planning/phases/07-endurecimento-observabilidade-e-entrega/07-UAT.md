---
status: testing
phase: 07-endurecimento-observabilidade-e-entrega
source: [07-VERIFICATION.md]
started: 2026-11-05T23:15:45Z
updated: 2026-11-07T23:53:00Z
---

## Current Test

number: 3
name: Try it out da Swagger UI pelo Gateway
expected: |
  Em `http://localhost:8080/swagger-ui.html`, o fluxo de login seguido de `POST /orders` funciona pelo Try it out, sem erro de CORS e usando o server relativo `/api`.
awaiting: user response

## Tests

### 1. Smoke de Correlation-ID na stack real
expected: Com o Docker ligado e a stack de pé, `bash scripts/smoke-correlation-id.sh` termina com sucesso e o mesmo Correlation-ID aparece nos logs dos 6 serviços. Guardar a saída como evidência. Falha intermitente com o ID presente nos logs aponta para o WR-01 do 07-REVIEW.md.
result: pass
source: automated
evidence: 07-SMOKE-EVIDENCE.txt

### 2. Prova de falha visível do CI
expected: Na branch descartável `ci/prova-falha-visivel`, com uma asserção invertida no `CorrelationIdFilterTest` do gateway, o push gera um run vermelho no GitHub Actions apontando o teste quebrado. Depois a branch é apagada.
result: pass
source: automated
evidence: 07-CI-FAILURE-EVIDENCE.txt (run https://github.com/Lucasllo/B2B_commercial/actions/runs/42426572364 — failure no job sem-localstack (gateway), CorrelationIdFilterTest:51; branch apagada no remoto e local)

### 3. Try it out da Swagger UI pelo Gateway
expected: Em `http://localhost:8080/swagger-ui.html`, o fluxo de login seguido de `POST /orders` funciona pelo Try it out, sem erro de CORS e usando o server relativo `/api`.
result: [pending]

## Summary

total: 3
passed: 2
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps

[none yet]
