# DynamoDB — o banco NoSQL do notification-service

Arquivos: `notification-service/src/main/java/com/orderflow/notification/history/NotificationRecord.java`,
`notification-service/src/main/java/com/orderflow/notification/history/NotificationRepository.java`,
`notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java`,
`localstack-init/ready.d/01-create-notification-resources.sh`,
`notification-service/pom.xml`, `notification-service/src/main/resources/application.yml`.

## O que é o DynamoDB

O **DynamoDB** é o banco de dados **NoSQL** da AWS. No projeto ele não roda
na AWS de verdade — assim como o SQS (ver
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)), ele roda
localmente via **LocalStack**, então tudo funciona de graça na máquina do
desenvolvedor.

A pergunta natural é: se o projeto já usa Postgres em outros serviços, por
que também usar DynamoDB? A resposta está no propósito do projeto de
portfólio — demonstrar SQL e NoSQL lado a lado. O `notification-service` é o
**único** lugar do projeto que usa DynamoDB, guardando o histórico de
notificações.

## SQL vs. NoSQL — a diferença fundamental

No Postgres (usado pelo `inventory-service`, por exemplo), você define
**tabelas com colunas fixas** e pode fazer consultas complexas com `JOIN`,
filtros arbitrários em qualquer coluna etc. — é um banco **relacional**.

O DynamoDB é diferente: é um banco **chave-valor/documento**, otimizado para
respostas ultrarrápidas quando você já sabe exatamente **qual chave** está
buscando, mas muito mais limitado em consultas livres. A troca é: você abre
mão de flexibilidade de consulta em favor de performance previsível em
escala gigantesca — não importa se a tabela tem mil ou um bilhão de itens,
buscar por chave é igualmente rápido.

## As duas chaves da tabela `notification-history`

Toda tabela DynamoDB precisa de uma **chave primária**, definida **na
criação da tabela**, não no código Java. Quem cria a tabela é o script
`localstack-init/ready.d/01-create-notification-resources.sh`:

```bash
awslocal --region "$REGION" dynamodb create-table \
    --table-name "$TABLE_NAME" \
    --attribute-definitions \
        AttributeName=productId,AttributeType=S \
        AttributeName=sortKey,AttributeType=S \
    --key-schema \
        AttributeName=productId,KeyType=HASH \
        AttributeName=sortKey,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST
```

Isso define uma chave **composta**, com duas partes:

- **`productId`** (`HASH` = partition key / chave de partição) — decide **em
  qual "gaveta"** o item fica guardado. O DynamoDB usa essa chave para
  distribuir os dados fisicamente entre servidores; todos os itens com o
  mesmo `productId` ficam juntos.
- **`sortKey`** (`RANGE` = sort key / chave de ordenação) — dentro da mesma
  "gaveta" (mesmo `productId`), decide a **ordem** e distingue um item do
  outro. No projeto, o valor é montado como `eventType + "#" + eventId`
  (ex.: `"STOCK_ADJUSTED#3f2a..."`), em `NotificationService.record()`.

Pensa numa gaveta de arquivos: `productId` escolhe a gaveta certa, `sortKey`
é a etiqueta que diferencia cada papel dentro dessa gaveta. **Uma tabela
DynamoDB só permite buscar de forma eficiente por essa chave** — não existe
um "WHERE mensagem LIKE '%estoque%'" livre como no SQL.

## `@DynamoDbBean` — como o Java "conversa" com essa tabela

```java
@Getter
@Setter
@NoArgsConstructor
@DynamoDbBean
public class NotificationRecord {

    private String productId;
    private String sortKey;
    private String eventId;
    private String eventType;
    private String message;
    private String rawPayload;
    private Instant occurredAt;
    private Instant recordedAt;

    @DynamoDbPartitionKey
    public String getProductId() {
        return productId;
    }

    @DynamoDbSortKey
    public String getSortKey() {
        return sortKey;
    }
}
```

