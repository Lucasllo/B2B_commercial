# `@SqsListener` — como os serviços recebem mensagens

Arquivos:

- `notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java`
- `inventory-service/src/main/java/com/orderflow/inventory/saga/messaging/ReservationCommandListener.java`, `SagaCommandParser.java` e `InvalidShipStockException.java`
- `order-service/src/main/java/com/orderflow/order/saga/messaging/ReservationResultListener.java`
- `*/observability/CorrelationContext.java` (um por serviço)

Continuação de [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md) —
lá o foco foi o fluxo ponta a ponta e a idempotência; aqui o foco é **como,
mecanicamente, uma mensagem chega até o método Java** que a processa.

```java
@SqsListener("${orderflow.notifications.queue-name}")
public void onMessage(String payload,
                      @Header(name = CorrelationContext.SQS_ATTRIBUTE, required = false) String correlationId) {
    try (var scope = CorrelationContext.open(correlationId)) {
        log.info("Mensagem recebida da fila '{}'", queueName);
        try {
            notificationService.record(payload);
        } catch (InvalidNotificationEventException e) {
            log.warn("Mensagem descartada da fila '{}' orderId={} eventType={}: {}", queueName,
                    orDash(e.getOrderId()), orDash(e.getEventType()), e.getMessage());
        }
    }
}
```

À primeira vista parece que essa classe "fica esperando" uma mensagem
chegar. Na prática, o mecanismo é o oposto: **é o Spring que fica indo
buscar**.

## 1. `@SqsListener` não é quem escuta — é uma etiqueta

`@SqsListener("${orderflow.notifications.queue-name}")` é só uma
**anotação** — uma etiqueta no método `onMessage`. Sozinha, ela não faz
absolutamente nada (o mesmo princípio de `@Retryable`, visto em
[14-spring-retry.md](14-spring-retry.md): uma anotação descreve uma
intenção, mas precisa de um mecanismo por trás para funcionar de verdade).

Quem de fato faz o trabalho é a **infraestrutura do Spring Cloud AWS**, que
entra em ação porque o serviço tem a dependência
`spring-cloud-aws-starter-sqs` no `pom.xml`. Quando a aplicação sobe, o
Spring:

1. Varre todos os beans procurando métodos com `@SqsListener`.
2. Encontra `onMessage`, lê o nome da fila do parâmetro da anotação
   (`${orderflow.notifications.queue-name}` → resolvido para
   `notification-events-queue` pelo `application.yml`).
3. Cria, **em segundo plano**, um "container ouvinte" dedicado a essa fila —
   uma thread (ou conjunto de threads) que roda continuamente, sem você
   escrever nenhum laço `while` manual.

## 2. O que essa thread de fundo faz, sem parar

Essa thread em segundo plano fica em um ciclo parecido com este
(simplificado — é o que a biblioteca faz por trás):

```
enquanto a aplicação estiver rodando:
    pergunta ao SQS: "tem mensagem nova na fila notification-events-queue?"
    se tiver, pega uma (ou várias) mensagem(ns)
    para cada mensagem recebida:
        chama onMessage(texto_da_mensagem, atributo_correlationId)
    espera um pouco e repete
```

Essa técnica se chama **long polling**: em vez de perguntar "tem mensagem?"
e desistir na hora se a resposta for não, a chamada ao SQS fica **esperando
alguns segundos** por uma resposta antes de tentar de novo — evita ficar
batendo na fila sem parar (o que gastaria requisições à toa) e ainda assim
reage rápido quando uma mensagem chega.

É por isso que o produtor e o consumidor não precisam se conhecer: o relay do outbox
(ver [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)) só deposita a mensagem
na fila; é essa thread de fundo do consumidor que vai buscar, no seu próprio ritmo.

## 3. Por que o corpo chega como `String`, não como objeto

```java
public void onMessage(String payload, ...
```

Quando a mensagem chega, o Spring precisa decidir **como entregar o
conteúdo** para o método. Ele olha o **tipo do parâmetro declarado** —
aqui, `String` — e simplesmente entrega o corpo bruto da mensagem, texto
puro, sem tentar transformar em nenhum objeto Java.

