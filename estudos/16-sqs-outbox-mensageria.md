# SQS, Outbox e mensageria entre serviços

Arquivos:

- `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java` (o `setStock` grava o `STOCK_ADJUSTED` no outbox)
- `inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockAdjustedEvent.java`
- `inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxWriter.java` e `OutboxRelay.java`
- `order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxWriter.java` e `OutboxRelay.java`
- `inventory-service/.../config/SqsMessagingConfig.java`, `order-service/.../config/SqsMessagingConfig.java`, `notification-service/.../config/SqsMessagingConfig.java`
- `notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java`
- `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java`
- `localstack-init/ready.d/01-create-notification-resources.sh` e `02-create-order-saga-resources.sh`

## O problema que a mensageria resolve

Imagina que o `inventory-service` (estoque) muda a quantidade de um produto e
precisa **avisar** o `notification-service` disso. A forma mais simples seria
o inventory-service chamar diretamente uma API do notification-service (HTTP
síncrono). O problema: se o notification-service estiver fora do ar naquele
instante, a mensagem se perde e ninguém sabe.

A solução é desacoplar os dois serviços com uma **fila de mensagens**: quem
envia (produtor) só precisa colocar a mensagem na fila; quem recebe
(consumidor) lê da fila quando quiser/puder. Se o consumidor cair, a mensagem
continua esperando na fila.

## O que é o SQS

**SQS (Simple Queue Service)** é o serviço de filas da AWS. No projeto, ele
não roda na AWS de verdade — roda localmente via **LocalStack** (um
"simulador" de AWS em Docker), então tudo funciona de graça na máquina do
desenvolvedor.

Uma fila é basicamente uma lista: um serviço **manda** mensagens (`send`),
outro **escuta** e processa (`listen`/`consume`). O projeto tem três filas principais:

| Fila | Quem manda | O que vai nela | Quem escuta |
|---|---|---|---|
| `notification-events-queue` | `inventory-service` e `order-service` | `STOCK_ADJUSTED` e os oito `ORDER_*` | `NotificationEventListener` (notification-service) |
| `inventory-commands-queue` | `order-service` | `ReserveStock`, `ReleaseStock`, `ShipStock` | `ReservationCommandListener` (inventory-service) |
| `order-events-queue` | `inventory-service` | `StockReserved`, `StockReservationFailed` | `ReservationResultListener` (order-service) |

As duas filas da saga têm uma DLQ cada (`inventory-commands-dlq` e `order-events-dlq`).
A `notification-events-queue` não tem DLQ.

As filas e a tabela DynamoDB são criadas **só** pelos scripts de
`localstack-init/ready.d/`, que o LocalStack roda quando sobe (ver
[01-docker-compose.md](01-docker-compose.md)). Nenhum código Java cria fila. Por isso os
três serviços usam `queue-not-found-strategy: fail` no `application.yml`: se o script
falhar ou não rodar, a aplicação falha alto em vez de o Spring Cloud AWS criar a fila
sozinho e esconder o problema.

## O padrão Transactional Outbox

### O problema chamado "dual write"

Toda mudança que precisa avisar outro serviço tem **duas operações**:

- (a) salvar no banco Postgres (uma transação de banco);
- (b) enviar para o SQS (uma chamada de rede).

Essas duas coisas **não são atômicas** — não acontecem como uma coisa só. Pode
acontecer de (a) funcionar e (b) falhar (rede caiu, SQS indisponível etc.). Resultado:
o banco diz que o estoque mudou, mas ninguém foi avisado.

Na Fase 3, o `STOCK_ADJUSTED` era publicado exatamente assim: um `StockEventPublisher`
chamava o SQS **depois** do commit, e uma falha só virava uma linha `ERROR` no log
(D-29/D-30, risco aceito na época). No plano 05-04 esse publicador foi **removido**. Hoje
nenhum serviço chama o SQS de dentro de uma regra de negócio.

### Como o outbox fecha a lacuna

