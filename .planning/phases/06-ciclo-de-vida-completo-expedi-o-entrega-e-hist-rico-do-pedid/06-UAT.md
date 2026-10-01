---
status: complete
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
source: [06-01-SUMMARY.md, 06-02-SUMMARY.md, 06-03-SUMMARY.md, 06-04-SUMMARY.md, 06-05-SUMMARY.md, 06-06-SUMMARY.md, 06-07-SUMMARY.md]
started: 2026-10-01T23:03:55.118Z
updated: 2026-10-01T23:08:00.068Z
---

## Current Test
<!-- OVERWRITE each test - shows where we are -->

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Com `docker compose down -v` e depois `docker compose up -d --build`, todos os containers (gateway, auth, catalog, inventory, order, notification, Postgres, LocalStack) ficam healthy sem erro de migration Flyway (V3 do order, V4 do inventory) e `GET /actuator/health` pelo Gateway responde UP.
result: pass

### 2. LocalStack: tabela de notificações com partição entityId
expected: Com a stack no ar, `awslocal dynamodb describe-table` mostra a tabela de notificações com hash key `entityId`. O healthcheck do LocalStack só fica healthy com essa partição, e o init hook recria a tabela se ela existir com o key-schema antigo (productId) num LocalStack que não reiniciou.
result: pass

### 3. Smoke da jornada completa na stack real
expected: `scripts/smoke-order-lifecycle.sh` passa a jornada feliz pelo Gateway: o pedido criado chega a CONFIRMED com carrier e trackingCode S10 (`^[A-Z]{2}[0-9]{9}BR$`), vai para SHIPPED e DELIVERED, o estoque baixa (7 em mãos / 0 reservado) e o comprador lê a linha do tempo ORDER_CREATED → ORDER_DELIVERED.
result: pass

### 4. Smoke dos caminhos tristes e recusas
expected: No mesmo script: INSUFFICIENT_STOCK leva a CANCELLED (timeline CREATED/APPROVED/CANCELLED), rejeição dá CREATED/PENDING_APPROVAL/REJECTED, ação fora da tabela responde 409 invalid_order_transition, BUYER em /ship responde 403 e pedido de outra empresa responde 404 order_not_found.
result: pass

### 5. Smokes anteriores sem regressão
expected: Na mesma stack, sem editar nada, `smoke-notification-flow.sh`, `smoke-order-flow.sh` e `smoke-order-saga.sh` continuam passando.
result: pass

### 6. Reactor inteiro verde
expected: Com o compose derrubado, `./mvnw -B verify` na raiz termina em BUILD SUCCESS em todos os módulos, incluindo os ITs com Testcontainers e o `e2e-tests`.
result: pass

### 7. Linha do tempo sem dual-write
expected: No order-service, o pacote timeline não tem cliente SQS: o escritor de eventos ORDER_* só chama o OutboxWriter numa transação MANDATORY (confira, por exemplo, com `grep -rn "Sqs" order-service/src/main/java/**/timeline`, que não deve retornar nada).
result: pass

### 8. Documentação legível no GitHub
expected: O README e o `docs/API.md` renderizados mostram o diagrama Mermaid do ciclo de vida, os endpoints ship/deliver/linha do tempo, os seis campos novos do pedido, o ShipStock, os oito eventos ORDER_*, o smoke e as limitações da Fase 6. A transportadora aparece explicitamente como simulada (costura CarrierGateway) e seguir um pedido pelo Swagger é compreensível.
result: pass

### 9. [06-01] StockReserved leva RESERVING a CONFIRMED com transportadora e codigo S10 gravados na mesma transacao e visiveis em GET /orders/{id}
expected: StockReserved leva RESERVING a CONFIRMED com transportadora e codigo S10 gravados na mesma transacao e visiveis em GET /orders/{id}
result: pass
source: automated
coverage_id: 06-01-D1

### 10. [06-01] Atribuicao deterministica por orderId; duplicata nao troca; pedido cancelado nunca recebe transportadora
expected: Atribuicao deterministica por orderId; duplicata nao troca; pedido cancelado nunca recebe transportadora
result: pass
source: automated
coverage_id: 06-01-D2

