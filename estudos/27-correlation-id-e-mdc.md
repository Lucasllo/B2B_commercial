# Correlation-ID e MDC

Arquivos:

- `gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java` (plano 07-01)
- `<serviço>/src/main/java/com/orderflow/<serviço>/observability/CorrelationContext.java` e
  `CorrelationIdFilter.java` nos cinco serviços (planos 07-02, 07-04, 07-05 e 07-07)
- `order-service/src/main/java/com/orderflow/order/config/ClientConfig.java` — o `RestClient` que
  reenvia o ID para catalog e auth
- `OutboxWriter.java` e `OutboxRelay.java` em `saga/outbox/` do order-service e do
  inventory-service
- `order-service/src/main/resources/db/migration/V4__correlation_id.sql` e
  `inventory-service/src/main/resources/db/migration/V5__outbox_correlation_id.sql`
- `ReservationResultListener` (order), `ReservationCommandListener` (inventory) e
  `NotificationEventListener` (notification)
- `logging.pattern.correlation` no `application.yml` dos seis módulos
- `scripts/smoke-correlation-id.sh`

Continuação de [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md), onde o outbox foi
explicado, e de [19-sqslistener-consumo.md](19-sqslistener-consumo.md), onde o listener roda
numa thread que não é a da requisição HTTP.

## O que é um Correlation-ID, em uma frase

Um **Correlation-ID** é uma etiqueta colada numa requisição para que todas as linhas de log
que ela provoca — no Gateway, no serviço, e mais tarde numa mensagem de fila — possam ser
achadas juntas. É o número do protocolo de um atendimento: você não precisa saber em qual
guichê a pessoa está; basta procurar o número.

Aqui a etiqueta se chama `X-Correlation-Id`. Ela nasce no **Gateway** (D-92). Se o cliente
já mandou um valor que parece um identificador de verdade, o Gateway reaproveita. Se não
mandou, ou mandou lixo, o Gateway gera um UUID novo.

## Por que o MDC é por thread — e por que o `finally` existe

O SLF4J guarda dados "ao lado" do log numa estrutura chamada **MDC** (*Mapped Diagnostic
Context*). Pense numa gaveta colada na thread: `MDC.put("correlationId", id)` coloca o
número na gaveta, e o padrão de log

```yaml
logging:
  pattern:
    correlation: "[%X{correlationId:-}] "
```

imprime `[abc-123] ` no começo de cada linha **dessa thread**. `%X{correlationId:-}` quer
dizer "o valor da gaveta, ou nada se ela estiver vazia".

A gaveta **não** viaja com a requisição. Ela viaja com a **thread**. O Tomcat devolve a
mesma thread para a próxima requisição. Se o filtro fizer `MDC.put` e esquecer de tirar, o
próximo pedido herda o ID do anterior — dois clientes diferentes aparecem no log como se
fossem o mesmo atendimento (QUAL-02).

Por isso a limpeza fica num `finally`:

```java
MDC.put(MDC_KEY, id);
try {
    chain.doFilter(pedidoComUmSoHeader, respostaQueIgnoraEco);
} finally {
    // log de acesso aqui, enquanto a gaveta ainda tem o ID
    MDC.remove(MDC_KEY);
}
```

O `finally` roda mesmo quando a cadeia lança exceção. Sem ele, o erro seria justamente o
caso em que a gaveta ficaria suja.

## Por que o Gateway não confia no valor que o cliente mandou (D-92)

O header vem de fora, e o Gateway **não autentica** ninguém (D-05): ele só roteia. Qualquer
um pode mandar `X-Correlation-Id`. Se esse texto entrasse cru no log, um cliente poderia
mandar uma quebra de linha e **inventar linhas de log** (log injection, T-07-01). Exemplo
do que não pode aparecer no arquivo:

```text
X-Correlation-Id: abc
X-Injected: 1
```

