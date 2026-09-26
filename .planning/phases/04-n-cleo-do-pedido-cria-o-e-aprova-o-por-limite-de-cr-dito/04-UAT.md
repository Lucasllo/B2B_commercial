---
status: complete
phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
source: [04-VERIFICATION.md]
started: 2026-09-26T00:15:37Z
updated: 2026-09-26T00:29:57Z
---

## Current Test

[testing complete]

## Tests

### 1. Swagger UI do order-service e clareza do README para um avaliador externo
steps: Com a stack no ar (`docker compose up -d --wait`), abrir `http://localhost:8085/swagger-ui.html`, clicar em Authorize com um token de BUYER (gerado pelo `scripts/smoke-order-flow.sh`), executar `POST /orders` e `GET /orders` via Try it out; depois ler as seções "Como a aprovação por crédito funciona" e "Limitações conhecidas (Fase 4)" do README.md; ao terminar, `docker compose down`.
expected: Os cinco endpoints de pedido aparecem com o cadeado (bearerAuth); o Try it out com token funciona e sem token devolve 401; o README explica com clareza a regra de exposição acumulada e diz explicitamente que APPROVED ainda não reserva estoque nem confirma o pedido.
result: pass

## Summary

total: 1
passed: 1
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
