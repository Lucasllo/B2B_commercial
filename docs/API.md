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
| order-service | `POST` | `/api/orders/{orderId}/ship` | Expede um pedido `CONFIRMED` (`SHIPPED`) e baixa o estoque (Fase 6) | Bearer, papel `SELLER_ADMIN` |
| order-service | `POST` | `/api/orders/{orderId}/deliver` | Registra a entrega de um pedido `SHIPPED` (`DELIVERED`) (Fase 6) | Bearer, papel `SELLER_ADMIN` |
| notification-service | `GET` | `/api/notifications/orders/{orderId}` | Linha do tempo de um pedido: os eventos `ORDER_*`, em ordem de ciclo de vida (Fase 6) | Bearer — `SELLER_ADMIN` (qualquer pedido) ou `BUYER` (só pedidos da própria empresa) |
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
| 400 | `invalid_identifier` | `{productId}` ou `{orderId}` não é um UUID válido |
| 404 | `order_not_found` | `GET /notifications/orders/{orderId}` chamado por um `BUYER` para um pedido sem eventos, inexistente ou de outra empresa — os três casos são indistinguíveis (Fase 6) |
| 503 | `notification_store_unavailable` | Falha do SDK da AWS ao falar com o DynamoDB (indisponibilidade, timeout, erro de credencial) — o motivo real fica só no log do servidor |

**Erros específicos de `order-service`:**

| Status | `error` | Quando ocorre |
|---|---|---|
| 400 | `invalid_parameter` | `{orderId}` não é um UUID válido |
| 404 | `order_not_found` | `{orderId}` não corresponde a nenhum pedido, **ou** corresponde a um pedido de outra empresa e o requisitante é `BUYER` (pedido alheio é indistinguível de inexistente) |
| 409 | `order_not_pending` | `POST /orders/{orderId}/approve` ou `/reject` sobre um pedido que não está em `PENDING_APPROVAL` |
| 409 | `invalid_order_transition` | `POST /orders/{orderId}/ship` ou `/deliver` sobre um pedido cujo status atual não permite a transição (por exemplo `ship` em `CANCELLED`, ou `deliver` em `CONFIRMED`); o corpo traz `Order cannot transition from <ATUAL> to <DESTINO>` e o pedido não é alterado (Fase 6) |
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
    "entityId": "6a4f2e10-...",
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

**Mudança na Fase 6:** o primeiro campo da resposta passou a se chamar `entityId` (antes
`productId`). A partição do DynamoDB virou genérica para guardar também os eventos de pedido; aqui
`entityId` é sempre o id do produto consultado. Os demais campos não mudaram, e a rota continua
exclusiva de `SELLER_ADMIN`.

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

## `GET /api/notifications/orders/{orderId}`

Devolve a **linha do tempo** de um pedido (Fase 6): um registro por transição, em ordem de
`occurredAt` e, para eventos no mesmo instante, na ordem do ciclo de vida (`ORDER_CREATED` antes de
`ORDER_APPROVED`, e assim por diante — a fila padrão do SQS não garante ordem de entrega, então a
ordem é restaurada na leitura). Cada transição do pedido é publicada pelo outbox do `order-service`
na `notification-events-queue` e gravada pelo `notification-service` no DynamoDB, com `entityId` igual
ao id do pedido — nenhuma chamada HTTP entre os serviços. Só os eventos `ORDER_*` aparecem aqui; ver
"Eventos da linha do tempo do pedido" abaixo.

**Autenticação:** Bearer, papel `SELLER_ADMIN` ou `BUYER`.

- `SELLER_ADMIN` consulta qualquer pedido; um pedido sem eventos devolve lista vazia.
- `BUYER` só consulta pedidos da própria empresa (o `company_id` vem do token, nunca do caminho). Se
  a lista de eventos do pedido estiver vazia, ou se qualquer registro pertencer a outra empresa, a
  resposta é `404 order_not_found` — idêntica à de um pedido inexistente, para não revelar que o
  pedido existe.

**Resposta `200 OK`** (pedido entregue; `payload` abreviado para os campos principais):

