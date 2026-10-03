# Registro de decisões arquiteturais (ADRs)

Um ADR (Architecture Decision Record) é um arquivo curto que registra uma decisão de arquitetura, o
contexto em que ela foi tomada, as alternativas que existiam e as consequências de ter escolhido
uma delas. Aqui os ADRs contam o porquê de decisões que já foram tomadas durante as fases do
projeto; funcionam como um diário de arquitetura navegável, não como proposta de decisões futuras.

## Formato

Os ADRs seguem o MADR (Markdown Architectural Decision Records) traduzido para português, um arquivo
por decisão, nomeado `NNNN-titulo-em-kebab.md`. Cada arquivo tem:

- front matter com `status` (todos estão `Aceito`), `date` e `decision-makers`;
- as seções Contexto e problema, Fatores de decisão, Alternativas consideradas, Decisão,
  Consequências, Prós e contras das alternativas e Mais informações;
- ao menos uma alternativa marcada como **rejeitada**, com o motivo concreto;
- a linha `Fase de origem:` e links para as decisões `D-xx` dos `CONTEXT.md` das fases e para o código
  que implementa a decisão.

## Como ler

Comece pelos quatro primeiros, que são os mais estruturantes: a saga por orquestração (0001), o
Transactional Outbox (0002), o LocalStack no lugar da AWS real (0003) e a ausência deliberada de
service discovery e config server (0004). Os demais explicam decisões de segurança, persistência,
concorrência, observabilidade e organização do código. Cada ADR liga para as decisões `D-xx` que o
originaram e para o código atual.

A consistência dos ADRs é verificada mecanicamente: `bash scripts/check-adrs.sh` confere o formato, a
alternativa rejeitada, a fase de origem, os links, as decisões citadas e este índice.

## Índice

| ADR | Título | Status | Fase de origem |
|-----|--------|--------|----------------|
| [0001](0001-saga-por-orquestracao-no-order-service.md) | Saga por orquestração no order-service em vez de coreografia | Aceito | Fase 5 |
| [0002](0002-transactional-outbox-em-vez-de-publicacao-direta.md) | Transactional Outbox em vez de publicação direta no SQS | Aceito | Fase 3 e Fase 5 |
| [0003](0003-localstack-em-vez-de-aws-real.md) | LocalStack em vez de AWS real | Aceito | Fase 1 e Fase 3 |
| [0004](0004-sem-service-discovery-nem-config-server.md) | Sem service discovery nem config server | Aceito | Fase 1 |
| [0005](0005-jwt-auto-emitido-e-validado-localmente.md) | JWT auto-emitido pelo auth-service e validado localmente pelos resource servers | Aceito | Fase 1 |
| [0006](0006-dynamodb-para-o-historico-de-notificacoes.md) | DynamoDB para o histórico de notificações | Aceito | Fase 3 e Fase 6 |
| [0007](0007-credito-serializado-por-empresa.md) | Decisão de crédito serializada por empresa (`company_credit_lock`) | Aceito | Fase 4 |
| [0008](0008-correlation-id-proprio-em-vez-de-tracing-distribuido.md) | Correlation-ID próprio em vez de tracing distribuído | Aceito | Fase 7 |
