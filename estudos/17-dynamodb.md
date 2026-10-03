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
notificações: os ajustes de estoque de cada produto e a linha do tempo de cada pedido.

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
        AttributeName=entityId,AttributeType=S \
        AttributeName=sortKey,AttributeType=S \
    --key-schema \
        AttributeName=entityId,KeyType=HASH \
        AttributeName=sortKey,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST
```

Isso define uma chave **composta**, com duas partes:

- **`entityId`** (`HASH` = partition key / chave de partição) — decide **em
  qual "gaveta"** o item fica guardado. O DynamoDB usa essa chave para
  distribuir os dados fisicamente entre servidores; todos os itens com o
  mesmo `entityId` ficam juntos.
- **`sortKey`** (`RANGE` = sort key / chave de ordenação) — dentro da mesma
  "gaveta", distingue um item do outro. O valor é montado como
  `eventType + "#" + eventId` (ex.: `"STOCK_ADJUSTED#3f2a..."` ou
  `"ORDER_CONFIRMED#9b1c..."`), em `NotificationService`.

Pensa numa gaveta de arquivos: `entityId` escolhe a gaveta certa, `sortKey`
é a etiqueta que diferencia cada papel dentro dessa gaveta. **Uma tabela
DynamoDB só permite buscar de forma eficiente por essa chave** — não existe
um "WHERE mensagem LIKE '%estoque%'" livre como no SQL.

### Por que `entityId`, e não `productId` (D-80)

Até a Fase 5, a partição se chamava `productId`, porque só existia um tipo de evento: o
ajuste de estoque. Na Fase 6 chegaram os eventos do pedido (`ORDER_*`, ver
[31-linha-do-tempo-do-pedido.md](31-linha-do-tempo-do-pedido.md)). Eles também precisam de
uma gaveta — a do **pedido**.

Em vez de criar uma segunda tabela, a partição ganhou um nome genérico:

| Tipo de evento | `entityId` vale | `companyId` |
|---|---|---|
| `STOCK_ADJUSTED` | id do produto | nulo |
| os oito `ORDER_*` | id do pedido | id da empresa compradora |

Os dois tipos moram na mesma tabela, cada um na sua gaveta. Como ids de produto e de
pedido são UUIDs diferentes, as gavetas não se misturam. Mesmo assim, a rota de pedido
filtra por `eventType` na leitura (seção "Lendo", abaixo).

### Por que o init hook apaga e recria a tabela antiga

Mudar a chave de uma tabela DynamoDB **não** é possível: o key-schema é fixo desde a
criação. Um LocalStack que ficou ligado desde a Fase 5 ainda teria a tabela com partição
`productId`, e o código novo quebraria ao gravar `entityId`.

Por isso o script confere a chave antes de criar:

```bash
if awslocal --region "$REGION" dynamodb describe-table --table-name "$TABLE_NAME" >/dev/null 2>&1; then
    CURRENT_HASH_KEY=$(awslocal --region "$REGION" dynamodb describe-table --table-name "$TABLE_NAME" \
        --query "Table.KeySchema[?KeyType=='HASH'].AttributeName" --output text)
    if [ "$CURRENT_HASH_KEY" != "entityId" ]; then
        echo "notification-history com particao '$CURRENT_HASH_KEY' (esperado entityId) — recriando a tabela"
        awslocal --region "$REGION" dynamodb delete-table --table-name "$TABLE_NAME" >/dev/null
        awslocal --region "$REGION" dynamodb wait table-not-exists --table-name "$TABLE_NAME"
    fi
fi
```

Se a tabela existe com a chave errada, ela é apagada; logo depois, o `create-table` roda
de novo com `entityId`. Apagar é seguro aqui porque o LocalStack roda com
`PERSISTENCE=0`: não há dado real a migrar. Em produção, isso seria uma migração de
verdade (tabela nova + cópia), nunca um `delete-table`.

O healthcheck do compose também confere a chave (`grep -q entityId`), para nenhum serviço
subir contra a tabela antiga (ver [01-docker-compose.md](01-docker-compose.md)).

## `@DynamoDbBean` — como o Java "conversa" com essa tabela

```java
@Getter
@Setter
@NoArgsConstructor
@DynamoDbBean
public class NotificationRecord {

    private String entityId;
    private String companyId;
    private String sortKey;
    private String eventId;
    private String eventType;
    private String message;
    private String rawPayload;
    private Instant occurredAt;
    private Instant recordedAt;

