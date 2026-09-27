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

- Se `record(payload)` lançar `InvalidNotificationEventException` (corpo
  acima de 64 KB, JSON malformado ou que não é um objeto / com conteúdo
  sobrando depois do objeto, campo obrigatório faltando, tipo de evento
  desconhecido, quantidade negativa) — esse erro **é capturado
  aqui**, só vira um log de aviso, e o método **retorna normalmente**.
  Resultado: a mensagem é confirmada e some da fila. Faz sentido: se a
  mensagem é estruturalmente inválida, tentar de novo nunca vai fazer ela
  ficar válida — ela ficaria voltando para sempre, e a
  `notification-events-queue` ainda não tem uma fila de mensagens mortas
  (DLQ) para isolar esse caso (as filas da saga, da seção 5, já têm).
- Qualquer **outra** exceção (por exemplo, uma falha ao gravar no DynamoDB)
  **não é capturada aqui** — ela sobe, o método não termina normalmente, a
  mensagem **não** é confirmada, e o SQS a entrega de novo mais tarde. Isso é
  seguro porque a gravação no DynamoDB é idempotente por chave (ver
  [17-dynamodb.md](17-dynamodb.md)): reprocessar a mesma mensagem depois não
  duplica nada.

Esse raciocínio de "deixa voltar para a fila" só vale para falhas
**passageiras** (o DynamoDB fora do ar por um instante, por exemplo). Uma
falha **permanente** do DynamoDB — como um item acima de 400 KB, que ele
sempre vai recusar — faria a mensagem voltar para sempre. É por isso que o
`NotificationService` checa o limite de 64 KB logo no começo (WR-02): isso
transforma esse caso num `InvalidNotificationEventException`, ou seja, em
descarte com log, em vez de um laço infinito.

Um detalhe de segurança sobre o `log.warn`: parte da mensagem da exceção
pode vir de fora (por exemplo, o tipo de evento desconhecido que chegou no
JSON). Esse valor externo passa antes por `sanitizeForLog`, no
`NotificationService`: caracteres de controle (incluindo U+0085, U+2028 e
U+2029, que muitas ferramentas de log tratam como quebra de linha) viram
`_`, e o valor é cortado em 64 caracteres. Assim, ninguém consegue mandar
uma mensagem que "forje" uma linha de log falsa (WR-06).

## 5. Os outros dois listeners (Fase 5)

Com a saga de reserva (ver [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md)),
o projeto passou a ter **três** métodos `@SqsListener`, todos com a mesma forma: parâmetro
`String`, um *parser* que valida o texto, e descarte com `WARN` quando a mensagem é
estruturalmente inválida.

| Listener | Serviço | Fila | Chama |
|---|---|---|---|
| `NotificationEventListener` | `notification-service` | `notification-events-queue` | `NotificationService.record` |
| `ReservationCommandListener` | `inventory-service` | `inventory-commands-queue` | `InventoryService.reserveAll` |
| `ReservationResultListener` | `order-service` | `order-events-queue` | `OrderSagaService.applyStockReserved` / `applyReservationFailed` |

Duas diferenças em relação ao que foi explicado acima.

**1. Uma resposta "não" não é erro.** No `inventory-service`, "estoque insuficiente" **não** vira
exceção. O `reserveAll` grava a falha no outbox e retorna normalmente, então a mensagem é
confirmada. Se virasse exceção, o SQS entregaria o mesmo comando de novo, ele falharia de novo,
e o `order-service` nunca saberia a resposta. Só falhas **técnicas** (banco fora, conflito que
não se resolveu) escapam do método e fazem a mensagem voltar.

**2. Aqui existe DLQ.** As duas filas da saga foram criadas com uma DLQ e
`maxReceiveCount: 3`: depois da terceira entrega sem confirmação, o SQS tira a mensagem da fila
principal e a guarda na DLQ. Uma mensagem com problema técnico permanente não volta para
sempre.

**Um detalhe de configuração: sem long polling.** No `order-service` e no `inventory-service`,
o `application.yml` tem:

```yaml
spring.cloud.aws.sqs.listener.poll-timeout: 0s
```

O cliente SQS desses serviços é o mesmo usado nas chamadas rápidas (o relay) e tem um limite
de tempo curto por chamada (WR-03). O long polling padrão espera até 20 s por mensagem, então
estouraria esse limite a cada ciclo. Com `0s`, cada pergunta à fila responde na hora, com ou sem
mensagem. O `notification-service` não tem esse problema e continua com o long polling da
seção 2.

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
