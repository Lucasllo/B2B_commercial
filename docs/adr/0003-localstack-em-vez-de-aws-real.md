---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0003 — LocalStack em vez de AWS real

## Contexto e problema

O projeto precisa demonstrar uso de serviços AWS (SQS para a mensageria da saga, DynamoDB para o
histórico de notificações) porque a vaga-alvo pede essa familiaridade. Ao mesmo tempo, é um projeto
de portfólio: não deve gerar custo recorrente de nuvem, nem exigir que um avaliador tenha conta AWS
para ver o sistema funcionar. Qualquer pessoa precisa conseguir clonar o repositório e subir tudo
com um comando.

Há também uma questão de teste: a integração com SQS e DynamoDB precisa ser exercitada de verdade
(formato real das mensagens, comportamento de fila, DLQ, chave do DynamoDB), e não apenas simulada
com mocks.

## Fatores de decisão

- Zero custo de nuvem e nenhum deploy real ativo continuamente (constraint do projeto).
- Todo o sistema sobe localmente com `docker compose` e roda no CI.
- Testes de integração contra SQS e DynamoDB de verdade, com Testcontainers.
- Honestidade sobre o que a simulação não garante.

## Alternativas consideradas

- **LocalStack, com a imagem fixada e provisionamento por init hooks** — **escolhida**.
- **AWS real com deploy contínuo** — **rejeitada**: custo recorrente e dependência de conta e de
  credenciais para qualquer pessoa que quiser rodar ou avaliar o projeto; o projeto não exige deploy
  ativo.
- **Mocks e fakes em memória no lugar de SQS e DynamoDB** — **rejeitada**: testes só com mocks
  passam sem provar a serialização real das mensagens, o comportamento de fila e redrive nem o
  formato real do item no DynamoDB (Pitfall 7 da pesquisa); o caminho da saga nunca deve depender só
  disso.

## Decisão

SQS e DynamoDB rodam em um container LocalStack no `docker-compose.yml`, com a imagem fixada numa
versão (`localstack/localstack:2026.08.3`), nunca `latest`, porque o comportamento já mudou entre
versões. As filas, as DLQs e a tabela DynamoDB são criadas por init hooks em
`localstack-init/ready.d/`, o único lugar que provisiona recursos (as aplicações usam
`queue-not-found-strategy=fail`, D-61). Os testes de integração sobem LocalStack real por
Testcontainers (`LocalStackTestSupport` em cada serviço que usa AWS).

Desde a versão 2026.03.0 a imagem exige um `LOCALSTACK_AUTH_TOKEN` de uma conta gratuita (tier
Hobby). O token vem do ambiente ou do `.env` local (fora do git) e do GitHub Actions secret no CI;
sem ele, a subida e os testes falham com mensagem clara em vez de serem pulados em silêncio
(D-103). Este ADR descreve a exigência sem registrar valor algum.

### Consequências

- Bom: custo zero, subida local reprodutível e a mesma infraestrutura nos testes, no smoke e no CI.
- Bom: o código usa as APIs reais (`SqsTemplate`, `@SqsListener`, DynamoDB Enhanced Client), que
  seguem valendo numa conta AWS de verdade.
- Ruim: o token Hobby é obrigatório para quem rodar o projeto; é preciso cadastrar a conta antes do
  primeiro `docker compose up`.
- Ruim: a sessão do LocalStack Hobby é única por token. O Testcontainers falha (exit 126) quando
  outra sessão está ativa com o mesmo token, o que obriga a derrubar o `docker compose` antes de
  `./mvnw verify` e serializa, no CI, os módulos que usam LocalStack (D-101).
- Ruim: LocalStack é uma simulação; a paridade com a AWS real não é garantida em todos os casos de
  borda, por isso a lógica de consumidores e idempotência segue a semântica documentada do SQS
  (entrega pelo menos uma vez, sem ordem garantida) e não o comportamento acidental do emulador.

## Prós e contras das alternativas

### LocalStack

- Bom: AWS simulada localmente e gratuita no uso do portfólio; testável por Testcontainers.
- Ruim: exige conta e token; sessão única por token; é emulação, não a AWS.

### AWS real com deploy contínuo

- Bom: comportamento idêntico ao de produção.
- Ruim: custo recorrente, credenciais a gerenciar e barreira para quem avalia.

### Mocks e fakes em memória

- Bom: rápido e sem dependência de container nem de token.
- Ruim: falsa confiança, pois não exercita o formato real da mensagem, o redrive nem a chave do
  DynamoDB.

## Mais informações

Fase de origem: Fase 1 (decisão de stack e subida do LocalStack) e Fase 3 (primeira integração real
com SQS e DynamoDB).

- Decisão de provisionamento: D-61 em
  [05-CONTEXT.md](../../.planning/phases/05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o/05-CONTEXT.md).
- Decisões de CI sobre o token e a sessão única: D-101 e D-103 em
  [07-CONTEXT.md](../../.planning/phases/07-endurecimento-observabilidade-e-entrega/07-CONTEXT.md).
- Restrições e decisões-chave: [PROJECT.md](../../.planning/PROJECT.md) (Constraints e Key
  Decisions); a exigência do token e a sessão única estão no [STATE.md](../../.planning/STATE.md),
  em Blockers/Concerns.
- Base da pesquisa: [PITFALLS.md](../../.planning/research/PITFALLS.md) (LocalStack divergindo da AWS
  real; mocks sem confiança de integração).
- Código e configuração: [docker-compose.yml](../../docker-compose.yml),
  [localstack-init/ready.d/](../../localstack-init/ready.d/) e
  [LocalStackTestSupport.java](../../order-service/src/test/java/com/orderflow/order/support/LocalStackTestSupport.java).