```json
[
  {
    "entityId": "3f7b1c2a-...",
    "eventId": "a1c0e2d4-...",
    "eventType": "ORDER_CREATED",
    "message": "Pedido criado — total 400.00",
    "payload": {
      "eventId": "a1c0e2d4-...",
      "eventType": "ORDER_CREATED",
      "occurredAt": "2026-09-30T14:32:00.120000Z",
      "orderId": "3f7b1c2a-...",
      "companyId": "9a21e4d0-...",
      "createdBy": "8c793e97-...",
      "total": 400.00
    },
    "occurredAt": "2026-09-30T14:32:00.120000Z",
    "recordedAt": "2026-09-30T14:32:01.508312Z"
  },
  {
    "entityId": "3f7b1c2a-...",
    "eventId": "b7d3f1a9-...",
    "eventType": "ORDER_APPROVED",
    "message": "Pedido aprovado automaticamente — dentro do limite de crédito",
    "payload": {
      "eventId": "b7d3f1a9-...",
      "eventType": "ORDER_APPROVED",
      "occurredAt": "2026-09-30T14:32:00.120000Z",
      "orderId": "3f7b1c2a-...",
      "companyId": "9a21e4d0-...",
      "decidedBy": "SYSTEM"
    },
    "occurredAt": "2026-09-30T14:32:00.120000Z",
    "recordedAt": "2026-09-30T14:32:01.511070Z"
  },
  {
    "entityId": "3f7b1c2a-...",
    "eventId": "c2e8a6b1-...",
    "eventType": "ORDER_CONFIRMED",
    "message": "Pedido confirmado — transportadora Expresso Cerrado, rastreio AB123456789BR",
    "payload": {
      "eventId": "c2e8a6b1-...",
      "eventType": "ORDER_CONFIRMED",
      "occurredAt": "2026-09-30T14:32:03.402000Z",
      "orderId": "3f7b1c2a-...",
      "companyId": "9a21e4d0-...",
      "carrier": "Expresso Cerrado",
      "trackingCode": "AB123456789BR"
    },
    "occurredAt": "2026-09-30T14:32:03.402000Z",
    "recordedAt": "2026-09-30T14:32:04.790415Z"
  },
  {
    "entityId": "3f7b1c2a-...",
    "eventId": "d9a4b3c7-...",
    "eventType": "ORDER_SHIPPED",
    "message": "Pedido enviado pelo vendedor 8c793e97-...",
    "payload": { "eventType": "ORDER_SHIPPED", "shippedBy": "8c793e97-...", "...": "..." },
    "occurredAt": "2026-09-30T14:40:10.000000Z",
    "recordedAt": "2026-09-30T14:40:11.213907Z"
  },
  {
    "entityId": "3f7b1c2a-...",
    "eventId": "e5b1c8d2-...",
    "eventType": "ORDER_DELIVERED",
    "message": "Pedido entregue — registrado pelo vendedor 8c793e97-...",
    "payload": { "eventType": "ORDER_DELIVERED", "deliveredBy": "8c793e97-...", "...": "..." },
    "occurredAt": "2026-09-30T15:05:42.000000Z",
    "recordedAt": "2026-09-30T15:05:43.640021Z"
  }
]
```

Os campos têm o mesmo significado de `GET /notifications/{productId}`; `entityId` é o id do pedido.
`payload` é o corpo original do evento, como objeto JSON aninhado, e traz só os campos do tipo (campos
nulos são omitidos).

**Resposta `200 OK`** (`SELLER_ADMIN`, pedido sem eventos registrados — por exemplo o evento ainda
está a caminho): `[]`.

**Erros possíveis:** `400 invalid_identifier` (`{orderId}` não é um UUID válido), `401 unauthorized`,
`403 forbidden` (papel diferente de `SELLER_ADMIN`/`BUYER`, ou `BUYER` sem claim `company_id` válido),
`404 order_not_found` (só para `BUYER`: pedido sem eventos, inexistente ou de outra empresa),
`503 notification_store_unavailable`.

### Eventos da linha do tempo do pedido

Oito tipos `ORDER_*`, todos com o envelope comum `eventId`, `eventType`, `occurredAt` (instante
ISO-8601 da própria transição — o mesmo valor de `createdAt`, `decidedAt`, `confirmedAt`,
`cancelledAt`, `shippedAt` ou `deliveredAt` do pedido), `orderId` e `companyId`, mais os campos de
cada tipo. Um evento que não cumpre o contrato (campo obrigatório ausente, rastreio fora do padrão,
texto acima do limite) é descartado inteiro, com log `WARN`, e nunca gravado parcialmente.

