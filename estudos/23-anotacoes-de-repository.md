# Anotações de repository — `@Query`, `@Param`, `@Modifying`, `@Lock` e outras

Arquivo principal: `order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLockRepository.java`
(com comparações a `OrderRepository` e aos repositories dos outros serviços).

```java
public interface CompanyCreditLockRepository extends JpaRepository<CompanyCreditLock, UUID> {

    @Modifying
    @Query(value = "INSERT INTO {h-schema}company_credit_lock (company_id) VALUES (:companyId) "
            + "ON CONFLICT (company_id) DO NOTHING", nativeQuery = true)
    void ensureExists(@Param("companyId") UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM CompanyCreditLock l WHERE l.companyId = :companyId")
    Optional<CompanyCreditLock> lockForUpdate(@Param("companyId") UUID companyId);
}
```

Continuação de [21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md): lá o
foco foi **para que** serve a trava por empresa; aqui o foco é **o que cada `@` faz** no
repository que implementa essa trava.

## 1. O que é um repository

Repare que `CompanyCreditLockRepository` é uma **interface**, não uma classe. Não existe
nenhum código que implemente esses métodos. Quem escreve esse código é o **Spring Data JPA**:
quando a aplicação sobe, ele lê a interface e **gera sozinho** uma classe que funciona.

O trecho `extends JpaRepository<CompanyCreditLock, UUID>` quer dizer: "este repository cuida
da entidade `CompanyCreditLock`, cujo id é do tipo `UUID`". Só com essa linha você já ganha,
sem escrever nada: `save`, `findById`, `findAll`, `deleteById`, `count` e outros.

### Primeiro jeito de criar consultas: pelo nome do método (sem `@`)

No `OrderRepository`:

```java
Page<Order> findByCompanyIdAndStatus(UUID companyId, OrderStatus status, Pageable pageable);
```

O Spring **lê o nome do método** e monta a consulta: `findBy` + `CompanyId` + `And` +
`Status` vira `WHERE company_id = ? AND status = ?`. Isso se chama **consulta derivada**
(*derived query*). Outros exemplos no projeto:

- `findByEmail(String email)`, no `UserRepository` do `auth-service`
- `existsBySku(String sku)`, no `ProductRepository` do `catalog-service`, que devolve
  `true`/`false`
- `findByProductIdAndReservationId(...)`, no `StockReservationRepository` do
  `inventory-service`

É o jeito preferido quando a consulta é simples. As anotações entram quando o nome do método
não dá conta.

## 2. `@Query`: escrever a consulta à mão

Quando a consulta é complicada demais para caber num nome de método, ela é escrita direto na
anotação. Existem **duas linguagens** possíveis.

### JPQL (o padrão)

```java
@Query("SELECT l FROM CompanyCreditLock l WHERE l.companyId = :companyId")
```

Parece SQL, mas **não é**. É a JPQL (*Java Persistence Query Language*), que fala em
**classes e atributos Java**:

- `CompanyCreditLock` é o **nome da classe**, não o nome da tabela (`company_credit_lock`)
- `l.companyId` é o **atributo Java**, não a coluna (`company_id`)

O Hibernate traduz a JPQL para o SQL do banco. Vantagem: se o banco mudar, a consulta continua
funcionando. Outro exemplo no projeto, no `OrderRepository`:

```java
@Query("SELECT SUM(o.total) FROM Order o WHERE o.companyId = :companyId AND o.status IN :statuses")
BigDecimal sumTotalByCompanyIdAndStatusIn(@Param("companyId") UUID companyId,
                                           @Param("statuses") Collection<OrderStatus> statuses);
```

### SQL nativo (`nativeQuery = true`)

```java
@Query(value = "INSERT INTO {h-schema}company_credit_lock (company_id) VALUES (:companyId) "
        + "ON CONFLICT (company_id) DO NOTHING", nativeQuery = true)
```