1. Em vez de enviar direto ao SQS, o serviço grava **na mesma transação de banco** duas
   coisas: a mudança de negócio (ex.: o estoque) **e** uma linha na tabela
   `outbox_event`, com o evento a ser publicado depois.
2. Como as duas gravações acontecem na mesma transação SQL, elas são atômicas: ou as duas
   acontecem, ou nenhuma acontece. Não tem como "salvar o estoque mas perder o evento".
3. Um processo separado — o **relay** (`OutboxRelay`, disparado pelo `OutboxRelayJob` a
   cada `orderflow.outbox.relay-interval: 1000` ms) — lê as linhas ainda não publicadas,
   manda para o SQS, e só então marca a linha como publicada.
4. Se o envio falhar, a linha continua lá (`attempts` sobe) e o relay tenta de novo no
   próximo ciclo.

Os detalhes da tabela, do `SELECT ... FOR UPDATE SKIP LOCKED` e do lote estão em
[26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md). O `order-service` e o
`inventory-service` têm cada um a sua cópia do outbox (mesmo código, pacote trocado, sem
módulo comum — D-62).

### O `STOCK_ADJUSTED` hoje

O fluxo do ajuste de estoque ficou assim:

1. Alguém chama `PUT /inventory/{productId}`.
2. `InventoryController` chama `InventoryService.setStock(...)` (`@Transactional` +
   `@Retryable`). Ele salva o estoque e, **na mesma transação**, grava o evento no outbox:

   ```java
   Instant adjustedAt = Instant.now();
   StockAdjustedEvent event = StockAdjustedEvent.of(productId, previousQuantityOnHand, quantityOnHand, adjustedAt);
   outboxWriter.enqueue(event.eventId(), StockAdjustedEvent.EVENT_TYPE, productId.toString(), event);
   return StockResponse.from(inventory);
   ```

3. A resposta do `PUT` volta para o vendedor. O controller não fala com o SQS.
4. Em até ~1 s, o relay do inventory-service lê a linha e manda para a
   `notification-events-queue`.
5. O `notification-service` recebe a mensagem e grava no DynamoDB (ver
   [17-dynamodb.md](17-dynamodb.md) e [19-sqslistener-consumo.md](19-sqslistener-consumo.md)).

Por que o `occurredAt` é capturado **dentro** do `setStock` (WR-04)? Porque a publicação
acontece depois, no relay, fora da transação. Com dois `PUT` concorrentes no mesmo
produto, a ordem de publicação pode ser o contrário da ordem em que as transações
commitaram. Como o histórico é ordenado por `occurredAt`, o horário precisa vir da
transação que gravou o ajuste.

E o retry? Se uma tentativa do `setStock` falha por conflito de versão, a transação dela
sofre rollback — e a linha do outbox vai junto. Só a tentativa que deu commit deixa um
evento (ver [14-spring-retry.md](14-spring-retry.md)).

`OutboxWriter.enqueue` é `@Transactional(propagation = Propagation.MANDATORY)`: chamado
fora de uma transação já aberta, ele falha na hora. Gravar o evento numa transação
separada quebraria justamente a atomicidade que o outbox existe para garantir.

## Para qual fila vai cada evento — o roteamento do relay

O relay não recebe o nome da fila de quem gravou o evento. Ele decide pela coluna
`eventType`, num método `resolveQueue`. No `order-service`:

```java
private String resolveQueue(String eventType) {
    if (ReserveStockCommand.EVENT_TYPE.equals(eventType) || RELEASE_STOCK_EVENT_TYPE.equals(eventType)
            || ShipStockCommand.EVENT_TYPE.equals(eventType)) {
        return inventoryCommandsQueue;
    }
    if (OrderLifecycleEvent.EVENT_TYPES.contains(eventType)) {
        return notificationEventsQueue;
    }
    throw new IllegalStateException("No queue configured for outbox eventType '" + eventType + "'");
}
```

No `inventory-service`:

