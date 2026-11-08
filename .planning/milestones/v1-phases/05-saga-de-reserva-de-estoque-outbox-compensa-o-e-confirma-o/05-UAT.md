---
status: complete
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
source: 05-01-SUMMARY.md, 05-02-SUMMARY.md, 05-03-SUMMARY.md, 05-04-SUMMARY.md, 05-05-SUMMARY.md, 05-06-SUMMARY.md
started: 2026-09-30T22:09:34Z
updated: 2026-09-30T22:12:40Z
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Com `docker compose down -v` e depois `docker compose up -d --build --wait`, a stack inteira sobe do zero sem erro: todos os containers ficam healthy, as migrações Flyway do order-service (V1-V2) e do inventory-service (V1-V3) rodam, e `GET http://localhost:8080/actuator/health` (Gateway) responde UP.
result: pass

### 2. order-service ligado ao LocalStack saudável (05-01 D6)
expected: Na mesma subida, `docker compose ps` mostra o LocalStack healthy só depois de as filas `inventory-commands-queue` e `order-events-queue` (e as DLQs) existirem, e o order-service só sobe depois dele. `docker compose exec localstack awslocal sqs list-queues` lista as quatro filas.
result: pass

### 3. Saga observável pelo Swagger e pelo smoke
expected: `bash scripts/smoke-order-saga.sh` termina com sucesso. Em `http://localhost:8085/swagger-ui.html`, com um token de BUYER obtido pelo login (o script não imprime tokens, por design — T-05-25), um `POST /orders` dentro do estoque e do limite volta RESERVING e, poucos segundos depois, `GET /orders/{id}` mostra CONFIRMED com `confirmedAt`. Um pedido acima do disponível termina CANCELLED com `cancellationCode` INSUFFICIENT_STOCK e um `cancellationReason` legível.
result: pass

### 4. Documentação da saga legível para um avaliador (05-06 D4)
expected: As seções do README 'Saga de reserva de estoque (Fase 5)', 'Como observar a saga' e 'Limitações conhecidas (Fase 5)', a subseção de estados/códigos de cancelamento em `docs/API.md` e `docs/VISAO-GERAL.md` explicam o outbox, a entrega pelo menos uma vez, a idempotência, o timeout/compensação e a lápide, e dá para entender tudo sem abrir o código.
result: pass

### 5. Suíte completa verde depois das correções do review
expected: Com o compose derrubado (`docker compose down`) e o Docker de pé, `./mvnw -B verify` termina com BUILD SUCCESS: testes de integração do inventory/order e o E2E da saga (`e2e-tests`) passam por cima das correções WR-01/WR-02/WR-04, que só foram compiladas e testadas por unidade.
result: pass

### 6. 05-01 D1
expected: Aprovação automática dentro do limite grava RESERVING + ReserveStock no outbox na mesma transação, e o relay publica na fila real do LocalStack sem atributo JavaType
result: pass
source: automated
coverage_id: 05-01-D1

### 7. 05-01 D2
expected: Migração V2 move pedidos APPROVED legados para RESERVING e insere um comando ReserveStock por pedido no outbox, itens na ordem de line_number
result: pass
source: automated
coverage_id: 05-01-D2

### 8. 05-01 D3
expected: Aprovação manual do vendedor entra no mesmo ponto de entrada da saga (RESERVING + outbox), sob a mesma trava; rejeição nunca inicia a saga; dez aprovações simultâneas geram exatamente uma linha ReserveStock
result: pass
source: automated
coverage_id: 05-01-D3

### 9. 05-01 D4
expected: O relay isola falha de envio por evento — um evento com erro de envio não impede a publicação dos demais do lote, e um eventType desconhecido é registrado como falha sem envio
result: pass
source: automated
coverage_id: 05-01-D4

### 10. 05-01 D5
expected: Filas inventory-commands-queue e order-events-queue existem com RedrivePolicy apontando para suas DLQs e maxReceiveCount 3, criadas só pelo init hook do LocalStack
result: pass
source: automated
coverage_id: 05-01-D5

### 11. 05-02 D1
expected: Estoque insuficiente produz StockReservationFailed com reasonCode INSUFFICIENT_STOCK e failures [{productId, requested, available}]; nada é reservado (quantityReserved 0, nenhuma linha em stock_reservations)
result: pass
source: automated
coverage_id: 05-02-D1

### 12. 05-02 D2
expected: Produto sem linha de estoque produz PRODUCT_NOT_STOCKED com available 0; a mensagem é consumida uma única vez (sem reentrega)
result: pass
source: automated
coverage_id: 05-02-D2

### 13. 05-02 D3
expected: Multi-item com um produto insuficiente ou sem linha de estoque falha tudo (tudo-ou-nada) — o produto que estava ok não é reservado
result: pass
source: automated
coverage_id: 05-02-D3

