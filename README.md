# OrderFlow

OrderFlow é uma plataforma de gerenciamento de pedidos B2B/atacado: uma empresa vendedora
("seller") disponibiliza um catálogo de produtos para múltiplas empresas compradoras, que criam
pedidos sujeitos a aprovação por limite de crédito, reserva de estoque, atribuição de
transportadora e acompanhamento até a entrega. É um projeto de portfólio técnico voltado a
demonstrar competências exigidas para uma vaga de Desenvolvedor Java Pleno.

O que já funciona hoje (Fase 1 — esqueleto vertical): autenticação com JWT auto-emitido, gestão
de empresas compradoras e limite de crédito, tudo rodando atrás de um API Gateway com a stack
inteira subindo localmente via `docker compose`.

## Pré-requisitos

- Docker com Docker Compose V2 (`docker compose`, não o binário Python legado `docker-compose`)
- Java 21

**Não é preciso ter Maven instalado** — o Maven Wrapper (`./mvnw`) já está versionado no
repositório e baixa a distribuição correta sob demanda.

## Setup em um passo

1. Crie uma conta gratuita (tier Hobby) em [https://app.localstack.cloud](https://app.localstack.cloud)
   e copie o Auth Token em Workspace → Auth Token. **A imagem do LocalStack não inicia sem esse
   token desde a versão 2026.03.0** — sem ele, o `docker compose up` falha na subida do serviço
   `localstack` (e a subida inteira, por causa do `depends_on: condition: service_healthy`), mesmo
   no tier gratuito. Faça esse cadastro antes do primeiro `docker compose up` para não perder tempo
   depurando um container que reinicia sozinho.
2. Copie `.env.example` para `.env`:
   ```
   cp .env.example .env
   ```
3. Preencha, no `.env`, as variáveis `LOCALSTACK_AUTH_TOKEN` (o token copiado no passo 1) e
   `POSTGRES_PASSWORD` (qualquer string não vazia — só existe dentro do `docker-compose` local).

## Execução

```
docker compose up -d --wait
```

Esse comando sobe os seis serviços (`postgres`, `localstack`, `auth-service`, `catalog-service`,
`inventory-service`, `gateway`) e só retorna quando todos estiverem `healthy`. Confira com:

```
docker compose ps
```

Para derrubar a stack:

```
docker compose down -v
```

## Build e testes

```
./mvnw -B -pl auth-service,catalog-service,inventory-service test
```
Testes unitários, sem necessidade de Docker.

```
./mvnw -B -pl auth-service,catalog-service,inventory-service verify
```
Suíte completa, incluindo testes de integração com Testcontainers — exige o Docker em execução.

```
./mvnw -B -pl inventory-service verify -Dit.test=StockReservationConcurrencyIT
```
Roda isoladamente o teste que prova o Success Criteria 3 da Fase 2: requisições HTTP reais e
concorrentes, disparadas de threads virtuais e liberadas juntas por uma barreira, nunca reservam
mais do que o disponível.

## Credenciais de demonstração

O usuário `admin@orderflow.local` com a senha `ChangeMe!123` é semeado automaticamente pela
migração Flyway `V2__seed_seller_admin.sql` (papel `SELLER_ADMIN`) na primeira subida do banco.
Essa credencial existe **exclusivamente para demonstração deste portfólio**: ela não é uma
credencial administrativa real, não deve ser reaproveitada em nenhum outro contexto ou ambiente,
e o fato de estar versionada no repositório é intencional — é um dado de demonstração, não um
segredo vazado.

## Endpoints disponíveis nesta fase

Todos os endpoints abaixo são acessados através do API Gateway, em `http://localhost:8080`:

### Autenticação e empresas (Fase 1)

- `POST /api/auth/login` — autentica com `{ "email": ..., "password": ... }` e devolve um JWT.
- `GET /api/auth/me` — devolve os claims do JWT enviado (requer header `Authorization: Bearer <token>`).
- `GET /actuator/health` — health check do próprio Gateway.

`GET /.well-known/jwks.json` é interno ao `auth-service` (não roteado pelo Gateway) e existe para
que os serviços das Fases 2+ validem o token localmente, sem chamar o auth-service a cada
requisição (AUTH-03, D-03).

### Catálogo (Fase 2 — papel exigido entre parênteses)

- `POST /api/products` (SELLER_ADMIN) — cria produto (nome, SKU, preço, descrição); status sempre
  nasce `ACTIVE`, ignorando qualquer valor enviado pelo cliente.
- `GET /api/products/{id}` (qualquer autenticado) — detalhe de um produto; produto `DISCONTINUED`
  é 404 para BUYER e 200 para SELLER_ADMIN.
- `PUT /api/products/{id}` (SELLER_ADMIN) — atualiza nome, preço e descrição; SKU é imutável.
- `PUT /api/products/{id}/status` (SELLER_ADMIN) — retira (`DISCONTINUED`) ou reativa (`ACTIVE`)
  um produto; nunca há remoção física de linha.
- `GET /api/products` (qualquer autenticado) — listagem paginada (`page`/`size`); BUYER vê apenas
  produtos `ACTIVE`, SELLER_ADMIN vê todos.

### Estoque (Fase 2 — papel exigido entre parênteses)

- `PUT /api/inventory/{productId}` (SELLER_ADMIN) — define/redefine a quantidade em estoque
  (`quantityOnHand`); upsert — a primeira chamada cria a linha.
- `GET /api/inventory/{productId}` (qualquer autenticado) — devolve `quantityOnHand`,
  `quantityReserved` e `quantityAvailable` (número exato, nunca um booleano).
- `POST /api/inventory/{productId}/reservations` (SELLER_ADMIN) — reserva `{reservationId,
  quantity}`; idempotente pelo `reservationId` fornecido pelo chamador; `409` se a quantidade
  pedida excede o disponível.
- `DELETE /api/inventory/{productId}/reservations/{reservationId}` (SELLER_ADMIN) — libera uma
  reserva; repetir ou liberar um identificador inexistente é no-op silencioso (idempotente).

Catálogo e estoque são sempre duas chamadas HTTP separadas pelo Gateway — nenhum dos dois
serviços enriquece a resposta do outro (D-16).

### Fluxo de demonstração completo

Comandos copiáveis, executados em sequência (`jq` é opcional, só para extrair campos do JSON;
sem ele, copie o valor manualmente da resposta impressa):

```bash
# 1. Autenticar como o vendedor de demonstração da Fase 1
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@orderflow.local","password":"ChangeMe!123"}' | jq -r .accessToken)

# 2. Criar um produto
PRODUCT_ID=$(curl -s -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Caixa de parafusos M6","sku":"PARAFUSO-M6-100","price":49.90,"description":"Caixa com 100 unidades"}' \
  | jq -r .id)

# 3. Definir o estoque desse produto
curl -s -X PUT http://localhost:8080/api/inventory/$PRODUCT_ID \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"quantityOnHand":100}'

# 4. Consultar a disponibilidade (chamada separada do catálogo, D-16)
curl -s http://localhost:8080/api/inventory/$PRODUCT_ID -H "Authorization: Bearer $TOKEN"

# 5. Reservar uma quantidade
curl -s -X POST http://localhost:8080/api/inventory/$PRODUCT_ID/reservations \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"reservationId":"demo-1","quantity":10}'

# 6. Conferir que a disponibilidade caiu exatamente pela quantidade reservada (100 -> 90)
curl -s http://localhost:8080/api/inventory/$PRODUCT_ID -H "Authorization: Bearer $TOKEN"
```

## Arquitetura

As decisões arquiteturais desta fase (topologia do Gateway, modelo de Company/User, estratégia de
validação de JWT, isolamento de tenant) estão registradas em
[`.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-SKELETON.md`](.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-SKELETON.md).