```java
private String resolveQueue(String eventType) {
    if (StockReservedEvent.EVENT_TYPE.equals(eventType) || StockReservationFailedEvent.EVENT_TYPE.equals(eventType)) {
        return orderEventsQueue;
    }
    if (StockAdjustedEvent.EVENT_TYPE.equals(eventType)) {
        return notificationEventsQueue;
    }
    throw new IllegalStateException("No queue configured for outbox eventType '" + eventType + "'");
}
```

Três detalhes:

- Os eventos `ORDER_*` são roteados por uma **lista explícita**
  (`OrderLifecycleEvent.EVENT_TYPES`, com os oito tipos), não por "começa com `ORDER_`".
  Um tipo novo, esquecido na lista, vira erro em vez de ir para a fila errada.
- Um `eventType` desconhecido lança `IllegalStateException`. O relay captura isso **só
  para aquele evento** (`recordFailure` + log `WARN`) e segue com o resto do lote. Um
  evento quebrado nunca derruba os outros.
- A `notification-events-queue` é uma fila de **fan-out**: recebe eventos de dois
  serviços diferentes. Quem separa é o consumidor, olhando o `eventType` (ver
  [31-linha-do-tempo-do-pedido.md](31-linha-do-tempo-do-pedido.md)).

## O Correlation-ID viaja como atributo da mensagem

O relay roda numa thread `@Scheduled`, sem requisição HTTP. O Correlation-ID da
requisição original (ver [27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md)) não
está mais no MDC dessa thread. Por isso ele é gravado na **linha** do outbox (coluna
`correlation_id`): o `OutboxWriter` lê `CorrelationContext.current()` na hora de gravar.

Na hora de enviar, o relay põe esse valor num **atributo** da mensagem SQS — um
"cabeçalho" que viaja junto do corpo, sem mexer no JSON:

```java
try (var scope = CorrelationContext.open(event.getCorrelationId())) {
    Message<String> message = MessageBuilder.withPayload(event.getPayload())
            .setHeader(CorrelationContext.SQS_ATTRIBUTE, event.getCorrelationId())
            .build();
    sqsOperations.send(queueName, message);
    ...
}
```

`CorrelationContext.SQS_ATTRIBUTE` vale `"correlationId"`. Do outro lado, cada listener lê
esse atributo com `@Header(name = CorrelationContext.SQS_ATTRIBUTE, required = false)` e
abre o MDC com ele (ver [19-sqslistener-consumo.md](19-sqslistener-consumo.md)). O corpo
JSON (o contrato entre os serviços) continua igual — o ID é metadado, não dado de negócio.

## Quem fala com o SQS: `SqsOperations`

O relay recebe `SqsOperations` no construtor. É a interface do `SqsTemplate`, a classe
pronta do Spring Cloud AWS que sabe conectar, formatar e enviar mensagens — você não
escreve esse código de baixo nível na mão.

Em nenhum lugar existe um `new SqsTemplate(...)`. Ele aparece pronto por injeção de
dependência, porque o `pom.xml` declara:

```xml
<artifactId>spring-cloud-aws-starter-sqs</artifactId>
```

— um "starter" de auto-configuração. Você só **pede** no construtor; o Spring entrega a
instância já configurada.

O `OutboxRelay` é o **único** arquivo de produção do `order-service` e do
`inventory-service` que importa `io.awspring.cloud.sqs.operations.` (o plano verifica isso
com um `grep`). Quem quiser publicar alguma coisa tem que passar pelo outbox.

### Para onde ele manda — isso vem da configuração

O cliente lê a configuração de AWS do `application.yml`:

```yaml
spring:
  cloud:
    aws:
      region:
        static: us-east-1
      credentials:
        access-key: test
        secret-key: test
      endpoint: ${SPRING_CLOUD_AWS_ENDPOINT:http://localhost:4566}
```

- `endpoint` — em vez da AWS real, aponta para o **LocalStack** (`http://localstack:4566`
  dentro do docker-compose, que sobrescreve a variável).
- `access-key`/`secret-key: test` — credenciais falsas, de propósito. O LocalStack não
  valida autenticação, e nunca se corre o risco de falar com a AWS de verdade por
  acidente.