### 14. 05-02 D4
expected: Mensagem malformada (JSON inválido, eventType desconhecido) é descartada com log WARN sanitizado; o consumo continua para a próxima mensagem válida
result: pass
source: automated
coverage_id: 05-02-D4

### 15. 05-02 D5
expected: Com estoque suficiente, reserva todos os itens e responde StockReserved; quantityOnHand não muda, quantityReserved reflete exatamente o pedido, uma linha por produto em stock_reservations
result: pass
source: automated
coverage_id: 05-02-D5

### 16. 05-02 D6
expected: Reentrega do mesmo ReserveStock decrementa a disponibilidade uma única vez e reemite um StockReserved por entrega, com eventId distintos (Success Criteria 4)
result: pass
source: automated
coverage_id: 05-02-D6

### 17. 05-02 D7
expected: Uma falha anterior não deixa rastro no livro — o mesmo comando reenviado depois de o vendedor ajustar o estoque é reavaliado e pode ter sucesso
result: pass
source: automated
coverage_id: 05-02-D7

### 18. 05-02 D8
expected: Dois pedidos disputando as últimas unidades ao mesmo tempo terminam com exatamente um StockReserved e um StockReservationFailed; quantityReserved nunca passa de quantityOnHand
result: pass
source: automated
coverage_id: 05-02-D8

### 19. 05-02 D9
expected: Livro parcialmente preenchido (anomalia técnica) lança IllegalStateException sem gravar nada no outbox — vai para reentrega e DLQ, nunca para um resultado de negócio
result: pass
source: automated
coverage_id: 05-02-D9

### 20. 05-02 D10
expected: Outbox do inventory-service (V2, FOR UPDATE SKIP LOCKED, Propagation.MANDATORY) é o único caminho de publicação do resultado da reserva; nenhum código de produção fora do relay e do publicador legado de STOCK_ADJUSTED fala com o SQS diretamente
result: pass
source: automated
coverage_id: 05-02-D10

### 21. 05-03 D1
expected: StockReservationFailed (INSUFFICIENT_STOCK/PRODUCT_NOT_STOCKED/RESERVATION_CANCELLED) leva o pedido RESERVING a CANCELLED com código, motivo legível montado por modelo fixo, cancelledAt preenchido e decidedBy/decidedAt/reason intactos; crédito liberado
result: pass
source: automated
coverage_id: 05-03-D1

### 22. 05-03 D2
expected: StockReserved com os itens do pedido confirma o pedido (CONFIRMED, confirmedAt preenchido, campos de cancelamento nulos); pedido CONFIRMED continua consumindo crédito
result: pass
source: automated
coverage_id: 05-03-D2

### 23. 05-03 D3
expected: Resultado duplicado (falha ou sucesso) é no-op na segunda entrega — cancelledAt/confirmedAt inalterados, guardado pelo estado sem tabela de mensagens processadas
result: pass
source: automated
coverage_id: 05-03-D3

### 24. 05-03 D4
expected: StockReserved tardio para pedido já CANCELLED mantém CANCELLED e grava, na mesma transação, um ReleaseStock no outbox (reason=LATE_RESERVATION) que o relay entrega na inventory-commands-queue; falha tardia após CONFIRMED mantém CONFIRMED
result: pass
source: automated
coverage_id: 05-03-D4

### 25. 05-03 D5
expected: StockReserved cujos itens não batem com os do pedido, resultado para pedido inexistente, ou mensagem malformada/eventType desconhecido são descartados com log WARN sem mudar pedido algum; o listener continua processando
result: pass
source: automated
coverage_id: 05-03-D5

### 26. 05-03 D6
expected: GET /orders/{id} expõe cancellationCode, cancellationReason, confirmedAt e cancelledAt; POST /orders e approve continuam devolvendo RESERVING com 201/200
result: pass
source: automated
coverage_id: 05-03-D6

### 27. 05-03 D7
expected: Nenhum cancellationReason contém Exception, 'at com.orderflow' ou 'http://'; a mensagem é montada por modelo fixo (CancellationReasons), nunca texto livre vindo da fila (T-05-14)
result: pass
source: automated
coverage_id: 05-03-D7

### 28. 05-04 D1
expected: Pedido preso em RESERVING além do prazo é cancelado pelo SagaTimeoutJob com RESERVATION_TIMEOUT e, na mesma transação, um ReleaseStock com os itens do pedido chega à inventory-commands-queue
result: pass
source: automated
coverage_id: 05-04-D1

### 29. 05-04 D2
expected: Pedido dentro do prazo não é tocado pelo job; resultado de falha tardio para pedido já cancelado por timeout é no-op; StockReserved tardio gera um SEGUNDO ReleaseStock (LATE_RESERVATION) e o pedido continua CANCELLED; crédito liberado por timeout permite novo pedido
result: pass
source: automated
coverage_id: 05-04-D2

