# Estudos do OrderFlow

Material de estudo em português para entender, peça por peça, como o OrderFlow funciona.
Cada nota explica uma parte do projeto em nível iniciante, com analogias e sem pressupor
conhecimento prévio, e depois mostra como ela aparece no código real, com caminhos de
arquivo e trechos copiados dos fontes.

Diferente de [`docs/`](../docs/), que é documentação para terceiros e avaliadores, esta
pasta é para consulta própria durante o aprendizado.

## Para quem é e como usar

- Para quem está aprendendo Java/Spring, microsserviços, mensageria, bancos SQL e NoSQL,
  testes e CI e quer um projeto completo como referência.
- Para o próprio autor, como revisão do que foi construído e do porquê de cada decisão.
- Leia com o código aberto ao lado. Quando a nota citar
  `order-service/src/main/java/com/orderflow/order/order/OrderService.java`, abra o arquivo
  e confira.
- A maioria das notas segue o mesmo caminho: o assunto **em uma frase**, o **problema** que
  ele resolve, o assunto **no código do projeto** (seções numeradas) e um fechamento
  **com uma analogia** ou um resumo "em uma frase cada".

## Estado do projeto

As Fases 1 a 6 estão concluídas e a Fase 7 está em verificação:

- **Fase 1:** tudo sobe com o `docker compose`, e um usuário se autentica pelo Gateway com
  JWT (papel e empresa).
- **Fase 2:** catálogo de produtos e estoque com reserva protegida contra concorrência.
- **Fase 3:** primeiro evento no SQS virando histórico de notificação no DynamoDB.
- **Fase 4:** criação de pedidos com aprovação por limite de crédito.
- **Fase 5:** a saga de reserva de estoque com Transactional Outbox e compensação.
- **Fase 6:** expedição, entrega e a linha do tempo completa do pedido.
- **Fase 7:** OpenAPI, Correlation-ID nos logs, testes completos, CI e ADRs.

A posição atual fica em [STATE.md](../.planning/STATE.md) e o plano completo em
[ROADMAP.md](../.planning/ROADMAP.md).

## Comece por aqui