| `eventType` | Quando é emitido | Campos do tipo | Exemplo de `message` |
|---|---|---|---|
| `ORDER_CREATED` | Na criação do pedido (`POST /orders`) | `createdBy`, `total` | `Pedido criado — total 400.00` |
| `ORDER_PENDING_APPROVAL` | Na criação, quando o total fica acima do limite de crédito | — | `Pedido aguardando aprovação do vendedor — valor acima do limite de crédito disponível` |
| `ORDER_APPROVED` | Aprovação automática na criação (`decidedBy` = `SYSTEM`) ou `POST /orders/{orderId}/approve` | `decidedBy`, `reason` (opcional) | `Pedido aprovado automaticamente — dentro do limite de crédito` ou `Pedido aprovado pelo vendedor 8c793e97-... — motivo: cliente estratégico` |
| `ORDER_REJECTED` | `POST /orders/{orderId}/reject` | `decidedBy`, `reason` | `Pedido rejeitado pelo vendedor 8c793e97-... — motivo: limite excedido` |
| `ORDER_CONFIRMED` | `StockReserved` leva o pedido a `CONFIRMED` | `carrier`, `trackingCode` | `Pedido confirmado — transportadora Expresso Cerrado, rastreio AB123456789BR` |
| `ORDER_CANCELLED` | `StockReservationFailed` ou timeout da saga leva o pedido a `CANCELLED` | `cancellationCode`, `cancellationReason` | `Pedido cancelado (INSUFFICIENT_STOCK) — Estoque insuficiente: produto SKU-001 — disponível 7, solicitado 20` |
| `ORDER_SHIPPED` | `POST /orders/{orderId}/ship` | `shippedBy` | `Pedido enviado pelo vendedor 8c793e97-...` |
| `ORDER_DELIVERED` | `POST /orders/{orderId}/deliver` | `deliveredBy` | `Pedido entregue — registrado pelo vendedor 8c793e97-...` |

A entrada em `RESERVING` não tem evento próprio (é parte da decisão, na mesma transação), e nada é
emitido para uma transição recusada (`409`), para uma duplicata de `StockReserved` nem para um
`StockReserved` tardio de um pedido já `CANCELLED`.

---

## `POST /api/orders`

