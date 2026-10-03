# A pasta de testes: estrutura, convenções e Testcontainers

Arquivos:

- `auth-service/src/test/java/`, `catalog-service/src/test/java/`,
  `inventory-service/src/test/java/`, `notification-service/src/test/java/`,
  `order-service/src/test/java/` e `gateway/src/test/java/`
- `e2e-tests/` — o módulo Maven só de testes de ponta a ponta (planos 05-05 e 06-06)
- `order-service/src/test/java/com/orderflow/order/order/OrderStatusDiagramConsistencyTest.java`
- `OpenApiDocsIT.java` de cada serviço
- `scripts/check-no-skipped-tests.sh`
- os `pom.xml` de cada módulo

## O que é a pasta de testes, em nível bem básico

Todo projeto Java/Maven separa dois tipos de código: o código que **roda de
verdade em produção** (`src/main/java`) e o código que **existe só para testar**
esse primeiro código (`src/test/java`). São duas árvores de pastas irmãs, com a
mesma estrutura de pacotes, mas com propósitos opostos: uma vira o `.jar` que roda
no servidor, a outra nunca vai para produção — ela só roda na sua máquina e no CI
(ver [29-github-actions-ci.md](29-github-actions-ci.md)) para provar que a primeira
funciona.

No OrderFlow, cada um dos seis módulos tem a sua própria pasta de testes, e existe
um sétimo módulo que **só** tem testes:

```
auth-service/src/test/java/com/orderflow/auth/
catalog-service/src/test/java/com/orderflow/catalog/
inventory-service/src/test/java/com/orderflow/inventory/
notification-service/src/test/java/com/orderflow/notification/
order-service/src/test/java/com/orderflow/order/
gateway/src/test/java/com/orderflow/gateway/
e2e-tests/src/test/java/com/orderflow/e2e/
```

O `gateway` não tem regra de negócio, mas tem regras de **roteamento** e o filtro
do Correlation-ID. Desde o plano 07-10 ele tem `GatewayRoutesTest` (unitário, lê a
tabela de rotas) e `GatewayRoutingIT` (sobe o Gateway de verdade contra cinco
servidores HTTP falsos, um por serviço). Ver
[10-gateway-application-yml.md](10-gateway-application-yml.md).

## A convenção de nomes: `*Test.java` vs `*IT.java`

Isso é a coisa mais importante para entender a estrutura. O projeto segue uma
convenção clássica do Maven:

- **`*Test.java`** = teste **unitário**. Testa uma peça pequena de código
  isolada, sem subir o Spring, sem banco de dados real, rápido de rodar.
- **`*IT.java`** ("Integration Test") = teste **de integração**. Sobe a
  aplicação Spring inteira, conecta num banco de dados real (via Docker), testa
  o sistema "de fora para dentro" através de requisições HTTP reais.

Essa diferença de sufixo não é só estética — dois plugins Maven diferentes
procuram por esses sufixos:

- **Surefire** (`mvn test`) executa só os `*Test.java`. Rápido, não precisa de
  Docker.
- **Failsafe** (`mvn verify`) executa os `*IT.java`. Mais lento, **exige Docker
  rodando**.

Isso já foi mencionado de passagem em [07-pasta-target.md](07-pasta-target.md),
que fala dos relatórios `surefire-reports/` e `failsafe-reports/` gerados por
cada um.

## Testes unitários das regras centrais, sem Docker

Durante as Fases 1 a 6 a maioria dos testes era `*IT.java`. Na Fase 7, a matriz
regra → teste (ver [33-matriz-regra-teste.md](33-matriz-regra-teste.md)) mostrou
regras importantes provadas **só** por teste de integração. O problema: um IT é
lento, precisa de Docker e, quando falha, você não sabe de cara se foi a regra ou
o container. Um unitário é o teste de bancada: a peça fora do carro, presa numa
morsa.

Hoje cada regra central tem as duas camadas. Os unitários não sobem o Spring nem
nenhum container — usam JUnit 5, AssertJ e, quando a classe tem colaboradores,
Mockito. Os principais, por módulo:

- **auth-service** — `TokenServiceTest` (claims do JWT, com `NimbusJwtEncoder`
  real), `CompanyGuardTest` (isolamento por empresa, sem contexto Spring),
  `CompanyServiceTest`, `AuthControllerTest` (o 401 do login é igual para e-mail
  desconhecido e senha errada) e o antigo `SeedPasswordHashTest`.