Aqui é **SQL puro do Postgres**, com os nomes reais da tabela e da coluna. Foi preciso usar SQL
nativo porque o `ON CONFLICT DO NOTHING` é um recurso **exclusivo do Postgres** que não existe
na JPQL. O preço: essa consulta só funciona no Postgres.

O `{h-schema}` é um marcador que o Hibernate troca pelo schema padrão do banco (`"order"`).
Na JPQL isso não é necessário, porque o Hibernate já sabe onde está cada entidade; no SQL
nativo, ele não mexe no texto a não ser nesse marcador.

### `@Param`: ligar o parâmetro Java ao `:nome` da consulta

```java
void ensureExists(@Param("companyId") UUID companyId);
```

Na consulta aparece `:companyId`. O `@Param("companyId")` diz: "o valor deste parâmetro Java
vai no lugar de `:companyId`".

Isso também é uma **proteção de segurança**. O valor nunca é "colado" dentro do texto da
consulta: ele vai separado, e o banco o trata sempre como **dado**, nunca como comando. É o que
impede o ataque de **SQL Injection**, em que alguém manda um texto como
`'; DROP TABLE orders; --` esperando que vire parte do SQL.

## 3. `@Modifying`: "esta consulta altera dados"

### O problema

Por padrão, o Spring Data acha que toda `@Query` é uma **leitura** (`SELECT`): executa a
consulta esperando receber linhas de volta para transformar em objetos.

Um `INSERT`, `UPDATE` ou `DELETE` **não devolve linhas**, só "quantas linhas foram afetadas".
Sem avisar o Spring, ele tenta tratar o `INSERT` como leitura e a execução falha com um erro
dizendo que a consulta não é um `SELECT`.

### A solução

```java
@Modifying
@Query(value = "INSERT INTO ...", nativeQuery = true)
void ensureExists(@Param("companyId") UUID companyId);
```

O `@Modifying` avisa: "**esta consulta modifica dados; execute como alteração, não como
leitura**". Por trás, o Spring passa a chamar `executeUpdate()` em vez de buscar uma lista de
resultados.

O método pode devolver:

- `void`, como aqui, quando o resultado não interessa
- `int`, com o número de linhas afetadas. Um `int desativarProdutosAntigos()`, por exemplo,
  poderia devolver `42`.

### Precisa de transação

Uma consulta `@Modifying` **precisa rodar dentro de uma transação**, senão dá
`TransactionRequiredException`. Aqui isso já está garantido: quem chama `ensureExists` é o
`CompanyCreditLocker.acquire`, que tem `@Transactional(propagation = MANDATORY)`.

### Duas opções do `@Modifying`: a memória do JPA

Dentro de uma transação, o JPA mantém uma **memória temporária** dos objetos que já leu. Ela
se chama *persistence context*, ou "cache de primeiro nível". É ela que permite o *dirty
checking* visto em [22-services-de-pedido.md](22-services-de-pedido.md).

Um `@Modifying` **vai direto ao banco e passa por cima dessa memória**, o que pode deixá-la
desatualizada:

```java
Product p = productRepository.findById(id).get();   // p.status = ACTIVE (guardado na memória)
productRepository.deactivateAll();                  // @Modifying: UPDATE direto no banco → INACTIVE
Product p2 = productRepository.findById(id).get();  // ainda ACTIVE! veio da memória, não do banco
```

Duas opções resolvem isso:

| Opção | O que faz | Quando usar |
|---|---|---|
| `@Modifying(flushAutomatically = true)` | **Antes** de rodar a consulta, grava no banco as alterações pendentes da memória | Quando você alterou objetos antes e a consulta precisa "enxergar" essas mudanças |
| `@Modifying(clearAutomatically = true)` | **Depois** de rodar a consulta, limpa a memória | Quando você vai ler de novo os mesmos dados depois da alteração |

