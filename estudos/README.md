# Estudos — OrderFlow

Notas de estudo pessoal para entender, elemento por elemento, como o projeto OrderFlow
funciona. Cada arquivo explica uma parte do projeto em nível iniciante, com analogias e
sem pressupor conhecimento prévio.

Diferente de [`docs/`](../docs/), que é documentação voltada a terceiros/portfólio, esta
pasta é para consulta própria durante o aprendizado.

## Índice

1. [Docker Compose](01-docker-compose.md) — os serviços que sobem juntos localmente
   (Postgres, LocalStack, auth-service, gateway) e como eles dependem uns dos outros
2. [Spring Boot e Spring Cloud](02-spring-boot-e-spring-cloud.md) — quais versões o
   projeto usa e por que essas escolhas foram feitas
3. [Dockerfile](03-dockerfile.md) — como cada microsserviço é empacotado em uma imagem
   Docker, do código-fonte até o container rodando
4. [Maven Wrapper (mvnw / mvnw.cmd)](04-maven-wrapper.md) — como o projeto compila sem
   exigir que o Maven esteja instalado na máquina
5. [SecurityConfig](05-security-config.md) — como o `auth-service` decide quem pode
   acessar o quê
6. [JWKS](06-jwks.md) — como outros serviços vão confirmar que um token de login é
   autêntico, sem perguntar ao `auth-service` toda vez
7. [Pasta target](07-pasta-target.md) — a pasta de saída gerada pelo Maven a cada build,
   por que ela não é versionada e por que pode ser apagada sem medo
8. [Flyway e Migrations](08-flyway-migrations.md) — como o banco de dados evolui de
   forma controlada e versionada, e como isso funciona em produção
9. [GlobalExceptionHandler](09-global-exception-handler.md) — como o `auth-service`
   padroniza e protege as respostas de erro em toda a aplicação
10. [application.yml do gateway](10-gateway-application-yml.md) — como o roteamento de
    requisições para os outros serviços é configurado
11. [CompanyGuard](11-companyguard.md) — como o projeto impede que uma empresa veja ou
    altere dados de outra empresa
12. [Records e suas anotações](12-records-e-anotacoes.md) — o que é um `record` em Java
    e todas as anotações de validação/serialização usadas nos DTOs do projeto
13. [springdoc-openapi (Swagger UI)](13-springdoc-openapi.md) — como cada serviço passou a
    expor documentação interativa navegável, com botão Authorize funcional para testar
    endpoints protegidos direto no navegador
14. [Spring Retry (@Retryable, @Recover, @EnableRetry)](14-spring-retry.md) — como o
    `inventory-service` reexecuta automaticamente operações de estoque que colidem por
    conflito de versão, e por que a ordem entre o aspecto de retry e o de transação importa
15. [Pasta de testes e Testcontainers](15-testes.md) — a convenção `*Test.java` vs
    `*IT.java`, as dependências de teste declaradas nos `pom.xml`, e como o Testcontainers
    sobe um Postgres real em Docker para cada suíte de integração
16. [SQS, Outbox e mensageria entre serviços](16-sqs-outbox-mensageria.md) — como o
    `inventory-service` publica eventos no SQS (LocalStack) e o `notification-service` os
    consome de forma idempotente para o DynamoDB, o problema de dual-write que o padrão
    Transactional Outbox (ainda não implementado) vai resolver na Fase 5
17. [DynamoDB](17-dynamodb.md) — o banco NoSQL do `notification-service`, partition key e
    sort key da tabela `notification-history`, `@DynamoDbBean`/Enhanced Client, e como isso
    se compara ao Postgres/JPA usado nos demais serviços
18. [OIDC e a claim `iss`](18-oidc-claim-iss.md) — o que é a claim `iss` de um JWT, o que é
    OIDC e por que o projeto não usa descoberta automática, e a correção (T-03-02/WR-07)
    que passou a rejeitar tokens com emissor errado ou ausente
19. [`@SqsListener` — como o notification-service recebe mensagens](19-sqslistener-consumo.md)
    — o container ouvinte em segundo plano, long polling, por que o parâmetro é `String`, e
    como o retorno (ou exceção) do método decide se a mensagem é confirmada ou volta à fila
20. [`@ConfigurationProperties` — o `ClientProperties`](20-configuration-properties.md) —
    como o `order-service` lê do `application.yml` os endereços e timeouts dos serviços que
    chama, por que `URI`/`Duration` e `@Validated` fazem a aplicação falhar cedo na
    inicialização, e como o `ClientConfig` transforma isso em `RestClient`
21. [Crédito e trava por empresa (pasta `credit`)](21-credito-e-trava-por-empresa.md) — a
    regra de limite de crédito (`CreditPolicy`), a condição de corrida entre pedidos
    simultâneos da mesma empresa, e como a linha de trava com `SELECT ... FOR UPDATE` e
    `Propagation.MANDATORY` faz os pedidos passarem pela checagem um de cada vez
22. [Os services de pedido](22-services-de-pedido.md) — `OrderCreationService` (validação
    "mais barato primeiro" e chamadas HTTP fora da transação), `OrderService` (gravação com
    checagem de crédito, consulta com 404 para outras empresas e paginação com ordenação fixa)
    e `OrderDecisionService` (aprovação/rejeição manual com trava + `refresh`), e por que o
    `@Transactional` exige classes separadas
