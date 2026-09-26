# SQS, Outbox e mensageria entre serviços

Arquivos: `inventory-service/src/main/java/com/orderflow/inventory/stock/messaging/StockEventPublisher.java`,
`inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java`,
`inventory-service/src/main/java/com/orderflow/inventory/stock/StockAdjustmentResult.java`,
`inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockAdjustedEvent.java`,
`inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java`,
`notification-service/src/main/java/com/orderflow/notification/config/SqsMessagingConfig.java`,
`notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java`,
`notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java`,
`localstack-init/ready.d/01-create-notification-resources.sh`.

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
outro **escuta** e processa (`listen`/`consume`). No projeto:

- A fila se chama `notification-events-queue`. Ela é criada por
  `localstack-init/ready.d/01-create-notification-resources.sh:17`, que roda
  `awslocal sqs create-queue --queue-name notification-events-queue` quando o
  LocalStack sobe. Esse script também cria a tabela DynamoDB
  `notification-history` (chave de partição `productId`, chave de ordenação
  `sortKey`) na mesma execução.
- **Quem envia**: `StockEventPublisher`, no `inventory-service`.
- **Quem recebe**: `NotificationEventListener`, no `notification-service`,
  usando `@SqsListener("${orderflow.notifications.queue-name}")` — essa
  anotação do Spring diz "fique escutando essa fila; toda mensagem que
  chegar, chame este método".

O script do LocalStack é comentado explicando uma decisão deliberada: **é o
único lugar do projeto que cria esses recursos** — nenhum código Java cria a
fila ou a tabela em tempo de execução. Por isso o `application.yml` do
notification-service usa `queue-not-found-strategy: fail`: se o init hook
falhar ou não rodar, a aplicação falha ao subir (erro visível), em vez de
criar a fila silenciosamente e mascarar o problema. O `application.yml` do
inventory-service (o lado que **envia**) também tem
`queue-not-found-strategy: fail`: se a fila não existir porque o init hook
quebrou, o envio falha alto em vez de o Spring Cloud AWS criar a fila
sozinho e esconder o problema.

### Fluxo real, passo a passo

1. Alguém chama `PUT /inventory/{productId}` para ajustar estoque.
2. `InventoryController` chama `InventoryService.setStock(...)` (um método
   `@Transactional` + `@Retryable`). Ele salva a mudança no Postgres e, ainda
   **dentro** da transação, captura `adjustedAt = Instant.now()`, devolvendo
   tudo num `StockAdjustmentResult` (o estoque novo, a quantidade anterior e
   o `adjustedAt`). Quando o método retorna, o proxy do Spring já fez o
   commit da transação.
3. Depois disso, o controller chama
   `StockEventPublisher.publishStockAdjusted(productId, result.previousQuantityOnHand(), result.stock().quantityOnHand(), result.adjustedAt())`:

   ```java
   public void publishStockAdjusted(UUID productId, int previousQuantityOnHand, int newQuantityOnHand,
                                     Instant adjustedAt) {
       StockAdjustedEvent event = StockAdjustedEvent.of(productId, previousQuantityOnHand, newQuantityOnHand, adjustedAt);
       try {
           sqsTemplate.send(to -> to.queue(queueName).payload(event));
       } catch (RuntimeException e) {
           log.error("Falha ao publicar evento {} (eventId={}) do produto {} na fila '{}' — o "
                           + "ajuste de estoque ja foi gravado no banco, mas este evento se "
                           + "perdeu (limitacao conhecida da Fase 3, D-30; resolvida na Fase 5 "
                           + "pelo Transactional Outbox)",
                   event.eventType(), event.eventId(), productId, queueName, e);
       }
   }
   ```

   Isso monta um `StockAdjustedEvent` — com um `eventId` novo e o
   `occurredAt` recebido da transação — e coloca a mensagem na fila SQS.

   Por que o `occurredAt` **não** é gerado aqui, na hora de publicar (WR-04)?
   Porque este método roda **depois** do commit, fora da transação. Com dois
   `PUT` concorrentes no mesmo produto, a ordem em que cada um chega ao
   publicador pode ser o contrário da ordem em que as transações realmente
   commitaram. Como o histórico do notification-service é ordenado por
   `occurredAt`, o ajuste mais novo apareceria antes do mais antigo. Capturar
   o horário dentro da transação que gravou o ajuste faz o `occurredAt`
   refletir a ordem real.