No `ensureExists`, nenhuma das duas é necessária. O `INSERT` cria uma linha que ninguém tinha
lido antes, e a leitura seguinte (`lockForUpdate`) é um `SELECT ... FOR UPDATE`, que sempre vai
ao banco.

## 4. `@Lock`: trancar as linhas lidas

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT l FROM CompanyCreditLock l WHERE l.companyId = :companyId")
Optional<CompanyCreditLock> lockForUpdate(@Param("companyId") UUID companyId);
```

O `@Lock` diz: "**ao ler estas linhas, tranque-as no banco**". A consulta JPQL é um `SELECT`
normal. Por causa do `@Lock(PESSIMISTIC_WRITE)`, o Hibernate acrescenta `FOR UPDATE` ao SQL
que envia ao Postgres, mais ou menos assim:

```sql
select c.company_id from "order".company_credit_lock c where c.company_id = ? for update
```

A partir daí, **qualquer outra transação** que tentar trancar a mesma linha **fica parada
esperando** até esta terminar (no `commit` ou `rollback`).

Assim como o `@Modifying`, o `@Lock` **só funciona dentro de uma transação**. A trava dura até
a transação acabar, e sem transação não haveria "até quando" segurar.

### Os tipos de trava (`LockModeType`)

| Tipo | SQL gerado (Postgres) | O que faz | Analogia |
|---|---|---|---|
| **`PESSIMISTIC_WRITE`** (usado aqui) | `FOR UPDATE` | Ninguém mais pode trancar nem alterar a linha até eu terminar | Levo a caderneta comigo: ninguém lê nem escreve |
| `PESSIMISTIC_READ` | `FOR SHARE` | Outros podem **ler com trava** ao mesmo tempo, mas ninguém pode **alterar** | Vários podem olhar a caderneta, ninguém escreve nela |
| `PESSIMISTIC_FORCE_INCREMENT` | `FOR UPDATE` + incrementa `@Version` | Como `WRITE`, e ainda aumenta a versão da entidade | Levo a caderneta e carimbo "revisada" |
| `OPTIMISTIC` | nada no `SELECT` | Não tranca. No final, confere se a `@Version` mudou | Não levo a caderneta; no fim confiro se alguém mexeu |
| `OPTIMISTIC_FORCE_INCREMENT` | nada no `SELECT` | Como `OPTIMISTIC`, e aumenta a versão mesmo sem alteração | |

`PESSIMISTIC_WRITE` foi escolhido porque o objetivo é **fazer os pedidos da mesma empresa
esperarem um pelo outro**. Com `PESSIMISTIC_READ`, dois pedidos poderiam pegar a trava ao
mesmo tempo, e a condição de corrida voltaria.

Os tipos `OPTIMISTIC` funcionam com a coluna `@Version`. É o que o `inventory-service` usa na
entidade `Inventory`, junto com o retry de [14-spring-retry.md](14-spring-retry.md). Lá não é
preciso `@Lock` no repository: quando a entidade tem `@Version`, o JPA já faz a checagem
otimista automaticamente em todo `UPDATE`.

## 5. Outras anotações úteis em repositories

### `@Repository`

```java
@Repository
public class NotificationRepository { ... }
```

Marca uma **classe** como "componente de acesso a dados", para o Spring criar e injetar. É
usada no projeto no `NotificationRepository` do `notification-service`, que é uma classe comum
falando com o DynamoDB (ver [17-dynamodb.md](17-dynamodb.md)).

Nas **interfaces** que estendem `JpaRepository` ela **não é necessária**: o Spring Data já as
encontra sozinho. Por isso nenhum repository JPA do projeto a usa.

Outra vantagem do `@Repository`: ele converte erros específicos do banco em exceções padrão do
Spring (`DataAccessException`), para o resto do código não depender do tipo de banco.

### `@Transactional` no repository

Os métodos herdados do `JpaRepository` já vêm com transação própria (`save`, `delete` etc. com
`@Transactional`, os de leitura com `@Transactional(readOnly = true)`). Também é possível
colocar `@Transactional` num método próprio do repository:

```java
@Transactional
@Modifying
@Query("UPDATE Product p SET p.status = 'INACTIVE' WHERE p.updatedAt < :date")
int deactivateOlderThan(@Param("date") OffsetDateTime date);
```

Neste projeto, porém, a regra é deixar a transação no **service** (como em `OrderService`). É
lá que se decide o que precisa acontecer "tudo junto ou nada", e o repository só executa.

### `@EntityGraph`: evitar o problema "N+1"

Imagine listar 20 pedidos e, para cada um, mostrar os itens. Sem cuidado, o JPA faz **1**
consulta para os pedidos + **20** consultas, uma para os itens de cada pedido. São 21 idas ao
banco: o famoso **problema N+1**.

```java
@EntityGraph(attributePaths = "items")
Page<Order> findByCompanyId(UUID companyId, Pageable pageable);
```

O `@EntityGraph` diz: "já traga os `items` junto, na mesma consulta". Não é usado no projeto:
a listagem de pedidos evita o problema por outro caminho, devolvendo `OrderSummaryResponse`
**sem itens** (ver [22-services-de-pedido.md](22-services-de-pedido.md)).

### `@QueryHints`: dicas extras para a consulta

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
Optional<CompanyCreditLock> lockForUpdate(UUID companyId);
```

