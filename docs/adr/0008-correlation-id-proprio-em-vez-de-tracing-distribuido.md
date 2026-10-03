---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0008 — Correlation-ID próprio em vez de tracing distribuído

## Contexto e problema

Um pedido atravessa o Gateway, o order-service, o inventory-service e o notification-service, parte
por HTTP e parte por SQS. Quando algo dá errado, quem avalia o projeto precisa seguir a mesma
requisição por todos os logs. O ponto difícil é a mensageria: o relay do outbox roda em outra thread e
em outro momento, depois do commit da requisição, então o contexto da requisição original não está
mais disponível quando a mensagem é enviada.

É preciso decidir como o identificador de uma requisição viaja entre serviços e se isso é feito com
uma biblioteca de tracing distribuído ou com um mecanismo mínimo, escrito à mão.

## Fatores de decisão

- Um único `grep` de um identificador deve mostrar a requisição em todos os serviços.
- O envelope de negócio das mensagens SQS não deve mudar.
- O mecanismo deve ser explicável: MDC, propagação por HTTP e propagação por mensageria.
- Sem infraestrutura nova de observabilidade (coletor, armazenamento de traces).

## Alternativas consideradas

- **Correlation-ID próprio: header `X-Correlation-Id`, MDC, coluna no outbox e atributo SQS** —
  **escolhida**.
- **Micrometer Tracing / OpenTelemetry com coletor (Zipkin ou Jaeger) e `traceparent` W3C** —
  **rejeitada por enquanto**: exigiria dependências e um coletor para operar, e o objetivo aqui é
  mostrar o mecanismo; fica registrada como evolução natural, e não como descarte definitivo (D-98).
- **Logs em JSON estruturado** — fora de escopo desta fase: o formato de texto com MDC no padrão é
  legível em `docker compose logs` e fácil de demonstrar com `grep`; logs em JSON ficam para o backlog.
- **Campo `correlationId` dentro do envelope da mensagem** — **rejeitada**: o envelope de negócio
  (`eventId`, `eventType`, `occurredAt` e campos do tipo) foi mantido sem alterações, e o identificador
  viaja como atributo da mensagem SQS (D-94).

## Decisão

O Gateway gera um UUID quando o cliente não manda `X-Correlation-Id`; quando manda, só reaproveita o
valor se casar `[A-Za-z0-9-]{1,64}`, o que evita injeção de linhas de log, e devolve o header na
resposta (D-92). Cada serviço tem um filtro HTTP que põe o valor no MDC (`correlationId`) durante a
requisição e o limpa no fim (D-93), e o padrão de log inclui `[%X{correlationId}]` em cada linha, em
texto (D-97).

Pelo SQS o identificador viaja como coluna `correlation_id` na tabela `outbox_event`, gravada pelo
`OutboxWriter` na mesma transação, mais um atributo de mensagem String chamado `correlationId`, enviado
pelo `OutboxRelay`; os listeners leem o atributo e o põem no MDC durante o processamento (D-94).
Fluxos sem requisição HTTP herdam o identificador do pedido, guardado em `orders.correlation_id`: o job
de timeout da saga e as mensagens que ele dispara reaproveitam esse valor (D-95). As chamadas HTTP
síncronas do order-service ao catalog-service e ao auth-service também propagam o header, por um
interceptor do `RestClient` (D-96).

### Consequências

- Bom: o avaliador faz um `grep` do identificador e vê o gateway, o order, o inventory e o
  notification; a prova na stack real é o smoke `scripts/smoke-correlation-id.sh`, previsto na D-99 e entregue no plano 07-11, e os testes automatizados cobrem o filtro e o atributo SQS.
- Bom: nenhuma dependência nova de runtime e nenhuma infraestrutura de tracing para subir.
- Ruim: não há spans, nem tempos por etapa, nem mapa de dependências; o ID só amarra linhas de log.
- Ruim: o atributo `correlationId` passa a fazer parte do contrato de transporte entre order,
  inventory e notification, e a coluna entra nas migrações de dois serviços (D-94 está marcada como
  custosa de reverter).
- Ruim: o filtro e o contexto são duplicados por serviço (ver ADR 0011); mudar o contrato do atributo
  exige editar os serviços em conjunto.
- Transições feitas por `/approve`, `/ship` e `/deliver` usam o identificador da própria requisição, e
  não o original do pedido (`TRANSITION_CORRELATION_SOURCE=request-id`, decidido no plano 07-02). Uma
  extensão barata é usar `orders.correlation_id` também nessas transições para amarrar o ciclo de vida
  inteiro de um pedido num identificador só.

## Prós e contras das alternativas

### Correlation-ID próprio

- Bom: mínimo, didático, sem dependências, funciona também pelo caminho assíncrono do outbox.
- Ruim: só correlaciona logs; não mede latência nem desenha o grafo de chamadas.

### Micrometer Tracing / OpenTelemetry com coletor

- Bom: padrão da indústria, spans, tempos e visualização em Zipkin ou Jaeger.
- Ruim: dependências, configuração e um coletor a operar; esconde o mecanismo que o projeto quer
  demonstrar.

### Logs em JSON

- Bom: prontos para ELK ou Loki.
- Ruim: menos legíveis em `docker compose logs`; sem consumidor de logs no projeto.

### Campo no envelope da mensagem

- Bom: o identificador acompanha o corpo da mensagem.
- Ruim: altera o contrato de negócio compartilhado entre os serviços.

## Mais informações

Fase de origem: Fase 7 (endurecimento, observabilidade e entrega).

- Decisões: D-92, D-93, D-94, D-95, D-96, D-97, D-98 e D-99 (D-92 a D-99) em
  [07-CONTEXT.md](../../.planning/phases/07-endurecimento-observabilidade-e-entrega/07-CONTEXT.md);
  a escolha de duplicar o código por serviço e o atributo SQS está registrada em
  [07-02-SUMMARY.md](../../.planning/phases/07-endurecimento-observabilidade-e-entrega/07-02-SUMMARY.md).
- Tracing distribuído e logs em JSON como itens adiados: seção Deferred Ideas do mesmo `07-CONTEXT.md`.
- Código:
  [CorrelationIdFilter.java do Gateway](../../gateway/src/main/java/com/orderflow/gateway/CorrelationIdFilter.java),
  [CorrelationContext.java do order-service](../../order-service/src/main/java/com/orderflow/order/observability/CorrelationContext.java)
  e [OutboxRelay.java do order-service](../../order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java).
- Relacionado: [ADR 0002](0002-transactional-outbox-em-vez-de-publicacao-direta.md), que explica o
  relay, e [ADR 0011](0011-codigo-duplicado-por-servico-em-vez-de-modulo-comum.md), sobre o código duplicado.
