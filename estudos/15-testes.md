# A pasta de testes: estrutura, convenções e Testcontainers

Arquivos: `auth-service/src/test/java/`, `catalog-service/src/test/java/`,
`inventory-service/src/test/java/`, `notification-service/src/test/java/` e
`order-service/src/test/java/` (o `gateway` continua sem pasta de testes), mais os
`pom.xml` de cada um desses cinco serviços.

## O que é a pasta de testes, em nível bem básico

Todo projeto Java/Maven separa dois tipos de código: o código que **roda de
verdade em produção** (`src/main/java`) e o código que **existe só para testar**
esse primeiro código (`src/test/java`). São duas árvores de pastas irmãs, com a
mesma estrutura de pacotes, mas com propósitos opostos: uma vira o `.jar` que roda
no servidor, a outra nunca vai para produção — ela só roda na sua máquina (ou
futuramente num CI) para provar que a primeira funciona.

No OrderFlow, cada microsserviço tem a sua própria pasta de testes:

```
auth-service/src/test/java/com/orderflow/auth/
catalog-service/src/test/java/com/orderflow/catalog/
inventory-service/src/test/java/com/orderflow/inventory/
notification-service/src/test/java/com/orderflow/notification/
order-service/src/test/java/com/orderflow/order/
```

O `gateway` é o único serviço **sem** nenhuma pasta de teste — faz sentido,
porque ele não tem lógica de negócio própria, só encaminha requisições (ver
[10-gateway-application-yml.md](10-gateway-application-yml.md)).

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

No projeto hoje, a **maioria é `*IT.java`**, mas já existem seis testes unitários
puros (`*Test.java`), nenhum deles sobe o Spring:

- `SeedPasswordHashTest` (auth-service) — verifica se a senha de demonstração bate
  com um hash gravado numa migration do banco, sem precisar do Spring nem de rede.
- `NotificationServiceTest` e `NotificationEventListenerTest` (notification-service)
  — testam o service e o consumidor de mensagens com as dependências simuladas pelo
  Mockito.
- `OrderCreationServiceTest` (order-service) — testa a criação de pedido também com
  Mockito no lugar dos colaboradores.
- `OrderDomainTest` (order-service) — testa a regra de limite de crédito e as
  transições de status do pedido, sem nenhum I/O.
- `DownstreamClientsTest` (order-service) — testa os clientes HTTP que chamam o
  auth-service e o catalog-service apontando para uma porta fechada, para provar
  que eles falham de forma segura quando o vizinho nem responde.

## Quais dependências de teste o projeto usa

Olhando o `pom.xml` de `auth-service` e `catalog-service`, os dois declaram
exatamente o mesmo bloco (os outros três serviços variam em cima dele — ver a
tabela logo abaixo):

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

Os outros serviços partem dessa base, mas com diferenças:

| Serviço | Dependências de teste |
| --- | --- |
| auth, catalog | o bloco acima (starter-test, spring-boot-testcontainers, junit-jupiter, postgresql) |
| inventory | o bloco acima **+** `org.testcontainers:localstack` e `org.awaitility:awaitility` |
| notification | `spring-boot-starter-test`, `junit-jupiter`, `localstack`, `awaitility` — **sem** `postgresql` (o serviço não usa Postgres) e **sem** `spring-boot-testcontainers` |
| order | `spring-boot-starter-test`, `junit-jupiter`, `postgresql` — **sem** `spring-boot-testcontainers`: a conexão com o banco é registrada à mão com `@DynamicPropertySource` na classe `OrderTestInfrastructure` |

O que cada uma faz:

- **`spring-boot-starter-test`** — é um "combo", não uma biblioteca única. Ele
  traz junto JUnit 5 (o framework que executa os testes e fornece a anotação
  `@Test`), AssertJ (uma forma mais legível de escrever verificações, tipo
  `assertThat(resultado).isEqualTo(...)`), Mockito (biblioteca para simular
  dependências — usada nos testes unitários do notification-service e no
  `OrderCreationServiceTest`, e também via `@MockitoBean` no
  `NotificationStoreUnavailableIT`, que troca um bean real por um simulado para
  forçar uma falha do DynamoDB) e MockMvc (simula requisições HTTP sem precisar de um
  servidor real rodando numa porta).