### 30. 05-04 D3
expected: ReleaseStock de uma reserva existente devolve a quantidade reservada e marca a linha liberada; repetir o ReleaseStock não muda nada (idempotente)
result: pass
source: automated
coverage_id: 05-04-D3

### 31. 05-04 D4
expected: ReleaseStock chegando ANTES do ReserveStock grava lápides para todos os produtos do comando — inclusive produto sem linha de estoque; o ReserveStock posterior encontra a lápide e responde StockReservationFailed com reasonCode RESERVATION_CANCELLED e failures vazio, sem reservar nada
result: pass
source: automated
coverage_id: 05-04-D4

### 32. 05-04 D5
expected: ReleaseStock e ReserveStock do mesmo pedido chegando juntos terminam sempre com quantity_reserved inalterado e nenhuma reserva viva, em qualquer ordem de processamento (5 rodadas)
result: pass
source: automated
coverage_id: 05-04-D5

### 33. 05-04 D6
expected: PUT /inventory/{productId} grava STOCK_ADJUSTED no outbox na mesma transação (published_at preenchido pelo relay, eventId da mensagem igual ao id da linha); PUT recusado (409) não grava linha nova; testes de contrato pré-existentes (6 campos, sem JavaType) continuam passando
result: pass
source: automated
coverage_id: 05-04-D6

### 34. 05-04 D7
expected: Único arquivo de produção do inventory-service que importa a API de envio SQS é o relay do outbox; publicador direto legado e seu teste de falha não existem mais
result: pass
source: automated
coverage_id: 05-04-D7

### 35. 05-05 D1
expected: Os dois contextos Spring reais sobem isolados no mesmo JVM: nomes, schemas do Flyway e títulos do OpenAPI próprios, migrações corretas por schema (order v1-v2, inventory v1-v2-v3), risco de colisão de classpath documentado, health sem token nos dois
result: pass
source: automated
coverage_id: 05-05-D1

### 36. 05-05 D2
expected: Módulo e2e-tests no reactor e nos seis Dockerfiles; imagens do order-service e do inventory-service constroem com o módulo novo
result: pass
source: automated
coverage_id: 05-05-D2

### 37. 05-05 D3
expected: Caminho de falha (escrito primeiro, D-68): pedido com estoque insuficiente termina CANCELLED com INSUFFICIENT_STOCK e motivo citando disponível/solicitado; produto sem linha de estoque termina CANCELLED com PRODUCT_NOT_STOCKED
result: pass
source: automated
coverage_id: 05-05-D3

### 38. 05-05 D4
expected: Pedido com estoque suficiente passa por RESERVING e termina CONFIRMED, com quantityReserved/quantityAvailable refletidos no inventory-service
result: pass
source: automated
coverage_id: 05-05-D4

### 39. 05-05 D5
expected: Reenviar o mesmo ReserveStock de um pedido já CONFIRMED direto na fila real não reserva de novo — replay idempotente, mesmo confirmedAt, só um segundo StockReserved sai no outbox
result: pass
source: automated
coverage_id: 05-05-D5

### 40. 05-05 D6
expected: Os dois pontos de entrada da saga (D-48) de ponta a ponta: pedido acima do limite fica PENDING_APPROVAL, é aprovado manualmente pelo vendedor, e também termina CONFIRMED com o estoque reservado
result: pass
source: automated
coverage_id: 05-05-D6

### 41. 05-06 D1
expected: scripts/smoke-order-saga.sh demonstra a saga completa na stack real (docker compose up --wait), pelo Gateway: pedido com estoque suficiente reserva e confirma com o estoque refletido no inventory (Success Criteria 2), pedido acima do disponível ou de produto sem estoque cancela com motivo legível sem afetar a reserva confirmada (Success Criteria 3), a aprovação manual do vendedor também confirma via saga (D-48), e o ajuste de estoque do passo 3 chega ao histórico de notificações pelo outbox (D-60)
result: pass
source: automated
coverage_id: 05-06-D1

### 42. 05-06 D2
expected: scripts/smoke-order-flow.sh (Fase 4) ajustado ao estado RESERVING nos passos 5 e 11 (D-54) e ao estoque de P1 definido no passo 3 — continua SMOKE OK sem regressão nos Success Criteria 1 a 4 originais
result: pass
source: automated
coverage_id: 05-06-D2

### 43. 05-06 D3
expected: scripts/smoke-notification-flow.sh (Fase 3) continua passando sem nenhuma alteração de código, mesmo com STOCK_ADJUSTED agora saindo pelo outbox do inventory-service (05-04)
result: pass
source: automated
coverage_id: 05-06-D3

## Summary

total: 43
passed: 43
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none yet]
