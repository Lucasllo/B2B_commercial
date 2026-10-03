# Estudos — OrderFlow

Notas de estudo pessoal para entender, elemento por elemento, como o projeto OrderFlow
funciona. Cada arquivo explica uma parte do projeto em nível iniciante, com analogias e
sem pressupor conhecimento prévio.

Diferente de [`docs/`](../docs/), que é documentação voltada a terceiros/portfólio, esta
pasta é para consulta própria durante o aprendizado.

## Índice

1. [Docker Compose](01-docker-compose.md) — os serviços que sobem juntos localmente
   (Postgres, LocalStack, auth, catalog, inventory, notification, order e gateway), como
   eles dependem uns dos outros e o healthcheck do LocalStack que só fica saudável com as
   filas e a tabela de notificações no formato atual
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
12. [Records e suas anotações](12-records-e-anotacoes.md) — o que é um `record` em Java,
    todas as anotações de validação/serialização usadas nos DTOs do projeto e os `records`
    que aparecem fora dos DTOs (`ReservationOutcome`, `CarrierAssignment`, `ClientProperties`)
13. [springdoc-openapi (Swagger UI)](13-springdoc-openapi.md) — o que cada serviço põe no
    seu spec OpenAPI no nível "contratos + erros" (`@Tag`, `@Operation`, `@ApiResponse`,
    `@Schema` com exemplos, o enum `OrderStatus` real), o `server` `/api` que faz o "Try it
    out" passar pelo Gateway, o `ErrorResponse` que existe só para a documentação, o login
    público e o JWKS fora do spec, e o `OpenApiDocsIT` que percorre o contrato inteiro
14. [Spring Retry (@Retryable, @Recover, @EnableRetry)](14-spring-retry.md) — como o
    `inventory-service` reexecuta automaticamente operações de estoque (REST e comandos da
    saga) que colidem por conflito de versão, por que a ordem entre o aspecto de retry e o
    de transação importa, o `@Recover` extra para a anomalia técnica e o atributo
    `recover = "..."` que escolhe o plano B pelo nome (WR-02)
15. [Pasta de testes e Testcontainers](15-testes.md) — a convenção `*Test.java` vs
    `*IT.java`, os testes unitários das regras centrais sem Docker, as dependências de teste
    de cada módulo, como o Testcontainers sobe Postgres e LocalStack reais, o módulo
    `e2e-tests` com dois serviços reais na mesma JVM, o `OrderStatusDiagramConsistencyTest`
    que confere o diagrama da documentação, o `OpenApiDocsIT` e a guarda contra teste
    desligado
16. [SQS, Outbox e mensageria entre serviços](16-sqs-outbox-mensageria.md) — as três filas
    e quem publica/consome cada uma, o problema de dual-write e como o Transactional Outbox o
    fecha (inclusive para o `STOCK_ADJUSTED`, desde o 05-04), o roteamento do relay por
    `eventType` (comandos da saga, respostas, `STOCK_ADJUSTED` e `ORDER_*`), o
    Correlation-ID como atributo da mensagem, o conversor sem `JavaType`, o limite de tempo
    do cliente SQS (WR-03), o limite de tamanho do corpo (WR-02) e o consumo idempotente
17. [DynamoDB](17-dynamodb.md) — o banco NoSQL do `notification-service`, a partição
    genérica `entityId` (produto ou pedido) e a sort key da tabela `notification-history`,
    por que o init hook recria a tabela com a chave antiga, `@DynamoDbBean`/Enhanced Client,
    a ordenação `occurredAt` → ciclo de vida → `sortKey`, e a comparação com o Postgres/JPA
18. [OIDC e a claim `iss`](18-oidc-claim-iss.md) — o que é a claim `iss` de um JWT, o que é
    OIDC e por que o projeto não usa descoberta automática, e a correção (T-03-02/WR-07)
    que passou a rejeitar tokens com emissor errado ou ausente nos quatro resource servers
    (catalog, inventory, notification, order)
19. [`@SqsListener` — como os serviços recebem mensagens](19-sqslistener-consumo.md) — o
    container ouvinte em segundo plano, long polling, por que o parâmetro é `String`, o
    atributo `correlationId` restaurado no MDC, como o retorno (ou exceção) decide se a
    mensagem é confirmada, o WARN sanitizado de evento `ORDER_*` descartado (WR-03), e os
    listeners da saga (`ReserveStock`/`ReleaseStock`/`ShipStock`, falha de negócio vs.
    técnica, DLQ, `ShipStock` inválido → DLQ (WR-01), `poll-timeout: 0s`)
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
    checagem de crédito, consulta com 404 para outras empresas e paginação com ordenação fixa),
    `OrderDecisionService` (aprovação/rejeição manual com trava + `refresh`),
    `OrderShipmentService` (expedição e entrega) e o `OrderTimelineEvents` que grava a linha
    do tempo na mesma transação, e por que o `@Transactional` exige classes separadas
23. [Anotações de repository](23-anotacoes-de-repository.md) — consultas derivadas pelo nome
    do método, `@Query` (JPQL vs. SQL nativo) e `@Param`, `@Modifying` e a memória do JPA,
    `@Lock` e os tipos de trava pessimista/otimista, e outras como `@Repository`,
    `@EntityGraph` (problema N+1), `@QueryHints` e `@Procedure`
24. [Pasta `scripts` — testes de fumaça e guardas](24-scripts-smoke.md) — o que é um smoke
    test e como ele difere dos testes Java, o "modo rigoroso" do Bash, como os scripts rodam
    `curl` dentro do container do Gateway, o que cada passo dos cinco smokes prova
    (notificação, pedidos e crédito, saga, ciclo de vida completo e Correlation-ID) e o que
    fazem as guardas `check-*.sh` e o `ci-summary.sh`
