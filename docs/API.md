<!-- generated-by: gsd-doc-writer -->
# OrderFlow — Referência da API

> Endpoints disponíveis hoje no repositório, pertencentes ao `auth-service`, `catalog-service`,
> `inventory-service`, `notification-service` e `order-service`, todos acessados através do API
> Gateway. Veja [VISAO-GERAL.md](VISAO-GERAL.md) para o contexto do projeto e o estado atual dos
> serviços.
>
> **Prefere testar no navegador em vez de ler?** Cada serviço também expõe uma Swagger UI
> interativa na própria porta (não pelo Gateway): `http://localhost:8081/swagger-ui.html`
> (auth-service), `:8082` (catalog-service), `:8083` (inventory-service), `:8084`
> (notification-service), `:8085` (order-service). Cole um JWT no botão **Authorize** e exercite
> qualquer endpoint abaixo via **Try it out** — ver
> [VISAO-GERAL.md § Documentação interativa](VISAO-GERAL.md#documentação-interativa-swagger-ui).

## URL base

Todas as chamadas de cliente externo passam pelo Gateway:

```
http://localhost:8080
```

O Gateway roteia por prefixo de caminho, removendo `/api` antes de encaminhar ao serviço
downstream (`StripPrefix=1` em todas as rotas, `gateway/src/main/resources/application.yml`):

| Prefixo no Gateway | Serviço downstream | Exemplo |
|---|---|---|
| `/api/auth/**`, `/api/companies/**` | `auth-service` | `POST /api/auth/login` → `POST /auth/login` |
| `/api/products/**` | `catalog-service` | `GET /api/products/{id}` → `GET /products/{id}` |
| `/api/inventory/**` | `inventory-service` | `GET /api/inventory/{productId}` → `GET /inventory/{productId}` |
| `/api/notifications/**` | `notification-service` | `GET /api/notifications/{productId}` → `GET /notifications/{productId}` |
| `/api/orders/**` | `order-service` | `POST /api/orders` → `POST /orders` |

`GET /.well-known/jwks.json` **não é roteado pelo Gateway** — é interno ao `auth-service`
(`http://auth-service:8081/.well-known/jwks.json` na rede do Docker Compose) e existe para que
outros serviços validem o JWT localmente, sem chamar o `auth-service` a cada requisição.

## Autenticação

A API usa JWT auto-emitido pelo `auth-service` (RSA), enviado no header `Authorization`:

```
Authorization: Bearer <token>
```

O token é obtido em `POST /api/auth/login` e carrega os claims `role` (`SELLER_ADMIN` ou `BUYER`)
e, para usuários `BUYER`, `company_id`. Não há refresh token nesta fase — apenas access token, com
TTL informado no próprio corpo de resposta do login.

`catalog-service` e `inventory-service` são *resource servers*: nunca emitem token, apenas validam
o que o `auth-service` emitiu, lendo o claim `role` (string única) e convertendo-o na authority
`ROLE_<valor>` — o mesmo mecanismo de validação local usado pelo `auth-service` para si mesmo.

Endpoints públicos, sem token: `POST /api/auth/login`, `GET /.well-known/jwks.json` (interno) e
`GET /actuator/health` (Gateway) / `GET /actuator/health/**` (cada serviço). Todos os demais
exigem um token válido; a ausência de token resulta em `401 unauthorized` antes mesmo de o
controller ser executado.

## Visão geral dos endpoints

| Serviço | Método | Caminho (via Gateway) | Descrição | Autenticação |
|---|---|---|---|---|
| auth-service | `POST` | `/api/auth/login` | Autentica com email/senha e devolve um JWT | Nenhuma |
| auth-service | `GET` | `/api/auth/me` | Devolve os claims do JWT do requisitante | Bearer (qualquer papel) |
| auth-service | `POST` | `/api/companies` | Cria uma empresa compradora + usuário `BUYER` vinculado | Bearer, papel `SELLER_ADMIN` |
| auth-service | `GET` | `/api/companies/{companyId}/credit-limit` | Consulta o limite de crédito de uma empresa | Bearer — `SELLER_ADMIN` ou o próprio `BUYER` da empresa |
| auth-service | `PUT` | `/api/companies/{companyId}/credit-limit` | Atualiza o limite de crédito de uma empresa | Bearer, papel `SELLER_ADMIN` |
| catalog-service | `POST` | `/api/products` | Cria um produto no catálogo (status inicial `ACTIVE`) | Bearer, papel `SELLER_ADMIN` |
| catalog-service | `GET` | `/api/products/{productId}` | Consulta um produto pelo id | Bearer (qualquer papel) |
| catalog-service | `PUT` | `/api/products/{productId}` | Atualiza nome/descrição/preço de um produto | Bearer, papel `SELLER_ADMIN` |
| catalog-service | `PUT` | `/api/products/{productId}/status` | Retira (`DISCONTINUED`) ou reativa (`ACTIVE`) um produto | Bearer, papel `SELLER_ADMIN` |
| catalog-service | `GET` | `/api/products` | Lista produtos, paginado | Bearer (qualquer papel) |
| inventory-service | `PUT` | `/api/inventory/{productId}` | Define a quantidade em estoque de um produto (upsert) | Bearer, papel `SELLER_ADMIN` |
| inventory-service | `GET` | `/api/inventory/{productId}` | Consulta o estoque exato de um produto | Bearer (qualquer papel) |
| inventory-service | `POST` | `/api/inventory/{productId}/reservations` | Reserva uma quantidade de estoque (idempotente) | Bearer, papel `SELLER_ADMIN` |
| inventory-service | `DELETE` | `/api/inventory/{productId}/reservations/{reservationId}` | Libera uma reserva de estoque (idempotente) | Bearer, papel `SELLER_ADMIN` |
| notification-service | `GET` | `/api/notifications/{productId}` | Histórico de eventos de notificação de um produto, em ordem cronológica | Bearer, papel `SELLER_ADMIN` |
| order-service | `POST` | `/api/orders` | Cria um pedido a partir de itens do catálogo, decidido automaticamente pelo limite de crédito | Bearer, papel `BUYER` |
| order-service | `GET` | `/api/orders` | Lista pedidos, paginado (`BUYER` só da própria empresa, `SELLER_ADMIN` todos) | Bearer (qualquer papel) |
| order-service | `GET` | `/api/orders/{orderId}` | Consulta um pedido pelo id (`BUYER` só da própria empresa) | Bearer (qualquer papel) |
| order-service | `POST` | `/api/orders/{orderId}/approve` | Aprova manualmente um pedido `PENDING_APPROVAL` | Bearer, papel `SELLER_ADMIN` |
| order-service | `POST` | `/api/orders/{orderId}/reject` | Rejeita um pedido `PENDING_APPROVAL`, com motivo obrigatório | Bearer, papel `SELLER_ADMIN` |
| gateway | `GET` | `/actuator/health` | Health check do Gateway | Nenhuma |

> As rotas de reserva/liberação do `inventory-service` são restritas a `SELLER_ADMIN` nesta fase —
> nenhum comprador reserva estoque diretamente. Uma identidade de serviço própria para o
> `order-service` chamar essas rotas é introduzida em uma fase futura.

## Formato de erro padrão

Todos os serviços seguem o mesmo envelope básico de erro:

```json
{
  "error": "unauthorized",
  "message": "Authentication is required"
}
```

Erros de validação de campo (`400`) incluem também `fields`, um mapa de nome do campo → mensagem:

```json
{
  "error": "validation_failed",
  "message": "One or more fields are invalid",
  "fields": {
    "email": "must not be blank"
  }
}
```

Alguns erros do `inventory-service` incluem campos extras específicos do contexto (ver
`insufficient_stock` na tabela abaixo).

**Erros comuns a todos os serviços:**

| Status | `error` | Quando ocorre |
|---|---|---|
| 400 | `validation_failed` | Corpo da requisição falha em uma validação de Bean Validation (campo em branco, formato inválido, valor monetário com mais de 2 casas decimais, etc.) |
| 401 | `unauthorized` | Requisição sem token, token inválido/expirado, ou (só no `auth-service`) credenciais de login incorretas |
| 403 | `forbidden` | Token válido, mas o papel/empresa do requisitante não tem permissão para a ação (`@PreAuthorize` negado) |

**Erros específicos de `auth-service`:**

| Status | `error` | Quando ocorre |
|---|---|---|
| 404 | `company_not_found` | `{companyId}` não corresponde a nenhuma empresa cadastrada |
| 409 | `email_already_used` | E-mail do `buyerUser` já está em uso por outro usuário (lançado também para `DataIntegrityViolationException`, cobrindo a corrida sem checagem prévia) |

Senha errada e e-mail inexistente em `/auth/login` devolvem exatamente o mesmo `401 unauthorized` —
isso é intencional, para não expor se um e-mail existe na base.

**Erros específicos de `catalog-service`:**

| Status | `error` | Quando ocorre |
|---|---|---|
| 400 | `malformed_request` | Corpo da requisição não desserializa — por exemplo, um valor de `status` fora do enum `ProductStatus` em `PUT /products/{productId}/status` |
| 404 | `product_not_found` | `{productId}` não corresponde a nenhum produto persistido, **ou** o produto está `DISCONTINUED` e o requisitante não é `SELLER_ADMIN` (um produto fora do catálogo é indistinguível de inexistente para o comprador) |
| 409 | `sku_already_used` | O `sku` enviado em `POST /products` já pertence a outro produto — lançado tanto pela checagem prévia (`existsBySku`) quanto por `DataIntegrityViolationException` da constraint `UNIQUE(products.sku)` |

**Erros específicos de `inventory-service`:**

| Status | `error` | Quando ocorre |
|---|---|---|
| 400 | `malformed_request` | Corpo da requisição não desserializa |
| 404 | `inventory_not_found` | `{productId}` não tem linha de inventário — em `GET`, `POST .../reservations` ou `DELETE .../reservations/{reservationId}` (`setStock` nunca cai aqui: é sempre upsert) |
| 409 | `insufficient_stock` | A quantidade pedida em `POST .../reservations` excede `quantityAvailable`; o corpo inclui os campos extras `available` e `requested` |
| 409 | `stock_below_reserved` | `PUT /inventory/{productId}` tenta gravar `quantityOnHand` menor que a quantidade já reservada |
| 409 | `data_conflict` | Rede de segurança genérica para `DataIntegrityViolationException` não capturada pelos handlers acima |
| 503 | `reservation_conflict` | Disputa de concorrência sobre a mesma linha de inventário esgotou as reexecuções (até 10 tentativas com backoff); semanticamente distinto de `insufficient_stock` — significa "tente novamente", não "não há estoque" |

**Erros específicos de `notification-service`:**

| Status | `error` | Quando ocorre |
|---|---|---|
| 400 | `invalid_identifier` | `{productId}` não é um UUID válido |
| 503 | `notification_store_unavailable` | Falha do SDK da AWS ao falar com o DynamoDB (indisponibilidade, timeout, erro de credencial) — o motivo real fica só no log do servidor |

**Erros específicos de `order-service`:**

| Status | `error` | Quando ocorre |
|---|---|---|
| 400 | `invalid_parameter` | `{orderId}` não é um UUID válido |
| 404 | `order_not_found` | `{orderId}` não corresponde a nenhum pedido, **ou** corresponde a um pedido de outra empresa e o requisitante é `BUYER` (pedido alheio é indistinguível de inexistente) |
| 409 | `order_not_pending` | `POST /orders/{orderId}/approve` ou `/reject` sobre um pedido que não está em `PENDING_APPROVAL` |
| 422 | `invalid_order_items` | Um ou mais `productId` do pedido não correspondem a um produto `ACTIVE` no catálogo (inexistente ou `DISCONTINUED`); o corpo inclui o campo extra `productIds` com os ids inválidos, na ordem em que apareceram no pedido |
| 422 | `order_total_out_of_range` | O total do pedido não cabe na coluna `NUMERIC(19,2)` |
| 503 | `catalog_service_unavailable` | `catalog-service` fora do ar, lento (acima do timeout) ou com resposta malformada — nenhum pedido é criado |
| 503 | `auth_service_unavailable` | `auth-service` fora do ar, lento, com resposta malformada, ou empresa sem limite de crédito registrado — nenhum pedido é criado |

---

## `POST /api/auth/login`

Autentica um usuário e emite um JWT.

**Autenticação:** nenhuma.

**Corpo da requisição:**

```json
{
  "email": "admin@orderflow.local",
  "password": "ChangeMe!123"
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `email` | string | Sim | Formato de e-mail válido |
| `password` | string | Sim | Não pode ser vazio |

**Resposta `200 OK`:**

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

`expiresIn` é o TTL do token em segundos.

**Erros possíveis:** `400 validation_failed` (campos em branco/formato inválido), `401 unauthorized`
(credenciais inválidas).

---

## `GET /api/auth/me`

Devolve os claims do JWT enviado — não consulta o banco de dados.

**Autenticação:** Bearer, qualquer papel autenticado.

**Resposta `200 OK`:**

```json
{
  "userId": "3f7b1c2a-...",
  "role": "BUYER",
  "companyId": "9a21e4d0-..."
}
```

`companyId` é `null` para usuários com papel `SELLER_ADMIN` (o claim `company_id` não existe no
token desse papel).

**Erros possíveis:** `401 unauthorized` (sem token ou token inválido/expirado).

---

## `POST /api/companies`

Cria uma empresa compradora e o usuário `BUYER` vinculado a ela, em uma única operação
transacional.

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Corpo da requisição:**

```json
{
  "name": "Comprador Exemplo Ltda",
  "creditLimit": 50000.00,
  "buyerUser": {
    "email": "compras@exemplo.com",
    "password": "SenhaForte123"
  }
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `name` | string | Sim | Até 255 caracteres |
| `creditLimit` | number | Sim | `>= 0.00`, no máximo 2 casas decimais |
| `buyerUser.email` | string | Sim | Formato de e-mail válido, até 255 caracteres |
| `buyerUser.password` | string | Sim | Mínimo 8 caracteres |

Qualquer campo extra enviado (por exemplo, tentando forçar um papel diferente de `BUYER`) é
silenciosamente ignorado — o papel do usuário criado é sempre fixado como `BUYER` no servidor.

**Resposta `201 Created`** (header `Location: /companies/{id}`):

```json
{
  "id": "9a21e4d0-...",
  "name": "Comprador Exemplo Ltda",
  "creditLimit": 50000.00,
  "createdAt": "2026-09-19T14:32:00Z",
  "buyerUser": {
    "id": "3f7b1c2a-...",
    "email": "compras@exemplo.com",
    "role": "BUYER"
  }
}
```

Nenhum campo de senha ou hash é retornado.

**Erros possíveis:** `400 validation_failed`, `401 unauthorized`, `403 forbidden` (papel diferente
de `SELLER_ADMIN`), `409 email_already_used`.

---

## `GET /api/companies/{companyId}/credit-limit`

Consulta o limite de crédito atual de uma empresa.

**Autenticação:** Bearer — permitido para `SELLER_ADMIN` (qualquer empresa) ou para um usuário
`BUYER` cujo `company_id` no token seja igual a `{companyId}`.

**Resposta `200 OK`:**

```json
{
  "companyId": "9a21e4d0-...",
  "creditLimit": 50000.00
}
```

**Erros possíveis:** `401 unauthorized`, `403 forbidden` (`BUYER` de outra empresa tentando ler o
limite alheio), `404 company_not_found`.

---

## `PUT /api/companies/{companyId}/credit-limit`

Atualiza o limite de crédito de uma empresa.

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Corpo da requisição:**

```json
{
  "creditLimit": 75000.00
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `creditLimit` | number | Sim | `>= 0.00`, no máximo 2 casas decimais |

**Resposta `200 OK`:**

```json
{
  "companyId": "9a21e4d0-...",
  "creditLimit": 75000.00
}
```

**Erros possíveis:** `400 validation_failed`, `401 unauthorized`, `403 forbidden` (qualquer papel
diferente de `SELLER_ADMIN`, incluindo o próprio `BUYER` da empresa), `404 company_not_found`.

---

## `POST /api/products`

Cria um produto no catálogo. O status inicial é sempre `ACTIVE`, fixado no servidor — o campo não
existe no corpo da requisição e qualquer valor extra enviado é silenciosamente ignorado.

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Corpo da requisição:**

```json
{
  "sku": "SKU-001",
  "name": "Caixa de parafusos M4",
  "description": "Embalagem com 500 unidades",
  "price": 129.90
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `sku` | string | Sim | Não vazio, até 64 caracteres |
| `name` | string | Sim | Não vazio, até 255 caracteres |
| `description` | string | Não | Até 1000 caracteres |
| `price` | number | Sim | `>= 0.00`, no máximo 2 casas decimais |

**Resposta `201 Created`** (header `Location: /products/{id}`):

```json
{
  "id": "6a4f2e10-...",
  "sku": "SKU-001",
  "name": "Caixa de parafusos M4",
  "description": "Embalagem com 500 unidades",
  "price": 129.90,
  "status": "ACTIVE",
  "createdAt": "2026-09-19T14:32:00Z"
}
```

**Erros possíveis:** `400 validation_failed`, `401 unauthorized`, `403 forbidden` (papel diferente
de `SELLER_ADMIN`), `409 sku_already_used`.

---

## `GET /api/products/{productId}`

Consulta um produto pelo id.

**Autenticação:** Bearer, qualquer papel autenticado.

Um produto `DISCONTINUED` é visível apenas para `SELLER_ADMIN`; para qualquer outro papel, ele é
tratado como inexistente (`404 product_not_found`) — um produto fora do catálogo é indistinguível
de um produto que nunca existiu.

**Resposta `200 OK`:**

```json
{
  "id": "6a4f2e10-...",
  "sku": "SKU-001",
  "name": "Caixa de parafusos M4",
  "description": "Embalagem com 500 unidades",
  "price": 129.90,
  "status": "ACTIVE",
  "createdAt": "2026-09-19T14:32:00Z"
}
```

**Erros possíveis:** `401 unauthorized`, `404 product_not_found` (id inexistente, ou produto
`DISCONTINUED` visto por um papel diferente de `SELLER_ADMIN`).

---

## `PUT /api/products/{productId}`

Atualiza nome, descrição e preço de um produto. Não altera `sku` (imutável) nem `status` — a
retirada/reativação é feita por `PUT /products/{productId}/status`.

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Corpo da requisição:**

```json
{
  "name": "Caixa de parafusos M4 (novo preço)",
  "description": "Embalagem com 500 unidades",
  "price": 139.90
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `name` | string | Sim | Não vazio, até 255 caracteres |
| `description` | string | Não | Até 1000 caracteres |
| `price` | number | Sim | `>= 0.00`, no máximo 2 casas decimais |

**Resposta `200 OK`:** mesmo formato de `GET /products/{productId}`, com os campos atualizados.

**Erros possíveis:** `400 validation_failed`, `401 unauthorized`, `403 forbidden`,
`404 product_not_found`.

---

## `PUT /api/products/{productId}/status`

Retira (`DISCONTINUED`) ou reativa (`ACTIVE`) um produto. Este é o único caminho de "remoção" do
catálogo — nenhuma linha de produto é fisicamente apagada, porque pedidos referenciam produtos por
id.

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Corpo da requisição:**

```json
{
  "status": "DISCONTINUED"
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `status` | string | Sim | Um dos valores do enum: `ACTIVE`, `DISCONTINUED` |

Um valor fora do enum não é aceito silenciosamente como `null` — a desserialização falha e o
`inventory-service`/`catalog-service` devolve `400 malformed_request`.

**Resposta `200 OK`:** mesmo formato de `GET /products/{productId}`, com `status` atualizado.

**Erros possíveis:** `400 validation_failed`, `400 malformed_request` (valor de `status`
desconhecido), `401 unauthorized`, `403 forbidden`, `404 product_not_found`.

---

## `GET /api/products`

Lista produtos, paginado (parâmetros de query padrão do Spring: `page`, `size`, `sort`). Tamanho
de página padrão: 20; tamanho máximo: 100.

**Autenticação:** Bearer, qualquer papel autenticado.

Comprador (`BUYER` ou qualquer papel não `SELLER_ADMIN`) recebe apenas produtos `ACTIVE`;
`SELLER_ADMIN` recebe todos os produtos, incluindo `DISCONTINUED`. A visão é derivada do papel do
token — nunca de um parâmetro de query, para impedir que um comprador peça a visão completa.

**Resposta `200 OK`:**

```json
{
  "content": [
    {
      "id": "6a4f2e10-...",
      "sku": "SKU-001",
      "name": "Caixa de parafusos M4",
      "description": "Embalagem com 500 unidades",
      "price": 129.90,
      "status": "ACTIVE",
      "createdAt": "2026-09-19T14:32:00Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

**Erros possíveis:** `401 unauthorized`.

---

## `PUT /api/inventory/{productId}`

Define a quantidade em estoque de um produto (upsert): a primeira chamada para um `productId` cria
a linha de inventário; as seguintes atualizam a mesma linha.

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Corpo da requisição:**

```json
{
  "quantityOnHand": 100
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `quantityOnHand` | integer | Sim | `>= 0` |

Redefinir a quantidade para um valor menor que a já reservada é recusado com
`409 stock_below_reserved`, em vez de deixar a constraint do banco rejeitar com uma mensagem bruta.

**Resposta `200 OK`:**

```json
{
  "productId": "6a4f2e10-...",
  "quantityOnHand": 100,
  "quantityReserved": 0,
  "quantityAvailable": 100
}
```

`quantityAvailable` é sempre `quantityOnHand - quantityReserved`, exposto como número — nunca um
indicador booleano de disponível/indisponível.

**Erros possíveis:** `400 validation_failed`, `401 unauthorized`, `403 forbidden` (papel diferente
de `SELLER_ADMIN`), `409 stock_below_reserved`, `409 data_conflict`, `503 reservation_conflict`
(disputa de concorrência sobre a mesma linha esgotou as reexecuções).

---

## `GET /api/inventory/{productId}`

Consulta o estoque exato de um produto — sem restrição de papel além de estar autenticado.

**Autenticação:** Bearer, qualquer papel autenticado.

**Resposta `200 OK`:** mesmo formato de `PUT /inventory/{productId}`.

**Erros possíveis:** `401 unauthorized`, `404 inventory_not_found` (nenhuma linha de inventário
para o `productId`).

---

## `POST /api/inventory/{productId}/reservations`

Reserva uma quantidade de estoque. Operação idempotente: `reservationId` é um identificador de
idempotência fornecido pelo chamador, único por par `(productId, reservationId)` — reenviar a
mesma requisição com o mesmo `reservationId` devolve o estado atual sem duplicar a reserva.

**Autenticação:** Bearer, papel `SELLER_ADMIN`. Restrito nesta fase: nenhum comprador reserva
estoque diretamente.

**Corpo da requisição:**

```json
{
  "reservationId": "a1b2c3d4-...",
  "quantity": 10
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `reservationId` | string | Sim | Não vazio, até 255 caracteres |
| `quantity` | integer | Sim | Positivo (`> 0`) |

**Resposta `200 OK`:** mesmo formato de `PUT /inventory/{productId}`, refletindo a reserva
aplicada (ou a resposta idempotente, se `reservationId` já havia sido usado antes).

**Resposta de erro `409 insufficient_stock`** (quando `quantity` excede `quantityAvailable`):

```json
{
  "error": "insufficient_stock",
  "message": "Requested quantity exceeds available stock",
  "available": 5,
  "requested": 10
}
```

**Erros possíveis:** `400 validation_failed`, `401 unauthorized`, `403 forbidden`,
`404 inventory_not_found`, `409 insufficient_stock`, `409 data_conflict`,
`503 reservation_conflict` (disputa de concorrência esgotou as reexecuções — semanticamente
diferente de `insufficient_stock`: significa "tente novamente", não "não há estoque real").

---

## `DELETE /api/inventory/{productId}/reservations/{reservationId}`

Libera uma reserva de estoque. Operação idempotente: reserva ausente ou já liberada é um no-op
silencioso que devolve o estado atual sem alterar nada — não é um erro.

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Resposta `200 OK`:** mesmo formato de `PUT /inventory/{productId}`.

**Erros possíveis:** `401 unauthorized`, `403 forbidden`, `404 inventory_not_found` (`productId`
sem linha de inventário — a ausência da própria reserva não gera erro), `409 data_conflict`,
`503 reservation_conflict`.

---

## `GET /api/notifications/{productId}`

Lista o histórico de eventos de notificação de um produto, em ordem cronológica de `occurredAt`
(a fila SQS padrão não garante ordem de entrega, então a ordem é restaurada na leitura, não na
gravação). Consumido de forma assíncrona: o `inventory-service` publica o evento na fila
`notification-events-queue` depois de commitar um ajuste de estoque, e o `notification-service`
grava o histórico no DynamoDB sem nenhuma chamada HTTP de entrada do `inventory-service` — ver
[README.md § Como o evento flui](../README.md#como-o-evento-flui-sqs--dynamodb).

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Resposta `200 OK`** (produto com um evento `STOCK_ADJUSTED` registrado):

```json
[
  {
    "productId": "6a4f2e10-...",
    "eventId": "3f7b1c2a-...",
    "eventType": "STOCK_ADJUSTED",
    "message": "Estoque do produto 6a4f2e10-... ajustado de 0 para 7 unidades",
    "payload": {
      "eventId": "3f7b1c2a-...",
      "eventType": "STOCK_ADJUSTED",
      "productId": "6a4f2e10-...",
      "previousQuantityOnHand": 0,
      "newQuantityOnHand": 7,
      "occurredAt": "2026-09-23T02:26:12.244646700Z"
    },
    "occurredAt": "2026-09-23T02:26:12.244646700Z",
    "recordedAt": "2026-09-23T02:26:13.108921400Z"
  }
]
```

`message` é um texto legível montado pelo servidor a partir do evento — não vem do
`inventory-service`. `payload` é o corpo original do evento de contrato, como objeto JSON aninhado
(não texto escapado), para inspeção direta pela Swagger UI. `recordedAt` é o instante em que o
`notification-service` gravou o item no DynamoDB, sempre igual ou posterior a `occurredAt`.

**Resposta `200 OK`** (produto sem nenhum evento registrado):

```json
[]
```

**Erros possíveis:** `400 invalid_identifier` (`{productId}` não é um UUID válido),
`401 unauthorized`, `403 forbidden` (qualquer papel diferente de `SELLER_ADMIN`, incluindo
`BUYER`), `503 notification_store_unavailable` (DynamoDB indisponível — o motivo real fica só no
log do servidor).

---

## `POST /api/orders`

Cria um pedido a partir de itens do catálogo. A empresa dona do pedido vem sempre do claim
`company_id` do próprio token — não existe (nem é aceito) um campo de empresa no corpo. Cada item é
validado e precificado com uma chamada síncrona ao `catalog-service` (`GET /products/{productId}`);
o preço, nome e SKU são congelados como snapshot no momento da criação e não mudam depois, mesmo
que o produto mude no catálogo. O pedido nasce `APPROVED` (decidido automaticamente, `decidedBy:
"SYSTEM"`) se `exposição de crédito atual da empresa + total do pedido` couber no limite de
crédito da empresa (consultado no `auth-service`); caso contrário nasce `PENDING_APPROVAL` e espera
a decisão manual do vendedor — ver
[README.md § Como a aprovação por crédito funciona](../README.md#como-a-aprovação-por-crédito-funciona).

**Autenticação:** Bearer, papel `BUYER`.

**Corpo da requisição:**

```json
{
  "items": [
    { "productId": "6a4f2e10-...", "quantity": 4 }
  ]
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `items` | array | Sim | Não vazio, no máximo 50 itens; `productId` não pode se repetir no mesmo pedido |
| `items[].productId` | string (UUID) | Sim | — |
| `items[].quantity` | integer | Sim | Positivo (`> 0`), no máximo 1000000 |

**Resposta `201 Created`** (header `Location: /orders/{id}`; contrato completo, `id` sempre o
primeiro campo):

```json
{
  "id": "3f7b1c2a-...",
  "companyId": "9a21e4d0-...",
  "status": "APPROVED",
  "total": 400.00,
  "createdBy": "8c793e97-...",
  "createdAt": "2026-09-25T14:32:00Z",
  "decidedBy": "SYSTEM",
  "decidedAt": "2026-09-25T14:32:00Z",
  "reason": null,
  "items": [
    {
      "lineNumber": 1,
      "productId": "6a4f2e10-...",
      "sku": "SKU-001",
      "name": "Caixa de parafusos M4",
      "unitPrice": 100.00,
      "quantity": 4,
      "subtotal": 400.00
    }
  ]
}
```

`status` é `APPROVED` ou `PENDING_APPROVAL` — nunca outro valor nesta resposta, já que a criação
sempre decide entre esses dois. `decidedBy`/`decidedAt`/`reason` só são preenchidos quando o pedido
já nasce `APPROVED` (decisão automática, `decidedBy: "SYSTEM"`, `reason: null`); num pedido
`PENDING_APPROVAL`, os três campos vêm nulos até a decisão manual (ver
`POST /orders/{orderId}/approve`/`reject` abaixo).

**Erros possíveis:** `400 validation_failed` (lista vazia/ausente, mais de 50 itens, `productId`
nulo, `quantity` nula/`<= 0`/acima de 1000000, `productId` repetido), `400 malformed_request` (corpo
não é JSON válido), `401 unauthorized`, `403 forbidden` (papel diferente de `BUYER`),
`422 invalid_order_items` (item inexistente ou `DISCONTINUED` no catálogo),
`422 order_total_out_of_range` (total não cabe em `NUMERIC(19,2)`),
`503 catalog_service_unavailable`, `503 auth_service_unavailable`.

---

## `GET /api/orders`

Lista pedidos, paginado (`page`, `size` — máximo 100 — e `sort`, parâmetros padrão do Spring),
sempre do pedido mais recente para o mais antigo.

**Autenticação:** Bearer, qualquer papel autenticado.

`BUYER` vê apenas os pedidos da própria empresa (derivada do claim `company_id` do token, nunca de
um parâmetro de query); `SELLER_ADMIN` vê os pedidos de todas as empresas. O filtro opcional
`?status=` vale para os dois papéis igualmente.

**Resposta `200 OK`:**

```json
{
  "content": [
    {
      "id": "3f7b1c2a-...",
      "companyId": "9a21e4d0-...",
      "status": "APPROVED",
      "total": 400.00,
      "createdBy": "8c793e97-...",
      "createdAt": "2026-09-25T14:32:00Z",
      "decidedBy": "SYSTEM",
      "decidedAt": "2026-09-25T14:32:00Z",
      "reason": null
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

O resumo de cada pedido na listagem não inclui os itens — só `GET /orders/{orderId}` traz o
detalhe item a item.

**Erros possíveis:** `401 unauthorized`.

---

## `GET /api/orders/{orderId}`

Consulta o detalhe completo de um pedido pelo id, incluindo os itens.

**Autenticação:** Bearer, qualquer papel autenticado.

`BUYER` só enxerga pedidos da própria empresa — um pedido de outra empresa devolve `404
order_not_found`, o mesmo erro de um id inexistente (nunca `403`), para não revelar que o pedido
existe. `SELLER_ADMIN` consulta qualquer pedido, de qualquer empresa.

**Resposta `200 OK`:** mesmo formato completo de `POST /orders` (ORDER_RESPONSE_CONTRACT), com
`items` incluído.

**Erros possíveis:** `400 invalid_parameter` (`{orderId}` não é um UUID válido),
`401 unauthorized`, `404 order_not_found` (id inexistente, ou pedido de outra empresa visto por um
`BUYER`).

---

## `POST /api/orders/{orderId}/approve`

Aprova manualmente um pedido `PENDING_APPROVAL`. A aprovação manual não reavalia o limite de
crédito — mesmo que a soma resultante ultrapasse o limite da empresa, o pedido é aprovado e passa a
consumir crédito normalmente a partir desse momento (nenhuma chamada ao `auth-service` acontece
nesta decisão).

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Corpo da requisição** (opcional):

```json
{
  "reason": "cliente estratégico"
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `reason` | string | Não | Até 500 caracteres; em branco é gravado como `null` |

**Resposta `200 OK`:** mesmo formato de `GET /orders/{orderId}`, com `status: "APPROVED"`,
`decidedBy` igual ao `sub` do JWT do vendedor (nunca do corpo), `decidedAt` preenchido e `reason`
igual ao motivo enviado (ou `null`).

**Erros possíveis:** `400 invalid_parameter` (`{orderId}` não é um UUID válido),
`400 validation_failed` (`reason` acima de 500 caracteres), `401 unauthorized`,
`403 forbidden` (papel diferente de `SELLER_ADMIN`, incluindo o próprio `BUYER` dono do pedido),
`404 order_not_found`, `409 order_not_pending` (pedido não está em `PENDING_APPROVAL` — decisão
anterior permanece inalterada).

---

## `POST /api/orders/{orderId}/reject`

Rejeita um pedido `PENDING_APPROVAL`. Pedido rejeitado nunca consome crédito.

**Autenticação:** Bearer, papel `SELLER_ADMIN`.

**Corpo da requisição** (obrigatório, ao contrário de `approve`):

```json
{
  "reason": "limite excedido"
}
```

| Campo | Tipo | Obrigatório | Restrições |
|---|---|---|---|
| `reason` | string | Sim | Não vazio, até 500 caracteres |

**Resposta `200 OK`:** mesmo formato de `GET /orders/{orderId}`, com `status: "REJECTED"`,
`decidedBy` igual ao `sub` do JWT do vendedor, `decidedAt` preenchido e `reason` igual ao motivo
enviado.

**Erros possíveis:** `400 invalid_parameter` (`{orderId}` não é um UUID válido),
`400 malformed_request` (sem corpo), `400 validation_failed` (`reason` vazio, em branco ou acima de
500 caracteres), `401 unauthorized`, `403 forbidden` (papel diferente de `SELLER_ADMIN`),
`404 order_not_found`, `409 order_not_pending`.

---

## `GET /actuator/health`

Health check do próprio Gateway (usado pelo `docker-compose` para aguardar o serviço ficar
saudável antes de considerar a stack pronta). Cada serviço downstream também expõe seu próprio
`GET /actuator/health/**` publicamente, sem passar pelo Gateway.

**Autenticação:** nenhuma.

**Resposta `200 OK`:**

```json
{ "status": "UP" }
```

---

## `GET /.well-known/jwks.json` (uso interno)

Publica a chave pública RSA usada para assinar os tokens, no formato JWKS padrão. Não é roteado
pelo Gateway — destina-se a outros serviços do próprio sistema validarem o JWT localmente, sem
depender de uma chamada síncrona ao `auth-service` a cada requisição.

**Autenticação:** nenhuma (é um endpoint de chave pública).

**Resposta `200 OK`:**

```json
{
  "keys": [
    { "kty": "RSA", "e": "AQAB", "kid": "...", "n": "..." }
  ]
}
```

Contém apenas material público (`n`, `e`) — nenhum componente privado da chave é exposto.