Isso conecta com o `SqsMessagingConfig` (ver
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)): o conversor
configurado lá (`setPayloadTypeMapper(message -> null)`) garante que a
decisão de "para que tipo desserializar" **nunca** vem de um atributo da
mensagem — vem só da assinatura do método. Como o método pede `String`, é
exatamente o JSON cru que chega em `payload`, e é o próprio serviço quem
decide como interpretar esse texto.

## 4. O segundo parâmetro: o Correlation-ID que veio na mensagem

```java
@Header(name = CorrelationContext.SQS_ATTRIBUTE, required = false) String correlationId
```

O relay que publicou a mensagem anexou um **atributo** `correlationId` (ver
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)). `@Header` pede ao Spring esse
atributo. `required = false` quer dizer: se a mensagem não tiver o atributo, chega `null`,
sem erro.

A thread do listener **não** é a thread da requisição HTTP que começou tudo. A gaveta do
MDC dela está vazia (ver [27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md)). Por
isso o método abre um escopo novo:

```java
try (var scope = CorrelationContext.open(correlationId)) {
    ...
}
```

`CorrelationContext.open` faz três coisas:

1. **Valida** o valor. Só entra no log o que casa `[A-Za-z0-9-]{1,64}`. Nulo, quebra de
   linha, espaço ou texto longo demais viram um UUID novo. O atributo vem de fora, então é
   tratado como fronteira de confiança.
2. Coloca o ID no MDC. Toda linha de log daquela mensagem (`Mensagem recebida...`,
   `Evento registrado...`) sai com o mesmo `[id]` da requisição original.
3. Devolve um `Scope` que, ao fechar, **restaura** o valor que estava antes. O
   `try (...)` fecha o escopo mesmo se der exceção.

Por que restaurar? Porque a thread do container é **reutilizada**. Sem restaurar, a
próxima mensagem herdaria o ID da anterior, e duas mensagens diferentes apareceriam no
log como se fossem o mesmo atendimento.

Os três listeners do projeto fazem isso do mesmo jeito. O do notification-service e o do
inventory-service ainda têm uma sobrecarga `onMessage(String payload)` **sem**
`@SqsListener`, que chama a outra com `null` — só para os testes unitários antigos
continuarem compilando. O container registra apenas o método anotado.

## 5. O que acontece depois que `onMessage` termina — confirmar ou não a mensagem

Aqui está o detalhe que faz o listener funcionar de forma confiável:

- **Se `onMessage` retorna normalmente** (sem lançar exceção) — o Spring
  Cloud AWS entende "processamento OK" e **confirma a mensagem** ao SQS
  (tecnicamente, chama `DeleteMessage`). A mensagem é apagada da fila para
  sempre.
- **Se `onMessage` lança uma exceção não capturada** — o Spring **não
  confirma** a mensagem. Ela continua na fila e, depois de um tempo (o
  "timeout de visibilidade"), o SQS a entrega de novo para ser tentada outra
  vez.

É por isso que o `try/catch` de `InvalidNotificationEventException` é tão deliberado:

- Se `record(payload)` lançar `InvalidNotificationEventException`, esse erro **é
  capturado**, só vira um log de aviso, e o método **retorna normalmente**. A mensagem é
  confirmada e some da fila. Os motivos possíveis: corpo acima de 64 KB, JSON malformado
  ou que não é um objeto (ou com conteúdo sobrando depois do objeto), tipo de evento
  desconhecido, campo obrigatório faltando, quantidade negativa — e, para os eventos
  `ORDER_*`, as regras por tipo (por exemplo, `trackingCode` fora do padrão
  `^[A-Z]{2}[0-9]{9}BR$`; ver [31-linha-do-tempo-do-pedido.md](31-linha-do-tempo-do-pedido.md)).
  Faz sentido descartar: tentar de novo nunca vai fazer a mensagem ficar válida, e a
  `notification-events-queue` não tem DLQ.
