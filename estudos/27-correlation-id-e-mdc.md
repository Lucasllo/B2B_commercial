# Correlation-ID e MDC

Arquivos desta parte (o filtro nasce no plano 07-01; os outros serviços repetem o padrão
depois):

- `gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java`
- `logging.pattern.correlation` no `application.yml` do Gateway (e, em seguida, dos cinco serviços)

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

## Por que não é Micrometer Tracing / OpenTelemetry agora (D-98)

Tracing distribuído (span, traceparent, exportador) responde uma pergunta mais cara: a
árvore inteira de chamadas, com tempo em cada pedaço. O que este projeto precisa provar é
mais estreito: **um identificador estável que dá para dar `grep` nos logs**. MDC + um
header + uma coluna resolvem isso sem um coletor, sem amostragem e sem mais uma
dependência. OpenTelemetry continua sendo a extensão natural no dia em que o `grep` deixar
de bastar — não antes.