### 11. [06-01] Codigo S10 com digito verificador correto (exemplo oficial 47312482 -> 9) e padrao ^[A-Z]{2}[0-9]{9}BR$
expected: Codigo S10 com digito verificador correto (exemplo oficial 47312482 -> 9) e padrao ^[A-Z]{2}[0-9]{9}BR$
result: pass
source: automated
coverage_id: 06-01-D3

### 12. [06-01] V3 aplicada sobre base legada faz backfill de CONFIRMED e o banco recusa CONFIRMED sem transportadora
expected: V3 aplicada sobre base legada faz backfill de CONFIRMED e o banco recusa CONFIRMED sem transportadora
result: pass
source: automated
coverage_id: 06-01-D4

### 13. [06-01] Tabela unica de 9 transicoes testada nos 81 pares e consultada por todo metodo de transicao do Order e pelas guardas da saga
expected: Tabela unica de 9 transicoes testada nos 81 pares e consultada por todo metodo de transicao do Order e pelas guardas da saga
result: pass
source: automated
coverage_id: 06-01-D5

### 14. [06-02] POST /orders/{id}/ship leva CONFIRMED a SHIPPED com shippedAt/shippedBy (sub do JWT), mantendo transportadora e rastreio
expected: POST /orders/{id}/ship leva CONFIRMED a SHIPPED com shippedAt/shippedBy (sub do JWT), mantendo transportadora e rastreio
result: pass
source: automated
coverage_id: 06-02-D1

### 15. [06-02] ShipStock gravado no outbox na mesma transacao, entregue pelo relay na inventory-commands-queue com reservationId=orderId, itens do pedido e sem reason
expected: ShipStock gravado no outbox na mesma transacao, entregue pelo relay na inventory-commands-queue com reservationId=orderId, itens do pedido e sem reason
result: pass
source: automated
coverage_id: 06-02-D2

### 16. [06-02] Duas expedicoes simultaneas: uma vence, a outra recebe InvalidOrderTransitionException, exatamente um ShipStock
expected: Duas expedicoes simultaneas: uma vence, a outra recebe InvalidOrderTransitionException, exatamente um ShipStock
result: pass
source: automated
coverage_id: 06-02-D3

### 17. [06-02] POST /orders/{id}/deliver leva SHIPPED a DELIVERED sem gravar nada no outbox e sem nova mensagem de estoque; DELIVERED e SHIPPED seguem consumindo credito
expected: POST /orders/{id}/deliver leva SHIPPED a DELIVERED sem gravar nada no outbox e sem nova mensagem de estoque; DELIVERED e SHIPPED seguem consumindo credito
result: pass
source: automated
coverage_id: 06-02-D4

### 18. [06-02] Cada um dos 9 status x 4 acoes responde 200 so na origem da aresta da acao e 409 nos demais casos sem alterar o pedido; CREATED->DELIVERED e CANCELLED imutavel sao 409
expected: Cada um dos 9 status x 4 acoes responde 200 so na origem da aresta da acao e 409 nos demais casos sem alterar o pedido; CREATED->DELIVERED e CANCELLED imutavel sao 409
result: pass
source: automated
coverage_id: 06-02-D5

### 19. [06-02] 404 order_not_found, 403 forbidden (BUYER), 401 sem token e 400 invalid_parameter para id nao UUID em ship e deliver
expected: 404 order_not_found, 403 forbidden (BUYER), 401 sem token e 400 invalid_parameter para id nao UUID em ship e deliver
result: pass
source: automated
coverage_id: 06-02-D6

### 20. [06-03] ShipStock na inventory-commands-queue baixa quantity_on_hand e quantity_reserved pela quantidade do livro e marca a reserva como expedida, na mesma transacao
expected: ShipStock na inventory-commands-queue baixa quantity_on_hand e quantity_reserved pela quantidade do livro e marca a reserva como expedida, na mesma transacao
result: pass
source: automated
coverage_id: 06-03-D1

### 21. [06-03] A expedicao nao publica STOCK_ADJUSTED
expected: A expedicao nao publica STOCK_ADJUSTED
result: pass
source: automated
coverage_id: 06-03-D2

