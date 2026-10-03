# Visão geral — os seis módulos e como eles se ligam

Nota "mapa": o que cada módulo faz, qual papel pode fazer o quê, e quem chama quem em cada
momento do processo, com os endpoints e filas envolvidos. As outras notas aprofundam cada
peça; esta mostra o conjunto.

Versão desenhada, com os diagramas: [Mapa do OrderFlow](https://claude.ai/code/artifact/28ba7064-135a-4a8e-bb53-df77ba32c987)
(página privada no claude.ai).

## A ideia do projeto, em uma frase

Uma empresa **vendedora** tem um catálogo, e várias empresas **compradoras** fazem pedidos.
Cada pedido precisa passar por duas perguntas: **"a empresa tem crédito?"** e **"tem estoque
de todos os itens?"**. Se as duas forem "sim", o pedido fica `CONFIRMED`, ganha uma
transportadora, e depois o vendedor o **expede** (`SHIPPED`) e registra a **entrega**
(`DELIVERED`). Cada passo fica numa **linha do tempo** que comprador e vendedor podem consultar.

Em vez de um sistema só, cada responsabilidade virou um **microsserviço**: uma aplicação
Spring Boot separada, com porta própria e schema próprio no banco.

## 1. Os dois papéis

No login, o usuário recebe um **JWT** (ver [06-jwks.md](06-jwks.md)) com três informações:
quem é (`sub`), o papel (`role`) e a empresa (`company_id`).

| Papel | Quem é | O que faz |
|---|---|---|
| `BUYER` | Funcionário de uma empresa compradora | Vê o catálogo (só produtos ativos), consulta estoque, consulta o limite de crédito **da própria empresa**, **cria pedidos**, vê **só os pedidos da própria empresa** e a linha do tempo deles |
| `SELLER_ADMIN` | Administrador da empresa vendedora | Cadastra empresas e **define o limite de crédito**, cria/edita/ativa/desativa produtos, **define o estoque**, **aprova ou rejeita** pedidos pendentes, **expede e registra a entrega**, vê pedidos de **todas** as empresas, consulta o histórico de notificações |

Regra de segurança que se repete no projeto inteiro: o `company_id` **sempre vem do token**,
nunca do corpo da requisição. Um comprador não consegue fingir ser outra empresa escrevendo
outro id no JSON (ver [11-companyguard.md](11-companyguard.md)).

## 2. Cada módulo

### `gateway` (porta 8080): a porta de entrada

O único endereço que o cliente usa. Olha o começo do caminho, remove o `/api` e repassa.
**Não valida o JWT**: cada serviço faz isso sozinho (ver [10-gateway-application-yml.md](10-gateway-application-yml.md)).

| Caminho no gateway | Vai para |
|---|---|
| `/api/auth/**`, `/api/companies/**` | `auth-service` :8081 |
| `/api/products/**` | `catalog-service` :8082 |
| `/api/inventory/**` | `inventory-service` :8083 |
| `/api/notifications/**` | `notification-service` :8084 |
| `/api/orders/**` | `order-service` :8085 |

Exemplo: `POST /api/orders` chega ao `order-service` como `POST /orders`.

Duas coisas a mais moram no gateway desde a Fase 7:

- **O Correlation-ID.** Toda requisição sai do gateway com o header `X-Correlation-Id` (o do
  cliente, se for válido, ou um UUID novo). Cada serviço tem seu próprio `CorrelationIdFilter`,
  põe o ID no começo de cada linha de log e o devolve na resposta. O `order-service` o repassa
  nas chamadas HTTP ao catálogo e ao `auth-service`, e o outbox o leva junto pelas filas. Assim
  um único ID junta os logs de todos os serviços que um pedido tocou (ver
  [27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md)).
- **Uma Swagger UI só**, em `http://localhost:8080/swagger-ui.html`, com um seletor que busca o
  OpenAPI de cada serviço em `/docs/<serviço>/v3/api-docs` (ver
  [28-openapi-agregado-no-gateway.md](28-openapi-agregado-no-gateway.md)).

### `auth-service` (8081): identidade, empresas e crédito

- `POST /auth/login`: e-mail e senha viram um JWT assinado com chave RSA.
- `GET /auth/me`: devolve quem você é, lido do próprio token.
- `GET /.well-known/jwks.json`: publica a **chave pública**. Os outros serviços a usam para
  conferir a assinatura dos tokens sem perguntar ao `auth-service` a cada requisição.
- `POST /companies` (`SELLER_ADMIN`): cadastra uma empresa compradora.
- `GET /companies/{id}/credit-limit`: o vendedor vê qualquer empresa; o comprador, só a dele.
- `PUT /companies/{id}/credit-limit` (`SELLER_ADMIN`): altera o limite.

### `catalog-service` (8082): os produtos

- `POST /products`, `PUT /products/{id}` e `PUT /products/{id}/status` (`SELLER_ADMIN`).
- `GET /products` e `GET /products/{id}` (qualquer usuário logado). O comprador só enxerga
  produtos ativos; o vendedor enxerga todos.
- É daqui que sai o **preço oficial** do pedido. O comprador nunca informa preço.

### `inventory-service` (8083): o estoque

- `PUT /inventory/{productId}` (`SELLER_ADMIN`): define a quantidade e publica `STOCK_ADJUSTED`
  para o `notification-service`, pelo outbox.
- `GET /inventory/{productId}` (qualquer logado): a disponibilidade.
- `POST`/`DELETE /inventory/{id}/reservations` (`SELLER_ADMIN`): reserva e liberação manuais,
  criadas na Fase 2 para testar a reserva atômica e mantidas como ferramenta administrativa.
- **Na saga ele não recebe REST.** Escuta a `inventory-commands-queue` e obedece a três
  comandos: `ReserveStock` (reserva **todos os itens ou nenhum** e responde na
  `order-events-queue`), `ReleaseStock` (devolve a reserva) e `ShipStock` (baixa física na
  expedição). Só o primeiro tem resposta.

### `order-service` (8085): o centro

- `POST /orders` (`BUYER`): cria o pedido.
- `GET /orders` e `GET /orders/{id}`: comprador vê os da empresa dele, vendedor vê todos.
  Aceita filtro `?status=`.
- `POST /orders/{id}/approve` e `/reject` (`SELLER_ADMIN`): decisão sobre um pedido pendente.
- `POST /orders/{id}/ship` e `/deliver` (`SELLER_ADMIN`): expedição (`CONFIRMED` → `SHIPPED`) e
  entrega (`SHIPPED` → `DELIVERED`). Fora da ordem, 409 `invalid_order_transition` (ver
  [30-ciclo-de-vida-expedicao-e-entrega.md](30-ciclo-de-vida-expedicao-e-entrega.md)).
- É o **orquestrador da saga**: guarda o estado do pedido, manda comandos ao estoque e reage às
  respostas (ver [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md)). Um job
  cancela pedidos que ficam tempo demais esperando o estoque.
- A cada mudança de status, grava um evento `ORDER_*` no outbox para a linha do tempo.

### `notification-service` (8084): o histórico

- **Só recebe.** Escuta a `notification-events-queue` e grava cada evento no **DynamoDB**
  (ver [17-dynamodb.md](17-dynamodb.md)). Nunca chama ninguém.
- `GET /notifications/{productId}` (`SELLER_ADMIN`): o histórico de ajustes de estoque de um
  produto.
- `GET /notifications/orders/{orderId}` (`SELLER_ADMIN` ou `BUYER`): a **linha do tempo** do
  pedido, com os eventos `ORDER_CREATED`, `ORDER_PENDING_APPROVAL`, `ORDER_APPROVED`,
  `ORDER_REJECTED`, `ORDER_CONFIRMED`, `ORDER_CANCELLED`, `ORDER_SHIPPED` e `ORDER_DELIVERED`.
  O comprador só vê pedidos da própria empresa; para os outros recebe o mesmo 404
  `order_not_found` de um pedido inexistente.
- A tabela do DynamoDB usa uma chave genérica, `entityId`, que é o id do produto ou o do
  pedido.

## 3. Duas formas de conversar

| Tipo | Como funciona | Onde |
|---|---|---|
| **Síncrona (HTTP)** | Quem chama **espera a resposta** | `order-service` → catálogo e crédito; todos → JWKS do `auth-service` |
| **Assíncrona (SQS)** | Deixa uma mensagem na fila e segue a vida; outro lê depois | `order` ↔ `inventory` (saga), `inventory` → `notification` e `order` → `notification` |

**Por que HTTP para catálogo e crédito?** O pedido só pode ser aceito ou recusado depois da
resposta. Sem o preço e o limite, não há o que decidir.

**Por que fila para o estoque?** Se o `inventory-service` estiver fora do ar, o comando espera
na fila e o pedido não se perde. Nenhum serviço fica "preso" esperando o outro.

## 4. Quem fala com quem, e quando

| De → Para | Como | Endpoint / fila | Quando e por quê |
|---|---|---|---|
| Cliente → gateway | HTTP | `/api/**` | Sempre; é a única entrada |
| Todos → `auth` | HTTP | `GET /.well-known/jwks.json` | Na subida e quando a chave muda, para validar o JWT localmente |
| `order` → `catalog` | HTTP | `GET /products/{id}` | Ao criar o pedido: o produto existe, está ativo, quanto custa? |
| `order` → `auth` | HTTP | `GET /companies/{id}/credit-limit` | Ao criar o pedido: qual o limite da empresa? |
| `order` → `inventory` | SQS | `inventory-commands-queue` (`ReserveStock`, `ReleaseStock`, `ShipStock`) | Quando o pedido é aprovado, para devolver estoque (cancelamento tardio ou tempo limite) e na expedição |
| `inventory` → `order` | SQS | `order-events-queue` (`StockReserved` / `StockReservationFailed`) | Resposta da tentativa de reserva |
| `inventory` → `notification` | SQS | `notification-events-queue` (`STOCK_ADJUSTED`) | Quando o vendedor ajusta o estoque |
| `order` → `notification` | SQS | `notification-events-queue` (`ORDER_*`) | A cada mudança de status do pedido, para a linha do tempo |

Três ausências que são de propósito:

- O `order-service` **nunca chama o `inventory-service` por HTTP**.
- `catalog` e `inventory` **não se conhecem**.
- O `notification-service` **não chama ninguém**.

Nas duas chamadas HTTP, o `order-service` repassa o **JWT do próprio comprador**. Então as
regras de permissão do `auth-service` (comprador só lê o limite da própria empresa) continuam
valendo ali. Repassa também o `X-Correlation-Id`.

Toda mensagem que o `order-service` e o `inventory-service` mandam para uma fila sai pelo
**outbox** (ver [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)): nenhum dos dois
envia direto ao SQS.

## 5. O fluxo de um pedido

```
1. BUYER → POST /api/orders
2. order-service: produto repetido? → GET /products/{id} (um por item) → total cabe na coluna?
                  → GET /companies/{id}/credit-limit
3. Regra de crédito: exposição + total ≤ limite?
      sim → RESERVING (e o comando ReserveStock vai para o outbox, na mesma transação)
      não → PENDING_APPROVAL (espera o vendedor)
4. ← 201 Created  (o comprador recebe a resposta aqui; o estoque ainda não foi tocado)

Se ficou PENDING_APPROVAL:
   SELLER_ADMIN → POST /api/orders/{id}/approve → RESERVING (entra na mesma saga)
   SELLER_ADMIN → POST /api/orders/{id}/reject  → REJECTED  (fim)

Depois, de forma assíncrona:
5. OutboxRelay envia ReserveStock → inventory-commands-queue
6. inventory-service reserva tudo ou nada → order-events-queue
7. StockReserved → CONFIRMED (com transportadora e rastreio)
   StockReservationFailed → CANCELLED
   sem resposta em 2 minutos → SagaTimeoutJob cancela (CANCELLED) e manda ReleaseStock

Depois, pelo vendedor:
8. SELLER_ADMIN → POST /api/orders/{id}/ship    → SHIPPED   (ShipStock: o estoque sai da prateleira)
9. SELLER_ADMIN → POST /api/orders/{id}/deliver → DELIVERED (fim)

A qualquer momento:
   BUYER ou SELLER_ADMIN → GET /api/notifications/orders/{id} → a linha do tempo do pedido
```

**Exposição** é a soma dos pedidos da empresa que já consomem crédito: `RESERVING`,
`CONFIRMED`, `SHIPPED` e `DELIVERED` (ver [21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md)).

## 6. Os estados do pedido

```
CREATED ─┬─ cabe no crédito ──► RESERVING ─┬─ StockReserved ─────────► CONFIRMED ─ ship ─► SHIPPED ─ deliver ─► DELIVERED
         │                          ▲       └─ falha ou tempo limite ─► CANCELLED
         └─ passa do limite ─► PENDING_APPROVAL
                                    ├─ approve (SELLER_ADMIN) ─┘
                                    └─ reject  (SELLER_ADMIN) ─► REJECTED
```

(`APPROVED` existe entre `CREATED`/`PENDING_APPROVAL` e `RESERVING`, mas só dentro da mesma
transação; a API nunca o mostra.)

Cada seta tem **um único responsável**: a regra de crédito, o vendedor, a resposta do estoque ou
o job de tempo limite. Todas as setas permitidas estão numa tabela só, `OrderStatus.transitions()`,
e um teste confere que o diagrama do README é igual a ela (ver
[30-ciclo-de-vida-expedicao-e-entrega.md](30-ciclo-de-vida-expedicao-e-entrega.md)).

## 7. Onde está cada dado

| Serviço | Banco |
|---|---|
| `auth`, `catalog`, `inventory`, `order` | Uma instância PostgreSQL, **um schema por serviço** (ver [08-flyway-migrations.md](08-flyway-migrations.md)) |
| `notification` | DynamoDB (via LocalStack) |
| Filas | SQS (via LocalStack), criadas pelos scripts de `localstack-init/ready.d/` |

Nenhum serviço lê o schema de outro. Se precisa de um dado alheio, pergunta por HTTP ou recebe
por fila.

## Resumindo com uma analogia

Pensa num **atacadista** num prédio de escritórios:

- A **recepção** (`gateway`) só olha para onde você quer ir e aponta a sala certa.
- O **RH/crachás** (`auth-service`) entrega o crachá com seu cargo e sua empresa, e guarda a
  ficha de crédito de cada cliente. Todo mundo no prédio sabe reconhecer um crachá verdadeiro
  sem ligar para o RH (a chave pública).
- A **vitrine** (`catalog-service`) mostra os produtos e os preços.
- O **almoxarifado** (`inventory-service`) separa as mercadorias, mas só recebe pedidos por um
  **escaninho** (a fila). Ninguém entra lá gritando.
- O **balcão de pedidos** (`order-service`) confere a vitrine e a ficha de crédito, anota o
  pedido e deixa o bilhete no escaninho do almoxarifado. Depois recolhe a resposta, chama a
  transportadora e, quando a mercadoria sai, deixa outro bilhete: "pode dar baixa".
- O **arquivo** (`notification-service`) só recebe cópias de tudo o que aconteceu e guarda. É
  ali que o cliente vai quando quer ver o histórico do pedido dele.
- E todo papel que circula no prédio leva o mesmo **número de protocolo** (o Correlation-ID),
  carimbado na recepção.