O filtro só aceita o que casa `[A-Za-z0-9-]{1,64}`: letras, dígitos e hífen, no máximo 64
caracteres. UUID cabe. Espaço, `!`, CR, LF e string vazia **não** cabem — viram um UUID
novo **antes** de entrar no MDC. O valor rejeitado nunca é escrito no log.

O nome do header é lido ignorando caixa (`x-correlation-id` e `X-Correlation-Id` são o
mesmo). O pedido encaminhado leva **um** valor só: um `TreeSet` com
`String.CASE_INSENSITIVE_ORDER` junta as duas grafias numa chave só (senão o serviço
receberia o header duas vezes). A resposta também leva um valor só — o do Gateway. Se o
serviço ecoar outro `X-Correlation-Id`, o wrapper da resposta ignora esse eco.

A linha de acesso é só `MÉTODO caminho -> status`. Caminho vem de `getRequestURI()`, que
**não** inclui a query string. Header `Authorization` e corpo não entram nessa linha.
`/actuator/**` também não: o healthcheck do compose bate ali o tempo todo e afogaria o
`grep`.

## Por que, no SQS, o ID não pode "continuar no MDC" (D-94)

O relay do outbox não roda dentro da requisição. Ele acorda depois, num `@Scheduled`, numa
**outra thread**, quando a gaveta MDC da requisição já foi esvaziada — às vezes o processo
até reiniciou. Não há gaveta para ler.

O ID precisa estar **gravado na linha** do `outbox_event` (coluna `correlation_id`), na
mesma transação que grava o evento. Na hora de publicar, o relay abre um MDC novo a partir
dessa coluna e copia o valor para um **atributo da mensagem** SQS (`correlationId`). O
listener do outro lado faz o caminho inverso: atributo → MDC, só enquanto processa aquela
mensagem, e restaura o que havia antes ao terminar. Thread de container é reutilizada; sem
restaurar, a mensagem seguinte herdaria o ID.

## Quais transições usam o ID da requisição, e quais herdam o do pedido (D-95)

`POST /orders/{id}/approve`, `/ship` e `/deliver` são HTTP. O ID que importa no log e no
outbox **daquela** chamada é o ID **da própria requisição** — o mesmo que o Gateway acabou
de colocar no MDC. Não precisa de código extra para "buscar o ID original do pedido".

O que não tem HTTP é o job (o timeout da saga, por exemplo). Esse job não recebe header.
Ele lê o ID que foi **gravado no pedido** quando a saga começou. Se a linha for antiga e
estiver nula, gera um UUID novo só para o log daquele job não ficar sem etiqueta.

Isso não cola todas as transições de um pedido debaixo de um único ID. Cola o suficiente
para seguir **uma** requisição. Juntar tudo sob o ID do pedido fica para depois: é trocar
"ler o MDC" por "ler a coluna".

## O caminho completo, módulo por módulo

O Gateway foi o primeiro (07-01). Depois, cada serviço ganhou a sua parte. Pense numa corrida de
revezamento: cada corredor recebe o bastão, corre o seu trecho segurando ele, e entrega para o
próximo. Se alguém deixar o bastão cair, o grep não acha o resto da prova.

### A peça comum: `CorrelationContext`

Cada um dos cinco serviços tem uma cópia de `observability/CorrelationContext.java` (não existe
módulo comum — a mesma decisão que já duplicava o outbox; ver [32-adrs.md](32-adrs.md), ADR 0011).
Ela concentra três nomes e uma regra:

```java
public static final String MDC_KEY = "correlationId";
public static final String HEADER = "X-Correlation-Id";
public static final String SQS_ATTRIBUTE = "correlationId";

private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{1,64}");
```

e um método `open` que valida, põe na gaveta e devolve um "escopo" que **restaura o valor
anterior** quando fecha:

```java
public static Scope open(String raw) {
    String previous = MDC.get(MDC_KEY);
    String id = (raw != null && VALID.matcher(raw).matches()) ? raw : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, id);
    return () -> {
        if (previous == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previous);
        }
    };
}
```