O mesmo código Java fala com o LocalStack ou com a AWS real; só a configuração muda.

### Uma analogia para o `SqsTemplate`

O `SqsTemplate` é como uma **agência dos Correios pronta para uso**: você não precisa
saber como o caminhão funciona — só chega no balcão e diz "essa carta (`payload`), para
esse endereço (a fila)". A agência já vem pronta assim que você contrata o serviço
(`spring-cloud-aws-starter-sqs`); o endereço da agência (LocalStack ou AWS) vem do
`application.yml`.

## `SqsMessagingConfig` — sem nome de classe Java atravessando a fronteira

O `SqsTemplate` (e, do lado do consumidor, o mecanismo por trás do `@SqsListener`)
transforma objetos Java em JSON e vice-versa. Quem faz essa tradução é um
**`MessagingMessageConverter`**.

### O problema

Por padrão, ao enviar, o conversor anexa um atributo chamado `JavaType`, com o **nome
completo da classe Java** do objeto — por exemplo,
`com.orderflow.inventory.stock.dto.StockAdjustedEvent`. A ideia é ajudar o consumidor a
saber "para qual classe eu desserializo isso".

Só que cada serviço tem **sua própria cópia** do DTO, em outro pacote — o
notification-service tem `com.orderflow.notification.history.dto.StockAdjustedEvent`. Se o
consumidor confiasse no atributo, tentaria carregar uma classe que **não existe** no
classpath dele e explodiria antes de o listener rodar.

### A correção, dos dois lados

**Quem produz** desliga o atributo na origem. O `inventory-service` e o `order-service`
produzem **e** consomem, então fazem as duas coisas no mesmo bean:

```java
@Bean
public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
    SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
    converter.doNotSendPayloadTypeHeader();
    converter.setPayloadTypeMapper(message -> null);
    return converter;
}
```

**Quem só consome** (o `notification-service`) só tem a segunda linha:

```java
converter.setPayloadTypeMapper(message -> null);
```

- `doNotSendPayloadTypeHeader()` — o produtor **para de mandar** o `JavaType`.
- `setPayloadTypeMapper(message -> null)` — o consumidor **nunca** decide o tipo olhando
  um atributo da mensagem. Quem decide é o parâmetro do método do listener
  (`onMessage(String payload, ...)` pede `String`, o JSON cru).

Por que o consumidor se defende mesmo com o produtor já corrigido? Porque ele não pode
depender de todo produtor lembrar de configurar isso. É defesa em profundidade.

A auto-configuração do Spring Cloud AWS procura um bean `MessagingMessageConverter` e, se
achar, usa no `SqsTemplate` e na fábrica de listeners — continuando a aplicar o
`ObjectMapper` do Spring Boot (que serializa `Instant` como texto ISO-8601). O contrato
entre os serviços fica sendo só o **JSON do payload**.

### Um limite de tempo para falar com o SQS (WR-03)

O `SqsMessagingConfig` do inventory-service (e o do order-service, igual) declara um
segundo bean:

```java
@Bean
public SqsAsyncClientCustomizer sqsAsyncClientTimeoutCustomizer() {
    return builder -> builder.overrideConfiguration(c -> c
            .apiCallTimeout(Duration.ofSeconds(5))
            .apiCallAttemptTimeout(Duration.ofSeconds(2)));
}
```

O cliente padrão espera cerca de 30 segundos por tentativa e faz 3 tentativas. Na Fase 3,
esse limite protegia a thread HTTP do `PUT`. Hoje quem fala com o SQS é o relay, e ele
segura as linhas do outbox travadas enquanto envia. Um SQS lento não pode prender esse
ciclo por minutos. Com o limite, a chamada desiste rápido, a falha vira
`recordFailure` e o próximo ciclo tenta de novo.

