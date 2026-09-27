# Crédito e trava por empresa — a pasta `credit` do order-service

Pasta: `order-service/src/main/java/com/orderflow/order/credit/`
(usada por `OrderService` e `OrderDecisionService`, em `order-service/src/main/java/com/orderflow/order/order/`).

## O que é, em uma frase

A pasta `credit` responde à pergunta **"esta empresa ainda tem crédito para fazer este
pedido?"** e garante que a resposta continue certa mesmo quando chegam vários pedidos da
mesma empresa **ao mesmo tempo**.

São 4 arquivos, com dois papéis diferentes:

| Arquivo | Papel |
|---|---|
| `CreditPolicy.java` | **A regra**: faz a conta e diz se o pedido cabe no limite |
| `CompanyCreditLock.java` | **A trava**: uma linha no banco por empresa |
| `CompanyCreditLockRepository.java` | Os comandos SQL que criam e trancam essa linha |
| `CompanyCreditLocker.java` | O "botão trancar" que os outros serviços chamam |

## 1. A regra: `CreditPolicy`

O OrderFlow é um sistema de atacado. Cada empresa compradora tem um **limite de crédito**,
por exemplo R$ 10.000. É o máximo que ela pode "dever" em pedidos aprovados.

```java
public final class CreditPolicy {

    private CreditPolicy() {
    }

    public static boolean fitsWithinLimit(BigDecimal exposure, BigDecimal orderTotal, BigDecimal creditLimit) {
        return exposure.add(orderTotal).compareTo(creditLimit) <= 0;
    }
}
```

Em português: **"o que a empresa já deve + o valor deste pedido novo é menor ou igual ao
limite?"**

- **`exposure`** (exposição): a soma dos pedidos da empresa que já estão consumindo
  crédito. São os pedidos com status `APPROVED`, `RESERVING`, `CONFIRMED`, `SHIPPED` ou
  `DELIVERED`, lista definida num lugar só, em `OrderStatus.CREDIT_CONSUMING`. Desde a Fase 5,
  um pedido aprovado passa direto para `RESERVING` (ver
  [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md)); `APPROVED` fica na lista
  só por compatibilidade. Um pedido `CANCELLED` pela saga sai da lista, então **devolve o
  crédito**.
- **`orderTotal`**: o valor do pedido novo.
- **`creditLimit`**: o limite da empresa, que vem do `auth-service`.

Exemplo com limite de R$ 10.000:

| Já deve | Pedido novo | Conta | Resultado |
|---|---|---|---|
| R$ 7.000 | R$ 3.000 | 10.000 ≤ 10.000 | **Aprovado automaticamente** (igual ao limite aprova) |
| R$ 7.000 | R$ 3.500 | 10.500 > 10.000 | **Aguardando aprovação manual** (`PENDING_APPROVAL`) |

Repare que estourar o limite **não recusa** o pedido. Ele vai para uma fila, onde um
vendedor decide aprovar ou rejeitar manualmente.

### Detalhes que parecem pequenos, mas importam

- **`BigDecimal` em vez de `double`**: `double` erra contas com centavos (em Java,
  `0.1 + 0.2` dá `0.30000000000000004`). Para dinheiro, sempre `BigDecimal`.
- **`compareTo` em vez de `equals`**: para o `BigDecimal`, `10.0` e `10.00` são
  "diferentes" no `equals`, porque o número de casas decimais (a *escala*) é diferente. O
  `compareTo` compara só o valor, que é o que interessa aqui.
- **`final` + construtor `private`**: ninguém pode criar um objeto `CreditPolicy` nem herdar
  dela. É só uma "calculadora": chama-se `CreditPolicy.fitsWithinLimit(...)` direto. Uma
  regra de negócio pura, sem banco nem Spring, também é fácil de testar com um teste unitário
  simples.

## 2. O problema que a trava resolve: dois pedidos ao mesmo tempo

A empresa tem limite de R$ 10.000 e ainda não deve nada. Dois funcionários dela clicam em
"enviar pedido" **no mesmo instante**, cada um com um pedido de R$ 8.000. Sem trava, isto
pode acontecer:

```
Pedido A: soma a exposição → R$ 0
Pedido B: soma a exposição → R$ 0      (A ainda não salvou!)
Pedido A: 0 + 8.000 ≤ 10.000 → APROVADO, salva
Pedido B: 0 + 8.000 ≤ 10.000 → APROVADO, salva
```

Resultado: **R$ 16.000 aprovados para um limite de R$ 10.000**. Os dois olharam o saldo antes
de qualquer um gravar. Esse tipo de bug se chama **condição de corrida** (*race
condition*). Ele não aparece quando se testa sozinho, clicando um pedido de cada vez, e é
justamente por isso que é perigoso.

A solução é fazer os pedidos **da mesma empresa** passarem pela checagem **um de cada vez**:

```
Pedido A: pega a chave da empresa X
Pedido B: tenta pegar a chave da empresa X → ESPERA
Pedido A: soma exposição (0), 0 + 8.000 ≤ 10.000 → APROVADO, salva, devolve a chave
Pedido B: pega a chave, soma exposição (8.000), 8.000 + 8.000 > 10.000 → PENDENTE
```

A "chave" é uma **linha numa tabela do banco**.

## 3. `CompanyCreditLock`: a tabela da chave

Criada na migration `V1__init_order_schema.sql` (ver
[08-flyway-migrations.md](08-flyway-migrations.md)):

```sql
CREATE TABLE company_credit_lock (
    company_id UUID PRIMARY KEY
);
```

É a tabela mais simples possível: uma coluna só, o id da empresa. Ela **não guarda dado
nenhum**. Existe só para ser trancada. Cada empresa tem sua própria linha, e é por isso que
**empresas diferentes não se bloqueiam**: um pedido da empresa X nunca espera por um da
empresa Y.

A classe Java é o espelho dessa tabela (uma `@Entity` do JPA):

```java
@Entity
@Table(name = "company_credit_lock")
@Getter
public class CompanyCreditLock {

    @Id
    @Column(name = "company_id")
    private UUID companyId;

    protected CompanyCreditLock() {
    }

    public CompanyCreditLock(UUID companyId) {
        this.companyId = companyId;
    }
}
```

- Não há `@GeneratedValue`: o id **é** o próprio `companyId`, porque cada empresa tem
  exatamente uma linha.
- Não há chave estrangeira (FK) para a tabela de empresas, porque ela mora no banco do
  `auth-service`. Cada microsserviço tem seus próprios dados, e o `company_id` aqui é só uma
  referência ("opaca") a algo que vive em outro serviço.
- O construtor vazio `protected` existe porque o Hibernate precisa dele para montar o objeto
  ao ler do banco. O código do projeto usa o outro construtor.

## 4. `CompanyCreditLockRepository`: os dois comandos SQL

### `ensureExists`: garante que a linha existe

```java
@Modifying
@Query(value = "INSERT INTO {h-schema}company_credit_lock (company_id) VALUES (:companyId) "
        + "ON CONFLICT (company_id) DO NOTHING", nativeQuery = true)
void ensureExists(@Param("companyId") UUID companyId);
```

Não dá para trancar uma linha que não existe. Então, antes, ele tenta criar:

- **`ON CONFLICT DO NOTHING`**: "se a linha já existir, tudo bem, não faça nada e não dê
  erro". Isso resolve até o caso de dois pedidos da **primeira compra** de uma empresa
  tentarem criar a linha ao mesmo tempo: um cria, o outro espera e vira "não fiz nada".
  Uma operação que pode ser repetida sem mudar o resultado é chamada de **idempotente**.
- **`nativeQuery = true`**: SQL puro do Postgres (o `ON CONFLICT` não existe na linguagem de
  consulta do JPA).
- **`{h-schema}`**: um marcador que o Hibernate troca pelo nome do schema do banco
  (`"order"`), tanto em produção quanto nos testes com Testcontainers.
- **`@Modifying`**: avisa o Spring Data que essa consulta **altera** dados (não é um
  `SELECT`).

### `lockForUpdate`: tranca a linha

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT l FROM CompanyCreditLock l WHERE l.companyId = :companyId")
Optional<CompanyCreditLock> lockForUpdate(@Param("companyId") UUID companyId);
```

O `@Lock(PESSIMISTIC_WRITE)` faz o Hibernate gerar um **`SELECT ... FOR UPDATE`**, que diz ao
Postgres: **"estou lendo esta linha e ninguém mais pode trancá-la até eu terminar"**. Outra
transação que tentar o mesmo `FOR UPDATE` na mesma linha **fica parada esperando** até a
primeira terminar.

### Pessimista vs. otimista

| | Pessimista (aqui) | Otimista (`inventory-service`) |
|---|---|---|
| Ideia | "Vai haver conflito, tranco antes" | "Provavelmente não vai haver conflito, confiro no fim" |
| Como | `SELECT ... FOR UPDATE` | coluna `@Version` + retry |
| Em caso de disputa | o segundo **espera** | o segundo **falha e tenta de novo** |

O bloqueio otimista está explicado em [14-spring-retry.md](14-spring-retry.md). Aqui o
pessimista faz sentido porque a checagem de crédito precisa **ler uma soma** (a exposição) e
decidir com base nela, e é essa leitura que não pode ficar desatualizada.

## 5. `CompanyCreditLocker`: o "botão trancar"

```java
@Component
public class CompanyCreditLocker {

    private final CompanyCreditLockRepository companyCreditLockRepository;

