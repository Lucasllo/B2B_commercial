# Os services de pedido — `OrderCreationService`, `OrderService`, `OrderDecisionService` e `OrderShipmentService`

Arquivos:
- `order-service/src/main/java/com/orderflow/order/order/` (os services e os controllers
  `OrderController`, `OrderDecisionController` e `OrderShipmentController`)
- `order-service/src/main/java/com/orderflow/order/timeline/OrderTimelineEvents.java` (os eventos
  da linha do tempo, desde a Fase 6)

## O que são, em uma frase

São a "gerência" dos pedidos: os controllers recebem a requisição HTTP e passam o trabalho
para eles, e cada um cuida de uma parte da vida de um pedido.

| Classe | Responsabilidade | Quem chama |
|---|---|---|
| `OrderCreationService` | **Preparar** um pedido novo: conferir produtos, calcular preços, buscar o limite de crédito | `POST /orders` |
| `OrderService` | **Gravar** o pedido novo com a checagem de crédito, e **consultar** pedidos | `OrderCreationService`, `GET /orders`, `GET /orders/{id}` |
| `OrderDecisionService` | **Decidir** um pedido pendente: o vendedor aprova ou rejeita | `POST /orders/{id}/approve` e `/reject` |
| `OrderShipmentService` | **Expedir** e registrar a **entrega** de um pedido confirmado (Fase 6) | `POST /orders/{id}/ship` e `/deliver` |

Os quatro usam um ajudante comum, o `OrderTimelineEvents`, que grava no outbox um evento de linha
do tempo a cada mudança de status (seção 6).

## 1. Por que existe uma camada "Service"?

O projeto segue uma divisão em camadas, como uma empresa com setores:

```
Cliente (navegador, Postman)
   ↓  requisição HTTP
Controller   → "recepção": recebe o pedido, lê quem é o usuário no JWT, devolve a resposta
   ↓
Service      → "gerência": aplica as regras de negócio (é aqui que estão estas classes)
   ↓
Repository   → "arquivo": lê e grava no banco de dados
```

O controller não sabe as regras de negócio, e o repository não sabe o que é um "pedido
aprovado". Toda a lógica fica no service.

## 2. `OrderCreationService`: preparar o pedido

Um comprador (papel `BUYER`) envia algo assim:

```json
{ "items": [ { "productId": "abc...", "quantity": 10 },
             { "productId": "def...", "quantity": 3 } ] }
```

Repare que o comprador **não manda preço**. Se mandasse, poderia mentir e dizer que o
produto custa R$ 0,01. O preço sempre vem do `catalog-service`. O `companyId` também não
vem do corpo: o `OrderController` lê do JWT (a claim `company_id`).

O método `create` faz as conferências nesta ordem, **da mais barata para a mais cara**.

### Passo 1: produto repetido?

```java
private void rejectDuplicateProductIds(List<OrderItemRequest> items) {
    Set<UUID> seen = new HashSet<>();
    for (OrderItemRequest item : items) {
        if (!seen.add(item.productId())) {
            throw new DuplicateOrderItemsException();
        }
    }
}
```

Um `Set` é uma coleção que não aceita repetidos. O `add` devolve `false` quando o item já
estava lá. Se o mesmo produto aparece duas vezes, o pedido é recusado na hora. Essa checagem
é só memória, sem rede, então vem primeiro.

### Passo 2: os produtos existem? Quanto custam?

```java
for (OrderItemRequest itemRequest : request.items()) {
    Optional<CatalogProductResponse> product =
            catalogServiceClient.findOrderableProduct(itemRequest.productId(), bearerToken);
    if (product.isEmpty()) {
        invalidProductIds.add(itemRequest.productId());
        continue;
    }

    CatalogProductResponse catalogProduct = product.get();
    BigDecimal subtotal = catalogProduct.price().multiply(BigDecimal.valueOf(itemRequest.quantity()));
    pricedItems.add(new PricedItem(lineNumber++, catalogProduct.id(), catalogProduct.sku(),
            catalogProduct.name(), catalogProduct.price(), itemRequest.quantity(), subtotal));
}
```

Para cada item, pergunta ao `catalog-service` pela rede (com o `RestClient` montado a partir
do `ClientProperties`, ver [20-configuration-properties.md](20-configuration-properties.md)).