`Scope` estende `AutoCloseable`, então dá para usar com `try (var scope = ...)`: o Java chama
`close()` sozinho no fim do bloco, mesmo com exceção. É o mesmo `finally` do Gateway, escrito de
um jeito que não dá para esquecer. A mesma regra do Gateway vale aqui: o header HTTP e o atributo
SQS vêm de fora, então só entra no log o que casa `[A-Za-z0-9-]{1,64}`.

### 1. Entrada por HTTP: o filtro de cada serviço

Os cinco serviços têm um `CorrelationIdFilter` (um `OncePerRequestFilter`) que lê o header
`X-Correlation-Id` que o Gateway mandou, abre o escopo, deixa a requisição seguir e, no fim,
escreve a linha de acesso e fecha o escopo:

```java
CorrelationContext.Scope scope = CorrelationContext.open(request.getHeader(CorrelationContext.HEADER));
try {
    filterChain.doFilter(request, response);
} finally {
    try {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/actuator")) {
            log.info("{} {} -> {}", request.getMethod(), path, response.getStatus());
        }
    } finally {
        scope.close();
    }
}
```

Diferente do Gateway, o filtro do serviço **não** devolve o header na resposta. Quem é dono do
header de resposta é o Gateway.

### 2. Saída por HTTP: o `RestClient` do order-service (D-96)

Para criar um pedido, o order-service chama o catalog-service (preços) e o auth-service (limite
de crédito). Para o ID chegar lá, o `RestClient` montado em `ClientConfig` tem um interceptor —
um "carimbo" aplicado em toda requisição que sai:

```java
.requestInterceptor((request, body, execution) -> {
    String id = CorrelationContext.current();
    if (id != null) {
        request.getHeaders().set(CorrelationContext.HEADER, id);
    }
    return execution.execute(request, body);
})
```

Ele lê da gaveta (`MDC`) e copia para o header. Se a gaveta estiver vazia, o header não sai. Do
outro lado, o filtro do catalog e do auth (plano 07-05) põe o ID no MDC deles e a linha de acesso
sai com o mesmo `[id]`. É assim que catalog e auth entram na prova, mesmo sem nenhuma fila.

### 3. Gravação: a coluna `correlation_id` do outbox (order e inventory)

O order-service ganhou a migração `V4__correlation_id.sql`:

```sql
ALTER TABLE orders ADD COLUMN correlation_id VARCHAR(64);
ALTER TABLE outbox_event ADD COLUMN correlation_id VARCHAR(64);
```

e o inventory-service, a `V5__outbox_correlation_id.sql` (só a coluna do outbox). As colunas
nascem nulas e sem valor retroativo: linhas antigas ficam nulas, e o código sabe lidar com isso.

O `OutboxWriter` dos dois serviços grava `CorrelationContext.current()` na linha, **na mesma
transação** do evento. No order-service, a coluna `orders.correlation_id` guarda ainda o ID da
requisição que **criou** o pedido — é dali que o job de timeout lê (D-95).

No inventory há dois caminhos para o MDC estar preenchido na hora da gravação: o `PUT` de estoque
(veio pelo filtro HTTP) e o `ReservationCommandListener` (veio pelo atributo da mensagem, item 5).

### 4. Publicação: o atributo SQS no relay

O `OutboxRelay` roda numa thread do `@Scheduled`, sem gaveta nenhuma. Ele abre o escopo a partir
da **coluna** e copia o mesmo valor para o atributo da mensagem:

```java
try (var scope = CorrelationContext.open(event.getCorrelationId())) {
    Message<String> message = MessageBuilder.withPayload(event.getPayload())
            .setHeader(CorrelationContext.SQS_ATTRIBUTE, event.getCorrelationId())
            .build();
    sqsOperations.send(queueName, message);
```

O escopo aberto aqui é só para a linha de log "Evento outbox publicado" sair com o `[id]` certo.

### 5. Consumo: o listener restaura o ID

Os três listeners — `ReservationCommandListener` (inventory), `ReservationResultListener` (order)
e `NotificationEventListener` (notification, plano 07-07) — recebem o atributo como parâmetro e
abrem o escopo em volta de **todo** o processamento da mensagem:

