# A saga de reserva de estoque (Fase 5)

Pastas:
- `order-service/src/main/java/com/orderflow/order/saga/` (início da saga, outbox, consumo do resultado)
- `inventory-service/src/main/java/com/orderflow/inventory/saga/` e `.../stock/InventoryService.java` (reserva)
- `localstack-init/ready.d/02-create-order-saga-resources.sh` (as filas)

Continuação de [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md), onde o outbox foi
explicado como ideia, e de [22-services-de-pedido.md](22-services-de-pedido.md), onde o pedido
era criado e aprovado. Aqui o pedido aprovado finalmente **pede o estoque** e termina
`CONFIRMED` ou `CANCELLED`.

## O que é uma saga, em uma frase

Uma **saga** é uma operação que atravessa vários serviços e é feita em **passos locais**, cada
um com sua própria transação. Se um passo falha, os anteriores são **desfeitos por outro passo**
(a *compensação*), porque não existe uma transação única que abrace dois bancos diferentes.

Aqui a saga tem dois participantes:

| Passo | Serviço | Transação local |
|---|---|---|
| Aprovar e pedir estoque | `order-service` | Pedido vira `RESERVING` + comando no outbox |
| Reservar | `inventory-service` | Reserva todos os itens (ou nenhum) + resultado no outbox |
| Fechar | `order-service` | Pedido vira `CONFIRMED` ou `CANCELLED` |

É uma saga **orquestrada**: o `order-service` é o "maestro" que manda comandos e decide o que
fazer com cada resposta. O `inventory-service` só obedece e responde.

## 1. O caminho inteiro

```
order-service                          SQS                             inventory-service
─────────────                          ───                             ─────────────────
aprovação (auto ou manual)
  ReservationSagaStarter.start
  ├─ pedido → RESERVING           ┐
  └─ OutboxWriter.enqueue         ┘ mesma transação
OutboxRelayJob (a cada 1 s)
  OutboxRelay → ReserveStock ──► inventory-commands-queue ──► ReservationCommandListener
                                                                InventoryService.reserveAll
                                                                ├─ reserva tudo ou nada   ┐
                                                                └─ OutboxWriter.enqueue   ┘ mesma transação
                                                              OutboxRelay (a cada 1 s)
ReservationResultListener ◄──── order-events-queue ◄───────── StockReserved / StockReservationFailed
  OrderSagaService
  └─ RESERVING → CONFIRMED ou CANCELLED
```

Repara que os **dois lados usam o mesmo padrão**: cada serviço grava a mudança de negócio e a
mensagem na mesma transação, e um relay separado envia depois.

## 2. Entrando na saga — `ReservationSagaStarter`

```java
@Transactional(propagation = Propagation.MANDATORY)
public void start(Order order, OffsetDateTime now) {
    order.startReservation(now);                       // status = RESERVING
    UUID eventId = UUID.randomUUID();
    ReserveStockCommand command = ReserveStockCommand.from(order, eventId, now.toInstant());
    outboxWriter.enqueue(command.eventId(), ReserveStockCommand.EVENT_TYPE, order.getId().toString(), command);
}
```

- É o **único ponto de entrada** da saga. A aprovação automática (`OrderService`) e a manual
  (`OrderDecisionService.approve`) chamam o mesmo método.
- `Propagation.MANDATORY` quer dizer "só funciono **dentro** de uma transação já aberta; se não
  houver, falho na hora". É o mesmo truque do `CompanyCreditLocker` (ver
  [21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md)). Assim é impossível
  alguém chamar isto sozinho e gravar um comando sem o pedido ter mudado de status, ou o
  contrário.
- **`APPROVED` virou um passo lógico, não um lugar para ficar.** A decisão ainda é registrada
  (`decidedBy`, `decidedAt`, `reason`), mas o status gravado vai direto para `RESERVING`. Nunca
  existe "pedido aprovado sem comando enviado".

## 3. O outbox — `OutboxWriter`, `OutboxRelay`, `OutboxRelayJob`

### A tabela

Criada em `V2__order_reservation_saga.sql` (e copiada igual em `V2__outbox_event.sql` do
`inventory-service`):