### 22. [06-03] ShipStock reentregue (mesmo eventId ou outro) nao baixa de novo; ReserveStock reentregue apos a expedicao nao mexe no estoque
expected: ShipStock reentregue (mesmo eventId ou outro) nao baixa de novo; ReserveStock reentregue apos a expedicao nao mexe no estoque
result: pass
source: automated
coverage_id: 06-03-D3

### 23. [06-03] Reserva inexistente ou liberada e anomalia tecnica (IllegalStateException com orderId, nunca ExhaustedRetryException) sem alterar estoque
expected: Reserva inexistente ou liberada e anomalia tecnica (IllegalStateException com orderId, nunca ExhaustedRetryException) sem alterar estoque
result: pass
source: automated
coverage_id: 06-03-D4

### 24. [06-03] ReleaseStock e DELETE REST apos a expedicao nao mexem na reserva expedida nem na de outro pedido; o banco recusa released+shipped
expected: ReleaseStock e DELETE REST apos a expedicao nao mexem na reserva expedida nem na de outro pedido; o banco recusa released+shipped
result: pass
source: automated
coverage_id: 06-03-D5

### 25. [06-03] ShipStock malformado e descartado pelo parser (tamanho, JSON, items, quantidade, reservationId != orderId, campos ausentes/invalidos) com mensagem sanitizada
expected: ShipStock malformado e descartado pelo parser (tamanho, JSON, items, quantidade, reservationId != orderId, campos ausentes/invalidos) com mensagem sanitizada
result: pass
source: automated
coverage_id: 06-03-D6

### 26. [06-04] Evento ORDER_* valido na notification-events-queue vira item da tabela com particao entityId = orderId, sort key eventType#eventId e companyId, consultavel em GET /notifications/orders/{orderId} em ate 15 s
expected: Evento ORDER_* valido na notification-events-queue vira item da tabela com particao entityId = orderId, sort key eventType#eventId e companyId, consultavel em GET /notifications/orders/{orderId} em ate 15 s
result: pass
source: automated
coverage_id: 06-04-D1

### 27. [06-04] Os oito tipos ORDER_* validados por tipo com mensagem legivel exata; tipo desconhecido, campo ausente, UUID invalido, trackingCode/cancellationCode fora do padrao e texto acima do teto descartados sem gravar
expected: Os oito tipos ORDER_* validados por tipo com mensagem legivel exata; tipo desconhecido, campo ausente, UUID invalido, trackingCode/cancellationCode fora do padrao e texto acima do teto descartados sem gravar
result: pass
source: automated
coverage_id: 06-04-D2

### 28. [06-04] Linha do tempo em ordem de ciclo de vida mesmo com eventos de mesmo occurredAt fora de ordem
expected: Linha do tempo em ordem de ciclo de vida mesmo com eventos de mesmo occurredAt fora de ordem
result: pass
source: automated
coverage_id: 06-04-D3

### 29. [06-04] Reentregar o mesmo evento grava um unico registro (chave deterministica, putItem sem condicao)
expected: Reentregar o mesmo evento grava um unico registro (chave deterministica, putItem sem condicao)
result: pass
source: automated
coverage_id: 06-04-D4

### 30. [06-04] Autorizacao da linha do tempo (IDOR): SELLER_ADMIN ve qualquer pedido, BUYER so o da propria empresa, 404 identico para outra empresa/inexistente/sem eventos/empresas misturadas, 401, 403 sem company_id, 400 para nao-UUID, sem vazamento nos erros
expected: Autorizacao da linha do tempo (IDOR): SELLER_ADMIN ve qualquer pedido, BUYER so o da propria empresa, 404 identico para outra empresa/inexistente/sem eventos/empresas misturadas, 401, 403 sem company_id, 400 para nao-UUID, sem vazamento nos erros
result: pass
source: automated
coverage_id: 06-04-D5

### 31. [06-04] Historico de produto da Fase 3 inalterado (GET /notifications/{productId} so SELLER_ADMIN, STOCK_ADJUSTED, mesma ordenacao) com o campo entityId
expected: Historico de produto da Fase 3 inalterado (GET /notifications/{productId} so SELLER_ADMIN, STOCK_ADJUSTED, mesma ordenacao) com o campo entityId
result: pass
source: automated
coverage_id: 06-04-D6