Cria um pedido a partir de itens do catálogo. A empresa dona do pedido vem sempre do claim
`company_id` do próprio token — não existe (nem é aceito) um campo de empresa no corpo. Cada item é
validado e precificado com uma chamada síncrona ao `catalog-service` (`GET /products/{productId}`);
o preço, nome e SKU são congelados como snapshot no momento da criação e não mudam depois, mesmo
que o produto mude no catálogo. Se `exposição de crédito atual da empresa + total do pedido` couber
no limite de crédito da empresa (consultado no `auth-service`), o pedido é decidido automaticamente
(`decidedBy: "SYSTEM"`) e já nasce `RESERVING` — a decisão dispara a saga de reserva de estoque
(Fase 5) na mesma transação; caso contrário nasce `PENDING_APPROVAL` e espera a decisão manual do
vendedor. Esta resposta **não bloqueia** esperando o resultado da reserva — acompanhe o pedido por
`GET /orders/{orderId}` até ele chegar a `CONFIRMED` ou `CANCELLED` — ver
[README.md § Saga de reserva de estoque (Fase 5)](../README.md#saga-de-reserva-de-estoque-fase-5) e
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
  "status": "RESERVING",
  "total": 400.00,
  "createdBy": "8c793e97-...",
  "createdAt": "2026-09-25T14:32:00Z",
  "decidedBy": "SYSTEM",
  "decidedAt": "2026-09-25T14:32:00Z",
  "reason": null,
  "cancellationCode": null,
  "cancellationReason": null,
  "confirmedAt": null,
  "cancelledAt": null,
  "carrier": null,
  "trackingCode": null,
  "shippedAt": null,
  "shippedBy": null,
  "deliveredAt": null,
  "deliveredBy": null,
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

A partir da Fase 6 o contrato traz seis campos novos, entre `cancelledAt` e `items`: `carrier` e
`trackingCode` (preenchidos quando o pedido chega a `CONFIRMED`), `shippedAt` e `shippedBy`
(preenchidos por `POST /orders/{orderId}/ship`) e `deliveredAt` e `deliveredBy` (preenchidos por
`POST /orders/{orderId}/deliver`). Nesta resposta os seis vêm sempre nulos; um exemplo de pedido já
`CONFIRMED` está em `GET /orders/{orderId}`.

`status` é `RESERVING` ou `PENDING_APPROVAL` — nunca outro valor nesta resposta, já que a criação
sempre decide entre esses dois; `RESERVING` avança em seguida para `CONFIRMED` ou `CANCELLED` de
forma assíncrona (ver "Estados do pedido e saga de reserva de estoque" abaixo).
`decidedBy`/`decidedAt`/`reason` só são preenchidos quando o pedido já nasce decidido
automaticamente (`decidedBy: "SYSTEM"`, `reason: null`); num pedido `PENDING_APPROVAL`, os três
campos vêm nulos até a decisão manual (ver `POST /orders/{orderId}/approve`/`reject` abaixo). Os
quatro campos da saga (`cancellationCode`, `cancellationReason`, `confirmedAt`, `cancelledAt`) vêm
sempre nulos nesta resposta — só são preenchidos quando o resultado da reserva chega, consultável
por `GET /orders/{orderId}`.

**Erros possíveis:** `400 validation_failed` (lista vazia/ausente, mais de 50 itens, `productId`
nulo, `quantity` nula/`<= 0`/acima de 1000000, `productId` repetido), `400 malformed_request` (corpo
não é JSON válido), `401 unauthorized`, `403 forbidden` (papel diferente de `BUYER`),
`422 invalid_order_items` (item inexistente ou `DISCONTINUED` no catálogo),
`422 order_total_out_of_range` (total não cabe em `NUMERIC(19,2)`),
`503 catalog_service_unavailable`, `503 auth_service_unavailable`.

### Estados do pedido e saga de reserva de estoque (Fase 5)

| Transição | Disparada por | Consome crédito? |
|---|---|---|
| `CREATED` → `PENDING_APPROVAL` | Total acima do limite de crédito disponível da empresa | Não |
| `CREATED`/`PENDING_APPROVAL` → `APPROVED` | Decisão automática (`SYSTEM`) ou manual (`SELLER_ADMIN`, `POST /orders/{orderId}/approve`) | Sim — passo lógico da decisão, nunca um estado de repouso |
| `APPROVED` → `RESERVING` | Mesma transação da decisão — grava o comando `ReserveStock` no outbox | Sim |
| `PENDING_APPROVAL` → `REJECTED` | Decisão manual (`POST /orders/{orderId}/reject`) | Não |
| `RESERVING` → `CONFIRMED` | Evento `StockReserved` consumido da fila de resultado da saga | Sim |
| `RESERVING` → `CANCELLED` | Evento `StockReservationFailed`, ou timeout da saga (`RESERVATION_TIMEOUT`) | Não — libera o crédito consumido |
| `CONFIRMED` → `SHIPPED` (Fase 6) | `POST /orders/{orderId}/ship` — grava o comando `ShipStock` no outbox | Sim |
| `SHIPPED` → `DELIVERED` (Fase 6) | `POST /orders/{orderId}/deliver` — nenhuma mensagem de estoque | Sim |

`APPROVED` nunca é observado como o `status` de um pedido em repouso a partir da Fase 5 — é sempre
um passo intermediário registrado em `decidedBy`/`decidedAt`/`reason`, e a transição para
`RESERVING` acontece na mesma transação da decisão (D-50). Pela mesma razão `CREATED` nunca é
observado em repouso. `CANCELLED` só é alcançado pela saga — não existe endpoint de cancelamento.
`REJECTED`, `CANCELLED` e `DELIVERED` são terminais; qualquer aresta fora desta tabela devolve
`409 invalid_order_transition`. O diagrama completo, com as nove arestas permitidas, está em
[README.md § Ciclo de vida do pedido (Fase 6)](../README.md#ciclo-de-vida-do-pedido-fase-6) e é
conferido por teste contra a tabela de transições do código.

**Transportadora e rastreio são simulados.** Ao chegar a `CONFIRMED`, o pedido recebe `carrier` e
`trackingCode` no mesmo instante. A transportadora é uma das cinco abaixo (nomes fictícios, escolhida de
forma determinística a partir do `orderId`) e o rastreio segue o padrão S10 dos Correios,
`^[A-Z]{2}[0-9]{9}BR$`; nada é consultado em rede. A costura `CarrierGateway` é onde uma API real
entraria.

| Transportadoras possíveis (`carrier`) |
|---|
| `Expresso Cerrado`, `TransSul Cargas`, `Rapido Paulista`, `Norte Entregas`, `Litoral Log` |

**Mensagens da saga que cruzam as filas** (JSON, sem JWT; todas com `eventId`, `eventType`,
`occurredAt`, `orderId` e `reservationId` = id do pedido em texto):

| Mensagem | Fila | Produtor → consumidor | Campos adicionais |
|---|---|---|---|
| `ReserveStock` | `inventory-commands-queue` | `order-service` → `inventory-service` | `items[{productId, quantity}]` |
| `ReleaseStock` | `inventory-commands-queue` | `order-service` → `inventory-service` | `items[{productId, quantity}]`, `reason` |
| `ShipStock` (Fase 6) | `inventory-commands-queue` | `order-service` → `inventory-service` | `items[{productId, quantity}]`; sem `reason`; sem resposta |
| `StockReserved` / `StockReservationFailed` | `order-events-queue` | `inventory-service` → `order-service` | resultado da reserva |

`ShipStock` exemplo:

```json
{
  "eventId": "7d1c9a40-...",
  "eventType": "ShipStock",
  "occurredAt": "2026-09-30T14:40:10.000000Z",
  "orderId": "3f7b1c2a-...",
  "reservationId": "3f7b1c2a-...",
  "items": [
    { "productId": "6a4f2e10-...", "quantity": 4 }
  ]
}
```

O `inventory-service` baixa, para cada item, `quantityOnHand` e `quantityReserved` pela quantidade
**registrada no livro de reservas** (não a da mensagem), numa única transação. É idempotente pelo
livro: um `ShipStock` repetido para uma reserva já expedida é no-op, e um `ReleaseStock` (ou o
`DELETE` REST) para uma reserva já expedida é ignorado. Não há resposta ao `order-service` — o pedido
já está `SHIPPED` quando o comando é entregue — e a expedição não publica `STOCK_ADJUSTED`. Uma
reserva inexistente ou já liberada é uma anomalia: a mensagem é reentregue até a DLQ
(`inventory-commands-dlq`).

**Códigos de cancelamento (`cancellationCode`):**

| Código | Quando ocorre |
|---|---|
| `INSUFFICIENT_STOCK` | Quantidade solicitada de um ou mais itens excede o disponível no `inventory-service` |
| `PRODUCT_NOT_STOCKED` | Um ou mais itens não têm nenhuma linha de estoque cadastrada |
| `RESERVATION_CANCELLED` | A reserva foi cancelada antes de o `inventory-service` processá-la (corrida da fila SQS padrão contra um `ReleaseStock` de compensação, resolvida por lápide — ver [README.md § Saga de reserva de estoque](../README.md#saga-de-reserva-de-estoque-fase-5)) |
| `RESERVATION_TIMEOUT` | O resultado da reserva não chegou dentro do prazo configurado (`orderflow.saga.reservation-timeout`) |

`cancellationReason` é um texto legível montado pelo servidor a partir de um modelo fixo por
código — nunca texto livre vindo da fila de mensagens. Exemplo para `INSUFFICIENT_STOCK`:

```
"Estoque insuficiente: produto SKU-001 — disponível 7, solicitado 20"
```

---

## `GET /api/orders`

Lista pedidos, paginado (`page`, `size` — máximo 100 — e `sort`, parâmetros padrão do Spring),
sempre do pedido mais recente para o mais antigo.

**Autenticação:** Bearer, qualquer papel autenticado.

`BUYER` vê apenas os pedidos da própria empresa (derivada do claim `company_id` do token, nunca de
um parâmetro de query); `SELLER_ADMIN` vê os pedidos de todas as empresas. O filtro opcional
`?status=` vale para os dois papéis igualmente e aceita qualquer valor do enum de estado, incluindo
os da saga de reserva de estoque (`?status=RESERVING`, `?status=CONFIRMED`, `?status=CANCELLED`).

**Resposta `200 OK`:**

```json
{
  "content": [
    {
      "id": "3f7b1c2a-...",
      "companyId": "9a21e4d0-...",
      "status": "RESERVING",
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

O resumo de cada pedido na listagem não inclui os itens, os campos da saga
(`cancellationCode`/`cancellationReason`/`confirmedAt`/`cancelledAt`) nem os da expedição (Fase 6:
`carrier`/`trackingCode`/`shippedAt`/`shippedBy`/`deliveredAt`/`deliveredBy`) — só
`GET /orders/{orderId}` traz o detalhe completo, item a item.

**Erros possíveis:** `401 unauthorized`.

---

## `GET /api/orders/{orderId}`

Consulta o detalhe completo de um pedido pelo id, incluindo os itens.

**Autenticação:** Bearer, qualquer papel autenticado.

`BUYER` só enxerga pedidos da própria empresa — um pedido de outra empresa devolve `404
order_not_found`, o mesmo erro de um id inexistente (nunca `403`), para não revelar que o pedido
existe. `SELLER_ADMIN` consulta qualquer pedido, de qualquer empresa. É por este endpoint que o
cliente acompanha a saga de reserva de estoque: um pedido criado ou aprovado como `RESERVING` avança
para `CONFIRMED` ou `CANCELLED` de forma assíncrona (ver
[README.md § Saga de reserva de estoque (Fase 5)](../README.md#saga-de-reserva-de-estoque-fase-5)).

**Resposta `200 OK`:** mesmo formato completo de `POST /orders` (ORDER_RESPONSE_CONTRACT), com
`items` incluído — pedido `CONFIRMED` traz `confirmedAt`, `carrier` e `trackingCode` preenchidos;
pedido `SHIPPED` acrescenta `shippedAt` e `shippedBy`; pedido `DELIVERED`, `deliveredAt` e
`deliveredBy`; pedido `CANCELLED` traz `cancellationCode`, `cancellationReason` e `cancelledAt`
preenchidos (e nunca recebe transportadora). Exemplo de um pedido `CONFIRMED` (a ordem dos campos é
a do contrato, com os seis campos da Fase 6 entre `cancelledAt` e `items`):

```json
{
  "id": "3f7b1c2a-...",
  "companyId": "9a21e4d0-...",
  "status": "CONFIRMED",
  "total": 400.00,
  "createdBy": "8c793e97-...",
  "createdAt": "2026-09-30T14:32:00Z",
  "decidedBy": "SYSTEM",
  "decidedAt": "2026-09-30T14:32:00Z",
  "reason": null,
  "cancellationCode": null,
  "cancellationReason": null,
  "confirmedAt": "2026-09-30T14:32:03Z",
  "cancelledAt": null,
  "carrier": "Expresso Cerrado",
  "trackingCode": "AB123456789BR",
  "shippedAt": null,
  "shippedBy": null,
  "deliveredAt": null,
  "deliveredBy": null,
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

**Erros possíveis:** `400 invalid_parameter` (`{orderId}` não é um UUID válido),
`401 unauthorized`, `404 order_not_found` (id inexistente, ou pedido de outra empresa visto por um
`BUYER`).

---

## `POST /api/orders/{orderId}/approve`

Aprova manualmente um pedido `PENDING_APPROVAL`. A aprovação manual não reavalia o limite de
crédito — mesmo que a soma resultante ultrapasse o limite da empresa, o pedido é aprovado e passa a
consumir crédito normalmente a partir desse momento (nenhuma chamada ao `auth-service` acontece
nesta decisão). A aprovação dispara a saga de reserva de estoque na mesma transação — o pedido já
volta `RESERVING`, não `APPROVED` (D-50, D-54) — ver
[README.md § Saga de reserva de estoque (Fase 5)](../README.md#saga-de-reserva-de-estoque-fase-5).

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

**Resposta `200 OK`:** mesmo formato de `GET /orders/{orderId}` (incluindo os seis campos da Fase 6,
aqui nulos), com `status: "RESERVING"`, `decidedBy` igual ao `sub` do JWT do vendedor (nunca do
corpo), `decidedAt` preenchido e `reason` igual ao motivo enviado (ou `null`). Acompanhe o resultado
da reserva por `GET /orders/{orderId}` até `CONFIRMED`/`CANCELLED`.

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

**Resposta `200 OK`:** mesmo formato de `GET /orders/{orderId}` (incluindo os seis campos da Fase 6,
aqui nulos), com `status: "REJECTED"`, `decidedBy` igual ao `sub` do JWT do vendedor, `decidedAt`
preenchido e `reason` igual ao motivo enviado.

**Erros possíveis:** `400 invalid_parameter` (`{orderId}` não é um UUID válido),
`400 malformed_request` (sem corpo), `400 validation_failed` (`reason` vazio, em branco ou acima de
500 caracteres), `401 unauthorized`, `403 forbidden` (papel diferente de `SELLER_ADMIN`),
`404 order_not_found`, `409 order_not_pending`.

---

## `POST /api/orders/{orderId}/ship`

Expede um pedido `CONFIRMED` (Fase 6): o pedido passa a `SHIPPED`, mantendo a transportadora e o
rastreio atribuídos na confirmação, e o `order-service` grava na mesma transação (Transactional
Outbox) o comando `ShipStock` e o evento `ORDER_SHIPPED`. O relay entrega o `ShipStock` ao
`inventory-service`, que dá a baixa física do estoque (`quantityOnHand` e `quantityReserved` caem
pela quantidade reservada, uma única vez). A resposta **não espera** a baixa — acompanhe-a por
`GET /inventory/{productId}`. Duas chamadas simultâneas sobre o mesmo pedido: uma vence, a outra
recebe `409`, e só um `ShipStock` é gravado. `SHIPPED` continua consumindo crédito.

**Autenticação:** Bearer, papel `SELLER_ADMIN`. Sem corpo.

**Resposta `200 OK`:** o pedido no mesmo formato de `GET /orders/{orderId}`, com `status: "SHIPPED"`,
`shippedAt` preenchido e `shippedBy` igual ao `sub` do JWT do vendedor (nunca do corpo):

```json
{
  "id": "3f7b1c2a-...",
  "status": "SHIPPED",
  "carrier": "Expresso Cerrado",
  "trackingCode": "AB123456789BR",
  "shippedAt": "2026-09-30T14:40:10Z",
  "shippedBy": "8c793e97-...",
  "deliveredAt": null,
  "deliveredBy": null,
  "...": "demais campos como em GET /orders/{orderId}"
}
```

**Erros possíveis:** `400 invalid_parameter` (`{orderId}` não é um UUID válido),
`401 unauthorized`, `403 forbidden` (papel diferente de `SELLER_ADMIN`, incluindo o `BUYER` dono do
pedido), `404 order_not_found`, `409 invalid_order_transition` (o pedido não está em `CONFIRMED`; o
pedido permanece inalterado):

```json
{
  "error": "invalid_order_transition",
  "message": "Order cannot transition from CANCELLED to SHIPPED"
}
```

---

## `POST /api/orders/{orderId}/deliver`

Registra a entrega de um pedido `SHIPPED` (Fase 6): o pedido passa a `DELIVERED` e o `order-service`
grava o evento `ORDER_DELIVERED`. Não há nenhuma mensagem de estoque (a baixa já aconteceu na
expedição) e `DELIVERED` continua consumindo crédito. É um estado terminal.

**Autenticação:** Bearer, papel `SELLER_ADMIN`. Sem corpo.

**Resposta `200 OK`:** o pedido no mesmo formato de `GET /orders/{orderId}`, com
`status: "DELIVERED"`, `deliveredAt` preenchido e `deliveredBy` igual ao `sub` do JWT do vendedor;
`shippedAt`/`shippedBy`, `carrier` e `trackingCode` permanecem como estavam.

**Erros possíveis:** os mesmos de `POST /orders/{orderId}/ship` — `400 invalid_parameter`,
`401 unauthorized`, `403 forbidden`, `404 order_not_found` e `409 invalid_order_transition` (o pedido
não está em `SHIPPED`, por exemplo `Order cannot transition from CONFIRMED to DELIVERED`).

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