| Coluna | Para quê |
|---|---|
| `id` | O `eventId` da mensagem. **Sem `DEFAULT`**: a aplicação escolhe o UUID, e o mesmo valor vai no JSON |
| `aggregate_id` | O id do pedido a que o evento se refere |
| `event_type` | `ReserveStock`, `StockReserved` etc. Decide para qual fila vai |
| `payload` | O JSON já pronto, serializado **uma vez**, no momento da gravação |
| `published_at` | `NULL` = ainda não enviado |
| `attempts`, `last_error` | Quantas vezes o envio falhou e por quê |

Um **índice parcial** (`WHERE published_at IS NULL`) cobre só as linhas pendentes, que são as
únicas que o relay procura.

### Quem escreve: `OutboxWriter`

Converte o objeto em JSON e salva a linha. Também é `Propagation.MANDATORY`: gravar no outbox
fora de uma transação de negócio anularia o motivo de ele existir.

### Quem envia: `OutboxRelay` + `OutboxRelayJob`

```java
@Scheduled(fixedDelayString = "${orderflow.outbox.relay-interval}")   // 1000 ms
public void run() {
    outboxRelay.publishPendingBatch();
}
```

`@Scheduled` faz o Spring chamar o método sozinho, de tempos em tempos. Ele só funciona porque
`SchedulingConfig` tem `@EnableScheduling`.

`fixedDelay` conta a espera **a partir do fim** da execução anterior, então duas rodadas nunca
se sobrepõem.

Por que duas classes? Se `run()` ficasse no mesmo bean que `publishPendingBatch()`, a chamada
seria interna e pularia o proxy do `@Transactional`. É a mesma armadilha explicada na seção 3
de [22-services-de-pedido.md](22-services-de-pedido.md).

A cada rodada, o relay:

1. **Tranca um lote** de até 20 linhas pendentes:
   ```sql
   SELECT * FROM outbox_event WHERE published_at IS NULL
   ORDER BY attempts, created_at, id LIMIT :batchSize
   FOR UPDATE SKIP LOCKED
   ```
2. Para cada linha, descobre a fila pelo `event_type` e envia o `payload`.
3. Deu certo: `markPublished(agora)`. Deu errado: `recordFailure(erro)`, que soma 1 em
   `attempts`, guarda o erro e **segue para a próxima linha**. Uma falha nunca derruba o lote.

Dois detalhes da consulta:

- **`SKIP LOCKED`**: se outra instância do serviço já trancou uma linha, esta pula a linha em vez
  de esperar. Duas instâncias do relay nunca enviam a mesma linha ao mesmo tempo. O JPA não tem
  anotação para isso, por isso a consulta é nativa (ver [23-anotacoes-de-repository.md](23-anotacoes-de-repository.md)).
- **`ORDER BY attempts` primeiro**: um evento que falha sempre (por exemplo, uma fila que não
  existe) vai para o fim da fila. Ele nunca ocupa o lote inteiro e trava os eventos novos atrás
  dele.

### "Pelo menos uma vez"

Se o serviço cair **depois** de enviar e **antes** de gravar `published_at`, a linha continua
pendente e será enviada de novo. O outbox garante que a mensagem **nunca se perde**, mas ela
pode chegar **duplicada**. Por isso os dois consumidores precisam ser **idempotentes**: receber
a mesma mensagem duas vezes tem que dar o mesmo resultado que receber uma vez (seções 5 e 6).

## 4. As filas e o contrato das mensagens

### As filas

O script `02-create-order-saga-resources.sh` roda quando o LocalStack fica pronto e cria:

| Fila | Direção | Mensagens | DLQ |
|---|---|---|---|
| `inventory-commands-queue` | order → inventory | `ReserveStock`, `ReleaseStock` | `inventory-commands-dlq` |
| `order-events-queue` | inventory → order | `StockReserved`, `StockReservationFailed` | `order-events-dlq` |

**DLQ** (*dead-letter queue*, "fila de mensagens mortas") é para onde o SQS move uma mensagem
depois de **3 entregas sem sucesso** (`maxReceiveCount: 3`). Sem ela, uma mensagem que sempre
quebra voltaria para a fila para sempre.

Nenhum serviço Java cria filas. Os dois usam `queue-not-found-strategy: fail`: se o script
quebrar e a fila não existir, o envio **falha alto**. O comportamento padrão seria criar a fila
em silêncio, o que esconderia o problema.

### O contrato

O JSON segue o mesmo "envelope" plano do `STOCK_ADJUSTED` da Fase 3:

```json
{
  "eventId": "…",            // mesmo UUID da linha do outbox
  "eventType": "ReserveStock",
  "occurredAt": "2026-09-27T12:00:00.000000Z",
  "orderId": "…",
  "reservationId": "…",      // sempre igual ao orderId, em texto
  "items": [ { "productId": "…", "quantity": 4 } ]
}
```

- **`reservationId = orderId`**: o `inventory-service` usa o par (produto, `reservationId`)
  como chave de idempotência. Um pedido tem exatamente uma reserva por produto.
- Os records Java (`ReserveStockCommand`, `StockReservedEvent`…) existem **duplicados** nos dois
  serviços, sem módulo compartilhado. O contrato é o **JSON**, não a classe. É isso que deixa
  cada microsserviço evoluir e ser implantado sozinho.
- A falha traz `reasonCode` (`INSUFFICIENT_STOCK`, `PRODUCT_NOT_STOCKED`,
  `RESERVATION_CANCELLED`) e `failures` (produto, pedido, disponível).

## 5. O lado do estoque — `reserveAll`

`ReservationCommandListener` recebe o texto, valida com `SagaCommandParser` e chama
`InventoryService.reserveAll`. Esse método tem `@Retryable` + `@Transactional`, igual ao
`reserve` da Fase 2 (ver [14-spring-retry.md](14-spring-retry.md)).

### Primeiro: esse pedido já foi reservado?

O método procura linhas em `stock_reservations` com esse `reservationId`:

| Situação | O que faz |
|---|---|
| **Nenhuma linha** | Reserva nova (abaixo) |
| **Todos os produtos têm linha e nenhuma foi liberada** | **Replay**: o comando chegou de novo. Não mexe no estoque; só grava outro `StockReserved` no outbox, porque o `order-service` pode não ter recebido o primeiro |
| **Qualquer outra combinação** | Estado impossível pela saga (só acontece se alguém reservar por REST com o id de um pedido). Lança `IllegalStateException`, e a mensagem vai para reentrega e depois para a DLQ |

### A reserva nova: tudo ou nada

1. **Ordena os itens por `productId`.** Dois pedidos com produtos em comum trancam as linhas na
   mesma ordem, o que evita *deadlock* (cada um esperando o outro para sempre).
2. **Avalia todos os itens antes de escrever qualquer coisa:**
   - produto sem linha de estoque → falha com `available = 0` (`PRODUCT_NOT_STOCKED`);
   - quantidade maior que o disponível → falha com o disponível real (`INSUFFICIENT_STOCK`).
3. **Alguma falha?** Grava `StockReservationFailed` no outbox e **não toca no estoque**.
4. **Tudo passou?** Reserva cada item e grava `StockReserved` no outbox.

A reserva aumenta a quantidade reservada, mas **não diminui `quantity_on_hand`**. A baixa física
do estoque é da Fase 6, quando o pedido sai para entrega.

### Falha de negócio ≠ falha técnica

Esta é a diferença central entre este listener e o do `notification-service` (ver
[19-sqslistener-consumo.md](19-sqslistener-consumo.md)):

| O que aconteceu | Tratamento | A mensagem… |
|---|---|---|
| JSON malformado ou tipo desconhecido | Log `WARN` e retorna | é apagada (nunca vai funcionar) |
| **Estoque insuficiente** (negócio) | Grava a falha no outbox e retorna **normalmente** | é apagada (a resposta já está a caminho) |
| Banco fora do ar, conflito esgotado (técnico) | A exceção **escapa** | volta para a fila → até 3 vezes → DLQ |

"Não tem estoque" é uma **resposta válida**, não um erro. Se fosse lançada como exceção, o SQS
entregaria a mesma mensagem de novo, e ela falharia de novo, até cair na DLQ sem que ninguém
avisasse o `order-service`.

## 6. Fechando o pedido — `OrderSagaService`

`ReservationResultListener` valida o texto com `SagaEventParser` e encaminha:

- `StockReservationFailed` → `applyReservationFailed`
- `StockReserved` → `applyStockReserved`

Os dois métodos começam igual: `findByIdForUpdate` tranca a **linha do pedido**
(`PESSIMISTIC_WRITE`). Duas mensagens sobre o mesmo pedido, ou uma mensagem e o futuro job de
tempo limite, nunca decidem ao mesmo tempo.

### Idempotência pelo estado

Não existe uma tabela de "mensagens já processadas". **O próprio status do pedido é a memória:**

