---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0002 — Transactional Outbox em vez de publicação direta no SQS

## Contexto e problema

Quando um serviço grava no banco e publica uma mensagem no SQS como duas operações separadas, não
existe atomicidade entre elas (o problema do dual-write). Se o processo cair depois do commit e
antes do envio, o pedido existe sem que a reserva de estoque jamais seja pedida; se a publicação
ocorrer e o commit falhar, sai um comando para um pedido que não existe. Os dois lados divergem em
silêncio, e em testes manuais isso quase nunca aparece, porque as duas chamadas costumam funcionar
juntas.

O problema fica crítico quando a saga entra (ADR 0001): o order-service precisa que "o pedido mudou
de estado" e "o comando de reserva será publicado" sejam a mesma verdade. O inventory-service tem a
mesma necessidade ao responder com o resultado da reserva.

## Fatores de decisão

- Atomicidade entre a mudança de estado no PostgreSQL e o evento que deve sair para o SQS, sem
  transação distribuída (2PC).
- Entrega pelo menos uma vez é aceitável, desde que todo consumidor seja idempotente.
- Simplicidade de explicar e de operar localmente: sem componentes extras de infraestrutura.
- Constraint do projeto: o padrão Transactional Outbox é exigido em order-service e
  inventory-service.

## Alternativas consideradas

- **Transactional Outbox com relay por polling `@Scheduled`** — **escolhida**.
- **Publicação direta após o commit (dual-write)** — **rejeitada** para a saga: foi o que a Fase 3
  usou de propósito, com a limitação declarada (D-29, D-30), mas não oferece nenhuma garantia
  quando o processo cai entre o commit e o envio.
- **CDC com Debezium lendo o log do banco** — **rejeitada** neste escopo: é uma peça de
  infraestrutura a mais para operar e explicar; o polling simples basta na escala de demonstração e
  o CDC fica registrado como o próximo passo de uma versão de maior vazão.

## Decisão

Cada serviço que publica no SQS grava o evento numa tabela `outbox_event` na mesma transação da
mudança de estado, e um relay separado lê as linhas não publicadas e as envia. O relay é o único
caminho de envio SQS do sistema.

- O relay faz polling `@Scheduled` (cerca de 1 segundo, configurável), busca um lote com
  `SELECT ... FOR UPDATE SKIP LOCKED`, envia via `SqsTemplate` e marca `published_at`. A entrega é
  pelo menos uma vez, por isso todo consumidor é idempotente (D-59).
- O outbox existe nos dois serviços: o inventory-service grava o resultado da reserva na mesma
  transação dela, e o `STOCK_ADJUSTED` da Fase 3 migrou para o mesmo outbox, o que fecha a
  limitação do dual-write (D-60).
- O código do outbox é duplicado em cada serviço, sem módulo compartilhado (D-62; ver ADR 0011).

### Consequências

- Bom: um evento só é publicado se, e somente se, a transação que o gerou foi confirmada.
- Bom: a idempotência dos consumidores passa a ser requisito explícito e testado (livro
  `stock_reservations` no inventory, transição guardada por estado no order).
- Ruim: latência do polling. Cada salto da saga espera até cerca de 1 segundo pelo relay; a saga
  nunca é instantânea.
- Ruim: o outbox não tem retenção. As linhas publicadas nunca são apagadas e as tabelas crescem
  indefinidamente; a rotina de expurgo e a limpeza das lápides do livro de reservas (AR-05-02)
  continuam como limitações conhecidas e declaradas, adiadas para o backlog.
- Ruim: o código do relay existe duas vezes, e uma correção precisa ser aplicada nos dois.

## Prós e contras das alternativas

### Transactional Outbox com polling

- Bom: atomicidade sem 2PC; poucas peças móveis; fácil de explicar.
- Ruim: latência do polling e crescimento da tabela sem retenção.

### Publicação direta (dual-write)

- Bom: o código mais simples (`save` seguido de `send`).
- Ruim: divergência silenciosa entre banco e fila quando uma das duas operações falha.
- Aceita apenas na Fase 3, isolada e declarada; deixou de existir no caminho síncrono na Fase 5.

### CDC com Debezium

- Bom: menor latência e nenhum polling do banco pela aplicação.
- Ruim: outro componente de infraestrutura para operar, documentar e explicar, sem ganho
  demonstrável na escala deste projeto.

## Mais informações

Fase de origem: Fase 3 (dual-write declarado) e Fase 5 (outbox implementado).

- Decisões: D-29 e D-30 em
  [03-CONTEXT.md](../../.planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-CONTEXT.md);
  D-59, D-60 e D-62 em
  [05-CONTEXT.md](../../.planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-CONTEXT.md).
- Risco aceito sobre retenção e lápides: [05-SECURITY.md](../../.planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-SECURITY.md)
  (AR-05-02) e a seção "Limitações conhecidas" do [README.md](../../README.md).
- Base da pesquisa: [PITFALLS.md](../../.planning/research/PITFALLS.md), Pitfall 2 (dual-write).
- Código no order-service:
  [OutboxWriter.java](../../order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxWriter.java)
  e [OutboxRelay.java](../../order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java).
- Código no inventory-service:
  [OutboxWriter.java](../../inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxWriter.java)
  e [OutboxRelay.java](../../inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelay.java).
- Relacionado: [ADR 0001](0001-saga-por-orquestracao-no-order-service.md) e
  ADR 0011 (a ser escrito na Task 3).
