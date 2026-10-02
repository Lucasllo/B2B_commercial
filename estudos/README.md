# Estudos — OrderFlow

Notas de estudo pessoal para entender, elemento por elemento, como o projeto OrderFlow
funciona. Cada arquivo explica uma parte do projeto em nível iniciante, com analogias e
sem pressupor conhecimento prévio.

Diferente de [`docs/`](../docs/), que é documentação voltada a terceiros/portfólio, esta
pasta é para consulta própria durante o aprendizado.

## Índice

1. [Docker Compose](01-docker-compose.md) — os serviços que sobem juntos localmente
   (Postgres, LocalStack, auth, catalog, inventory, notification, order e gateway) e como
   eles dependem uns dos outros
2. [Spring Boot e Spring Cloud](02-spring-boot-e-spring-cloud.md) — quais versões o
   projeto usa e por que essas escolhas foram feitas
3. [Dockerfile](03-dockerfile.md) — como cada microsserviço é empacotado em uma imagem
   Docker, do código-fonte até o container rodando
4. [Maven Wrapper (mvnw / mvnw.cmd)](04-maven-wrapper.md) — como o projeto compila sem
   exigir que o Maven esteja instalado na máquina
5. [SecurityConfig](05-security-config.md) — como o `auth-service` decide quem pode
   acessar o quê
6. [JWKS](06-jwks.md) — como os outros serviços confirmam que um token de login é
   autêntico, sem perguntar ao `auth-service` toda vez (já implementado nos quatro
   resource servers)
7. [Pasta target](07-pasta-target.md) — a pasta de saída gerada pelo Maven a cada build,
   por que ela não é versionada e por que pode ser apagada sem medo
8. [Flyway e Migrations](08-flyway-migrations.md) — como o banco de dados evolui de
   forma controlada e versionada, e como isso funciona em produção
9. [GlobalExceptionHandler](09-global-exception-handler.md) — como o `auth-service`
   padroniza e protege as respostas de erro em toda a aplicação (em detalhe), e os
   mapeamentos específicos do handler de cada um dos outros serviços
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
    `*IT.java`, as dependências de teste declaradas nos `pom.xml`, como o Testcontainers
    sobe um Postgres (e, onde precisa, um LocalStack) real em Docker para cada suíte de
    integração, o Mockito nos testes unitários e o stub HTTP do `order-service`
16. [SQS, Outbox e mensageria entre serviços](16-sqs-outbox-mensageria.md) — como o
    `inventory-service` publica eventos no SQS (LocalStack) e o `notification-service` os
    consome de forma idempotente para o DynamoDB, onde o `occurredAt` é capturado (dentro
    da transação, WR-04), o limite de tempo do cliente SQS (WR-03), o limite de tamanho do
    corpo da mensagem (WR-02), o problema de dual-write e a ideia do padrão Transactional
    Outbox (já usado pela saga da Fase 5; o `STOCK_ADJUSTED` passa a usá-lo no 05-04)
17. [DynamoDB](17-dynamodb.md) — o banco NoSQL do `notification-service`, partition key e
    sort key da tabela `notification-history`, `@DynamoDbBean`/Enhanced Client, e como isso
    se compara ao Postgres/JPA usado nos demais serviços
18. [OIDC e a claim `iss`](18-oidc-claim-iss.md) — o que é a claim `iss` de um JWT, o que é
    OIDC e por que o projeto não usa descoberta automática, e a correção (T-03-02/WR-07)
    que passou a rejeitar tokens com emissor errado ou ausente nos quatro resource servers
    (catalog, inventory, notification, order)
19. [`@SqsListener` — como o notification-service recebe mensagens](19-sqslistener-consumo.md)
    — o container ouvinte em segundo plano, long polling, por que o parâmetro é `String`, e
    como o retorno (ou exceção) do método decide se a mensagem é confirmada ou volta à fila,
    e os dois listeners da saga (falha de negócio vs. técnica, DLQ, `poll-timeout: 0s`)
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
23. [Anotações de repository](23-anotacoes-de-repository.md) — consultas derivadas pelo nome
    do método, `@Query` (JPQL vs. SQL nativo) e `@Param`, `@Modifying` e a memória do JPA,
    `@Lock` e os tipos de trava pessimista/otimista, e outras como `@Repository`,
    `@EntityGraph` (problema N+1), `@QueryHints` e `@Procedure`
24. [Pasta `scripts` — testes de fumaça](24-scripts-smoke.md) — o que é um smoke test e como
    ele difere dos testes Java, o "modo rigoroso" do Bash, como os scripts rodam `curl` dentro
    do container do Gateway, e o que cada passo de `smoke-notification-flow.sh` e
    `smoke-order-flow.sh` prova sobre o sistema ligado de verdade (o segundo ainda espera
    `APPROVED` e precisa ser atualizado para a Fase 5)
25. [Visão geral da arquitetura](25-visao-geral-da-arquitetura.md) — o mapa do projeto: o que
    cada um dos seis módulos faz, o que `BUYER` e `SELLER_ADMIN` podem fazer, quem chama quem
    (HTTP ou SQS), em que momento e por qual endpoint ou fila, e os estados do pedido. Tem
    [versão com diagramas](https://claude.ai/code/artifact/28ba7064-135a-4a8e-bb53-df77ba32c987)
26. [A saga de reserva de estoque](26-saga-de-reserva-de-estoque.md) — como um pedido
    aprovado vira `RESERVING` e termina `CONFIRMED` ou `CANCELLED`: `ReservationSagaStarter`,
    a tabela `outbox_event` e o relay com `FOR UPDATE SKIP LOCKED`, as filas com DLQ, a
    reserva tudo-ou-nada do `inventory-service`, a idempotência pelo estado do pedido, a
    compensação com `ReleaseStock` e o que ainda falta no plano 05-04
27. [Correlation-ID e MDC](27-correlation-id-e-mdc.md) — o identificador que nasce no
    Gateway, por que o MDC do SLF4J é por thread e precisa ser limpo, por que o valor do
    cliente é validado antes de entrar no log, e como esse mesmo ID atravessa o SQS pela
    coluna do outbox em vez de "pular" de thread em thread