Essa classe é o "molde" de um item da tabela. É o **Enhanced Client** da AWS
(uma camada de conveniência sobre o SDK bruto) que lê essas anotações e faz
a tradução automática entre objeto Java ↔ item do DynamoDB — igual o
JPA/Hibernate faz para o Postgres em outros serviços, mas com uma API
própria (`@DynamoDbBean`, `@DynamoDbPartitionKey`, `@DynamoDbSortKey` em vez
de `@Entity`, `@Id` etc.).

Um detalhe que o comentário do arquivo destaca: essa classe é uma **classe
normal** (com Lombok `@Getter`/`@Setter`/`@NoArgsConstructor`), não um
`record` do Java — porque o Enhanced Client **exige** um construtor sem
argumentos e métodos getter/setter públicos para conseguir montar o objeto
depois de ler os dados brutos. Um `record` tem campos implicitamente finais
e não constrói assim, então não é compatível com `@DynamoDbBean`. Isso é
intencionalmente diferente do estilo usado na entidade `Inventory`
(JPA/Postgres) em outro serviço.

Os nomes `productId`/`sortKey` **precisam bater exatamente** com o
key-schema criado pelo script do LocalStack — se um dos lados mudar sem o
outro, a aplicação quebra ao tentar ler/gravar.

Um limite importante do DynamoDB: ele **recusa itens maiores que 400 KB**.
Como o campo `rawPayload` guarda o JSON inteiro do evento, um evento gigante
(por exemplo, com um campo extra inesperado enorme) falharia **sempre** ao
gravar — não é um erro passageiro que some numa nova tentativa. Por isso o
`NotificationService.record()` recusa corpos acima de 64 KB **antes** mesmo
de interpretar o JSON (WR-02): a mensagem é descartada com um log de aviso,
em vez de ficar voltando para a fila num laço infinito.

## Como o Java acessa a tabela — `NotificationRepository`

```java
public NotificationRepository(DynamoDbEnhancedClient dynamoDbEnhancedClient,
                               @Value("${orderflow.notifications.table-name}") String tableName) {
    this.table = dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(NotificationRecord.class));
}
```

Assim como o `SqsTemplate` (ver
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)), o
`DynamoDbEnhancedClient` **não é criado na mão** em lugar nenhum — ele
aparece pronto por injeção de dependência, graças à dependência declarada no
`pom.xml`:

```xml
<!-- Autoconfigura o DynamoDbEnhancedClient a partir das mesmas propriedades spring.cloud.aws.* -->
<artifactId>spring-cloud-aws-starter-dynamodb</artifactId>
```

— as mesmas propriedades de `endpoint`/`region`/`credentials` que já
configuram o `SqsTemplate`, apontando para o LocalStack:

```yaml
spring:
  cloud:
    aws:
      region:
        static: us-east-1
      credentials:
        access-key: test
        secret-key: test
      endpoint: ${SPRING_CLOUD_AWS_ENDPOINT:http://localhost:4566}
orderflow:
  notifications:
    table-name: notification-history
```

`dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(NotificationRecord.class))`
cria uma referência tipada à tabela `notification-history`, combinando o
nome da tabela (vindo da configuração, não hardcoded) com o "molde" da
classe `NotificationRecord`. O comentário do arquivo explica por que o
repositório usa o `DynamoDbEnhancedClient` diretamente em vez do
`DynamoDbTemplate` de conveniência do starter: a assinatura de consulta do
template não estava confirmada pela pesquisa da fase, e o resolvedor de nome
de tabela padrão dele derivaria um nome a partir da classe Java em vez de
usar o nome explícito vindo da configuração.

### Gravando (`save`)

```java
public void save(NotificationRecord record) {
    table.putItem(record);
}
```