| Nº | Nota | O que você aprende |
| --- | --- | --- |
| 25 | [Visão geral da arquitetura](25-visao-geral-da-arquitetura.md) | O mapa do projeto: os seis módulos, o que `BUYER` e `SELLER_ADMIN` podem fazer, quem chama quem (HTTP ou SQS), o fluxo e os estados do pedido. Tem [versão com diagramas](https://claude.ai/code/artifact/28ba7064-135a-4a8e-bb53-df77ba32c987) (desenhada antes das Fases 6 e 7) |
| 37 | [Design patterns do projeto](37-design-patterns-do-projeto.md) | Os padrões de arquitetura, mensageria, persistência e código: onde, em que fase e por que cada um entrou, e os padrões rejeitados de propósito |

## Notas por tema

### Infraestrutura, build e versões

| Nº | Nota | O que você aprende |
| --- | --- | --- |
| 01 | [Docker Compose](01-docker-compose.md) | Os 8 serviços que sobem juntos, as dependências entre eles e o healthcheck do LocalStack |
| 02 | [Spring Boot e Spring Cloud](02-spring-boot-e-spring-cloud.md) | Quais versões o projeto usa e por que essas escolhas |
| 03 | [Dockerfile](03-dockerfile.md) | Build multi-stage, JDK × JRE, cache de camadas, fat jar, `HEALTHCHECK` e usuário não-root |
| 04 | [Maven Wrapper](04-maven-wrapper.md) | Como `mvnw`/`mvnw.cmd` compilam o projeto sem Maven instalado |
| 07 | [Pasta `target`](07-pasta-target.md) | A saída do build, por que não é versionada e por que pode ser apagada |
| 35 | [Variáveis de ambiente e valor padrão](35-variaveis-de-ambiente-e-valor-padrao.md) | O `${NOME:valor}` do `application.yml`, por que `localhost` não serve dentro do container e o *relaxed binding* |
| 36 | [BOM, trem de releases e outros termos](36-bom-trem-de-releases-e-outros-termos.md) | BOM, bleeding-edge, trem de releases ("Northfields"), churn e service discovery |

### Segurança e multiempresa

| Nº | Nota | O que você aprende |
| --- | --- | --- |
| 05 | [SecurityConfig](05-security-config.md) | Como o `auth-service` guarda senhas, verifica login e decide quem acessa o quê |
| 06 | [JWKS](06-jwks.md) | Como os outros serviços confirmam que um token é autêntico sem perguntar ao `auth-service` toda vez |
| 18 | [OIDC e a claim `iss`](18-oidc-claim-iss.md) | O que é a claim `iss`, por que o projeto não usa descoberta OIDC e a correção que rejeita emissor errado ou ausente |
| 10 | [`application.yml` do Gateway](10-gateway-application-yml.md) | As rotas estáticas para cada serviço e o Gateway sem Spring Security |
| 11 | [CompanyGuard](11-companyguard.md) | Como uma empresa é impedida de ver ou alterar dados de outra |

### API, validação e erros

| Nº | Nota | O que você aprende |
| --- | --- | --- |
| 12 | [Records e suas anotações](12-records-e-anotacoes.md) | O que é um `record`, as anotações de validação/serialização dos DTOs e os records fora dos DTOs |
| 09 | [GlobalExceptionHandler](09-global-exception-handler.md) | Como as respostas de erro são padronizadas e protegidas, e o handler de cada serviço |
| 20 | [`@ConfigurationProperties`](20-configuration-properties.md) | O `ClientProperties` do `order-service`, `URI`/`Duration`, `@Validated` (falhar cedo) e o `RestClient` |
| 13 | [springdoc-openapi (Swagger UI)](13-springdoc-openapi.md) | O spec de cada serviço no nível "contratos + erros", o `server` `/api`, o `ErrorResponse` e o `OpenApiDocsIT` |
| 28 | [OpenAPI agregado no Gateway](28-openapi-agregado-no-gateway.md) | Uma Swagger UI só, o dropdown em `/docs/<svc>/v3/api-docs` e por que o "Try it out" passa pelo Gateway |

### Persistência e concorrência

| Nº | Nota | O que você aprende |
| --- | --- | --- |
| 08 | [Flyway e migrations](08-flyway-migrations.md) | Como o banco evolui de forma versionada e a regra de ouro das migrations aplicadas |
| 23 | [Anotações de repository](23-anotacoes-de-repository.md) | Consultas derivadas, `@Query`, `@Param`, `@Modifying`, `@Lock`, `@EntityGraph` e outras |
| 34 | [Lock otimista, reexecução e relay](34-lock-otimista-e-relay.md) | Introdução com analogias a `@Version`, `@Retryable` e ao relay do outbox |
| 14 | [Spring Retry](14-spring-retry.md) | `@Retryable`, `@Recover` e `@EnableRetry` no `inventory-service`, a ordem entre retry e transação e o `recover = "..."` |
| 21 | [Crédito e trava por empresa](21-credito-e-trava-por-empresa.md) | A regra `CreditPolicy`, a corrida entre pedidos simultâneos e a trava com `SELECT ... FOR UPDATE` e `MANDATORY` |
| 17 | [DynamoDB](17-dynamodb.md) | O banco NoSQL do `notification-service`, as chaves da tabela, `@DynamoDbBean` e a comparação com Postgres/JPA |

### Pedido e saga

| Nº | Nota | O que você aprende |
| --- | --- | --- |
| 22 | [Os services de pedido](22-services-de-pedido.md) | Criação, gravação com crédito, decisão manual, expedição e entrega, e por que o `@Transactional` exige classes separadas |
| 16 | [SQS, Outbox e mensageria](16-sqs-outbox-mensageria.md) | As filas, o problema de dual-write, o Transactional Outbox, o roteamento do relay e o consumo idempotente |
| 19 | [`@SqsListener`](19-sqslistener-consumo.md) | Como os serviços recebem mensagens, o Correlation-ID restaurado no MDC, confirmação × reentrega e DLQ |
| 26 | [A saga de reserva de estoque](26-saga-de-reserva-de-estoque.md) | `RESERVING` → `CONFIRMED`/`CANCELLED`, outbox com `SKIP LOCKED`, compensação, tempo limite e a lápide |
| 30 | [Expedição e entrega](30-ciclo-de-vida-expedicao-e-entrega.md) | A tabela de transições, `moveTo`, a transportadora simulada, o rastreio S10 e a baixa física com `ShipStock` |
| 31 | [A linha do tempo do pedido](31-linha-do-tempo-do-pedido.md) | Os oito eventos `ORDER_*`, como viram frases no `notification-service` e quem pode ver o histórico |

### Observabilidade, testes e entrega

| Nº | Nota | O que você aprende |
| --- | --- | --- |
| 27 | [Correlation-ID e MDC](27-correlation-id-e-mdc.md) | O ID que nasce no Gateway e atravessa HTTP, outbox e SQS até os seis logs |
| 15 | [Testes e Testcontainers](15-testes.md) | `*Test` × `*IT`, unitários sem Docker, Postgres e LocalStack reais, o módulo `e2e-tests` e a guarda contra teste desligado |
| 33 | [A matriz regra → teste](33-matriz-regra-teste.md) | Por que "sem lacunas" é medido por regra e não por porcentagem, e o `check-coverage-matrix.sh` |
| 24 | [Scripts de smoke e guardas](24-scripts-smoke.md) | O que é um smoke test, o que cada um dos cinco smokes prova e o que fazem as guardas `check-*.sh` |
| 29 | [GitHub Actions e CI](29-github-actions-ci.md) | Gatilhos, jobs e matriz, os módulos com LocalStack em série, falha visível sem token e onde ver relatórios |
| 32 | [ADRs](32-adrs.md) | O que é um ADR, o formato MADR em português, os 11 ADRs e o `check-adrs.sh` |

## Trilhas de leitura

- **Estou começando:** 25 → 37, antes de qualquer trilha.
- **Quero subir o projeto:** 01 → 02 → 36 → 03 → 04 → 07 → 35.
- **Quero entender a segurança:** 05 → 06 → 18 → 10 → 11.
- **Quero entender a API:** 12 → 09 → 20 → 13 → 28.
- **Quero entender o pedido e a saga (Core Value):** 22 → 21 → 34 → 14 → 16 → 19 → 26 → 30 → 31.
- **Quero entender a persistência:** 08 → 23 → 34 → 14 → 21 → 17.
- **Quero entender a entrega para avaliadores:** 27 → 15 → 33 → 24 → 29 → 32.

## Convenções deste material

- Caminhos em crase são relativos à raiz do repositório, por exemplo
  `order-service/pom.xml`. Links clicáveis são relativos a esta pasta (`../docs/`).
- Trechos de código são copiados dos arquivos. Partes omitidas aparecem como `// ...` ou
  `# ...`.
- Códigos como `D-90`, `WR-02` ou `T-03-02` são as decisões e correções registradas nos
  planos em `.planning/phases/`. "Plano 07-11" é o plano 11 da Fase 7.
- A numeração das notas segue a ordem em que foram escritas, não a ordem de leitura. Use
  as tabelas e trilhas acima para escolher por onde ir.

## Glossário rápido

- **Microsserviço:** aplicação pequena e independente, com seu próprio banco, que conversa
  com as outras pela rede. O OrderFlow tem cinco, mais o Gateway.
- **API Gateway:** a porta de entrada única que encaminha cada requisição para o serviço
  certo.
- **JWT:** token assinado que diz quem é o usuário, seu papel e sua empresa.
- **JWKS:** a lista pública de chaves usada para conferir a assinatura de um JWT.
- **`BUYER` / `SELLER_ADMIN`:** o comprador, que cria pedidos, e o administrador da
  empresa vendedora, que mantém catálogo e estoque e aprova, expede e entrega pedidos.
- **Bean:** objeto criado e guardado pelo Spring e entregue (injetado) a quem o pede.
- **Record:** tipo do Java para carregar dados imutáveis, com construtor, leitura,
  `equals` e `hashCode` gerados.
- **DTO:** objeto que só carrega dados de entrada ou saída da API.
- **Transação:** pacote de operações no banco que vale "tudo ou nada".
- **Migration:** script versionado que muda a estrutura do banco; aplicada uma vez só.
- **Lock otimista / pessimista:** detectar o conflito na hora de gravar (`@Version`) /
  trancar a linha antes de ler (`FOR UPDATE`).
- **SQS / fila:** serviço de filas da AWS; um serviço deixa a mensagem e outro a busca
  depois.
- **DLQ:** fila para onde vão as mensagens que falharam várias vezes, para alguém olhar.
- **Dual-write:** gravar no banco e publicar na fila como duas operações separadas, que
  podem dar certo uma e errado a outra.
- **Transactional Outbox / relay:** gravar o evento numa tabela na mesma transação do dado
  / o processo que leva esses eventos da tabela até a fila.
- **Saga / compensação:** uma operação dividida em passos entre serviços / o passo que
  desfaz o anterior quando algo dá errado.
- **Idempotente:** repetir a operação não muda o resultado.
- **Correlation-ID / MDC:** o número de protocolo que acompanha a requisição em todos os
  logs / onde o SLF4J guarda esse número para a thread atual.
- **LocalStack:** simulador local da AWS (aqui, SQS e DynamoDB).
- **DynamoDB:** banco NoSQL da AWS, consultado só pela chave definida na tabela.
- **IT:** teste de integração (classes `*IT`), que sobe a aplicação contra serviços reais.
- **Testcontainers:** biblioteca que sobe containers Docker de verdade durante os testes.
- **Smoke test:** teste rápido de ponta a ponta com o sistema inteiro ligado.
- **CI:** compilar e testar automaticamente a cada mudança enviada ao repositório.
- **ADR:** registro curto de uma decisão de arquitetura, com as alternativas rejeitadas.
- **OpenAPI / Swagger UI:** o contrato da API em JSON / a página que o mostra e permite
  testar.
