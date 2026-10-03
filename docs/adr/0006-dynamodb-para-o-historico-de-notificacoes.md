---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0006 — DynamoDB para o histórico de notificações

## Contexto e problema

O notification-service guarda o histórico do que aconteceu: ajustes de estoque na Fase 3 e,
depois, a linha do tempo completa de cada pedido (criado, aprovado, confirmado, expedido, entregue).
Esse histórico é só de acréscimo, é consultado sempre por um identificador (produto ou pedido), em
ordem cronológica, e não tem junções nem transações com outras tabelas.

O projeto também precisa demonstrar persistência SQL e NoSQL lado a lado, e o histórico de
notificações é o lugar natural para o lado NoSQL, enquanto os demais serviços usam PostgreSQL para os
dados transacionais.

## Fatores de decisão

- Constraint do projeto: DynamoDB (via LocalStack) para o histórico de notificações, em contraste
  com o PostgreSQL dos serviços transacionais.
- O padrão de acesso é chave-valor: consulta por identificador da entidade, devolvendo uma lista
  ordenada.
- Reentrega do SQS não pode duplicar registros.
- A mesma forma de consulta deve servir ao histórico de produto e à linha do tempo do pedido.

## Alternativas consideradas

- **DynamoDB com partição por identificador da entidade e ordenação por tipo e evento** —
  **escolhida**.
- **PostgreSQL para tudo, inclusive o histórico** — **rejeitada**: o projeto precisa justamente
  demonstrar SQL e NoSQL lado a lado, e o histórico (acréscimo e consulta por chave) é um caso de uso
  natural de NoSQL.
- **Consulta devolvendo um registro único por chave exata (`GetItem`)** — **rejeitada**: o
  endpoint devolve uma lista por identificador (Query por partition key), pensada para a linha do tempo
  do pedido na Fase 6; trocar para registro único quebraria o contrato.
- **Tabela DynamoDB separada só para pedidos** — **rejeitada**: foi avaliada na Fase 6 e a tabela
  ficou única, com a mesma chave determinística servindo aos dois agregados.

## Decisão

A tabela `notification-history` usa uma chave de partição que identifica a entidade e uma chave de
ordenação composta por `eventType#eventId` (D-33). Na Fase 3 a partição era o `productId` (D-32); na
Fase 6 ela foi renomeada para um nome genérico, `entityId`, para que eventos de produto
(`STOCK_ADJUSTED`) e de pedido (`ORDER_*`) convivam na mesma tabela sem mudar a ordenação (D-80). O
mapeamento usa o DynamoDB Enhanced Client com `@DynamoDbBean`.

- A consulta devolve uma lista de eventos por identificador (D-31); o histórico do pedido sai em
  `GET /notifications/orders/{orderId}`, com SELLER_ADMIN vendo qualquer pedido e BUYER só o da própria
  empresa, e 404 caso contrário (D-81).
- A idempotência vem da chave: a reentrega da mesma mensagem tem o mesmo `eventId` e sobrescreve,
  enquanto um novo evento do mesmo tipo gera uma linha nova (D-33, D-34).
- O registro guarda o payload bruto e uma mensagem legível (D-34).

### Consequências

- Bom: SQL e NoSQL convivem no projeto, cada um onde faz sentido, e o histórico é idempotente pela
  própria chave, sem tabela de deduplicação.
- Bom: uma tabela e um repositório servem produto e pedido.
- Ruim: a composição da chave de ordenação é prática irreversível. Mudá-la depois de haver itens reais
  quebra a leitura dos já gravados (D-33 está marcada como one-way).
- Ruim: a troca de `productId` para `entityId` na Fase 6 mudou o key-schema da tabela e o bean; o
  estado do LocalStack é recriado na subida, então não houve dado real a migrar, mas numa AWS real
  isso exigiria recriar a tabela.
- Ruim: DynamoDB não permite consultas ad hoc ou junções; qualquer nova forma de consulta pede uma
  chave ou um índice novo.

## Prós e contras das alternativas

### DynamoDB (escolhida)

- Bom: acesso por chave eficiente, esquema flexível para eventos de tipos diferentes, demonstra NoSQL.
- Ruim: consultas limitadas à chave; mudar a chave é caro.

### PostgreSQL para tudo

- Bom: uma única tecnologia de persistência, consultas ad hoc e junções.
- Ruim: o projeto deixaria de demonstrar SQL e NoSQL lado a lado.

### Registro único por chave exata

- Bom: contrato mais simples (um item).
- Ruim: não serve à linha do tempo, que é uma lista ordenada.

### Tabela separada para pedidos

- Bom: isolamento entre os dois agregados.
- Ruim: segundo mapeamento, segundo repositório e segundo init hook para a mesma forma de consulta.

## Mais informações

Fase de origem: Fase 3 (histórico por `productId`) e Fase 6 (partição genérica `entityId`).

- Decisões: D-31, D-32, D-33 e D-34 em
  [03-CONTEXT.md](../../.planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-CONTEXT.md);
  D-80 e D-81 em
  [06-CONTEXT.md](../../.planning/phases/06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid/06-CONTEXT.md).
- Alternativa da tabela separada: registrada no
  [06-DISCUSSION-LOG.md](../../.planning/phases/06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid/06-DISCUSSION-LOG.md).
- Código:
  [NotificationRepository.java](../../notification-service/src/main/java/com/orderflow/notification/history/NotificationRepository.java)
  e [NotificationRecord.java](../../notification-service/src/main/java/com/orderflow/notification/history/NotificationRecord.java).
- Relacionado: [ADR 0003](0003-localstack-em-vez-de-aws-real.md), que explica por que o DynamoDB roda
  no LocalStack.