### 32. [06-05] Criacao dentro do limite grava ORDER_CREATED, ORDER_APPROVED (SYSTEM) e ReserveStock, nessa ordem; acima do limite grava ORDER_CREATED e ORDER_PENDING_APPROVAL, sem ReserveStock
expected: Criacao dentro do limite grava ORDER_CREATED, ORDER_APPROVED (SYSTEM) e ReserveStock, nessa ordem; acima do limite grava ORDER_CREATED e ORDER_PENDING_APPROVAL, sem ReserveStock
result: pass
source: automated
coverage_id: 06-05-D1

### 33. [06-05] Envelope: eventId = id da linha, occurredAt = instante gravado pela transicao, nenhum campo nulo serializado, entrega na notification-events-queue real com published_at preenchido e attempts 0
expected: Envelope: eventId = id da linha, occurredAt = instante gravado pela transicao, nenhum campo nulo serializado, entrega na notification-events-queue real com published_at preenchido e attempts 0
result: pass
source: automated
coverage_id: 06-05-D2

### 34. [06-05] Aprovacao manual, rejeicao, confirmacao, cancelamento (falha e timeout), expedicao e entrega gravam cada um um evento na transacao da transicao; StockReserved tardio/duplicado e ship recusado nao geram evento
expected: Aprovacao manual, rejeicao, confirmacao, cancelamento (falha e timeout), expedicao e entrega gravam cada um um evento na transacao da transicao; StockReserved tardio/duplicado e ship recusado nao geram evento
result: pass
source: automated
coverage_id: 06-05-D3

### 35. [06-05] Jornada completa gera exatamente cinco linhas ORDER_* (CREATED, APPROVED, CONFIRMED, SHIPPED, DELIVERED), todas publicadas, e as cinco chegam a fila
expected: Jornada completa gera exatamente cinco linhas ORDER_* (CREATED, APPROVED, CONFIRMED, SHIPPED, DELIVERED), todas publicadas, e as cinco chegam a fila
result: pass
source: automated
coverage_id: 06-05-D4

### 36. [06-05] Relay: oito tipos ORDER_* para a fila de notificacoes, tres comandos para a de comandos, tipo desconhecido vira falha por evento sem derrubar o lote
expected: Relay: oito tipos ORDER_* para a fila de notificacoes, tres comandos para a de comandos, tipo desconhecido vira falha por evento sem derrubar o lote
result: pass
source: automated
coverage_id: 06-05-D5

### 37. [06-06] E2E: ship baixa quantityOnHand/quantityReserved uma vez (marca shipped), republicar ShipStock nao baixa de novo, deliver nao mexe no estoque, ship em CANCELLED responde 409
expected: E2E: ship baixa quantityOnHand/quantityReserved uma vez (marca shipped), republicar ShipStock nao baixa de novo, deliver nao mexe no estoque, ship em CANCELLED responde 409
result: pass
source: automated
coverage_id: 06-06-D4

### 38. [06-07] README tem o diagrama stateDiagram-v2 do ciclo de vida com exatamente as 9 arestas de OrderStatus.transitions(), rotuladas com o gatilho
expected: README tem o diagrama stateDiagram-v2 do ciclo de vida com exatamente as 9 arestas de OrderStatus.transitions(), rotuladas com o gatilho
result: pass
source: automated
coverage_id: 06-07-D1

### 39. [06-07] Visao geral tem o mesmo diagrama e a tabela transicao x gatilho x endpoint/mensagem x evento da linha do tempo
expected: Visao geral tem o mesmo diagrama e a tabela transicao x gatilho x endpoint/mensagem x evento da linha do tempo
result: pass
source: automated
coverage_id: 06-07-D2

### 40. [06-07] O comparador acusa aresta a mais, aresta a menos, estado inexistente e linha malformada
expected: O comparador acusa aresta a mais, aresta a menos, estado inexistente e linha malformada
result: pass
source: automated
coverage_id: 06-07-D3

## Summary

total: 40
passed: 40
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none yet]