25. [Visão geral da arquitetura](25-visao-geral-da-arquitetura.md) — o mapa do projeto: o que
    cada um dos seis módulos faz, o que `BUYER` e `SELLER_ADMIN` podem fazer (inclusive
    expedir e entregar), quem chama quem (HTTP ou SQS), em que momento e por qual endpoint ou
    fila, a linha do tempo do pedido, o Correlation-ID e o Swagger no Gateway, e os estados do
    pedido. Tem [versão com diagramas](https://claude.ai/code/artifact/28ba7064-135a-4a8e-bb53-df77ba32c987)
    (desenhada antes das Fases 6 e 7)
26. [A saga de reserva de estoque](26-saga-de-reserva-de-estoque.md) — como um pedido
    aprovado vira `RESERVING` e termina `CONFIRMED` ou `CANCELLED`: `ReservationSagaStarter`,
    a tabela `outbox_event` e o relay com `FOR UPDATE SKIP LOCKED`, as filas com DLQ, a
    reserva tudo-ou-nada do `inventory-service`, a idempotência pelo estado do pedido, a
    compensação com `ReleaseStock`, o job de tempo limite (`SagaTimeoutJob`), a devolução
    idempotente (`releaseAll`) e a lápide que resolve a corrida numa fila SQS sem ordem
27. [Correlation-ID e MDC](27-correlation-id-e-mdc.md) — o identificador que nasce no
    Gateway, por que o MDC do SLF4J é por thread e precisa ser limpo, por que o valor do
    cliente é validado antes de entrar no log, como cada um dos seis módulos recebe e repassa
    o ID (filtro HTTP, `RestClient`, coluna do outbox, atributo SQS, listener que restaura o
    MDC) e como o `smoke-correlation-id.sh` prova o mesmo ID nos seis logs
28. [OpenAPI agregado no Gateway](28-openapi-agregado-no-gateway.md) — por que o avaliador
    abre uma Swagger UI só, como o dropdown busca cada spec em `/docs/<svc>/v3/api-docs` na
    mesma origem, por que esse prefixo não é `/api`, e por que o "Try it out" passa pelo
    Gateway com `server` `/api`
29. [GitHub Actions e a integração contínua](29-github-actions-ci.md) — o que é CI e como um
    workflow se organiza (gatilhos, jobs, matriz, `needs`, secrets), por que os módulos com
    LocalStack rodam um de cada vez (sessão única do token Hobby), por que o CI falha de
    forma visível sem o token em vez de pular testes, por que PR de fork não recebe secret e
    onde ver os relatórios de teste (artifact e resumo do run)
30. [Ciclo de vida do pedido: expedição e entrega](30-ciclo-de-vida-expedicao-e-entrega.md)
    — a tabela única de 9 transições em `OrderStatus` e o único ponto que muda o status
    (`moveTo`), o 409 `invalid_order_transition`, a transportadora simulada e o código de
    rastreio S10 determinístico (SHA-256 do pedido), os endpoints `ship`/`deliver`, a baixa
    física do estoque pelo comando `ShipStock` (uma vez por reserva) e os três testes que
    travam tabela, API e diagrama da documentação
31. [A linha do tempo do pedido](31-linha-do-tempo-do-pedido.md) — como cada transição do
    pedido grava um dos oito eventos `ORDER_*` no outbox na mesma transação, como o relay os
    leva à `notification-events-queue`, como o `notification-service` valida cada tipo e monta
    a frase no servidor, a ordenação por ciclo de vida e o `GET /notifications/orders/{orderId}`
    com a regra `SELLER_ADMIN`/`BUYER` e o 404 idêntico que impede enumerar pedidos alheios
32. [ADRs — o diário das decisões de arquitetura](32-adrs.md) — o que é um ADR, o formato
    MADR em português usado em `docs/adr/`, por que toda decisão precisa de uma alternativa
    rejeitada, os 11 ADRs do projeto em uma linha cada e o que o `check-adrs.sh` confere
33. [A matriz regra → teste](33-matriz-regra-teste.md) — por que "sem lacunas" é medido por
    uma tabela regra → teste e não por porcentagem de linhas, o formato do `07-COVERAGE.md`,
    o que significa `LACUNA`, como o `check-coverage-matrix.sh` confere a matriz (modo
    estrito e `--report`) e roda no CI, e quais lacunas foram fechadas com testes unitários
    novos
34. [Lock otimista, reexecução automática e relay](34-lock-otimista-e-relay.md) — explicação
    para iniciantes, com analogias (a planilha disputada por dois vendedores, a caixa de
    saída e o carteiro), de como o campo `@Version` impede que uma gravação apague a outra,
    como o `@Retryable` relê e tenta de novo, e como o relay leva os avisos do outbox até a
    fila SQS sem perder nenhum
35. [Variáveis de ambiente e valor padrão](35-variaveis-de-ambiente-e-valor-padrao.md) — o
    que significa `${NOME:valor}` no `application.yml`, usando o `jwk-set-uri` como exemplo:
    por que o endereço vem de uma variável de ambiente com `localhost` como plano B, por que
    `localhost` aponta para o próprio container no Docker Compose, e como o *relaxed binding*
    transforma `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` na propriedade do Spring
36. [BOM, trem de releases e outros termos de versão](36-bom-trem-de-releases-e-outros-termos.md)
    — explicação para iniciantes, com analogias, de BOM (o kit de peças testadas juntas),
    bleeding-edge, trem de releases do Spring Cloud ("Northfields"), churn de versão e service
    discovery (a recepção do hotel), e por que o projeto dispensa Eureka/Consul
