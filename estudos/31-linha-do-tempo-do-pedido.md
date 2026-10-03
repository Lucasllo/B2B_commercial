# A linha do tempo do pedido (Fase 6)

Arquivos:

- `order-service/src/main/java/com/orderflow/order/timeline/OrderTimelineEvents.java` (quem grava os eventos)
- `order-service/src/main/java/com/orderflow/order/timeline/OrderLifecycleEvent.java` (o evento, lado produtor)
- `order-service/src/main/java/com/orderflow/order/saga/outbox/OutboxRelay.java` (o roteamento para a fila)
- `notification-service/src/main/java/com/orderflow/notification/history/dto/OrderLifecycleEvent.java` (o evento, lado consumidor)
- `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java` (validação, mensagem, ordenação, regra de leitura)
- `notification-service/src/main/java/com/orderflow/notification/history/NotificationController.java` (`GET /notifications/orders/{orderId}`)
- `notification-service/src/main/java/com/orderflow/notification/history/NotificationNotFoundException.java` e `config/GlobalExceptionHandler.java`

Continuação de [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md) (o outbox e o
roteamento), de [17-dynamodb.md](17-dynamodb.md) (a tabela com partição `entityId`) e de
[26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md) (as transições do
pedido).

## O que é, em uma frase

A **linha do tempo** é a lista de tudo que aconteceu com um pedido, em ordem: criado,
aprovado, confirmado, enviado, entregue. É como o rastreamento de uma encomenda nos
Correios — cada parada vira uma linha, com data, hora e uma frase legível.

O caminho inteiro:

```text
order-service                                    notification-service
  transição do pedido                              NotificationEventListener
  + evento ORDER_* no outbox    --relay-->           -> NotificationService.record
  (mesma transação)          notification-events-queue   -> valida por tipo
                                                         -> monta a frase
                                                         -> grava no DynamoDB (entityId = orderId)

GET /notifications/orders/{orderId}  -> lê a partição do pedido, ordena, aplica a regra de acesso
```

## 1. Os oito tipos de evento

`OrderLifecycleEvent` (no order-service) lista os oito tipos:

| Tipo | Quando acontece | Campos próprios |
|---|---|---|
| `ORDER_CREATED` | pedido criado | `createdBy`, `total` |
| `ORDER_PENDING_APPROVAL` | passou do limite de crédito, espera o vendedor | nenhum |
| `ORDER_APPROVED` | aprovado (automático ou pelo vendedor) | `decidedBy`, `reason` (opcional) |
| `ORDER_REJECTED` | rejeitado pelo vendedor | `decidedBy`, `reason` |
| `ORDER_CONFIRMED` | estoque reservado, transportadora atribuída | `carrier`, `trackingCode` |
| `ORDER_CANCELLED` | reserva falhou ou estourou o prazo | `cancellationCode`, `cancellationReason` |
| `ORDER_SHIPPED` | vendedor enviou | `shippedBy` |
| `ORDER_DELIVERED` | vendedor registrou a entrega | `deliveredBy` |

Todo evento tem ainda os campos comuns: `eventId`, `eventType`, `occurredAt`, `orderId` e
`companyId`. O envelope é **plano** (sem objetos aninhados), e os campos nulos são
omitidos do JSON (`@JsonInclude(JsonInclude.Include.NON_NULL)`). Cada fábrica preenche só
os campos do seu tipo.

A entrada em `RESERVING` **não** tem evento próprio (D-78). Para quem acompanha o pedido,
"aprovado" já diz que a reserva começou.

## 2. Onde cada evento nasce

O componente `OrderTimelineEvents` tem um método por tipo (`created`, `pendingApproval`,
`approved`, `rejected`, `confirmed`, `cancelled`, `shipped`, `delivered`). Todos fazem a
mesma coisa:

```java
private void enqueue(Order order, String eventType, BiFunction<Order, UUID, OrderLifecycleEvent> factory) {
    if (order.getId() == null) {
        throw new IllegalStateException(
                "Order must be persisted (non-null id) before writing a timeline event");
    }
    UUID eventId = UUID.randomUUID();
    OrderLifecycleEvent event = factory.apply(order, eventId);
    outboxWriter.enqueue(eventId, eventType, order.getId().toString(), event);
}
```

Eles são chamados nos services, **logo depois** de cada transição:

| Método do service | Transição | Evento(s) gravado(s) |
|---|---|---|
| `OrderService.createWithCreditCheck` | criação + decisão automática | `created` e depois `approved` **ou** `pendingApproval` |
| `OrderDecisionService.approve` | `PENDING_APPROVAL` → aprovado | `approved` |
| `OrderDecisionService.reject` | `PENDING_APPROVAL` → `REJECTED` | `rejected` |
| `OrderSagaService.applyStockReserved` | `RESERVING` → `CONFIRMED` | `confirmed` (só no ramo que confirma) |
| `OrderSagaService.applyReservationFailed` | `RESERVING` → `CANCELLED` | `cancelled` |
| `OrderSagaService.expireReservation` | `RESERVING` → `CANCELLED` (timeout) | `cancelled` |
| `OrderShipmentService.ship` | `CONFIRMED` → `SHIPPED` | `shipped` (junto com o comando `ShipStock`) |
| `OrderShipmentService.deliver` | `SHIPPED` → `DELIVERED` | `delivered` |

O 06-05-SUMMARY conta isso como "sete pontos de transição, mais a espera de aprovação" —
o cancelamento aparece em dois métodos, mas é a mesma transição.

Repare no `applyStockReserved`: uma resposta duplicada ou um sucesso tardio para um pedido
já cancelado **não** são transições, então não geram evento. Só o ramo que confirma chama
`orderTimelineEvents.confirmed(order)`.

### Por que na mesma transação (D-79)

Todos os métodos de `OrderTimelineEvents` são
`@Transactional(propagation = Propagation.MANDATORY)`. `MANDATORY` quer dizer: "eu só
funciono dentro de uma transação que **já está aberta**; se não houver, falho na hora".

É o outbox de novo (ver [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)). Se o
evento fosse gravado numa transação separada, a linha do tempo poderia mostrar uma
transição que deu rollback, ou esconder uma que foi gravada. Na mesma transação, ou os
dois existem, ou nenhum existe.

Pensa no livro de ponto de uma portaria: quem entra assina o livro **no mesmo gesto** em
que passa pela catraca. Não existe "entrou mas não assinou" nem "assinou mas não entrou".

### De onde vem o `occurredAt`

O horário do evento **nunca** é um `Instant.now()` separado. Ele vem da coluna que a
própria transição gravou:

```java
public static OrderLifecycleEvent approved(Order order, UUID eventId) {
    return new OrderLifecycleEvent(eventId, ORDER_APPROVED, instantOf(order.getDecidedAt(), "decidedAt"),
            ...
```

`created` usa `createdAt`, `approved`/`rejected` usam `decidedAt`, e assim por diante
(`confirmedAt`, `cancelledAt`, `shippedAt`, `deliveredAt`). Se a coluna estiver nula, é
porque alguém pediu o evento antes da transição acontecer — erro de programação,
`IllegalStateException`.

Uma consequência: na criação dentro do limite, `createdAt` e `decidedAt` recebem o mesmo
`now`. `ORDER_CREATED` e `ORDER_APPROVED` saem com o **mesmo** `occurredAt`. A seção 5
mostra como a leitura lida com isso.

## 3. Do outbox para a fila

`OrderTimelineEvents` **nunca** fala com o SQS. Quem envia é o `OutboxRelay`, como em todo
o resto do order-service. O relay escolhe a fila pelo `eventType`:

```java
if (OrderLifecycleEvent.EVENT_TYPES.contains(eventType)) {
    return notificationEventsQueue;
}
```

`EVENT_TYPES` é um `Set.of(...)` com os oito tipos. O roteamento é por **lista
explícita**, não por "começa com `ORDER_`": um tipo esquecido vira erro do próprio evento
em vez de ir parar na fila errada.

A fila é a mesma `notification-events-queue` da Fase 3, que já recebia `STOCK_ADJUSTED`. Não
foi criada fila nova. A mensagem leva o atributo `correlationId` (ver
[27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md)).

## 4. No notification-service: validar por tipo e montar a frase

### Duas cópias do mesmo contrato

O notification-service tem a **sua própria** classe `OrderLifecycleEvent`, em outro
pacote. Nenhum código é compartilhado entre os serviços — o contrato é o **JSON** que passa
pela fila (mesma regra do `StockAdjustedEvent`, D-62).

Na cópia do consumidor, todos os campos são anuláveis (`String`, `BigDecimal`, `UUID`).
De propósito: o consumidor **não confia** no produtor. Um campo ausente precisa ser
detectado como erro, não virar `0` ou `""` em silêncio.

### Despachar pelo tipo

`NotificationService.record` lê o `eventType` e escolhe o caminho:

```java
if (eventType != null && OrderLifecycleEvent.TYPES.contains(eventType)) {
    recordOrderEventWithContext(tree, eventType);
    return;
}
recordStockAdjusted(tree);
```

Para os `ORDER_*`, ele exige os campos comuns (`eventId`, `orderId`, `companyId`,
`occurredAt`) e depois as regras de cada tipo:

| Tipo | Regra |
|---|---|
| `ORDER_CREATED` | `createdBy` até 64 caracteres; `total` presente e não negativo |
| `ORDER_APPROVED` | `decidedBy` até 64; `reason` opcional, até 500 |
| `ORDER_REJECTED` | `decidedBy` até 64; `reason` obrigatório, até 500 |
| `ORDER_CONFIRMED` | `carrier` até 64; `trackingCode` casa `^[A-Z]{2}[0-9]{9}BR$` |
| `ORDER_CANCELLED` | `cancellationCode` casa `^[A-Z_]{1,40}$`; `cancellationReason` até 500 |
| `ORDER_SHIPPED` | `shippedBy` até 64 |
| `ORDER_DELIVERED` | `deliveredBy` até 64 |

Qualquer regra quebrada lança `InvalidNotificationEventException`, e o evento é
**descartado inteiro**. Nunca se grava metade de um evento. O listener registra um `WARN`
com o `orderId` e o `eventType` (já sanitizados) para o buraco na linha do tempo poder ser
investigado (WR-03, ver [19-sqslistener-consumo.md](19-sqslistener-consumo.md)).

### A frase é montada no servidor

O evento **não traz** a frase pronta. O notification-service a monta a partir dos campos
que ele mesmo conferiu:

| Tipo | Frase gravada em `message` |
|---|---|
| `ORDER_CREATED` | `Pedido criado — total 40.00` |
| `ORDER_PENDING_APPROVAL` | `Pedido aguardando aprovação do vendedor — valor acima do limite de crédito disponível` |
| `ORDER_APPROVED` (`decidedBy = SYSTEM`) | `Pedido aprovado automaticamente — dentro do limite de crédito` |
| `ORDER_APPROVED` (vendedor) | `Pedido aprovado pelo vendedor <decidedBy>` (+ ` — motivo: <reason>` se houver) |
| `ORDER_REJECTED` | `Pedido rejeitado pelo vendedor <decidedBy> — motivo: <reason>` |
| `ORDER_CONFIRMED` | `Pedido confirmado — transportadora <carrier>, rastreio <trackingCode>` |
| `ORDER_CANCELLED` | `Pedido cancelado (<cancellationCode>) — <cancellationReason>` |
| `ORDER_SHIPPED` | `Pedido enviado pelo vendedor <shippedBy>` |
| `ORDER_DELIVERED` | `Pedido entregue — registrado pelo vendedor <deliveredBy>` |

Por que não deixar o order-service mandar a frase? Porque aí a frase seria **texto livre**
vindo de fora, e quem consulta a linha do tempo leria o que qualquer um conseguisse pôr
na fila. Montando aqui, o formato é sempre o mesmo, e cada pedaço variável passou por uma
regra de tamanho ou de padrão.

O `total` sempre sai com duas casas: `event.total().setScale(2, RoundingMode.HALF_UP)`.
O leitor do JSON usa `USE_BIG_DECIMAL_FOR_FLOATS` para o valor em dinheiro não passar por
`double` (onde `40.00` viraria `40.0`). O JSON original continua guardado em `rawPayload`.

### Gravando

O item vai para a tabela `notification-history` com:

- `entityId` = o `orderId` (a gaveta do pedido — ver [17-dynamodb.md](17-dynamodb.md));
- `companyId` = a empresa compradora (usado na regra de leitura, seção 6);
- `sortKey` = `eventType#eventId`.

A gravação é `putItem` sem condição: se o relay mandar o mesmo evento duas vezes, a
segunda gravação cai na mesma chave e sobrescreve com o mesmo conteúdo. Uma linha só.

## 5. Lendo em ordem de ciclo de vida

A fila não garante ordem de chegada, e o DynamoDB devolve os itens pela `sortKey` (que não
é cronológica). A ordem é refeita na leitura:

```java
records.sort(Comparator.comparing(NotificationRecord::getOccurredAt)
        .thenComparingInt(record -> lifecycleRank(record.getEventType()))
        .thenComparing(NotificationRecord::getSortKey));
```

O `lifecycleRank` resolve o empate da seção 2: `ORDER_CREATED` vale 1 e `ORDER_APPROVED`
vale 3, então "criado" vem antes de "aprovado" mesmo com o mesmo `occurredAt`. Sem ele, a
ordem alfabética da `sortKey` poria `ORDER_APPROVED#...` antes de `ORDER_CREATED#...`. Os
detalhes estão em [17-dynamodb.md](17-dynamodb.md).

## 6. `GET /notifications/orders/{orderId}` — quem pode ver

Pelo Gateway, a rota é `GET /api/notifications/orders/{orderId}`. O controller aceita dois
papéis:

```java
@PreAuthorize("hasAnyRole('SELLER_ADMIN','BUYER')")
public List<NotificationResponse> getOrderTimeline(@PathVariable UUID orderId, Authentication authentication) {
    boolean sellerView = isSellerAdmin(authentication);
    UUID callerCompanyId = sellerView ? null : requireCompanyId((Jwt) authentication.getPrincipal());
    return notificationService.historyForOrder(orderId, callerCompanyId, sellerView);
}
```

(Trecho sem as anotações do Swagger.) A empresa do comprador vem **só** do claim
`company_id` do JWT, nunca do caminho nem de parâmetro. Um `BUYER` sem `company_id` válido
recebe 403.

A decisão fica em `historyForOrder`:

```java
List<NotificationRecord> orderRecords = notificationRepository.findByEntityId(orderId.toString())
        .stream()
        .filter(record -> OrderLifecycleEvent.TYPES.contains(record.getEventType()))
        .toList();

if (!sellerView) {
    String callerCompany = callerCompanyId == null ? null : callerCompanyId.toString();
    boolean ownedByCaller = !orderRecords.isEmpty() && callerCompany != null
            && orderRecords.stream().allMatch(record -> callerCompany.equals(record.getCompanyId()));
    if (!ownedByCaller) {
        throw new NotificationNotFoundException();
    }
}
return toSortedResponses(orderRecords);
```

- **`SELLER_ADMIN`** vê a linha do tempo de qualquer pedido. Se não houver eventos, recebe
  uma lista vazia.
- **`BUYER`** só vê se houver eventos **e todos** tiverem o `companyId` da empresa dele.
- O filtro por `eventType` garante que a rota de pedido só devolve `ORDER_*`, nunca um
  `STOCK_ADJUSTED`.

O "todos" (`allMatch`) não é exagero. Se alguém conseguisse pôr na fila um evento forjado
com o `orderId` de um pedido alheio e a própria empresa, a partição teria empresas
misturadas. Nesse caso o comprador também recebe 404.

### O 404 idêntico — e por que ele impede a enumeração

O comprador recebe **exatamente a mesma resposta** em quatro situações diferentes:

- o pedido é de outra empresa;
- o pedido não existe;
- o pedido existe, mas ainda não tem eventos;
- a partição tem empresas misturadas.

```java
@ExceptionHandler(NotificationNotFoundException.class)
public ResponseEntity<Map<String, Object>> handleNotFound(NotificationNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorBody("order_not_found", "Order not found"));
}
```

A mensagem é fixa (`"Order not found"`) e nenhum identificador entra nela.

Por que não responder `403` ("esse pedido existe, mas não é seu") para o pedido de outra
empresa? Porque essa diferença **vaza informação**. Um comprador mal-intencionado poderia
testar ids um por um: cada `403` confirmaria "aqui tem um pedido de alguém". Isso se chama
**enumeração** — e é a porta de entrada de um ataque IDOR (*Insecure Direct Object
Reference*, acessar um objeto alheio só trocando o id na URL).

Com o 404 idêntico, para o comprador o pedido de outra empresa é **indistinguível** de um
pedido que não existe. Ele não aprende nada testando ids. É a mesma regra que o
order-service já usa no `GET /orders/{id}` (D-47), agora repetida no notification-service
(D-81).

Pensa num cofre de banco: se você pergunta pela caixa 512 e ela não é sua, o atendente não
diz "essa é de outro cliente". Diz "não temos nada para você nessa caixa" — a mesma frase
que diria se a caixa estivesse vazia.

### Os outros códigos

| Situação | Resposta |
|---|---|
| sem token ou token inválido | 401 `unauthorized` |
| papel diferente de `SELLER_ADMIN`/`BUYER`, ou `BUYER` sem `company_id` válido | 403 `forbidden` |
| `orderId` não é UUID | 400 `invalid_identifier` |
| DynamoDB fora do ar | 503 `notification_store_unavailable` |

A rota antiga, `GET /notifications/{productId}` (histórico de estoque), continua só para
`SELLER_ADMIN`.

## Resumindo com uma analogia

O pedido é uma encomenda, e a linha do tempo é o rastreamento dela.

- **`OrderTimelineEvents`** = o funcionário que carimba o livro de ocorrências **no mesmo
  gesto** em que a encomenda muda de etapa.
- **O relay e a fila** = o malote que leva as folhas carimbadas para a central de
  rastreamento.
- **O notification-service** = a central: confere cada folha (campos, tamanhos, formatos),
  joga fora a folha rasurada inteira, escreve a frase padrão e arquiva na pasta do pedido.
- **`lifecycleRank`** = duas folhas com o mesmo carimbo de horário são arquivadas na ordem
  natural da história.
- **O 404 idêntico** = o balcão da central responde "não encontramos essa encomenda" para
  quem não é o dono — a mesma frase para "não existe" e "não é sua".