- Qualquer **outra** exceção (por exemplo, uma falha ao gravar no DynamoDB)
  **não é capturada aqui** — ela sobe, a mensagem **não** é confirmada, e o SQS a entrega
  de novo mais tarde. Isso é seguro porque a gravação no DynamoDB é idempotente por chave
  (ver [17-dynamodb.md](17-dynamodb.md)).

Esse raciocínio de "deixa voltar para a fila" só vale para falhas
**passageiras**. Uma falha **permanente** do DynamoDB — como um item acima de 400 KB, que
ele sempre vai recusar — faria a mensagem voltar para sempre. É por isso que o
`NotificationService` checa o limite de 64 KB logo no começo (WR-02): isso transforma
esse caso num `InvalidNotificationEventException`, ou seja, em descarte com log.

### O aviso de descarte diz de qual pedido era (WR-03)

Um evento `ORDER_*` descartado deixa um **buraco** na linha do tempo de um pedido. Antes
do plano 07-07, o aviso só dizia o motivo — não dava para saber qual pedido ficou
incompleto. Agora a exceção carrega dois dados a mais, `orderId` e `eventType`, e o aviso
fica assim:

```text
Mensagem descartada da fila 'notification-events-queue' orderId=<uuid> eventType=ORDER_CONFIRMED: Campo trackingCode ausente ou em formato invalido
```

Os dois valores vêm de fora, então são tratados antes de ir para o log:

- o `orderId` só aparece se o texto **casar o formato de UUID**; ausente ou fora do
  formato vira `?`;
- o `eventType` passa por `sanitizeForLog`: caracteres de controle (incluindo U+0085,
  U+2028 e U+2029, que muitas ferramentas tratam como quebra de linha) viram `_`, e o
  valor é cortado em 64 caracteres;
- o **payload bruto nunca** vai para o log.

Para mensagens que não são `ORDER_*` (um `STOCK_ADJUSTED` inválido, um JSON quebrado), os
dois valores são `null` e o log mostra `-`. A mesma sanitização vale para o tipo de evento
desconhecido citado na mensagem da exceção (WR-06): ninguém consegue mandar uma mensagem
que "forje" uma linha de log falsa.

## 6. Os outros dois listeners (a saga)

Com a saga (ver [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md)),
o projeto tem **três** métodos `@SqsListener`, todos com a mesma forma: corpo `String`,
atributo `correlationId` no MDC, um *parser* que valida o texto, e descarte com `WARN`
quando a mensagem é estruturalmente inválida.

| Listener | Serviço | Fila | Chama |
|---|---|---|---|
| `NotificationEventListener` | `notification-service` | `notification-events-queue` | `NotificationService.record` |
| `ReservationCommandListener` | `inventory-service` | `inventory-commands-queue` | `InventoryService.reserveAll` / `releaseAll` / `shipAll` |
| `ReservationResultListener` | `order-service` | `order-events-queue` | `OrderSagaService.applyStockReserved` / `applyReservationFailed` |

### Um parser, três comandos

O `SagaCommandParser` do inventory-service lê o `eventType` e devolve um objeto diferente
para cada comando. O listener distingue com `instanceof`:

```java
if (command instanceof ReserveStockCommand reserveStock) {
    inventoryService.reserveAll(reserveStock.orderId(), reserveStock.reservationId(), reserveStock.items());
    return;
}
if (command instanceof ReleaseStockCommand releaseStock) {
    inventoryService.releaseAll(releaseStock.orderId(), releaseStock.reservationId(), releaseStock.items());
    return;
}
if (command instanceof ShipStockCommand shipStock) {
    inventoryService.shipAll(shipStock.orderId(), shipStock.reservationId(), shipStock.items());
}
```

`ReserveStock` responde ao order-service (pelo outbox). `ReleaseStock` (a compensação) e
`ShipStock` (a baixa física quando o pedido é enviado, Fase 6) não respondem nada.

### Três diferenças em relação ao notification-service

**1. Uma resposta "não" não é erro.** No `inventory-service`, "estoque insuficiente" **não**
vira exceção. O `reserveAll` grava a falha no outbox e retorna normalmente, então a mensagem
é confirmada. Se virasse exceção, o SQS entregaria o mesmo comando de novo, ele falharia de
novo, e o `order-service` nunca saberia a resposta. Só falhas **técnicas** (banco fora,
conflito que não se resolveu, livro inconsistente) escapam do método e fazem a mensagem
voltar.