4. O `notification-service`, que está sempre escutando essa fila, recebe a
   mensagem via `NotificationEventListener.onMessage(String payload)`.
5. `NotificationService.record(...)` primeiro recusa corpos acima de 64 KB
   (WR-02, `MAX_RAW_PAYLOAD_BYTES = 64 * 1024`), depois valida o JSON, monta uma mensagem
   legível e grava um registro no DynamoDB (tabela `notification-history`).

## O `SqsTemplate` — o "telefone" que fala com o SQS

Antes de entender o `SqsMessagingConfig`, vale entender o que ele configura:
o `SqsTemplate`, usado em `StockEventPublisher.publishStockAdjusted`:

```java
sqsTemplate.send(to -> to.queue(queueName).payload(event));
```

O `SqsTemplate` é uma classe pronta do Spring Cloud AWS que sabe como
conectar, formatar e enviar mensagens para uma fila SQS — você não precisa
escrever esse código de baixo nível na mão (abrir conexão, montar a
requisição HTTP, serializar o objeto para JSON etc.).

Repara que em nenhum lugar do projeto existe um `new SqsTemplate(...)`: ele é
apenas **injetado** no construtor de `StockEventPublisher`:

```java
public StockEventPublisher(SqsTemplate sqsTemplate,
                            @Value("${orderflow.messaging.notification-events-queue}") String queueName) {
    this.sqsTemplate = sqsTemplate;
    this.queueName = queueName;
}
```

Isso funciona porque o `pom.xml` do inventory-service declara a dependência

```xml
<artifactId>spring-cloud-aws-starter-sqs</artifactId>
```

— um "starter" de auto-configuração. Assim que essa dependência está no
classpath, o Spring Boot cria automaticamente um bean `SqsTemplate` pronto
para uso e o disponibiliza para qualquer classe pedir no construtor, do
mesmo jeito que outros beans do projeto (como um `@Repository`) aparecem
prontos por injeção de dependência. Você só **pede** (`SqsTemplate
sqsTemplate` no construtor); o Spring entrega a instância já configurada.

### Para onde ele manda a mensagem — isso vem da configuração, não do código

O `SqsTemplate` autoconfigurado lê a configuração de AWS do
`application.yml` do inventory-service (no arquivo real, esse bloco fica
dentro de `spring:`, ou seja, as propriedades completas são
`spring.cloud.aws.*`):

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

- `endpoint: http://localhost:4566` — em vez de falar com a AWS real, ele
  aponta para o **LocalStack** rodando local (ou `http://localstack:4566`
  dentro do docker-compose, que sobrescreve essa variável de ambiente).
- `access-key`/`secret-key: test` — credenciais fixas e falsas, porque o
  LocalStack não valida autenticação de verdade; são propositalmente
  diferentes das variáveis `AWS_*` reais do ambiente do desenvolvedor, para
  nunca acidentalmente conseguir falar com a AWS de produção se o endpoint
  local estiver ausente.

Ou seja: o `SqsTemplate` em si é só a "interface de programação" —
essa configuração é quem decide se ele fala com o LocalStack (dev/teste) ou
com a AWS real (produção), sem precisar mudar uma linha de código Java.

### A sintaxe `to -> to.queue(...).payload(...)`

```java
sqsTemplate.send(to -> to.queue(queueName).payload(event));
```

Isso é uma **lambda** (uma função anônima curta) que recebe um "construtor de
mensagem" (`to`) e devolve como a mensagem deve ser montada:

- `.queue(queueName)` — para qual fila enviar.
- `.payload(event)` — qual objeto é o conteúdo da mensagem (o `SqsTemplate`
  serializa esse objeto para JSON automaticamente, usando o `ObjectMapper` do
  Spring Boot).

É a mesma ideia de builder que aparece em outros lugares do projeto — só que
em vez de montar o objeto passo a passo numa variável antes de enviar, o
método `send` já recebe a função que constrói a mensagem inteira e a envia
de uma vez.

### Uma analogia para o `SqsTemplate`

O `SqsTemplate` é como uma **agência dos Correios pronta para uso**: você não
precisa saber como um caminhão de carga funciona, nem como o sistema de
rastreamento é implementado — você só chega no balcão, diz "essa carta
(`payload`), para esse endereço (`queue`)", e a agência (o Spring Cloud AWS)
cuida do resto. A "agência" em si (o bean `SqsTemplate`) já vem pronta assim
que você contrata o serviço (`spring-cloud-aws-starter-sqs` no `pom.xml`); o
endereço da agência (LocalStack local vs. AWS real) é definido pela
configuração no `application.yml`, não pelo código.

