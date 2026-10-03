---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0011 — Código duplicado por serviço em vez de módulo comum

## Contexto e problema

Dois pedaços de código precisam existir em mais de um serviço: o outbox transacional (entidade
`outbox_event`, escritor e relay), no order-service e no inventory-service, e o contexto e o filtro do
Correlation-ID (`CorrelationContext` e `CorrelationIdFilter`), nos serviços HTTP. A saída óbvia para
evitar duplicação seria um módulo Maven `common` compartilhado.

O projeto ainda não tem módulo compartilhado, então criar um é uma decisão estrutural: pesar o custo
contra o benefício, e não apenas aceitar a duplicação por inércia.

## Fatores de decisão

- Autonomia dos serviços: cada um deve poder ser construído, testado e evoluído sem depender de uma
  biblioteca interna compartilhada.
- O custo real de um módulo comum no build e na imagem Docker.
- A quantidade de código duplicado: pequena e estável.
- Compartilhar código entre serviços aproxima o acoplamento que a arquitetura de microsserviços
  quer evitar.

## Alternativas consideradas

- **Duplicar o código em cada serviço, sem módulo comum** — **escolhida**.
- **Módulo Maven `common` compartilhado** — **rejeitada**: o custo supera o ganho neste tamanho. Cada
  um dos seis Dockerfiles copia os `pom.xml` de todos os módulos e executa `dependency:go-offline` para
  o seu serviço, de modo que um módulo novo exigiria editar os seis; entrariam também uma etapa a mais no
  reactor e o versionamento interno do módulo, tudo para evitar algumas dezenas de linhas por serviço.

## Decisão

O código do outbox é duplicado em cada serviço, sem módulo compartilhado, preservando a autonomia dos
serviços (D-62), no mesmo padrão do `TestJwt` copiado entre os serviços. O mesmo vale para o
`CorrelationContext` e o `CorrelationIdFilter` da Fase 7, cuja colocação foi decidida no plano 07-02
(`CORRELATION_CODE_PLACEMENT=duplicated-per-service`): cada serviço tem a própria cópia e o order-service
não importa o filtro do Gateway.

### Consequências

- Bom: cada serviço compila, testa e empacota sozinho; nenhum Dockerfile precisa mudar e nenhuma
  biblioteca interna precisa de versão.
- Bom: um serviço pode divergir quando precisar; cada relay roteia os tipos de evento do próprio
  serviço (o do order-service, por exemplo, também leva os `ORDER_*` à fila de notificações).
- Ruim: uma correção ou melhoria no outbox ou no Correlation-ID precisa ser aplicada em cada cópia,
  e há risco de as cópias divergirem sem querer.
- Ruim: se o contrato do atributo SQS `correlationId` ou o formato do outbox mudar, os serviços
  precisam ser editados em conjunto (ver ADR 0002 e ADR 0008); os testes de cada serviço cobrem a sua
  cópia, mas nada garante que as cópias continuem idênticas.
- Se o número de serviços ou o volume de código compartilhado crescer, a decisão deve ser revista e um
  módulo comum passa a compensar o custo de build.

## Prós e contras das alternativas

### Código duplicado por serviço

- Bom: autonomia total, nenhuma mudança de build; coerente com microsserviços.
- Ruim: manutenção em várias cópias e risco de divergência.

### Módulo `common` compartilhado

- Bom: uma única implementação do outbox e do Correlation-ID.
- Ruim: edita todos os Dockerfiles, complica o reactor e o versionamento, e acopla os serviços por uma
  biblioteca interna.

## Mais informações

Fase de origem: Fase 5 (outbox) e Fase 7 (Correlation-ID).

- Decisão: D-62 em
  [05-CONTEXT.md](../../.planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-CONTEXT.md);
  a colocação do código de Correlation-ID está em
  [07-02-SUMMARY.md](../../.planning/phases/07-endurecimento-observabilidade-e-entrega/07-02-SUMMARY.md)
  e a pergunta em aberto, em Claude's Discretion do
  [07-CONTEXT.md](../../.planning/phases/07-endurecimento-observabilidade-e-entrega/07-CONTEXT.md).
- Base: [ARCHITECTURE.md](../../.planning/research/ARCHITECTURE.md), Anti-Pattern 3 (compartilhar modelo
  mutável entre serviços por biblioteca comum).
- Código: [OutboxWriter.java do order-service](../../order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxWriter.java),
  [OutboxWriter.java do inventory-service](../../inventory-service/src/main/java/com/orderflow/inventory/saga/outbox/OutboxWriter.java)
  e [CorrelationContext.java do order-service](../../order-service/src/main/java/com/orderflow/order/observability/CorrelationContext.java).
- Relacionado: [ADR 0002](0002-transactional-outbox-em-vez-de-publicacao-direta.md) e
  [ADR 0008](0008-correlation-id-proprio-em-vez-de-tracing-distribuido.md).