- **`Optional`** é uma "caixa que pode estar vazia". `isEmpty()` quer dizer "o produto não
  existe ou não está disponível para venda".
- Se o produto não existir, o id vai para uma lista de inválidos, e o laço **continua**
  (`continue`) para conferir os próximos.
- Se existir, calcula `preço × quantidade` e guarda como um **`PricedItem`** ("item já
  precificado"), com o nome, o SKU e o preço daquele momento. Assim, se o preço mudar no
  catálogo amanhã, o pedido de hoje guarda o preço que valia quando foi feito.

### Passo 3: tudo ou nada

```java
if (!invalidProductIds.isEmpty()) {
    throw new InvalidOrderItemsException(invalidProductIds);
}
```

Se **um** item for inválido, o pedido **inteiro** é recusado. Nada é criado pela metade. O
erro devolve a lista completa de ids inválidos, para o comprador corrigir tudo de uma vez em
vez de descobrir um erro por tentativa.

### Passo 4: o total cabe na coluna do banco?

```java
private void guardTotalFitsInColumn(BigDecimal total) {
    int integerDigits = total.precision() - total.scale();
    if (integerDigits > MAX_TOTAL_INTEGER_DIGITS) {
        throw new OrderTotalOutOfRangeException();
    }
}
```

A coluna do total no Postgres é `NUMERIC(19,2)`: 19 dígitos no total, 2 depois da vírgula,
ou seja, até 17 antes. Um pedido absurdo (quantidade gigante × preço alto) estouraria essa
coluna. No `BigDecimal`, `precision()` é o total de dígitos e `scale()` é quantos estão
depois da vírgula, então a diferença é quantos estão **antes**. É o mesmo critério do
`@Digits(integer = 17, fraction = 2)` visto em [12-records-e-anotacoes.md](12-records-e-anotacoes.md).

### Passo 5: buscar o limite de crédito

```java
BigDecimal creditLimit = authServiceClient.getCreditLimit(companyId, bearerToken);
```

Uma segunda chamada de rede, agora ao `auth-service`. Ela vem **por último** de propósito:
não faz sentido gastar uma chamada de rede para um pedido que já seria recusado por um item
inválido.

### Passo 6: passar o trabalho adiante

```java
return orderService.createWithCreditCheck(companyId, createdBy, pricedItems, creditLimit);
```

Com tudo preparado, ela entrega para o `OrderService` gravar.

### Por que "mais barato primeiro"?

É como a fila de embarque de um aeroporto: primeiro se confere o nome no cartão, que é
rápido, e só depois se passa pelo raio-X, que é demorado. Se o nome estiver errado, ninguém
perde tempo no raio-X. Aqui: checagens em memória primeiro, chamadas de rede depois, e a
chamada ao `auth-service` só acontece se todo o resto passou.

## 3. Por que separar `OrderCreationService` e `OrderService`?

Esta é a decisão mais importante das classes de criação, e tem dois motivos.

### Motivo A: não fazer chamadas de rede com a empresa trancada

O `OrderService.createWithCreditCheck` usa `@Transactional` e **tranca a empresa** (a trava
de crédito explicada em [21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md)).
Enquanto a trava está presa, outros pedidos da mesma empresa **ficam esperando**.

Se as chamadas ao `catalog-service` e ao `auth-service` acontecessem **dentro** dessa
transação e um desses serviços demorasse 3 segundos para responder, todos os outros pedidos
da empresa ficariam 3 segundos parados. Por isso a regra é:

- **`OrderCreationService`**: **sem** `@Transactional`. Faz todas as chamadas de rede, que
  são lentas e imprevisíveis.
- **`OrderService`**: **com** `@Transactional`. Só faz contas e acessa o banco local, que são
  coisas rápidas. A trava fica presa pelo menor tempo possível.

### Motivo B: a armadilha do `@Transactional` na mesma classe

Por que não colocar os dois métodos na mesma classe, um com `@Transactional` e o outro sem?

O `@Transactional` funciona porque o Spring coloca um **intermediário** (chamado *proxy*) na
frente da classe. Quando **outra classe** chama o método, a chamada passa pelo intermediário,
e é ele que abre a transação:

```
OrderCreationService  →  [proxy do Spring: abre transação]  →  OrderService.createWithCreditCheck
```

Mas quando um método chama **outro método da mesma classe**, a chamada vai direto, sem passar
pelo intermediário:

```
UmaClasseSo.create()  →  this.createWithCreditCheck()     (sem proxy: @Transactional ignorado!)
```

Resultado: o `@Transactional` é **ignorado em silêncio**. Não há erro nem aviso, e a trava de
crédito para de funcionar, porque ela só vale dentro de uma transação. Separar em duas classes
garante que a chamada sempre passa pelo proxy. É o mesmo princípio de "a anotação é só uma
etiqueta; quem faz o trabalho é o mecanismo por trás", visto em
[14-spring-retry.md](14-spring-retry.md).

## 4. `OrderService`: gravar e consultar

### `createWithCreditCheck`: criar com checagem de crédito

Recebe os itens já precificados e o limite já lido. Em detalhes na nota 21, mas em resumo:

1. Tranca a empresa (`creditLocker.acquire`)
2. Monta o pedido (`Order.create`)
3. Soma quanto a empresa já deve (a exposição)
4. Cabe no limite? **Aprova automaticamente.** Não cabe? **Fica pendente** para o vendedor
5. Salva no banco
6. Grava os eventos da linha do tempo: `ORDER_CREATED` e, em seguida, `ORDER_APPROVED` ou
   `ORDER_PENDING_APPROVAL` (seção 6)
7. Se foi aprovado, **entra na saga**: `sagaStarter.start(order, now)` muda o status para
   `RESERVING` e grava o comando `ReserveStock` no outbox, **na mesma transação** (Fase 5, ver
   [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md))

```java
orderTimelineEvents.created(order);
if (order.getStatus() == OrderStatus.APPROVED) {
    orderTimelineEvents.approved(order);
    sagaStarter.start(order, now);
} else {
    orderTimelineEvents.pendingApproval(order);
}
```

Por isso um pedido dentro do limite **responde `RESERVING`**, não `APPROVED`. A aprovação
continua registrada (`decidedBy = SYSTEM`, `decidedAt`), mas `APPROVED` virou só um passo
dentro da transação, nunca o status gravado. O comprador recebe o 201 antes de o estoque ser
tocado; o resultado da reserva chega depois, por fila.

Quem muda o status não é o service: é o próprio pedido, com `order.approveAutomatically(now)`
e `order.holdForApproval()`. O service decide **qual** transição chamar, e a classe `Order`
garante que a transição é permitida (por exemplo, só um pedido recém-criado pode ser aprovado
automaticamente). Assim a regra de "de qual status para qual status pode ir" fica num lugar
só.

### `getById`: buscar um pedido

```java
@Transactional(readOnly = true)
public OrderResponse getById(UUID orderId, UUID callerCompanyId, boolean sellerView) {
    Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found"));
    if (!sellerView && !order.getCompanyId().equals(callerCompanyId)) {
        throw new OrderNotFoundException("Order not found");
    }
    return OrderResponse.from(order);
}
```

- **`readOnly = true`** avisa que a transação só vai ler, o que permite algumas otimizações
  (o Hibernate nem procura mudanças para gravar no final).
- **Vendedor** (`sellerView = true`) vê qualquer pedido.
- **Comprador** só vê os pedidos da própria empresa. Se tentar ver o de outra, recebe o
  **mesmo 404 de um pedido que não existe**. É proposital: um "403 – proibido" revelaria que o
  pedido **existe**, e isso já é informação vazada. Com 404, ele não descobre nada. Mesmo
  espírito do [11-companyguard.md](11-companyguard.md).
- `sellerView` e `callerCompanyId` são calculados no `OrderController` a partir do JWT, nunca
  de um parâmetro que o cliente possa escolher.

### `list`: listar pedidos com paginação

```java
@Transactional(readOnly = true)
public Page<OrderSummaryResponse> list(UUID callerCompanyId, boolean sellerView, OrderStatus status,
                                        Pageable pageable) {
    Sort fixedSort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), fixedSort);

    Page<Order> page;
    if (sellerView) {
        page = status == null
                ? orderRepository.findAll(sortedPageable)
                : orderRepository.findByStatus(status, sortedPageable);
    } else {
        page = status == null
                ? orderRepository.findByCompanyId(callerCompanyId, sortedPageable)
                : orderRepository.findByCompanyIdAndStatus(callerCompanyId, status, sortedPageable);
    }
    return page.map(OrderSummaryResponse::from);
}
```

- **Paginação** (`Pageable` / `Page`): em vez de devolver 10.000 pedidos de uma vez, devolve
  em páginas (`?page=0&size=20`). O `Page` também informa quantas páginas e itens existem no
  total.
- **Ordenação fixa**: sempre do mais novo para o mais antigo. Se o cliente mandar
  `?sort=...`, o parâmetro é **descartado**: o código cria um `Pageable` novo, com o número e o
  tamanho da página recebidos, mas com a ordenação dele. O `id` como desempate garante uma
  ordem estável, para dois pedidos criados no mesmo instante não "pularem" entre páginas.
- **`condição ? A : B`** é o operador ternário: "se a condição for verdadeira, use A; senão,
  B". Os quatro casos escolhem a consulta certa: vendedor ou comprador, com ou sem filtro de
  `status`. Para o vendedor, `?status=PENDING_APPROVAL` é a **fila de aprovação**.
- **`page.map(OrderSummaryResponse::from)`** transforma cada `Order` num resumo **sem os
  itens**, para a listagem ficar leve. `OrderSummaryResponse::from` é uma *referência de
  método*, um jeito curto de escrever `order -> OrderSummaryResponse.from(order)`.

## 5. `OrderDecisionService`: aprovar ou rejeitar manualmente

Quando um pedido estoura o limite, ele fica `PENDING_APPROVAL`. Um vendedor (papel
`SELLER_ADMIN`) decide:

```java
@Transactional
public OrderResponse approve(UUID orderId, String sellerId, String reason) {
    Order order = decide(orderId, o -> o.approveManually(sellerId, reason, currentInstant()));
    orderTimelineEvents.approved(order);
    sagaStarter.start(order, order.getDecidedAt());
    return OrderResponse.from(order);
}

@Transactional
public OrderResponse reject(UUID orderId, String sellerId, String reason) {
    Order order = decide(orderId, o -> o.reject(sellerId, reason, currentInstant()));
    orderTimelineEvents.rejected(order);
    return OrderResponse.from(order);
}
```

- **`sagaStarter.start`** (Fase 5): a aprovação manual entra na **mesma saga** da aprovação
  automática, pelo mesmo `ReservationSagaStarter`, e responde 200 com o pedido já em
  `RESERVING`. Pedido rejeitado nunca pede estoque.
- **`orderTimelineEvents.approved` / `.rejected`** (Fase 6): gravam `ORDER_APPROVED` ou
  `ORDER_REJECTED` no outbox para a linha do tempo (seção 6).

O miolo dos dois métodos é igual. A única diferença é **qual ação** fazer no pedido. Em vez de
repetir o código, os dois chamam um método comum, `decide`, e passam a ação como parâmetro.

### O que é `o -> o.approveManually(...)`?

É uma **lambda**: um pedacinho de código passado como se fosse um valor. Leia como: "dado um
pedido `o`, faça `o.approveManually(...)` nele". O tipo que recebe essa lambda é
`Consumer<Order>`: "algo que recebe um `Order` e faz alguma coisa com ele, sem devolver
nada".

### O método `decide`: o roteiro comum

```java
private Order decide(UUID orderId, Consumer<Order> transition) {
    Order order = orderRepository.findById(orderId)          // 1. busca o pedido
            .orElseThrow(() -> new OrderNotFoundException("Order not found"));

    creditLocker.acquire(order.getCompanyId());              // 2. tranca a empresa
    entityManager.refresh(order);                            // 3. relê o pedido

    transition.accept(order);                                // 4. executa a ação
    return order;
}
```

1. **Busca**: serve só para descobrir **de qual empresa** é o pedido, e assim saber qual
   trava pegar.
2. **Tranca**: a mesma trava da criação. Assim, uma aprovação manual e um pedido novo da
   mesma empresa não se atropelam na conta da exposição.
3. **Relê** (`entityManager.refresh`): enquanto esperava a trava, outro vendedor pode ter
   decidido esse mesmo pedido. Sem reler, o código veria o status antigo (`PENDING_APPROVAL`)
   e aprovaria de novo. O `refresh` descarta o que estava na memória e busca o estado atual do
   banco.
4. **Executa**: `transition.accept(order)` roda a lambda. É aqui que `approveManually` ou
   `reject` é chamado. O próprio `Order` confere se o status é `PENDING_APPROVAL` e, se não
   for, lança `OrderNotPendingException`. O `reject` também exige um motivo (`reason`)
   preenchido.

### Por que não há `save(order)` no final?

Dentro de uma transação, o JPA **acompanha** os objetos que leu do banco. No `commit`, ele
confere o que mudou e grava sozinho. Esse recurso se chama *dirty checking* ("checagem de
sujeira": um objeto alterado está "sujo" e precisa ser gravado).

### Outros detalhes

- **O horário é tomado depois da trava** (`currentInstant()`), truncado para microssegundos,
  que é a precisão do `TIMESTAMPTZ` do Postgres. Assim a ordem dos horários de decisão segue a
  ordem real em que as decisões passaram pela fila.
- **A decisão não recalcula o limite de crédito.** O vendedor está justamente dizendo "aprovo
  mesmo passando do limite". Por isso esta classe nem conhece os clientes HTTP.
- **A rejeição também passa pela trava**, mesmo sem mudar a exposição. É isso que impede uma
  aprovação e uma rejeição simultâneas do mesmo pedido de "ganharem as duas".
- **O `sellerId` vem do JWT** (a claim `sub`), lido no `OrderDecisionController`, nunca do
  corpo da requisição. Ninguém consegue se passar por outro vendedor escrevendo o id de outra
  pessoa no JSON.

## 6. Os services mais novos (Fases 5 e 6)

Depois que o pedido foi criado e decidido, outras classes cuidam do resto da vida dele. Aqui vai
só o resumo; o detalhe está nas notas 26 e 30.

### `OrderShipmentService`: expedir e entregar

Chamado pelo `OrderShipmentController` (só `SELLER_ADMIN`, sem corpo, quem agiu vem do `sub`
do JWT):

```java
@Transactional
public OrderResponse ship(UUID orderId, String sellerId) {
    Order order = lockOrder(orderId);
    OffsetDateTime now = currentInstant();

    order.ship(sellerId, now);

    ShipStockCommand command = ShipStockCommand.from(order, UUID.randomUUID(), now.toInstant());
    outboxWriter.enqueue(command.eventId(), ShipStockCommand.EVENT_TYPE, order.getId().toString(), command);
    orderTimelineEvents.shipped(order);
    return OrderResponse.from(order);
}
```

- **`lockOrder`** usa `findByIdForUpdate`: tranca a **linha do pedido**, a mesma trava da saga.
  Não precisa da trava de crédito da empresa, porque `CONFIRMED`, `SHIPPED` e `DELIVERED` já
  consomem crédito e a exposição não muda.
- **`ship`** leva `CONFIRMED` → `SHIPPED` e grava, na mesma transação, o comando `ShipStock`
  (para o estoque dar baixa física) e o evento `ORDER_SHIPPED`.
- **`deliver`** leva `SHIPPED` → `DELIVERED` e só grava `ORDER_DELIVERED`. Não fala com o
  estoque.
- Fora de ordem (expedir um pedido `RESERVING`, entregar um `CONFIRMED`…): 409
  `invalid_order_transition`.

Tudo isso está em [30-ciclo-de-vida-expedicao-e-entrega.md](30-ciclo-de-vida-expedicao-e-entrega.md).

### `OrderTimelineEvents`: o "escrivão" da linha do tempo

Cada mudança de status precisa virar um evento `ORDER_*` para o `notification-service` montar a
linha do tempo do pedido. Quem grava é esta classe, com um método por tipo de evento:

```java
@Transactional(propagation = Propagation.MANDATORY)
public void shipped(Order order) {
    enqueue(order, OrderLifecycleEvent.ORDER_SHIPPED, OrderLifecycleEvent::shipped);
}
```

- **`Propagation.MANDATORY`** de novo: o método **exige** uma transação já aberta e falha alto se
  não houver. Assim o evento é gravado na mesma transação da mudança de status. A linha do tempo
  nunca mostra uma transição que não aconteceu, nem esquece uma que aconteceu. É o mesmo truque
  do `CompanyCreditLocker` (nota 21) e do `ReservationSagaStarter` (nota 26).
- **Nunca fala com o SQS.** Só chama `outboxWriter.enqueue`. Quem envia é o `OutboxRelay`, que
  manda os tipos `ORDER_*` para a `notification-events-queue`.
- São oito tipos: `ORDER_CREATED`, `ORDER_PENDING_APPROVAL`, `ORDER_APPROVED`, `ORDER_REJECTED`,
  `ORDER_CONFIRMED`, `ORDER_CANCELLED`, `ORDER_SHIPPED` e `ORDER_DELIVERED`. A entrada em
  `RESERVING` não tem evento próprio: o `ORDER_APPROVED` já cobre.

Quem chama cada método:

| Onde | Eventos |
|---|---|
| `OrderService.createWithCreditCheck` | `created` + `approved` ou `pendingApproval` |
| `OrderDecisionService.approve` / `reject` | `approved` / `rejected` |
| `OrderSagaService.applyStockReserved` (só quando confirma) | `confirmed` |
| `OrderSagaService.applyReservationFailed` e `expireReservation` | `cancelled` |
| `OrderShipmentService.ship` / `deliver` | `shipped` / `delivered` |

### `OrderSagaService` e `SagaTimeoutJob`: a parte assíncrona

Não são chamados por controller nenhum. O `OrderSagaService` reage às respostas do estoque
(`CONFIRMED` ou `CANCELLED`), e o `SagaTimeoutJob` (`@Scheduled`) cancela pedidos parados em
`RESERVING` há mais de 2 minutos. Os dois estão em
[26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md).

## 7. O caminho completo de um pedido

```
Comprador: POST /orders
   → OrderController           (exige papel BUYER, lê company_id e sub do JWT)
   → OrderCreationService      (sem transação: duplicados → catálogo → total → limite)
   → OrderService              (com transação: trava → exposição → RESERVING ou PENDING_APPROVAL)
   ← 201 Created

Se ficou PENDING_APPROVAL:
Vendedor: GET /orders?status=PENDING_APPROVAL   → OrderService.list  (a fila de aprovação)
Vendedor: POST /orders/{id}/approve ou /reject  → OrderDecisionController (exige SELLER_ADMIN)
   → OrderDecisionService      (trava → relê → aprova (→ RESERVING) ou rejeita (→ REJECTED))

Se ficou RESERVING (Fase 5, assíncrono):
   OutboxRelay → inventory-commands-queue → inventory-service reserva
   → order-events-queue → OrderSagaService → CONFIRMED ou CANCELLED
   (ou SagaTimeoutJob → CANCELLED, se a resposta não chegar a tempo)

Se ficou CONFIRMED (Fase 6):
Vendedor: POST /orders/{id}/ship     → OrderShipmentController → OrderShipmentService → SHIPPED
Vendedor: POST /orders/{id}/deliver  → OrderShipmentController → OrderShipmentService → DELIVERED
```

A parte assíncrona está em [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md);
a expedição e a entrega, em [30-ciclo-de-vida-expedicao-e-entrega.md](30-ciclo-de-vida-expedicao-e-entrega.md).

## Resumindo com uma analogia

Pensa numa loja de atacado com quatro funcionários:

- O **atendente** (`OrderCreationService`) recebe a lista do cliente, confere se não há itens
  repetidos, liga para o estoque perguntando se cada produto existe e quanto custa, soma tudo
  e liga para o financeiro perguntando o limite do cliente. Ele faz todas as ligações **antes**
  de entrar na sala do cofre, para não segurar a fila lá dentro.
- O **caixa** (`OrderService`) entra na sala do cofre (a transação com a trava), confere a
  caderneta de fiado, aprova ou deixa pendente, anota e sai rápido. Ele também atende quem só
  quer **consultar** pedidos, e para clientes mostra só os pedidos deles, fingindo não conhecer
  os dos outros.
- O **gerente** (`OrderDecisionService`) olha os pedidos pendentes e decide. Antes, entra na
  mesma sala do cofre e **relê a ficha**, para garantir que outro gerente não decidiu aquele
  pedido enquanto ele esperava na porta.
- O **expedidor** (`OrderShipmentService`) pega a ficha de um pedido já confirmado, carimba
  "expedido" e, no mesmo caderno, deixa um bilhete para o almoxarifado dar baixa. Depois, quando
  o cliente recebe, carimba "entregue".
- E todos eles, a cada carimbo, ditam uma linha para o **escrivão** (`OrderTimelineEvents`), que
  só escreve se o carimbo estiver acontecendo naquele mesmo momento, no mesmo caderno.