**2. Aqui existe DLQ.** As duas filas da saga foram criadas com uma DLQ e
`maxReceiveCount: 3`: depois da terceira entrega sem confirmação, o SQS tira a mensagem da
fila principal e a guarda na DLQ (`inventory-commands-dlq` ou `order-events-dlq`). Uma
mensagem com problema técnico permanente não volta para sempre.

**3. `ShipStock` inválido não é descartado — vai para a DLQ (WR-01).** A regra geral é:
mensagem malformada → `WARN` e descarte. Mas um `ShipStock` só existe para um pedido que já
está `SHIPPED`. Se ele fosse descartado, a baixa física do estoque **sumiria sem deixar
rastro**: o pedido foi enviado, e o estoque continuaria contando aquelas unidades.

Por isso, quando a validação de um `ShipStock` falha, o parser lança um subtipo especial,
`InvalidShipStockException` (filho de `InvalidSagaMessageException`). O listener o captura
**antes** da captura genérica:

```java
try {
    command = sagaCommandParser.parse(payload);
} catch (InvalidShipStockException e) {
    log.error("ShipStock invalido enviado para reentrega/DLQ orderId={} fila='{}': {}",
            e.getOrderIdForLog(), queueName, e.getMessage());
    throw e;
} catch (InvalidSagaMessageException e) {
    log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
    return;
}
```

A ordem dos `catch` importa: o Java usa o primeiro que servir. Como
`InvalidShipStockException` é filho de `InvalidSagaMessageException`, se o `catch` genérico
viesse antes, ele pegaria o `ShipStock` também.

O `ShipStock` inválido vira uma **anomalia técnica**: log `ERROR` e a exceção é
**relançada**. A mensagem não é confirmada, o SQS a entrega mais duas vezes, e depois ela
para na `inventory-commands-dlq` — com o corpo intacto, esperando alguém investigar. O
`orderId` do log já vem sanitizado pelo parser (`?` se o corpo não trouxe), e o payload
nunca vai para o log. `ReserveStock` e `ReleaseStock` inválidos continuam sendo descartados
com `WARN`.

**Um detalhe de configuração: sem long polling.** No `order-service` e no
`inventory-service`, o `application.yml` tem:

```yaml
spring.cloud.aws.sqs.listener.poll-timeout: 0s
```

O cliente SQS desses serviços é o mesmo usado pelo relay e tem um limite de tempo curto
por chamada (WR-03). O long polling padrão espera até 20 s por mensagem, então estouraria
esse limite a cada ciclo. Com `0s`, cada pergunta à fila responde na hora, com ou sem
mensagem. O `notification-service` não tem esse problema e continua com o long polling
da seção 2.

## Resumindo com uma analogia

Pensa num carteiro (a thread de fundo do Spring Cloud AWS) que fica passando
de tempos em tempos numa caixa de correio (a fila SQS) perguntando "tem
carta nova?". Quando encontra uma carta, ele bate na sua porta (chama
`onMessage`) e entrega o conteúdo, junto com o número de protocolo carimbado no envelope
(o `correlationId`), que você anota no seu caderno só enquanto cuida daquela carta.

- Se você **aceitar** a carta (o método termina sem erro), o carteiro rasga o comprovante
  e a carta nunca mais volta.
- Se a carta estiver rasgada/ilegível (`InvalidNotificationEventException`), você mesmo
  rasga o comprovante — não adianta o carteiro trazer de novo. Mas anota no caderno de
  qual pedido ela era.
- Se você tiver um problema seu, temporário, ao guardar a carta (o banco fora do ar), não
  rasga o comprovante — e o carteiro traz a mesma carta de novo mais tarde.
- Se for uma ordem de **baixa de estoque** ilegível (`ShipStock` inválido), você não pode
  simplesmente jogar fora: devolve ao carteiro, e depois de três tentativas ela vai para a
  gaveta de "cartas com problema" (a DLQ), onde alguém vai olhar.