- **catalog-service** — `ProductServiceTest` (SKU único, produto nasce `ACTIVE`,
  comprador só vê ativos).
- **inventory-service** — `InventoryTest` e `StockReservationTest` (contas de
  físico, reservado e disponível; lápide), `InventoryServiceTest` (reserva
  tudo-ou-nada, idempotência, baixa pelo livro), `SagaCommandParserTest`,
  `ReservationCommandListenerTest`, `OutboxWriterTest` e `OutboxRelayTest`.
- **notification-service** — `NotificationServiceTest` e
  `NotificationEventListenerTest`.
- **order-service** — `OrderDomainTest` (limite de crédito e transições, sem I/O),
  `OrderServiceTest` (a trava da empresa é pega **antes** de ler a exposição),
  `OrderDecisionServiceTest`, `OrderShipmentServiceTest`, `OrderSagaServiceTest`,
  `OrderStatusTransitionsTest`, `CancellationReasonsTest`, `SagaTimeoutJobTest`,
  `SagaEventParserTest`, `SimulatedCarrierGatewayTest`, `TrackingCodesTest`,
  `OutboxWriterTest`, `OutboxRelayTest`, `OrderCreationServiceTest` e
  `DownstreamClientsTest` (os clientes HTTP falham de forma segura contra uma
  porta fechada e reenviam o Correlation-ID).
- **gateway** — `GatewayRoutesTest` e `CorrelationIdFilterTest`.

Cada um dos seis módulos tem o seu `CorrelationIdFilterTest` (ver
[27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md)).

## Quais dependências de teste o projeto usa

Olhando o `pom.xml` de `auth-service` e `catalog-service`, os dois declaram
exatamente o mesmo bloco (os outros módulos variam em cima dele — ver a tabela
logo abaixo):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

Repare o `<scope>test</scope>` em cada uma — isso diz ao Maven "essa biblioteca
só existe durante os testes, não entra no `.jar` final que vai para produção". É
a mesma ideia de separar `src/main` e `src/test`, só que aplicada às
dependências.

Os outros módulos partem dessa base, mas com diferenças:

| Módulo | Dependências de teste |
| --- | --- |
| auth, catalog | o bloco acima (starter-test, spring-boot-testcontainers, junit-jupiter, postgresql) |
| inventory | o bloco acima **+** `org.testcontainers:localstack` e `org.awaitility:awaitility` |
| notification | `spring-boot-starter-test`, `junit-jupiter`, `localstack`, `awaitility` — **sem** `postgresql` (o serviço não usa Postgres) e **sem** `spring-boot-testcontainers` |
| order | `spring-boot-starter-test`, `junit-jupiter`, `postgresql`, `localstack`, `awaitility` — **sem** `spring-boot-testcontainers`: a conexão com o banco e com o LocalStack é registrada à mão com `@DynamicPropertySource` na classe `OrderTestInfrastructure` |
| gateway | só `spring-boot-starter-test` — não há banco nem fila; os "serviços" do outro lado são servidores HTTP da própria JDK |
| e2e-tests | `order-service` e `inventory-service` (o código dos dois, como dependência de teste), `spring-boot-starter-test`, `junit-jupiter`, `postgresql`, `localstack`, `awaitility` |

O que cada uma faz:

- **`spring-boot-starter-test`** — é um "combo", não uma biblioteca única. Ele
  traz junto JUnit 5 (o framework que executa os testes e fornece a anotação
  `@Test`), AssertJ (uma forma mais legível de escrever verificações, tipo
  `assertThat(resultado).isEqualTo(...)`), Mockito (biblioteca para simular
  dependências — usada nos testes unitários e também via `@MockitoBean` no
  `NotificationStoreUnavailableIT`, que troca um bean real por um simulado para
  forçar uma falha do DynamoDB) e MockMvc (simula requisições HTTP sem precisar de um
  servidor real rodando numa porta).
- **`spring-boot-testcontainers`** — integra o Spring com a biblioteca
  Testcontainers (explicada abaixo), fornecendo a anotação `@ServiceConnection` —
  usada só no auth, catalog e inventory.
- **`org.testcontainers:junit-jupiter`** e **`org.testcontainers:postgresql`** —
  o motor do Testcontainers e o módulo específico para subir um Postgres.
