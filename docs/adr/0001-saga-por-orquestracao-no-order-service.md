---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0001 — Saga por orquestração no order-service em vez de coreografia

## Contexto e problema

Criar um pedido no OrderFlow atravessa mais de um serviço: o `order-service` decide o crédito e
guarda o pedido, o `inventory-service` reserva o estoque e o `notification-service` registra o
histórico. Não existe transação distribuída entre eles (cada serviço tem o próprio schema no
PostgreSQL e a comunicação é assíncrona, por SQS), então a consistência do fluxo precisa ser
construída com o padrão saga: uma sequência de transações locais com compensação quando uma etapa
falha.

A pergunta é quem conduz essa sequência. Ou os serviços reagem uns aos eventos dos outros sem um
dono do processo (coreografia), ou um serviço assume o papel de coordenador, envia comandos e reage
aos resultados (orquestração). O Core Value do projeto é justamente esse fluxo funcionando de ponta
a ponta, de modo confiável e explicável.

## Fatores de decisão

- A saga é pequena: dois participantes reais (order e inventory); o notification-service só ouve
  eventos e não participa da compensação.
- O processo precisa ser fácil de rastrear, de testar de ponta a ponta e de explicar em entrevista.
- O pedido nunca pode ficar preso num estado intermediário: sempre termina em `CONFIRMED` ou
  `CANCELLED`, inclusive quando a resposta do estoque não chega.
- Sem infraestrutura nova de coordenação: o projeto roda inteiro em `docker compose` local, sem
  custo de nuvem.

## Alternativas consideradas

- **Orquestração dentro do order-service (código, sem motor)** — **escolhida**.
- **Coreografia pura entre os serviços** — **rejeitada**: o fluxo ficaria implícito, espalhado
  em vários listeners, e difícil de acompanhar ponta a ponta; com apenas dois participantes ela não
  traz o ganho de desacoplamento que justifica o custo.
- **Motor de workflow dedicado (Camunda, Temporal, AWS Step Functions)** — **rejeitada**: é
  infraestrutura e curva de aprendizado desproporcionais para uma saga de dois participantes, e
  desviaria o foco da lógica de domínio em Java/Spring que o projeto quer demonstrar.

## Decisão

O `order-service` é o orquestrador. A máquina de estados vive no próprio agregado `Order`:
`CREATED → (PENDING_APPROVAL →) APPROVED → RESERVING → CONFIRMED | CANCELLED` (D-49). A saga é
disparada em toda entrada em `APPROVED`, automática ou manual, por um único ponto de entrada (D-48);
a decisão de aprovação grava `RESERVING` e o comando `ReserveStock` no outbox na mesma transação
(D-50). O comando carrega todos os itens e o inventory reserva tudo ou nada numa transação local, de
modo que não existe reserva parcial a compensar (D-55).

Há uma fila por direção, cada uma com DLQ (D-61): `inventory-commands-queue` (order → inventory) e
`order-events-queue` (inventory → order). O resultado só é aplicado se o pedido ainda estiver em
`RESERVING` (D-64), e um job `@Scheduled` cancela pedidos presos nesse estado e publica
`ReleaseStock` para compensar uma reserva tardia (D-63).

### Consequências

- Bom: o processo inteiro está legível em um lugar (`OrderSagaService`), a transição é guardada por
  estado e a idempotência não exige tabela de mensagens processadas (D-64).
- Bom: o fluxo é testável de ponta a ponta com Postgres e LocalStack reais (módulo `e2e-tests`),
  incluindo o caminho de falha e o timeout.
- Ruim: o order-service conhece o formato dos comandos e eventos do inventory-service; é um
  acoplamento moderado, normal em sagas orquestradas e aceito aqui.
- Ruim: a saga leva alguns segundos, porque cada salto passa pelo relay do outbox (ver ADR 0002); o
  README declara essa latência como limitação conhecida.
- A mesma trava por linha do pedido serializa o resultado da saga e o timeout, que são duas fontes
  de transição sobre o mesmo pedido (decisão registrada no `PROJECT.md`, Key Decisions).

## Prós e contras das alternativas

### Orquestração dentro do order-service

- Bom: processo explícito, fácil de rastrear, testar e explicar; sem dependência nova.
- Bom: o estado da saga é parte do agregado `Order`, persistido junto com o pedido.
- Ruim: o order-service concentra a lógica de coordenação e conhece o contrato do inventory.

### Coreografia pura

- Bom: acoplamento menor entre serviços e nenhum coordenador central.
- Ruim: processo implícito, difícil de acompanhar de ponta a ponta e de depurar quando um evento se
  perde.
- Ruim: com dois participantes o desacoplamento extra não compensa a perda de clareza.

### Motor de workflow (Camunda, Temporal, Step Functions)

- Bom: coordenação, retentativas e visibilidade prontas.
- Ruim: serviço extra para rodar, documentar e explicar; excesso de infraestrutura para um fluxo com
  dois participantes.

## Mais informações

Fase de origem: Fase 5 (saga de reserva de estoque).

- Decisões: D-48, D-49, D-50, D-55, D-61, D-63 e D-64 em
  [05-CONTEXT.md](../../.planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-CONTEXT.md).
- Decisões-chave do projeto: [PROJECT.md](../../.planning/PROJECT.md), tabela Key Decisions.
- Base da alternativa rejeitada: [ARCHITECTURE.md](../../.planning/research/ARCHITECTURE.md), Pattern 1
  (saga por orquestração, não por coreografia) e a seção "What NOT to Use" do
  [CLAUDE.md](../../.claude/CLAUDE.md).
- Código: [OrderSagaService.java](../../order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java)
  e [SagaTimeoutJob.java](../../order-service/src/main/java/com/orderflow/order/saga/SagaTimeoutJob.java).
- Relacionado: [ADR 0002](0002-transactional-outbox-em-vez-de-publicacao-direta.md), que garante a
  entrega dos comandos e eventos da saga.
