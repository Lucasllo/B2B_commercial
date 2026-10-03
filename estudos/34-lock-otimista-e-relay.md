# Lock otimista, reexecução automática e relay

Arquivos citados:

- `inventory-service/src/main/java/com/orderflow/inventory/stock/Inventory.java` — campo `@Version`
- `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java` — `@Retryable`
- `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java`
- `inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxRelay.java` e `OutboxRelayJob.java`

Explicação em linguagem de iniciante de três termos que aparecem em
[01-docker-compose.md](01-docker-compose.md) ("lock otimista + reexecução automática" e
"relay"). Para o aprofundamento, veja [14-spring-retry.md](14-spring-retry.md),
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md) e
[26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md).

## 1. Lock otimista + reexecução automática

### O problema: duas pessoas mexendo na mesma coisa ao mesmo tempo

Imagine uma planilha de estoque compartilhada que diz **"Caneta: 10 unidades"**. Dois
vendedores, Ana e Bruno, abrem essa planilha no mesmo segundo:

1. Ana lê "10" e quer reservar 3, então vai escrever **7**.
2. Bruno também lê "10" e quer reservar 5, então vai escrever **5**.
3. Ana salva **7**. Bruno salva **5** logo depois.

Resultado: a planilha diz 5, mas 8 canetas saíram (3 + 5). O certo seria **2**. A alteração
da Ana se **perdeu**, porque o Bruno sobrescreveu por cima dela. No banco de dados acontece
exatamente isso quando dois pedidos chegam juntos. Esse problema se chama **condição de
corrida**.

### A solução "otimista": um número de versão

Existem duas formas de resolver:

- **Pessimista:** "vai dar briga, então tranco a porta". Quem chega primeiro trava a linha, e
  o outro **espera na fila**. O projeto usa esse jeito na trava de crédito por empresa (ver
  [21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md)).
- **Otimista:** "provavelmente não vai dar briga, então deixo todo mundo entrar, mas confiro
  na saída".

O lock otimista funciona com uma etiqueta de **versão** colada no registro:

| produto | quantidade | versão |
|---|---|---|
| Caneta | 10 | **1** |

Agora a gravação diz: *"mude para 7, **mas só se** a versão ainda for 1, e passe a versão para
2"*.

1. Ana salva: a versão era 1, então a gravação é aceita. Fica `7`, versão `2`.
2. Bruno tenta salvar dizendo "só se a versão for 1". A versão agora é 2, então a gravação é
   **recusada**.

Ninguém sobrescreve ninguém. Quem chegou depois recebe um erro dizendo "alguém mexeu nisso
antes de você".

No projeto, essa etiqueta é o campo `version` da entidade `Inventory`:

```java
@Version
@Column(nullable = false)
private long version;
```

O JPA (a biblioteca que conversa com o banco) faz a conferência sozinho. Ele acrescenta
`WHERE version = ?` em cada `UPDATE` e, quando nenhuma linha é alterada, lança
`ObjectOptimisticLockingFailureException`.

### E a "reexecução automática"?

Bruno levou um "não", mas o pedido dele era legítimo e não deveria simplesmente falhar. A
**reexecução automática** (retry) faz o sistema dizer: *"Tudo bem, leia de novo e tente outra
vez"*.

1. Bruno relê: agora diz **7**, versão **2**.
2. Calcula 7 − 5 = **2** e salva "só se a versão for 2". A gravação é aceita.

O resultado final é **2**, o valor correto. Quem faz isso no projeto é o **Spring Retry**, com
a anotação `@Retryable` nos métodos do `InventoryService`:

```java
@Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
        maxAttempts = RETRY_MAX_ATTEMPTS,
        backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
```

Em português: "se der conflito de versão, tente de novo, até 10 vezes (`RETRY_MAX_ATTEMPTS`),
esperando um pouquinho entre as tentativas (começa em 20 ms e vai aumentando)". Se não der
certo de jeito nenhum, cai num "plano B" (`@Recover`), explicado em
[14-spring-retry.md](14-spring-retry.md).

**Resumindo:** o lock otimista **detecta** a briga, e a reexecução automática **resolve** a
briga tentando de novo. O `StockReservationConcurrencyIT` é um teste que dispara vários pedidos
ao mesmo tempo e confere que o estoque termina com o número certo.

## 2. Relay

"Relay" em inglês quer dizer **revezamento** ou **retransmissor**, como o corredor que pega o
bastão e leva adiante.

### O problema que ele resolve

Quando o estoque muda, o `inventory-service` precisa fazer **duas coisas**:

1. salvar a mudança no **banco de dados**;
2. avisar o `notification-service`, mandando uma mensagem pela **fila SQS**.

Se fizer as duas coisas diretamente, uma pode dar certo e a outra falhar:

- o banco salvou, mas a fila estava fora do ar, e o aviso **sumiu**;
- o aviso saiu, mas o banco desfez a gravação, e agora existe um aviso de algo que **nunca
  aconteceu**.

Esse problema tem nome: **dual-write** (escrita dupla).

### A solução: a caixa de saída (outbox) + o carteiro (relay)

Pense num escritório:

- O funcionário **não vai ao correio**. Ele escreve a carta e a coloca na **caixa de saída** da
  mesa, no mesmo momento em que arquiva o documento. As duas coisas acontecem juntas ou
  nenhuma acontece.
- De tempos em tempos, um **carteiro** passa, pega as cartas da caixa de saída, leva ao correio
  e marca cada uma como "enviada".

No projeto:

- A **caixa de saída** é a tabela `outbox_event` no banco. O aviso `STOCK_ADJUSTED` é gravado
  ali **na mesma transação** que a mudança de estoque, então os dois são salvos juntos ou
  nenhum é salvo.
- O **carteiro** é o **relay**. O `OutboxRelayJob` dispara o `OutboxRelay` sozinho, com
  `@Scheduled(fixedDelayString = "${orderflow.outbox.relay-interval}")`. Esse intervalo é
  `1000` ms no `application.yml`, ou seja, uma passada a cada segundo. Em cada passada, o
  relay lê as linhas ainda não enviadas, publica cada uma na fila SQS e marca como enviada.

Se a fila estiver fora do ar, nada se perde: a carta continua na caixa de saída, e o relay
tenta de novo na próxima passada. Por outro lado, um aviso pode acabar sendo entregue **mais de
uma vez** (o relay publicou, mas caiu antes de marcar como enviado). Por isso quem recebe a
mensagem precisa ser **idempotente**, isto é, processar o mesmo aviso duas vezes sem
duplicar nada (ver [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)).

O caminho completo fica assim:

```text
inventory-service ──grava──► tabela outbox_event
                                    │
                         relay (a cada 1 segundo)
                                    ▼
                       notification-events-queue (SQS)
                                    ▼
                notification-service ──grava──► DynamoDB
```

O `order-service` tem o mesmo par caixa de saída + carteiro, que leva os comandos da saga
(`ReserveStock`, `ReleaseStock`, `ShipStock`) e os eventos `ORDER_*` da linha do tempo.

## Em uma frase cada

- **Lock otimista:** uma etiqueta de versão que impede que uma gravação apague a outra sem
  perceber.
- **Reexecução automática:** quando a etiqueta recusa a gravação, o sistema relê e tenta de
  novo sozinho.
- **Relay:** o "carteiro" que pega os avisos guardados no banco e os entrega na fila, para
  nenhum aviso se perder.
