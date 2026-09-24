# `@SqsListener` — como o notification-service recebe mensagens

Arquivo: `notification-service/src/main/java/com/orderflow/notification/history/messaging/NotificationEventListener.java`.

Continuação de [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md) —
lá o foco foi o fluxo ponta a ponta e a idempotência; aqui o foco é **como,
mecanicamente, uma mensagem chega até o método Java** que a processa.

```java
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final String queueName;

    public NotificationEventListener(NotificationService notificationService,
                                      @Value("${orderflow.notifications.queue-name}") String queueName) {
        this.notificationService = notificationService;
        this.queueName = queueName;
    }

    @SqsListener("${orderflow.notifications.queue-name}")
    public void onMessage(String payload) {
        try {
            notificationService.record(payload);
        } catch (InvalidNotificationEventException e) {
            log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
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
entra em ação porque o `notification-service` tem a dependência
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
        chama onMessage(texto_da_mensagem)
    espera um pouco e repete
```

Essa técnica se chama **long polling**: em vez de perguntar "tem mensagem?"
e desistir na hora se a resposta for não, a chamada ao SQS fica **esperando
alguns segundos** por uma resposta antes de tentar de novo — evita ficar
batendo na fila sem parar (o que gastaria requisições à toa) e ainda assim
reage rápido quando uma mensagem chega.

Isso é o motivo de o fluxo publicado em
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md) funcionar mesmo
sem nenhuma ligação direta entre `inventory-service` e
`notification-service`: o produtor só deposita a mensagem na fila
(`StockEventPublisher`); é essa thread de fundo do consumidor que vai
buscar, de forma completamente independente, no seu próprio ritmo.

## 3. Por que o parâmetro é `String`, não um objeto

```java
public void onMessage(String payload) {
```

Quando a mensagem chega, o Spring precisa decidir **como entregar o
conteúdo** para o método. Ele olha o **tipo do parâmetro declarado** —
aqui, `String` — e simplesmente entrega o corpo bruto da mensagem, texto
puro, sem tentar transformar em nenhum objeto Java.

Isso conecta com o `SqsMessagingConfig` do notification-service (ver
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)): o conversor
configurado lá (`setPayloadTypeMapper(message -> null)`) garante que a
decisão de "para que tipo desserializar" **nunca** vem de um cabeçalho da
mensagem — vem só da assinatura do método. Como o método pede `String`, é
exatamente o JSON cru que chega em `payload`, e é o próprio
`NotificationService.record(String rawPayload)` quem decide, dentro dele,
como interpretar esse texto.

## 4. O que acontece depois que `onMessage` termina — confirmar ou não a mensagem

Aqui está o detalhe que faz o listener funcionar de forma confiável:

- **Se `onMessage` retorna normalmente** (sem lançar exceção) — o Spring
  Cloud AWS entende "processamento OK" e **confirma a mensagem** ao SQS
  (tecnicamente, chama `DeleteMessage`). A mensagem é apagada da fila para
  sempre.
- **Se `onMessage` lança uma exceção não capturada** — o Spring **não
  confirma** a mensagem. Ela continua na fila e, depois de um tempo (o
  "timeout de visibilidade"), o SQS a entrega de novo para ser tentada outra
  vez.

É por isso que o `try/catch` dentro do método é tão deliberado:

```java
try {
    notificationService.record(payload);
} catch (InvalidNotificationEventException e) {
    log.warn("Mensagem descartada da fila '{}': {}", queueName, e.getMessage());
}
```

- Se `record(payload)` lançar `InvalidNotificationEventException` (JSON
  malformado, campo obrigatório faltando etc.) — esse erro **é capturado
  aqui**, só vira um log de aviso, e o método **retorna normalmente**.
  Resultado: a mensagem é confirmada e some da fila. Faz sentido: se a
  mensagem é estruturalmente inválida, tentar de novo nunca vai fazer ela
  ficar válida — ela ficaria voltando para sempre, e o projeto ainda não tem
  uma fila de mensagens mortas (DLQ) para isolar esse caso.
- Qualquer **outra** exceção (por exemplo, uma falha ao gravar no DynamoDB)
  **não é capturada aqui** — ela sobe, o método não termina normalmente, a
  mensagem **não** é confirmada, e o SQS a entrega de novo mais tarde. Isso é
  seguro porque a gravação no DynamoDB é idempotente por chave (ver
  [17-dynamodb.md](17-dynamodb.md)): reprocessar a mesma mensagem depois não
  duplica nada.

## Resumindo com uma analogia

Pensa num carteiro (a thread de fundo do Spring Cloud AWS) que fica passando
de tempos em tempos numa caixa de correio (a fila SQS) perguntando "tem
carta nova?". Quando encontra uma carta, ele bate na sua porta (chama
`onMessage`) e entrega o conteúdo. Se você **aceitar** a carta (o método
termina sem erro), o carteiro rasga o comprovante e a carta nunca mais
volta. Se você **recusar** a carta por ela estar rasgada/ilegível
(`InvalidNotificationEventException`), você mesmo já rasga o comprovante —
não adianta o carteiro trazer de novo. Mas se você tiver um problema seu,
temporário, ao tentar guardar a carta no arquivo (uma falha ao gravar no
banco), você não rasga o comprovante — e o carteiro, sem confirmação, vai
trazer a mesma carta de novo mais tarde.
