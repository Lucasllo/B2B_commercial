# Visão geral — os seis módulos e como eles se ligam

Nota "mapa": o que cada módulo faz, qual papel pode fazer o quê, e quem chama quem em cada
momento do processo, com os endpoints e filas envolvidos. As outras notas aprofundam cada
peça; esta mostra o conjunto.

Versão desenhada, com os diagramas: [Mapa do OrderFlow](https://claude.ai/code/artifact/28ba7064-135a-4a8e-bb53-df77ba32c987)
(página privada no claude.ai).

## A ideia do projeto, em uma frase

Uma empresa **vendedora** tem um catálogo, e várias empresas **compradoras** fazem pedidos.
Cada pedido precisa passar por duas perguntas: **"a empresa tem crédito?"** e **"tem estoque
de todos os itens?"**. Se as duas forem "sim", o pedido termina `CONFIRMED`.

Em vez de um sistema só, cada responsabilidade virou um **microsserviço**: uma aplicação
Spring Boot separada, com porta própria e schema próprio no banco.

## 1. Os dois papéis

No login, o usuário recebe um **JWT** (ver [06-jwks.md](06-jwks.md)) com três informações:
quem é (`sub`), o papel (`role`) e a empresa (`company_id`).

| Papel | Quem é | O que faz |
|---|---|---|
| `BUYER` | Funcionário de uma empresa compradora | Vê o catálogo (só produtos ativos), consulta estoque, consulta o limite de crédito **da própria empresa**, **cria pedidos**, vê **só os pedidos da própria empresa** |
| `SELLER_ADMIN` | Administrador da empresa vendedora | Cadastra empresas e **define o limite de crédito**, cria/edita/ativa/desativa produtos, **define o estoque**, **aprova ou rejeita** pedidos pendentes, vê pedidos de **todas** as empresas, consulta o histórico de notificações |

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
  para o `notification-service`.
- `GET /inventory/{productId}` (qualquer logado): a disponibilidade.
- `POST`/`DELETE /inventory/{id}/reservations` (`SELLER_ADMIN`): reserva e liberação manuais,
  criadas na Fase 2 para testar a reserva atômica.
- **Na saga ele não recebe REST.** Escuta a `inventory-commands-queue`, reserva **todos os itens
  ou nenhum** e responde na `order-events-queue`.

### `order-service` (8085): o centro

- `POST /orders` (`BUYER`): cria o pedido.
- `GET /orders` e `GET /orders/{id}`: comprador vê os da empresa dele, vendedor vê todos.
  Aceita filtro `?status=`.
- `POST /orders/{id}/approve` e `/reject` (`SELLER_ADMIN`): decisão sobre um pedido pendente.
- É o **orquestrador da saga**: guarda o estado do pedido, manda comandos ao estoque e reage às
  respostas (ver [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md)).

### `notification-service` (8084): o histórico

- **Só recebe.** Escuta a `notification-events-queue` e grava cada evento no **DynamoDB**
  (ver [17-dynamodb.md](17-dynamodb.md)). Nunca chama ninguém.
- `GET /notifications/{productId}` (`SELLER_ADMIN`): o histórico de um produto.
- Hoje registra os ajustes de estoque; a Fase 6 acrescenta a linha do tempo do pedido.

## 3. Duas formas de conversar

| Tipo | Como funciona | Onde |
|---|---|---|
| **Síncrona (HTTP)** | Quem chama **espera a resposta** | `order-service` → catálogo e crédito; todos → JWKS do `auth-service` |
| **Assíncrona (SQS)** | Deixa uma mensagem na fila e segue a vida; outro lê depois | `order` ↔ `inventory` (saga) e `inventory` → `notification` |

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
| `order` → `inventory` | SQS | `inventory-commands-queue` (`ReserveStock`, `ReleaseStock`) | Quando o pedido é aprovado, ou para devolver estoque |
| `inventory` → `order` | SQS | `order-events-queue` (`StockReserved` / `StockReservationFailed`) | Resposta da tentativa de reserva |
| `inventory` → `notification` | SQS | `notification-events-queue` (`STOCK_ADJUSTED`) | Quando o vendedor ajusta o estoque |

Três ausências que são de propósito:

- O `order-service` **nunca chama o `inventory-service` por HTTP**.
- `catalog` e `inventory` **não se conhecem**.
- O `notification-service` **não chama ninguém**.

Nas duas chamadas HTTP, o `order-service` repassa o **JWT do próprio comprador**. Então as
regras de permissão do `auth-service` (comprador só lê o limite da própria empresa) continuam
valendo ali.

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
7. StockReserved → CONFIRMED    |    StockReservationFailed → CANCELLED
```

**Exposição** é a soma dos pedidos da empresa que já consomem crédito: `RESERVING`,
`CONFIRMED`, `SHIPPED` e `DELIVERED` (ver [21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md)).

## 6. Os estados do pedido

```
CREATED ─┬─ cabe no crédito ──► RESERVING ─┬─ StockReserved ──────────► CONFIRMED ─► SHIPPED ─► DELIVERED
         │                          ▲       └─ StockReservationFailed ─► CANCELLED        (Fase 6)
         └─ passa do limite ─► PENDING_APPROVAL
                                    ├─ approve (SELLER_ADMIN) ─┘
                                    └─ reject  (SELLER_ADMIN) ─► REJECTED
```

Cada seta tem **um único responsável**: a regra de crédito, o vendedor ou a resposta do estoque.

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
  pedido e deixa o bilhete no escaninho do almoxarifado. Depois recolhe a resposta.
- O **arquivo** (`notification-service`) só recebe cópias de tudo o que aconteceu e guarda.