```java
@SqsListener("${orderflow.notifications.queue-name}")
public void onMessage(String payload,
                      @Header(name = CorrelationContext.SQS_ATTRIBUTE, required = false) String correlationId) {
    try (var scope = CorrelationContext.open(correlationId)) {
        log.info("Mensagem recebida da fila '{}'", queueName);
```

`required = false` porque mensagens antigas não têm o atributo: nesse caso o `open` gera um UUID
só para aquela mensagem. Ao fechar, o escopo devolve a gaveta ao que era antes, e a próxima
mensagem na mesma thread começa limpa.

### O mapa inteiro

| Módulo | Recebe o ID por | Repassa o ID por |
|---|---|---|
| gateway | header do cliente (ou gera um UUID) | header `X-Correlation-Id` para o serviço |
| order-service | filtro HTTP; atributo SQS no `ReservationResultListener`; coluna `orders.correlation_id` no timeout | `RestClient` (header) para catalog e auth; coluna do outbox → atributo SQS |
| inventory-service | filtro HTTP; atributo SQS no `ReservationCommandListener` | coluna do outbox (`V5`) → atributo SQS |
| notification-service | atributo SQS no `NotificationEventListener` (e filtro HTTP nas consultas) | ninguém: é o fim da linha |
| catalog-service | filtro HTTP (chamada do order-service) | ninguém |
| auth-service | filtro HTTP (chamada do order-service) | ninguém |

E os seis imprimem o ID do mesmo jeito, com o mesmo padrão de log:

```yaml
logging:
  pattern:
    correlation: "[%X{correlationId:-}] "
```

## Como o `smoke-correlation-id.sh` prova tudo de uma vez

Os testes de cada serviço provam cada trecho do revezamento. O `CorrelationIdE2EIT` (módulo
`e2e-tests`) prova order + inventory juntos. Mas só a stack real tem os seis módulos ligados ao
mesmo tempo. Aí entra o smoke (plano 07-11, D-99):

1. Cria empresa, comprador, produto com estoque.
2. Faz `POST /api/orders` com um ID conhecido: `smoke-cid-<hora>-<aleatório>`. Confere que a
   resposta do Gateway traz **um** header `X-Correlation-Id`, com esse valor.
3. Espera o pedido chegar a `CONFIRMED` e o `ORDER_CONFIRMED` aparecer na linha do tempo — ou
   seja, a mensagem já passou por todas as filas.
4. Para cada um dos seis módulos, roda `docker compose logs` e procura `[<id>]`, com colchetes,
   do jeito que o padrão de log imprime:

   ```bash
   if docker compose logs --no-color "$svc" 2>&1 | grep -qF "[${cid}]"; then
   ```

5. Manda um `GET` com `X-Correlation-Id: bad value!` (tem espaço e `!`): o Gateway devolve um UUID
   novo no lugar.
6. Procura `bad value!` nos logs dos seis: precisa dar **zero**.

A última linha é `SMOKE OK correlation-id <id>`. Para repetir a prova à mão, com o ID que o smoke
imprimiu:

```bash
docker compose logs --no-color order-service | grep -F "[<id>]"
```

Mais sobre os smokes em [24-scripts-smoke.md](24-scripts-smoke.md).

## Por que não é Micrometer Tracing / OpenTelemetry agora (D-98)

Tracing distribuído (span, traceparent, exportador) responde uma pergunta mais cara: a
árvore inteira de chamadas, com tempo em cada pedaço. O que este projeto precisa provar é
mais estreito: **um identificador estável que dá para dar `grep` nos logs**. MDC + um
header + uma coluna resolvem isso sem um coletor, sem amostragem e sem mais uma
dependência. OpenTelemetry continua sendo a extensão natural no dia em que o `grep` deixar
de bastar — não antes. A decisão está registrada no ADR 0008 (ver [32-adrs.md](32-adrs.md)).