## `SqsMessagingConfig` — ajustando um detalhe de como o `SqsTemplate` converte mensagens

O `SqsTemplate` (e, do lado do consumidor, o mecanismo por trás do
`@SqsListener`) precisam transformar objetos Java em JSON e vice-versa. Quem
faz essa tradução é um **`MessagingMessageConverter`** — e por padrão o
Spring Cloud AWS já fornece um, sem você precisar declarar nada. Só que o
comportamento padrão desse conversor causa um problema específico neste
projeto, e é isso que os dois `SqsMessagingConfig` (um em cada serviço)
existem para corrigir. (O do inventory-service, além disso, também limita
o tempo de espera das chamadas ao SQS — ver a subseção sobre timeouts logo
depois do lado do produtor.)

### O problema que motivou esse bean

Por padrão, ao enviar uma mensagem, o conversor anexa um cabeçalho técnico
chamado `JavaType`, contendo o **nome completo da classe Java** do objeto
enviado — por exemplo, `com.orderflow.inventory.stock.dto.StockAdjustedEvent`.
A ideia original desse cabeçalho é ajudar quem consome a mensagem a saber
"para qual classe Java eu devo desserializar isso".

O problema: o `notification-service` tem sua **própria cópia** dessa classe,
em outro pacote e outro módulo Maven —
`com.orderflow.notification.history.dto.StockAdjustedEvent`. Os dois serviços
não compartilham uma biblioteca comum (cada um tem seu próprio DTO,
propositalmente — microsserviços independentes não deveriam depender do
mesmo `.jar` de domínio). Se o consumidor confiasse nesse cabeçalho para
decidir qual classe carregar, ele tentaria carregar
`com.orderflow.inventory.stock.dto.StockAdjustedEvent` — uma classe que
**não existe** no classpath do notification-service — e explodiria com um
erro de classe não encontrada, antes mesmo do `NotificationEventListener`
rodar. Como o Javadoc do arquivo do notification-service explica: sem uma
fila de mensagens mortas (DLQ), essa mensagem nunca seria confirmada e
voltaria para a fila **para sempre**, tentando (e falhando) repetidamente.

### A correção, um lado de cada vez

Cada serviço declara seu próprio `SqsMessagingConfig`, e cada um resolve a
parte do problema que cabe a ele — não é o mesmo código copiado duas vezes,
são duas correções diferentes:

**Lado do produtor** (`inventory-service/.../config/SqsMessagingConfig.java`):

```java
@Bean
public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
    SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
    converter.doNotSendPayloadTypeHeader();
    return converter;
}
```

`doNotSendPayloadTypeHeader()` faz o produtor **parar de mandar** o cabeçalho
`JavaType` desde a origem. A mensagem que chega na fila passa a carregar só o
JSON do evento, sem nenhum metadado de classe Java grudado nela.

#### Ainda no produtor: um limite de tempo para falar com o SQS (WR-03)

O mesmo `SqsMessagingConfig` do inventory-service declara um segundo bean,
que não tem nada a ver com conversão:

```java
@Bean
public SqsAsyncClientCustomizer sqsAsyncClientTimeoutCustomizer() {
    return builder -> builder.overrideConfiguration(c -> c
            .apiCallTimeout(Duration.ofSeconds(3))
            .apiCallAttemptTimeout(Duration.ofSeconds(1)));
}
```

O motivo: o `SqsAsyncClient` padrão (baseado em Netty) espera cerca de 30
segundos por tentativa e faz 3 tentativas. E o `publishStockAdjusted` roda
**de forma síncrona, na própria thread da requisição HTTP**, depois do
commit. Se o SQS (LocalStack) aceitasse a conexão mas nunca respondesse, a
resposta do `PUT` ficaria presa por 1 a 2 minutos — mesmo o ajuste já tendo
sido gravado de verdade no Postgres.

Com o limite (`apiCallAttemptTimeout` de 1s por tentativa e
`apiCallTimeout` de 3s para a chamada inteira), o cliente desiste rápido, a
exceção cai no `catch` do `publishStockAdjusted` e vira a linha `ERROR` de
evento perdido — e o vendedor recebe a resposta do `PUT` sem esperar.

