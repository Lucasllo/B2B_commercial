<!-- generated-by: gsd-doc-writer -->
# OrderFlow — Referência da API

> Endpoints disponíveis hoje no repositório, todos pertencentes ao `auth-service` e acessados
> através do API Gateway. Veja [VISAO-GERAL.md](VISAO-GERAL.md) para o contexto do projeto e o
> estado atual dos serviços.

## URL base

Todas as chamadas de cliente externo passam pelo Gateway:

```
http://localhost:8080
```

O Gateway remove o prefixo `/api` antes de encaminhar ao `auth-service`
(`Path=/api/auth/**,/api/companies/**` + `StripPrefix=1`). Ou seja, `POST /api/auth/login` no
Gateway chega como `POST /auth/login` no `auth-service`.

`GET /.well-known/jwks.json` **não é roteado pelo Gateway** — é interno ao `auth-service`
(`http://auth-service:8081/.well-known/jwks.json` na rede do Docker Compose) e existe para que
outros serviços validem o JWT localmente, sem chamar o `auth-service` a cada requisição.

## Autenticação

A API usa JWT auto-emitido (RSA), enviado no header `Authorization`:

```
Authorization: Bearer <token>
```

O token é obtido em `POST /api/auth/login` e carrega os claims `role` (`SELLER_ADMIN` ou `BUYER`)
e, para usuários `BUYER`, `company_id`. Não há refresh token nesta fase — apenas access token, com
TTL informado no próprio corpo de resposta do login.

Endpoints públicos, sem token: `POST /api/auth/login`, `GET /.well-known/jwks.json` (interno) e
`GET /actuator/health`. Todos os demais exigem um token válido; a ausência de token resulta em
`401 unauthorized` antes mesmo de o controller ser executado.

## Visão geral dos endpoints

| Método | Caminho (via Gateway) | Descrição | Autenticação |
|---|---|---|---|
| `POST` | `/api/auth/login` | Autentica com email/senha e devolve um JWT | Nenhuma |
| `GET` | `/api/auth/me` | Devolve os claims do JWT do requisitante | Bearer (qualquer papel) |
| `POST` | `/api/companies` | Cria uma empresa compradora + usuário `BUYER` vinculado | Bearer, papel `SELLER_ADMIN` |
| `GET` | `/api/companies/{companyId}/credit-limit` | Consulta o limite de crédito de uma empresa | Bearer — `SELLER_ADMIN` ou o próprio `BUYER` da empresa |
| `PUT` | `/api/companies/{companyId}/credit-limit` | Atualiza o limite de crédito de uma empresa | Bearer, papel `SELLER_ADMIN` |
| `GET` | `/actuator/health` | Health check do Gateway | Nenhuma |

## Formato de erro padrão

Todas as respostas de erro do `auth-service` seguem o mesmo envelope:

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

| Status | `error` | Quando ocorre |
|---|---|---|
| 400 | `validation_failed` | Corpo da requisição falha em uma validação de Bean Validation (campo em branco, e-mail inválido, valor monetário com mais de 2 casas decimais, etc.) |
| 401 | `unauthorized` | Requisição sem token, token inválido/expirado, ou credenciais de login incorretas |
| 403 | `forbidden` | Token válido, mas o papel/empresa do requisitante não tem permissão para a ação (`@PreAuthorize` negado) |
| 404 | `company_not_found` | `{companyId}` não corresponde a nenhuma empresa cadastrada |
| 409 | `email_already_used` | E-mail do `buyerUser` já está em uso por outro usuário |

Senha errada e e-mail inexistente em `/auth/login` devolvem exatamente o mesmo `401 unauthorized` —
isso é intencional, para não expor se um e-mail existe na base.

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

## `GET /actuator/health`

Health check do próprio Gateway (usado pelo `docker-compose` para aguardar o serviço ficar
saudável antes de considerar a stack pronta).

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