São "recados" para o Hibernate ou para o banco. O exemplo acima diria "se não conseguir a trava
em 3 segundos, desista com erro". O projeto **escolheu não ter** esse limite, porque nada que
segura a trava faz chamada de rede, então a espera é sempre curta (ver nota 21). Outro uso
comum é `org.hibernate.readOnly`, que avisa o Hibernate que os objetos lidos não serão
alterados.

### `@Procedure`: chamar uma *stored procedure*

```java
@Procedure("calcular_comissao")
BigDecimal calcularComissao(UUID sellerId);
```

Chama uma função guardada **dentro do banco**. É comum em sistemas legados. Projetos novos,
como este, preferem manter a lógica no Java.

## 6. Resumo em uma tabela

| Anotação | Em uma frase | No projeto? |
|---|---|---|
| (nenhuma) | O Spring monta a consulta **pelo nome do método** | Sim, em vários repositories |
| `@Query` | Escrever a consulta à mão, em JPQL ou SQL nativo | Sim: `CompanyCreditLockRepository`, `OrderRepository` |
| `@Param` | Liga um parâmetro Java ao `:nome` da consulta, com segurança | Sim |
| `@Modifying` | Avisa que a consulta **altera** dados (`INSERT`/`UPDATE`/`DELETE`) | Sim: `ensureExists` |
| `@Lock` | **Tranca** as linhas lidas até o fim da transação | Sim: `lockForUpdate` |
| `@Repository` | Marca uma **classe** como acesso a dados | Sim: `NotificationRepository` (DynamoDB) |
| `@Transactional` | Abre transação no método | Não no repository; fica nos services |
| `@EntityGraph` | Traz dados relacionados junto e evita N+1 | Não |
| `@QueryHints` | Dicas extras, como o tempo máximo de espera da trava | Não, por escolha |
| `@Procedure` | Chama uma função guardada no banco | Não |

## Resumindo com uma analogia

Pensa no repository como um **arquivista**. Você pede "me traga as fichas do cliente X", e ele
já sabe fazer isso só pelo pedido (consulta derivada). Quando o pedido é complicado, você
entrega um **bilhete escrito** (`@Query`), e os valores vão num envelope separado, nunca
rabiscados no bilhete (`@Param`). Se o bilhete manda **escrever** em vez de ler, você marca
"atenção: alteração" (`@Modifying`), senão ele fica esperando fichas que nunca vêm. E se você
quer que ninguém mexa naquela ficha enquanto trabalha, carimba **"reservado para mim"**
(`@Lock`): quem vier depois espera você terminar.