- **`org.testcontainers:localstack`** — módulo que sobe um LocalStack real (o
  "AWS de mentira" local) com SQS e DynamoDB, usado nos testes do inventory, do
  notification, do order e do e2e para publicar e consumir mensagens de verdade.
- **`org.awaitility:awaitility`** — ajuda a testar coisas **assíncronas**. Quando
  uma mensagem vai para uma fila, o efeito não acontece na hora; o Awaitility
  deixa escrever "espere até X acontecer (com um tempo máximo)", por exemplo, até
  a mensagem chegar na fila ou até o histórico aparecer no DynamoDB.

Nenhuma versão explícita aparece nesses artefatos — todas vêm do
`dependencyManagement` (BOM) do `pom.xml` raiz do projeto. A versão do
Testcontainers vem do BOM do Spring Boot (1.21.4, segundo o plano 07-11).

## O que é Testcontainers, e por que ele existe

Aqui está a peça central da estratégia de testes do projeto. Pensa assim: como
você testa um código que salva dados num banco PostgreSQL, sem esse teste ficar
"mentindo" (por exemplo, usando um banco em memória com comportamento diferente
do Postgres real)?

**Testcontainers** é uma biblioteca Java que, durante a execução dos testes, sobe
um **container Docker real** — nesse projeto, um Postgres real, versão
`postgres:16.15`, idêntica à usada em produção — só para aquela suíte de testes,
e derruba o container no final. Isso é o que torna os `*IT.java` "testes de
integração de verdade": eles não fingem que há um banco, eles conversam com um
Postgres de verdade rodando num container Docker, só que descartável.

Além do Postgres, os testes do inventory, do notification, do order e do
`e2e-tests` sobem também um container `localstack/localstack:2026.08.3` — e, como
explicado no `CLAUDE.md`, essa imagem exige a variável `LOCALSTACK_AUTH_TOKEN`
(lida do ambiente ou do arquivo `.env`); sem ela, esses testes nem começam.

É por isso que rodar `./mvnw verify` **exige Docker instalado e rodando na sua
máquina** — sem Docker, o Testcontainers não tem onde subir o container, e o
teste falha antes mesmo de começar.