O `SqsMessagingConfig` do notification-service não tem esse bean — lá só
existe o ajuste de conversão descrito abaixo.

**Lado do consumidor** (`notification-service/.../config/SqsMessagingConfig.java`):

```java
@Bean
public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
    SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
    converter.setPayloadTypeMapper(message -> null);
    return converter;
}
```

Aqui a correção é diferente: `setPayloadTypeMapper(message -> null)` diz ao
conversor "nunca tente decidir o tipo de destino olhando um cabeçalho da
mensagem — devolva sempre `null`". Nesse caso, quem passa a decidir a que
tipo desserializar é **o parâmetro do próprio método do listener**
(`onMessage(String payload)`, que pede `String` — o texto bruto do JSON,
sem nenhuma tentativa de virar objeto automaticamente).

O Javadoc desse arquivo é explícito sobre por que essa segunda correção
existe *mesmo já existindo a primeira, do lado do produtor*: **"o consumidor
não pode depender de o produtor desligar esse atributo — quem decide a
conversão é o tipo do parâmetro do método do listener, nunca o atributo
enviado pelo produtor"**. Ou seja, o consumidor se defende de forma
independente, sem confiar que quem publica a mensagem sempre vai lembrar de
configurar isso direito — uma defesa em profundidade, não uma correção
redundante.

### Por que isso é registrado como `@Bean` em vez de configurar na chamada

Em ambos os casos, o comentário do arquivo observa que a auto-configuração
do Spring Cloud AWS **procura** por um bean `MessagingMessageConverter` já
existente e, se encontrar, o usa tanto no `SqsTemplate` quanto (no lado do
consumidor) na fábrica padrão de containers de listener — continuando a
aplicar por baixo o `ObjectMapper` do Spring Boot (o mesmo que serializa
`Instant` como texto ISO-8601 em todo o resto da API). Isso é o padrão comum
no Spring: em vez de configurar cada chamada individualmente, você declara
**um bean central** e o framework aplica essa configuração em todos os
lugares relevantes automaticamente — o mesmo princípio já visto em
`RetryConfig` (`@EnableRetry`), só que aqui o "interruptor" é a própria
presença do bean, não uma anotação.

O contrato entre os dois serviços fica sendo, no fim, só o **JSON puro do
payload** — nada de metadado de classe Java atravessando a fronteira entre
serviços, o que é exatamente o que se espera de dois microsserviços que não
compartilham código de domínio.

## O que é o padrão Transactional Outbox (e por que ainda não existe aqui)

Aqui está o ponto mais sutil, e o motivo de comentários como "D-29"/"D-30"
aparecerem no código.

**O problema chamado "dual write"**: no passo 2/3 do fluxo acima, acontecem
**duas operações separadas**:

- (a) salvar no banco Postgres (uma transação de banco)
- (b) enviar para o SQS (uma chamada de rede separada, feita **depois** que a
  transação de (a) já commitou)

Essas duas coisas **não são atômicas** — não acontecem como uma coisa só.
Pode acontecer de (a) funcionar e (b) falhar (rede caiu, SQS indisponível
etc.). Resultado: o banco diz que o estoque mudou, mas ninguém foi avisado.
É exatamente essa limitação que o Javadoc do `StockEventPublisher` documenta
de forma explícita: a falha ao publicar é **capturada e apenas logada**,
nunca relançada — porque, quando esse método roda, a transação do ajuste de
estoque **já foi commitada**; relançar o erro faria a API responder "o
ajuste falhou" para um vendedor quando, na verdade, o ajuste já aconteceu.

Isso é uma **decisão deliberada e documentada** para esta fase do projeto
(D-29: publicar direto no SQS, sem outbox; D-30: aceitar o risco de
dual-write sem mitigação técnica por enquanto), não um bug esquecido. Cada
evento perdido deixa uma linha `ERROR` identificável nos logs — nunca falha
em silêncio. Isso vale também para um SQS que simplesmente não responde:
graças ao limite de tempo do WR-03, a espera fica limitada a 3 segundos e
termina nessa mesma linha `ERROR`.

O **padrão Outbox** é a forma de fechar essa lacuna de verdade:

1. Em vez de enviar direto ao SQS, o serviço grava **na mesma transação de
   banco** duas coisas: a mudança de negócio (ex.: estoque) **e** uma linha
   numa tabela extra chamada "outbox" (ex.: `outbox_events`), contendo o
   evento a ser publicado depois.
2. Como as duas gravações (dado + evento) acontecem na mesma transação SQL,
   elas são atômicas: ou as duas acontecem, ou nenhuma acontece. Não tem como
   "salvar o estoque mas perder o evento".
3. Um processo separado (um "poller" — algo que roda periodicamente) lê a
   tabela outbox, pega os eventos ainda não publicados, manda pro SQS, e só
   então marca aquela linha como "publicada".
4. Se o poller falhar no meio do caminho, ele simplesmente tenta de novo
   depois — o evento continua na tabela até ser confirmado como publicado.

**Importante**: essa tabela outbox, o poller (o "relay") e a saga de
reserva de estoque **ainda não existem no código** — são a Fase 5 do
`ROADMAP.md`, já planejada mas ainda não implementada. A Fase 4 já está
concluída: o `order-service` cria pedidos com aprovação por limite de
crédito (ver [22-services-de-pedido.md](22-services-de-pedido.md)), mas
ainda **sem saga e sem mensageria** — ele não publica nem consome nada do
SQS. Por isso, a única mensageria que existe hoje continua sendo a da Fase
3: o envio direto do `STOCK_ADJUSTED` — a versão simplificada que já
demonstra o SQS funcionando ponta a ponta antes de somar a complexidade do
outbox.

## Consumo idempotente — por que reentrega não é um problema aqui

O SQS padrão não garante nem ordem de entrega, nem entrega única — a mesma
mensagem pode chegar mais de uma vez. O `NotificationEventListener` e o
`NotificationService` são desenhados para tolerar isso:

- `NotificationService.record(...)` monta a chave do DynamoDB de forma
  **determinística**: `sortKey = eventType + "#" + eventId` (o mesmo
  `eventId` sempre produz a mesma chave). A gravação usa `PutItem` **sem
  expressão de condição** — ou seja, gravar o mesmo evento duas vezes
  simplesmente sobrescreve a mesma linha com o mesmo conteúdo. Não precisa de
  uma consulta prévia "já processei este evento?": a idempotência vem de
  graça da chave.
- Se o JSON for inválido (`InvalidNotificationEventException`), o listener
  captura o erro, loga um aviso e retorna normalmente — isso faz o Spring
  Cloud AWS confirmar a mensagem e apagá-la da fila. É a única defesa contra
  uma mensagem "envenenada" que nunca poderia ser processada (o projeto ainda
  não tem uma fila de mensagens mortas / DLQ). Isso inclui corpos acima de
  64 KB (WR-02): sem essa checagem, um item grande demais para o DynamoDB
  (que recusa itens acima de 400 KB) faria o `putItem` falhar sempre — e,
  como esse erro não é uma `InvalidNotificationEventException`, a mensagem
  voltaria para a fila eternamente.
- Qualquer **outra** exceção (por exemplo, uma falha ao gravar no DynamoDB)
  **não** é capturada — a mensagem não é confirmada, e o SQS a entrega de
  novo depois do timeout de visibilidade. Como a gravação é idempotente, essa
  reentrega é segura.
- Na leitura (`NotificationService.history`), os registros são reordenados em
  memória por `occurredAt` — porque a fila não garante ordem de chegada e a
  chave de ordenação do DynamoDB não é cronológica.

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
- **Idempotência por chave** = mesmo que o carteiro entregue a mesma carta
  duas vezes por engano, ela vai pro mesmo escaninho e substitui a cópia
  anterior — não vira duas entradas duplicadas no seu arquivo.

## Estado atual vs. planejado

| | Hoje (mecanismo da Fase 3, ainda em uso após a Fase 4) | Planejado (Fase 5) |
|---|---|---|
| Quem publica | `StockEventPublisher` chama `sqsTemplate.send` direto | Uma tabela `outbox_events` gravada na mesma transação do domínio |
| Atomicidade banco+evento | Não — dual-write conhecido (D-30) | Sim — outbox garante atomicidade |
| Se o SQS falhar | Evento se perde, fica só um log `ERROR` | Evento permanece na tabela até um poller confirmar a publicação |
| Escopo do evento | Só ajuste de estoque → notificação | Saga completa: pedido → crédito → reserva de estoque → confirmação |