Os valores subiram de 1s/3s para 2s/5s na Fase 5, porque o mesmo cliente é dividido com o
listener da saga. É também por isso que esses dois serviços usam
`spring.cloud.aws.sqs.listener.poll-timeout: 0s` (ver
[19-sqslistener-consumo.md](19-sqslistener-consumo.md)). O notification-service não tem
esse bean.

## Consumo idempotente — por que reentrega não é um problema

O SQS padrão não garante nem ordem de entrega, nem entrega única — a mesma
mensagem pode chegar mais de uma vez. O outbox piora isso de propósito: se o relay enviou
mas caiu antes de marcar a linha, ele envia de novo. O notification-service é desenhado
para tolerar:

- `NotificationService.record(...)` monta a chave do DynamoDB de forma
  **determinística**: `sortKey = eventType + "#" + eventId`. A gravação usa `PutItem`
  **sem expressão de condição** — gravar o mesmo evento duas vezes sobrescreve a mesma
  linha com o mesmo conteúdo. A idempotência vem de graça da chave.
- Se o JSON for inválido (`InvalidNotificationEventException`), o listener loga um aviso e
  retorna normalmente — a mensagem é confirmada e some. Sem DLQ nessa fila, é a única
  defesa contra uma mensagem "envenenada". Isso inclui corpos acima de 64 KB (WR-02,
  `MAX_RAW_PAYLOAD_BYTES = 64 * 1024`): sem essa checagem, um item grande demais para o
  DynamoDB (que recusa itens acima de 400 KB) faria o `putItem` falhar sempre.
- Qualquer **outra** exceção (por exemplo, o DynamoDB fora do ar) **não** é capturada — a
  mensagem não é confirmada e volta depois do timeout de visibilidade. Como a gravação é
  idempotente, a reentrega é segura.
- Na leitura, os registros são reordenados em memória (por `occurredAt` e, no empate, pela
  posição no ciclo de vida) — porque a fila não garante ordem de chegada (ver
  [17-dynamodb.md](17-dynamodb.md)).

## Resumindo com uma analogia

- **SQS** = uma caixa de correio entre dois prédios (serviços). Um deposita
  carta, o outro passa lá pra pegar quando quiser.
- **Dual-write problem** = você escreve a carta e já anota no seu caderno
  pessoal "mandei a carta", mas na hora de sair de casa pra levar ao correio,
  esquece ou perde a carta.
- **Outbox** = em vez de sair correndo pro correio, você guarda a carta numa
  caixa de saída dentro de casa, junto com a decisão de escrevê-la (tudo no
  mesmo gesto/transação). Um mensageiro passa periodicamente, pega tudo que
  está na caixa de saída e realmente leva ao correio, riscando da caixa só
  depois de confirmar a entrega.
- **Roteamento por `eventType`** = o mensageiro olha a etiqueta do envelope para saber
  em qual caixa de correio depositar. Etiqueta desconhecida, o envelope fica na caixa de
  saída com uma anotação de erro.
- **Atributo `correlationId`** = o número do protocolo carimbado do lado de fora do
  envelope, sem abrir a carta.
- **Idempotência por chave** = mesmo que o carteiro entregue a mesma carta
  duas vezes por engano, ela vai pro mesmo escaninho e substitui a cópia
  anterior — não vira duas entradas duplicadas no seu arquivo.

## Estado atual

| | Antes (Fase 3) | Hoje (desde o 05-04) |
|---|---|---|
| Quem publica `STOCK_ADJUSTED` | `StockEventPublisher` chamava o SQS depois do commit | `setStock` grava no `outbox_event`; o `OutboxRelay` envia |
| Atomicidade banco+evento | Não — dual-write conhecido (D-30) | Sim — dado e evento na mesma transação |
| Se o SQS falhar | Evento se perdia, ficava só um log `ERROR` | Evento continua na tabela; `attempts` sobe e o relay tenta de novo |
| Quem usa o outbox | ninguém | `order-service` (comandos da saga + oito `ORDER_*`) e `inventory-service` (respostas da saga + `STOCK_ADJUSTED`) |
| Correlation-ID | não existia | coluna `correlation_id` → atributo SQS `correlationId` |