- **`spring-boot-testcontainers`** — integra o Spring com a biblioteca
  Testcontainers (explicada abaixo), fornecendo a anotação `@ServiceConnection` —
  usada hoje só no auth, catalog e inventory.
- **`org.testcontainers:junit-jupiter`** e **`org.testcontainers:postgresql`** —
  o motor do Testcontainers e o módulo específico para subir um Postgres.
- **`org.testcontainers:localstack`** — módulo que sobe um LocalStack real (o
  "AWS de mentira" local) com SQS e DynamoDB, usado nos testes do inventory e do
  notification para publicar e consumir mensagens de verdade.
- **`org.awaitility:awaitility`** — ajuda a testar coisas **assíncronas**. Quando
  uma mensagem vai para uma fila, o efeito não acontece na hora; o Awaitility
  deixa escrever "espere até X acontecer (com um tempo máximo)", por exemplo, até
  a mensagem chegar na fila ou até o histórico aparecer no DynamoDB.

Nenhuma versão explícita aparece nesses artefatos — todas vêm do
`dependencyManagement` (BOM) do `pom.xml` raiz do projeto.

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

Além do Postgres, os testes do inventory e do notification sobem também um
container `localstack/localstack:2026.08.3` — e, como explicado no `CLAUDE.md`,
essa imagem exige a variável `LOCALSTACK_AUTH_TOKEN` (lida do ambiente ou do
arquivo `.env`); sem ela, esses testes nem começam.

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
- **order** — Postgres **+** um servidor HTTP falso que faz o papel dos vizinhos
  (auth e catalog), tudo registrado à mão via `OrderTestInfrastructure`.

Um detalhe interessante documentado no próprio comentário dessa classe: o
container é iniciado manualmente num bloco `static { postgres.start(); }`, **em
vez de** usar a anotação padrão `@Testcontainers`/`@Container` do JUnit 5. O
motivo é que essa anotação padrão derrubaria o container ao final da **primeira**
classe de teste — mas como várias classes `*IT.java` estendem essa mesma base, o
objetivo aqui é **compartilhar um único container Postgres** entre todas elas na
mesma execução, para não pagar o custo de subir um container novo (o que leva
alguns segundos) a cada classe de teste.

## Como tudo se encaixa na prática

Um exemplo real do projeto, `CompanyControllerIT.java` (a maior suíte, 30
testes): ele estende `AbstractIntegrationTest`, então já ganha o Postgres real e
o Spring rodando. Cada teste então usa `MockMvc` para simular uma requisição HTTP
real (por exemplo, `POST /companies`), e depois confere tanto a resposta HTTP
quanto o estado que ficou gravado no banco via os `Repository` do projeto. Ele
testa coisas como: uma empresa BUYER não pode ver dados de outra empresa
(isolamento entre tenants — ver [11-companyguard.md](11-companyguard.md)), token
expirado é rejeitado, senha nunca aparece na resposta de erro.

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

## O que ainda não existe

Vale registrar, para o quadro ficar completo: o `CLAUDE.md` do projeto lista
Mockito, Awaitility, Spring Cloud Contract e Testcontainers-LocalStack como
tecnologias planejadas. Mockito, Awaitility e Testcontainers-LocalStack **já estão
em uso** (ver acima). O que ainda falta:

- **Spring Cloud Contract** — nenhum teste de contrato entre serviços ainda.
- **Um teste com dois microsserviços reais** conversando de verdade. O
  order-service chama o auth-service e o catalog-service, mas nos testes esses
  vizinhos são **simulados** pelo `DownstreamStubServer` — um servidor HTTP falso
  montado com o `com.sun.net.httpserver.HttpServer` que já vem na própria JDK (não
  é WireMock; nenhum `pom.xml` do projeto usa WireMock).
- **Pipeline de CI** (`.github/workflows`) rodando esses testes automaticamente a
  cada push — também planejado no `CLAUDE.md`, mas ainda não implementado.