    public CompanyCreditLocker(CompanyCreditLockRepository companyCreditLockRepository) {
        this.companyCreditLockRepository = companyCreditLockRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(UUID companyId) {
        companyCreditLockRepository.ensureExists(companyId);
        companyCreditLockRepository.lockForUpdate(companyId)
                .orElseThrow(() -> new IllegalStateException(
                        "company_credit_lock row must exist after ensureExists for company " + companyId));
    }
}
```

Ela junta os dois passos (criar se não existir e trancar) numa chamada só: `acquire`
("adquirir a trava").

### Por que `Propagation.MANDATORY`

A trava do banco dura **até o fim da transação** e é liberada no `commit` (ou `rollback`). Se
alguém chamasse `acquire` fora de uma transação, a trava abriria e fecharia na hora, e não
protegeria nada: o bug de corrida voltaria sem nenhum erro visível.

As opções de *propagation* dizem o que fazer com transações:

- `REQUIRED` (o padrão): "se já houver transação, uso ela; se não houver, abro uma nova".
- **`MANDATORY`**: "só aceito ser chamado **dentro** de uma transação que já está aberta. Se
  não houver, lanço `IllegalTransactionStateException`".

Com `MANDATORY`, usar a trava do jeito errado vira um **erro alto e imediato** em vez de um bug
silencioso. É o mesmo espírito de *fail fast* da nota
[20-configuration-properties.md](20-configuration-properties.md).

### Por que não há limite de tempo de espera

Não foi configurado `lock_timeout`: um pedido espera a trava o tempo que for preciso. Isso é
seguro porque, enquanto a trava está presa, o código **nunca chama outro serviço pela rede**.
Ele só faz contas e acessa o banco local, então a espera é sempre curta. As chamadas HTTP (ao
`catalog-service` para preços e ao `auth-service` para o limite) acontecem **antes**, fora da
transação, no `OrderCreationService`.

## 6. Como tudo se junta: `OrderService.createWithCreditCheck`

```java
@Transactional                                              // 1. abre a transação
public OrderResponse createWithCreditCheck(UUID companyId, String createdBy, List<PricedItem> pricedItems,
                                            BigDecimal creditLimit) {
    creditLocker.acquire(companyId);                        // 2. tranca a empresa (espera se preciso)

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    Order order = Order.create(companyId, createdBy, pricedItems, now);

    BigDecimal exposure = orderRepository
            .sumTotalByCompanyIdAndStatusIn(companyId, OrderStatus.CREDIT_CONSUMING);  // 3. soma o que já deve
    if (exposure == null) {
        exposure = BigDecimal.ZERO;                         //    empresa sem pedidos → soma vem null
    }

    if (CreditPolicy.fitsWithinLimit(exposure, order.getTotal(), creditLimit)) {      // 4. aplica a regra
        order.approveAutomatically(now);
    } else {
        order.holdForApproval();
    }

    order = orderRepository.save(order);                    // 5. salva
    return OrderResponse.from(order);
}                                                           // 6. commit → trava liberada
```

**A ordem é o que faz funcionar**: tranca **antes** de somar a exposição. Se somasse antes de
trancar, a condição de corrida voltaria. O horário `now` também é tomado depois da trava, para
que a ordem dos horários de decisão siga a ordem real em que os pedidos passaram pela fila.

### A mesma trava na decisão manual: `OrderDecisionService`

Quando um vendedor aprova ou rejeita um pedido pendente, o código passa **pela mesma trava**:

```java
private Order decide(UUID orderId, Consumer<Order> transition) {
    Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found"));

    creditLocker.acquire(order.getCompanyId());   // mesma fila da criação
    entityManager.refresh(order);                 // relê o pedido depois de esperar

    transition.accept(order);
    return order;
}
```

- A busca inicial serve só para descobrir **de qual empresa** é o pedido, e assim saber qual
  trava pegar.
- Depois de trancar, **`entityManager.refresh(order)`** relê o pedido do banco. Enquanto
  esperava na fila, outro vendedor pode ter acabado de aprovar esse mesmo pedido, e sem a
  releitura o código decidiria com base num dado velho (poderia aprovar duas vezes).
- A rejeição não muda a exposição, mas passa pela trava assim mesmo: é isso que impede uma
  aprovação e uma rejeição simultâneas do mesmo pedido de "ganharem as duas".

## Resumindo com uma analogia

Pensa no caixa de uma loja que vende fiado. Cada cliente (empresa) tem um **limite** e uma
**caderneta** própria (a linha em `company_credit_lock`). A regra da loja é: antes de anotar
uma compra nova, pegue a caderneta daquele cliente, some o que ele já deve, veja se a compra
cabe no limite (`CreditPolicy`) e só então devolva a caderneta. Se dois atendentes forem
atender o mesmo cliente ao mesmo tempo, o segundo **espera** o primeiro devolver a caderneta,
e aí faz a conta já vendo a compra anterior. Assim ninguém vende além do limite por ter olhado
uma conta desatualizada. Clientes diferentes têm cadernetas diferentes, então ninguém espera à
toa. E se a caderneta de um cliente novo ainda não existir, o primeiro atendente que chegar
cria uma em branco (`ensureExists`).