`putItem` é uma operação de **substituição completa**: se já existe um item
com a mesma chave (`productId` + `sortKey`), ele é **inteiramente
sobrescrito**; se não existe, é criado. Não existe "atualizar só um campo"
aqui (isso seria `updateItem`, não usado no projeto) nem confirmação prévia
de "esse item já existe?". Essa característica é usada de propósito: se a
mesma mensagem SQS for entregue duas vezes (reentrega — ver a seção de
consumo idempotente em
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)), gravar de novo
com a mesma chave simplesmente sobrescreve com o mesmo conteúdo — sem gerar
duplicata, sem precisar de lógica extra de idempotência.

### Lendo (`findByProductId`)

```java
public List<NotificationRecord> findByProductId(String productId) {
    QueryConditional condition = QueryConditional.keyEqualTo(
            Key.builder().partitionValue(productId).build());
    return table.query(condition).items().stream().toList();
}
```

Isso é uma **Query** — o tipo de busca "nativo" e eficiente do DynamoDB: você
informa o valor exato da partition key (`productId`) e recebe de volta
**todos os itens dessa partição**, já agrupados. É o equivalente a um
`SELECT * FROM notification_history WHERE product_id = ?` no mundo SQL, só
que muito mais restrito: você só pode fazer isso pela chave definida na
criação da tabela, nunca por um campo qualquer como `message` ou
`eventType` sozinho (isso exigiria um índice secundário, que este projeto
não tem).

Como o DynamoDB não garante a ordem "cronológica" dos itens devolvidos (a
ordem segue a `sortKey`, que começa com o tipo do evento, não com a data), o
`NotificationService.history()` reordena os resultados em memória, no Java,
por `occurredAt` — e, em caso de empate, pela `sortKey`, como critério de
desempate. Esse `occurredAt` é capturado **dentro da transação** do ajuste
no inventory-service (WR-04), então reflete a ordem real em que os ajustes
foram commitados (ver [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)).

## Resumindo com uma analogia

- **Postgres/JPA** (usado em outros serviços) = um arquivo de fichário
  tradicional: você pode procurar por qualquer campo, cruzar informações de
  fichários diferentes, fazer perguntas complexas — mas isso tem um custo de
  processamento que cresce com o tamanho dos dados.
- **DynamoDB** = um sistema de **caixas postais numeradas**: extremamente
  rápido para "me dê tudo que está na caixa número X (`productId`)", mas você
  **não pode** perguntar "me dê tudo que contém a palavra 'estoque'" sem
  abrir caixa por caixa — a única forma eficiente de buscar é sabendo o
  número da caixa.
- **Partition key + sort key** = o número da caixa postal (`productId`) e,
  dentro dela, a etiqueta que ordena/diferencia cada carta (`sortKey` =
  `eventType#eventId`).
- **`putItem` sem condição** = trocar o conteúdo de uma posição específica na
  caixa por um novo envelope: se já tinha algo lá com aquele mesmo endereço
  exato, o conteúdo antigo simplesmente é substituído pelo novo (que, no
  caso de reentrega, é idêntico).

## Comparando com o Postgres/JPA do resto do projeto

| | Postgres (outros serviços) | DynamoDB (`notification-service`) |
|---|---|---|
| Tipo de banco | Relacional (SQL) | Chave-valor/documento (NoSQL) |
| Camada de mapeamento | JPA/Hibernate (`@Entity`, `@Id`) | Enhanced Client (`@DynamoDbBean`, `@DynamoDbPartitionKey`) |
| Classe de item | `record`/entidade JPA | Classe normal + Lombok (exige construtor vazio + setters) |
| Consulta livre por qualquer campo | Sim (`WHERE`, `JOIN`) | Não — só pela chave definida na criação da tabela |
| Como o esquema é criado | Flyway (migrations versionadas em SQL) | Script `create-table` do LocalStack init hook |
| Atualização parcial | `UPDATE` de colunas específicas | `putItem` substitui o item inteiro (sem `updateItem` neste projeto) |