    @DynamoDbPartitionKey
    public String getEntityId() {
        return entityId;
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
e não constrói assim, então não é compatível com `@DynamoDbBean`.

Os nomes `entityId`/`sortKey` **precisam bater exatamente** com o key-schema criado pelo
script do LocalStack — se um dos lados mudar sem o outro, a aplicação quebra ao tentar
ler/gravar.

O campo `companyId` não é chave: é um atributo comum, preenchido só nos itens de pedido.
Ele existe para a regra de leitura do comprador (só vê a linha do tempo do pedido da
própria empresa — ver [31-linha-do-tempo-do-pedido.md](31-linha-do-tempo-do-pedido.md)).
No DynamoDB, um item não precisa ter os mesmos atributos que o outro: o item de produto
simplesmente não tem `companyId`.

Um limite importante do DynamoDB: ele **recusa itens maiores que 400 KB**.
Como o campo `rawPayload` guarda o JSON inteiro do evento, um evento gigante
falharia **sempre** ao gravar — não é um erro passageiro que some numa nova tentativa.
Por isso o `NotificationService.record()` recusa corpos acima de 64 KB **antes** mesmo
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
configuram o cliente SQS, apontando para o LocalStack:

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
com a mesma chave (`entityId` + `sortKey`), ele é **inteiramente
sobrescrito**; se não existe, é criado. Não existe "atualizar só um campo"
aqui (isso seria `updateItem`, não usado no projeto) nem confirmação prévia
de "esse item já existe?". Essa característica é usada de propósito: se a
mesma mensagem SQS for entregue duas vezes (reentrega — ver a seção de
consumo idempotente em
[16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)), gravar de novo
com a mesma chave simplesmente sobrescreve com o mesmo conteúdo — sem gerar
duplicata, sem precisar de lógica extra de idempotência.

### Lendo (`findByEntityId`)

```java
public List<NotificationRecord> findByEntityId(String entityId) {
    QueryConditional condition = QueryConditional.keyEqualTo(
            Key.builder().partitionValue(entityId).build());
    return table.query(condition).items().stream().toList();
}
```

Isso é uma **Query** — o tipo de busca "nativo" e eficiente do DynamoDB: você
informa o valor exato da partition key e recebe de volta **todos os itens dessa
partição**. É o equivalente a um `SELECT * FROM notification_history WHERE entity_id = ?`
no mundo SQL, só que muito mais restrito: você só pode fazer isso pela chave definida na
criação da tabela, nunca por um campo qualquer como `message` ou `eventType` sozinho
(isso exigiria um índice secundário, que este projeto não tem).

O mesmo método serve às duas rotas do `NotificationService`:

- `history(productId)` — histórico de estoque do produto (`GET /notifications/{productId}`);
- `historyForOrder(orderId, callerCompanyId, sellerView)` — linha do tempo do pedido
  (`GET /notifications/orders/{orderId}`). Depois da Query, ele **filtra** em memória só os
  itens cujo `eventType` está em `OrderLifecycleEvent.TYPES`. Assim, um id de produto
  consultado pela rota de pedido não mostra `STOCK_ADJUSTED`.

### Ordenando: `occurredAt`, depois o ciclo de vida, depois a `sortKey`

O DynamoDB devolve os itens na ordem da `sortKey`. Essa ordem **não** é cronológica: ela
começa pelo tipo do evento e termina num UUID aleatório. E a fila SQS também não garante
a ordem de chegada. Por isso o `NotificationService` reordena em memória:

```java
records.sort(Comparator.comparing(NotificationRecord::getOccurredAt)
        .thenComparingInt(record -> lifecycleRank(record.getEventType()))
        .thenComparing(NotificationRecord::getSortKey));
```

1. **`occurredAt`** — quando o fato aconteceu (capturado na transação que o gravou, WR-04).
2. **`lifecycleRank`** — o desempate para eventos **do mesmo instante**.
3. **`sortKey`** — último desempate, só para a ordem ser sempre a mesma.

Por que o passo 2 existe? Na criação de um pedido dentro do limite de crédito, o
`order-service` grava `ORDER_CREATED` e `ORDER_APPROVED` na mesma transação, com o
**mesmo** `occurredAt`: `createdAt` e `decidedAt` são preenchidos com o mesmo `now`. Só com a `sortKey`, a
ordem alfabética poria `ORDER_APPROVED#...` **antes** de `ORDER_CREATED#...` — o pedido
apareceria aprovado antes de ser criado.

O `lifecycleRank` dá a cada tipo a sua posição no ciclo de vida:

```java
return switch (eventType) {
    case OrderLifecycleEvent.ORDER_CREATED -> 1;
    case OrderLifecycleEvent.ORDER_PENDING_APPROVAL -> 2;
    case OrderLifecycleEvent.ORDER_APPROVED, OrderLifecycleEvent.ORDER_REJECTED -> 3;
    case OrderLifecycleEvent.ORDER_CONFIRMED, OrderLifecycleEvent.ORDER_CANCELLED -> 4;
    case OrderLifecycleEvent.ORDER_SHIPPED -> 5;
    case OrderLifecycleEvent.ORDER_DELIVERED -> 6;
    default -> 0;
};
```

`STOCK_ADJUSTED` cai no `default` (0). Como todos os itens de produto têm o mesmo rank, o
histórico de estoque continua ordenado como antes: por `occurredAt` e depois `sortKey`.

## Resumindo com uma analogia

- **Postgres/JPA** (usado em outros serviços) = um arquivo de fichário
  tradicional: você pode procurar por qualquer campo, cruzar informações de
  fichários diferentes, fazer perguntas complexas — mas isso tem um custo de
  processamento que cresce com o tamanho dos dados.
- **DynamoDB** = um sistema de **caixas postais numeradas**: extremamente
  rápido para "me dê tudo que está na caixa número X (`entityId`)", mas você
  **não pode** perguntar "me dê tudo que contém a palavra 'estoque'" sem
  abrir caixa por caixa — a única forma eficiente de buscar é sabendo o
  número da caixa.
- **Partition key genérica** = as caixas postais não são só de produtos: algumas têm o
  número de um produto, outras o de um pedido. O carteiro não precisa saber a diferença —
  só o número.
- **Partition key + sort key** = o número da caixa postal (`entityId`) e,
  dentro dela, a etiqueta que diferencia cada carta (`sortKey` = `eventType#eventId`).
- **`lifecycleRank`** = duas cartas com o mesmo carimbo de horário: você as arruma pela
  ordem natural da história ("criado" antes de "aprovado"), não pela ordem alfabética.
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
| Mudar a chave | `ALTER TABLE` numa migration | Impossível: apagar e recriar a tabela |
| Atualização parcial | `UPDATE` de colunas específicas | `putItem` substitui o item inteiro (sem `updateItem` neste projeto) |