| Mensagem | Status atual | Resultado |
|---|---|---|
| `StockReservationFailed` | `RESERVING` | → `CANCELLED`, com código e motivo |
| `StockReservationFailed` | qualquer outro | ignorada (duplicata ou atrasada) |
| `StockReserved` | `RESERVING` | → `CONFIRMED` |
| `StockReserved` | `CANCELLED` | **grava `ReleaseStock`** no outbox (motivo `LATE_RESERVATION`) |
| `StockReserved` | `CONFIRMED` | ignorada (duplicata) |

A linha do `CANCELLED` é a **compensação**. O estoque foi reservado, mas o pedido já morreu (por
exemplo, cancelado por tempo limite). Em vez de deixar a reserva "órfã", o `order-service` pede
para devolvê-la.

### Duas defesas contra mensagens forjadas

A fila é uma **fronteira de confiança**: o serviço não pode assumir que tudo o que chega nela é
verdade.

- **Os itens precisam bater.** Se os itens de um `StockReserved` forem diferentes dos itens do
  pedido (produto ou quantidade), a mensagem é tratada como inválida e nada é gravado. Um
  "sucesso" falso não confirma pedido.
- **O motivo do cancelamento vem de um modelo fixo.** `CancellationReasons` monta o texto no
  servidor ("Estoque insuficiente: produto SKU-1 — disponível 2, solicitado 5"). O SKU vem do
  próprio pedido, e da mensagem só entram os números. Nenhum texto livre da fila chega ao
  banco ou à API.

### As colunas novas

`V2__order_reservation_saga.sql` acrescentou em `orders`:

- `reservation_started_at`: quando entrou em `RESERVING`. É o relógio do futuro tempo limite.
- `cancellation_code` e `cancellation_reason`: por que foi cancelado.
- `cancelled_at` e `confirmed_at`: quando.

O resultado da saga fica em colunas **próprias**, separadas da decisão do vendedor
(`decided_by`, `decided_at`, `reason`).

### Pedidos antigos

A mesma migration faz uma **migração de dados**: todo pedido que estava `APPROVED` (criado na
Fase 4) ganha uma linha `ReserveStock` no outbox e passa para `RESERVING`. Isso acontece em SQL
puro, dentro do Flyway, antes de a aplicação subir. Nenhum pedido antigo fica fora da saga.

## 7. O que ainda falta (plano 05-04)

- **O `inventory-service` ainda não processa `ReleaseStock`.** O `SagaCommandParser` só aceita
  `ReserveStock`; qualquer outro tipo é descartado com `WARN`. Hoje, então, o `ReleaseStock` de
  um sucesso atrasado é **enviado**, mas a devolução do estoque ainda não acontece.
- **Tempo limite:** um job `@Scheduled` vai cancelar pedidos parados em `RESERVING` há tempo
  demais (código `RESERVATION_TIMEOUT`) e enviar `ReleaseStock`.
- **`STOCK_ADJUSTED` pelo outbox:** o ajuste de estoque ainda usa o envio direto da Fase 3
  (`StockEventPublisher`). O relay do `inventory-service` já sabe mandar `STOCK_ADJUSTED` para a
  `notification-events-queue`; falta o `InventoryController` gravar no outbox em vez de enviar
  direto.

## Resumindo com uma analogia

Pensa no balcão de pedidos (`order-service`) e no almoxarifado (`inventory-service`) de um
atacadista, que só se falam por **escaninhos**:

- Quando o pedido é aprovado, o atendente **muda a ficha para "separando" e escreve o bilhete
  ao mesmo tempo**, no mesmo caderno (outbox). Não dá para fazer um sem o outro.
- Um **mensageiro** (relay) passa a cada segundo, leva os bilhetes pendentes ao escaninho e
  risca do caderno. Se não conseguiu, tenta na próxima volta. Às vezes leva o mesmo bilhete duas
  vezes.
- O almoxarife confere **todas** as prateleiras antes de separar qualquer coisa: ou separa o
  pedido inteiro, ou não separa nada e responde "faltou isso". Se recebe um bilhete repetido de
  um pedido já separado, só responde de novo "já separei".
- O atendente olha a **ficha** antes de agir: se já está "confirmado", ignora a resposta
  repetida. Se já está "cancelado" e chega "separei", manda outro bilhete: "pode devolver para a
  prateleira".