Cada serviço tem uma classe `AbstractIntegrationTest.java` que centraliza esse
setup, e quase todo `*IT.java` do serviço estende ela. As exceções são os testes
de concorrência com socket real (`StockReservationConcurrencyIT`,
`CreditLimitBoundaryConcurrencyIT` e `OrderDecisionConcurrencyIT`), que montam o
próprio setup. A versão do auth-service (resumida, sem o comentário):

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    @ServiceConnection // conecta o Spring Boot automaticamente a esse container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    static {
        postgres.start();
    }

    @Autowired
    protected MockMvc mockMvc;
}
```

O conteúdo dessa classe muda de serviço para serviço:

- **auth e catalog** — só o Postgres, com `@ServiceConnection` (como acima).
- **inventory** — Postgres com `@ServiceConnection` **+** LocalStack, cujas
  propriedades são registradas à mão com `@DynamicPropertySource`.
- **notification** — só o LocalStack (não há Postgres nesse serviço).
- **order** — Postgres **+** LocalStack **+** um servidor HTTP falso que faz o
  papel dos vizinhos (auth e catalog), tudo registrado à mão via
  `OrderTestInfrastructure`.

Um detalhe interessante documentado no próprio comentário dessa classe: o
container é iniciado manualmente num bloco `static { postgres.start(); }`, **em
vez de** usar a anotação padrão `@Testcontainers`/`@Container` do JUnit 5. O
motivo é que essa anotação padrão derrubaria o container ao final da **primeira**
classe de teste — mas como várias classes `*IT.java` estendem essa mesma base, o
objetivo aqui é **compartilhar um único container Postgres** entre todas elas na
mesma execução, para não pagar o custo de subir um container novo (o que leva
alguns segundos) a cada classe de teste.

## Como tudo se encaixa na prática

Um exemplo real do projeto, `CompanyControllerIT.java` (a maior suíte do
auth-service): ele estende `AbstractIntegrationTest`, então já ganha o Postgres
real e o Spring rodando. Cada teste então usa `MockMvc` para simular uma
requisição HTTP real (por exemplo, `POST /companies`), e depois confere tanto a
resposta HTTP quanto o estado que ficou gravado no banco via os `Repository` do
projeto. Ele testa coisas como: uma empresa BUYER não pode ver dados de outra
empresa (isolamento entre tenants — ver [11-companyguard.md](11-companyguard.md)),
token expirado é rejeitado, senha nunca aparece na resposta de erro.

Outro exemplo, `StockReservationConcurrencyIT.java` (inventory-service), é ainda
mais sofisticado: ele dispara **várias requisições HTTP simultâneas de verdade**
(usando `HttpClient` da própria linguagem Java, sem MockMvc de propósito, a partir
de threads virtuais) contra o mesmo produto, todas liberadas ao mesmo tempo por um
`CyclicBarrier` (uma "barreira" que trava várias threads até todas chegarem nela,
aí libera todas juntas), pra provar que o sistema nunca deixa reservar mais estoque
do que existe — mesmo sob concorrência real. Esse é exatamente o cenário de
conflito de versão que motivou o retry explicado em
[14-spring-retry.md](14-spring-retry.md).

O order-service repete a mesma receita (`HttpClient` + `CyclicBarrier` + threads
virtuais) nos seus dois testes de concorrência, `CreditLimitBoundaryConcurrencyIT`
e `OrderDecisionConcurrencyIT` — só que empacotada numa classe auxiliar,
`support/ConcurrentRequests`, para não repetir o código nos dois.

## O módulo `e2e-tests`: dois serviços reais no mesmo teste

Até a Fase 5, cada serviço era testado sozinho: o order-service mandava o comando
`ReserveStock` para a fila, e o teste conferia que ele chegou lá. Mas ninguém
provava que o inventory-service **de verdade** entendia aquele comando e
respondia algo que o order-service **de verdade** sabia ler. É como testar a
tomada e o plugue separadamente, sem nunca encaixar um no outro.

O módulo `e2e-tests` (plano 05-05) encaixa. Ele não tem `src/main`, só
`src/test`, e declara `order-service` e `inventory-service` como dependências de
teste. A classe `E2eInfrastructure` sobe **as duas aplicações Spring Boot reais
dentro da mesma JVM**, cada uma com o seu contexto, usando
`SpringApplicationBuilder` (e não `@SpringBootTest`, que só sabe subir uma
aplicação por teste):

```java
SpringApplicationBuilder builder = new SpringApplicationBuilder(InventoryServiceApplication.class)
...
return builder.run(
        "--spring.config.location=file:../inventory-service/src/main/resources/application.yml",
        "--spring.config.additional-location=classpath:/e2e/inventory-service-overrides.yml",
        ...
```

Os dois serviços dividem um Postgres (`postgres:16.15`, schemas `order` e
`inventory`, como no `docker-compose.yml`) e um LocalStack, ambos via
Testcontainers. Auth e catalog continuam simulados por um `DownstreamStubServer`.

Dois cuidados que o código resolve:

- **Qual `application.yml`?** Com os dois jars no classpath, `classpath:application.yml`
  é ambíguo. Por isso cada contexto lê o arquivo **real** do seu serviço pelo
  caminho no disco (`file:../...`), mais um override pequeno
  (`e2e/order-service-overrides.yml`, `e2e/inventory-service-overrides.yml`) que
  só troca o necessário — por exemplo `server.port: 0` (qualquer porta livre) e
  `spring.jmx.enabled: false` (duas aplicações no mesmo processo colidiriam nos
  nomes de MBean).
- **Quais migrações?** O Flyway padrão (`classpath:db/migration`) veria as
  migrações dos dois serviços e quebraria na versão 1 duplicada. O override aponta
  `spring.flyway.locations` para a pasta do próprio serviço, por exemplo
  `filesystem:../order-service/src/main/resources/db/migration`.

As suítes do módulo:

- `E2eContextsSmokeIT` — os dois contextos sobem, cada schema com as suas próprias
  migrações.
- `OrderReservationSagaE2EIT` — a saga de ponta a ponta: sucesso termina
  `CONFIRMED` com a reserva no inventory; estoque insuficiente ou produto sem linha
  de estoque termina `CANCELLED` com o código certo; reenviar o mesmo
  `ReserveStock` depois de `CONFIRMED` não reserva de novo.
- `OrderShipmentE2EIT` (plano 06-06) — expedir baixa físico e reservado no
  inventory uma vez só; entregar não mexe no estoque; pedido confirmado recebe
  transportadora e rastreio no formato certo.
- `CorrelationIdE2EIT` (plano 07-04) — o mesmo Correlation-ID aparece no listener do
  inventory, no listener do order e na linha do outbox do inventory.

O notification-service fica **fora** do E2E por custo (D-86): a junção dos três é
provada pelos smokes na stack real (ver [24-scripts-smoke.md](24-scripts-smoke.md)).

## `OrderStatusDiagramConsistencyTest`: o teste que lê a documentação

O `README.md` e o `docs/VISAO-GERAL.md` têm um diagrama Mermaid
(`stateDiagram-v2`) com os estados do pedido. O código tem a tabela
`OrderStatus.transitions()`. Documentação desenhada à mão envelhece: alguém cria
uma transição nova no código e esquece de redesenhar a seta.

Este teste unitário (plano 06-07) abre os dois arquivos Markdown, extrai cada
bloco `stateDiagram-v2`, lê as arestas `A --> B` e compara com a tabela:

```java
@ParameterizedTest(name = "{0}")
@ValueSource(strings = {"../README.md", "../docs/VISAO-GERAL.md"})
void everyStateDiagramInTheDocumentMatchesTheTransitionTableExactly(String document) throws IOException {
```

Aresta a mais, aresta a menos, estado que não existe em `OrderStatus` ou linha
fora do formato quebram o build. As setas de início e fim (`[*]`) são ignoradas.

Um detalhe bom: o arquivo também testa o **próprio comparador**.
`anExtraEdgeIsReported`, `aMissingEdgeIsReported` e `aNonexistentStateIsReported`
montam diagramas errados de propósito e conferem que o erro é acusado. Um
comparador que sempre responde "igual" passaria em tudo sem provar nada — esses
casos garantem que ele tem dentes.

O teste prova a metade "diagrama = tabela". A outra metade, "API = tabela", é do
`OrderLifecycleTransitionsIT`. Juntos, provam que o diagrama corresponde ao que a
API faz de verdade.

## `OpenApiDocsIT`: o teste do contrato da documentação

Cada um dos cinco serviços tem um `OpenApiDocsIT`. Ele pede o spec em
`/v3/api-docs` e percorre **todas** as operações exigindo resumo, tag, resposta
`401` e `ErrorResponse` em todo erro 4xx/5xx; confere o `server` `/api`; e, no
order-service, compara o enum do spec com `OrderStatus.values()`. Detalhes em
[13-springdoc-openapi.md](13-springdoc-openapi.md).

A ideia é a mesma do teste do diagrama: **documentação que tem um teste não
consegue mentir em silêncio**.

## `check-no-skipped-tests.sh`: nenhum teste desligado

Um teste com `@Disabled` não falha — ele simplesmente não roda, e o relatório fica
verde. É o alarme de incêndio sem pilha. O script
`scripts/check-no-skipped-tests.sh` procura, em toda pasta `*/src/test/java`, as
formas de desligar ou condicionar um teste no JUnit 4 e 5: `@Disabled...`,
`@EnabledIf...`, `@EnabledOn...`, `@EnabledFor...`, `@Ignore`,
`Assumptions.assume...` e `assumeTrue`/`assumeFalse`/`assumeThat`/`assumeNotNull`
— inclusive com o nome de pacote completo (`@org.junit.jupiter.api.Disabled`).

Achou algo, imprime `TESTE PULADO: <arquivo>:<linha>` e sai com erro. Não achou,
imprime `NENHUM TESTE PULADO`. Ele roda no job `guardas` do CI (ver
[29-github-actions-ci.md](29-github-actions-ci.md)).

## O que ainda não existe

- **Spring Cloud Contract** — o `CLAUDE.md` lista como planejado, mas nenhum
  `pom.xml` usa. O papel de "contrato entre serviços" é cumprido de outro jeito:
  o módulo `e2e-tests` liga os dois serviços reais pela fila, e os parsers
  (`SagaCommandParserTest`, `SagaEventParserTest`) fixam o formato das mensagens.
- **WireMock** — os vizinhos HTTP falsos (`DownstreamStubServer`) são montados com
  o `com.sun.net.httpserver.HttpServer` que já vem na JDK.
- **Limite de cobertura de linhas (JaCoCo)** — de propósito: "sem lacunas" é medido
  pela matriz regra → teste, não por porcentagem (D-105, ver
  [33-matriz-regra-teste.md](33-matriz-regra-teste.md)).
