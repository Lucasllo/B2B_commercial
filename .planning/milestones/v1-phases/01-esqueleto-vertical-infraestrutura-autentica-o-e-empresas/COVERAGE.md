# API Coverage — Phase 1

**Decision:** no external API integration.

No external API integration: Phase 1 stands up internal microservices (API Gateway, auth-service) and local infrastructure (PostgreSQL, LocalStack) with no calls to any third-party API/SDK/service.

## Reasoning

Reli o escopo da fase (ROADMAP.md §Phase 1 + 01-CONTEXT.md `<domain>`) procurando por uma
superfície de API externa que pudesse ser integrada parcialmente. Não há:

- **API Gateway e auth-service** são serviços internos deste próprio repositório, criados nesta
  fase. Não são um SDK ou serviço de terceiro sendo consumido.
- **PostgreSQL** é um datastore, acessado via JDBC/JPA — não é uma API de produto com um
  conjunto de capabilities das quais se escolhe um subconjunto.
- **LocalStack** é emulação local de infraestrutura AWS. Nesta fase ele apenas sobe saudável no
  `docker-compose` (INFRA-01) para provar a topologia-alvo; **nenhum código da Fase 1 faz
  qualquer chamada a SQS ou DynamoDB**. O primeiro consumo real acontece na Fase 3
  (NOTF-01/NOTF-02) e na Fase 5 (ORD-04/05/06) — é lá que uma matriz de cobertura de
  capabilities do SQS/DynamoDB faz sentido, não aqui.
- **Maven Central / Docker Hub / start.spring.io** são registries de artefato consultados em
  tempo de build, não superfícies de API integradas ao produto.
- **LocalStack Hobby account (`LOCALSTACK_AUTH_TOKEN`)** é uma credencial de ativação de imagem
  Docker, registrada como `user_setup` no plano `01-03-PLAN.md`. Não há chamada de API a partir
  do código da aplicação.

Nenhum OPT-OUT de capability é necessário porque não existe matriz de capabilities aplicável
nesta fase.

## Re-avaliar em

- **Fase 3** — primeira integração real com SQS + DynamoDB via LocalStack/AWS SDK v2.
- **Fase 5** — expansão do uso de SQS (outbox publisher, consumidor idempotente).
