# SQS, Outbox e mensageria entre serviços

Arquivos: `inventory-service/src/main/java/com/orderflow/inventory/stock/messaging/StockEventPublisher.java`,
`inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java`,
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
criar a fila silenciosamente e mascarar o problema.

### Fluxo real, passo a passo

1. Alguém chama `PUT /inventory/{productId}` para ajustar estoque.
2. `InventoryController` salva a mudança no Postgres (transação já commitada
   quando o método retorna).
3. Depois de salvar, o controller chama
   `StockEventPublisher.publishStockAdjusted(...)`:

   ```java
   public void publishStockAdjusted(UUID productId, int previousQuantityOnHand, int newQuantityOnHand) {
       StockAdjustedEvent event = StockAdjustedEvent.of(productId, previousQuantityOnHand, newQuantityOnHand);
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

   Isso monta um `StockAdjustedEvent` e coloca a mensagem na fila SQS.
4. O `notification-service`, que está sempre escutando essa fila, recebe a
   mensagem via `NotificationEventListener.onMessage(String payload)`.
5. `NotificationService.record(...)` valida o JSON, monta uma mensagem
   legível e grava um registro no DynamoDB (tabela `notification-history`).

## `SqsMessagingConfig` — por que existe um bean só para isso

```java
@Bean
public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
    SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
    converter.doNotSendPayloadTypeHeader();
    return converter;
}
```

Por padrão, o Spring Cloud AWS anexa um cabeçalho técnico à mensagem (o
atributo `JavaType`) com o **nome completo da classe Java** do evento no lado
de quem envia — por exemplo,
`com.orderflow.inventory.stock.dto.StockAdjustedEvent`. O problema: o
`notification-service` tem sua **própria** cópia dessa classe, em outro
pacote (`com.orderflow.notification.history.dto.StockAdjustedEvent`), porque
os dois serviços não compartilham uma biblioteca comum. Se o consumidor
tentasse usar esse cabeçalho para decidir qual classe carregar, ele quebraria
— a classe do produtor não existe no classpath do consumidor.

A solução, dos dois lados: o **produtor** (`inventory-service`) desliga o
envio desse cabeçalho com `doNotSendPayloadTypeHeader()`; o **consumidor**
(`notification-service`) já se defende de qualquer forma configurando seu
próprio conversor sem mapeador de tipo. O contrato entre os dois serviços
fica sendo só o **JSON puro do payload** — nada de metadado de classe Java
atravessando a fronteira entre serviços.

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
em silêncio.

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

**Importante**: essa tabela outbox, o poller, e a saga completa de pedido
(pedido → aprovação de crédito → reserva de estoque → confirmação) **ainda
não existem no código**. São a Fase 4 e a Fase 5 do `ROADMAP.md`, ainda não
iniciadas. O que existe hoje (Fase 3) é a versão simplificada — envio direto
— que já demonstra o SQS funcionando ponta a ponta antes de somar a
complexidade do outbox.

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
  não tem uma fila de mensagens mortas / DLQ).
- Qualquer **outra** exceção (por exemplo, uma falha ao gravar no DynamoDB)
  **não** é capturada — a mensagem não é confirmada, e o SQS a entrega de
  novo depois do timeout de visibilidade. Como a gravação é idempotente, essa
  reentrega é seguro.
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

| | Hoje (Fase 3) | Planejado (Fase 5) |
|---|---|---|
| Quem publica | `StockEventPublisher` chama `sqsTemplate.send` direto | Uma tabela `outbox_events` gravada na mesma transação do domínio |
| Atomicidade banco+evento | Não — dual-write conhecido (D-30) | Sim — outbox garante atomicidade |
| Se o SQS falhar | Evento se perde, fica só um log `ERROR` | Evento permanece na tabela até um poller confirmar a publicação |
| Escopo do evento | Só ajuste de estoque → notificação | Saga completa: pedido → crédito → reserva de estoque → confirmação |
