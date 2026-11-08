---
status: complete
phase: 02-cat-logo-e-estoque
source: [02-01-SUMMARY.md, 02-02-SUMMARY.md, 02-03-SUMMARY.md]
started: 2026-09-20T14:03:14Z
updated: 2026-09-20T14:15:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Criar produto e lê-lo de volta com preço/status corretos
expected: SELLER_ADMIN cria produto (POST /products), lê de volta (GET /products/{id}), preço exato em BigDecimal/NUMERIC(19,2), status sempre ACTIVE fixado no servidor.
result: pass
source: automated
coverage_id: 02-01-D1

### 2. Atualizar e retirar/reativar produto sem remoção física
expected: SELLER_ADMIN atualiza nome/preço/descrição (PUT /products/{id}) e retira/reativa produto por status (PUT /products/{id}/status) — nunca remoção física.
result: pass
source: automated
coverage_id: 02-01-D2

### 3. Listagem paginada por papel (MockMvc)
expected: BUYER lista catálogo paginado vendo apenas ACTIVE; SELLER_ADMIN vê tudo; detalhe de produto DISCONTINUED é 404 para BUYER e 200 para SELLER_ADMIN.
result: pass
source: automated
coverage_id: 02-01-D3

### 4. Definir/redefinir estoque e ler disponibilidade exata
expected: SELLER_ADMIN define e redefine o nível de estoque de um produto (upsert, D-18); qualquer autenticado lê a disponibilidade exata (on_hand - reserved), nunca um booleano (D-25).
result: pass
source: automated
coverage_id: 02-02-D1

### 5. Reserva atômica e idempotente (MockMvc)
expected: Reservar mais que o disponível devolve 409 com available/requested; reenviar a mesma reserva não duplica o decremento; disputa esgotada devolve 503 distinto do 409.
result: pass
source: automated
coverage_id: 02-02-D2

### 6. Liberação idempotente de reserva
expected: Repetir a liberação ou liberar um reservationId inexistente sobre produto com linha é no-op; liberar contra productId sem linha é 404; após liberar, a quantidade volta a caber em nova reserva.
result: pass
source: automated
coverage_id: 02-02-D3

### 7. Reservas concorrentes nunca excedem o estoque (HTTP real, três cenários)
expected: Requisições HTTP simultâneas por socket real, threads virtuais liberadas por barreira, contra as últimas unidades de um produto, nunca reservam mais do que o disponível — em três cenários de contenção (1, 3 e 5 unidades, até 20 contendores).
result: pass
source: automated
coverage_id: 02-03-D2

### 8. Reexecução por conflito de versão realmente acontece
expected: A reexecução configurada por conflito de lock otimista (@Retryable + @Transactional) de fato reexecuta contra transação nova e releitura da linha, provado isoladamente.
result: pass
source: automated
coverage_id: 02-03-D3

### 9. Sistema inteiro sobe com um comando e catálogo/estoque exigem JWT pelo Gateway
expected: |
  Com `docker compose up -d --wait`, os seis serviços da Fase 2 ficam saudáveis. Pelo Gateway,
  requisição sem token a `/api/products` e `/api/inventory/{id}` devolve 401 nas duas.
  `POST /api/auth/login` continua funcionando.
result: pass

### 10. Teste de concorrência é capaz de detectar a falha que existe para provar
expected: |
  Removendo temporariamente `@Version` de `Inventory.java` e rodando o cenário de 1 unidade
  contra 20 contendores concorrentes, mais de uma reserva é aceita (prova de que o teste tem
  poder de detecção). Restaurando `@Version`, a suíte volta a ficar verde.
result: pass

### 11. Listagem de catálogo e disponibilidade de estoque por papel, com a stack completa no ar
expected: |
  Com a stack rodando (gateway + docker-compose), um SELLER_ADMIN vê todos os produtos
  (inclusive descontinuados) e um BUYER vê apenas produtos ACTIVE — cada verificação por HTTP
  real, não apenas MockMvc.
result: pass

### 12. Prova de atomicidade sob concorrência real via HTTP (Success Criteria 3)
expected: |
  Requisições HTTP simultâneas, disparadas por threads virtuais liberadas juntas por uma
  barreira, contra as últimas unidades de um produto, nunca reservam mais do que o disponível.
result: pass

## Summary

total: 12
passed: 12
issues: 0
pending: 0
skipped: 0
