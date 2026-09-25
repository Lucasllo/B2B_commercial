# OrderFlow

OrderFlow é uma plataforma de gerenciamento de pedidos B2B/atacado: uma empresa vendedora
("seller") disponibiliza um catálogo de produtos para múltiplas empresas compradoras, que criam
pedidos sujeitos a aprovação por limite de crédito, reserva de estoque, atribuição de
transportadora e acompanhamento até a entrega. É um projeto de portfólio técnico voltado a
demonstrar competências exigidas para uma vaga de Desenvolvedor Java Pleno.

O que já funciona hoje (Fases 1 a 4): autenticação com JWT auto-emitido, gestão de empresas
compradoras e limite de crédito, catálogo de produtos, controle de estoque com reserva protegida
contra concorrência, um histórico de notificações assíncrono — um ajuste de estoque publica um
evento numa fila SQS real que o `notification-service` consome e grava no DynamoDB, sem nenhuma
chamada REST entre os dois serviços — e a criação de pedidos com aprovação automática ou manual por
limite de crédito, o núcleo do valor B2B do projeto: tudo rodando atrás de um API Gateway com a
stack inteira subindo localmente via `docker compose`.

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

Esse comando sobe os oito serviços (`postgres`, `localstack`, `auth-service`, `catalog-service`,
`inventory-service`, `notification-service`, `order-service`, `gateway`) e só retorna quando todos
estiverem `healthy`. A fila `notification-events-queue` e a tabela `notification-history` nascem
sozinhas nessa subida, provisionadas pelo init hook do LocalStack (`localstack-init/ready.d/`) —
nenhum passo manual é necessário. Confira com:

```
docker compose ps
```

Para derrubar a stack:

```
docker compose down -v
```

## Build e testes

```
./mvnw -B -pl auth-service,catalog-service,inventory-service,notification-service,order-service test
```
Testes unitários, sem necessidade de Docker.

```
./mvnw -B -pl auth-service,catalog-service,inventory-service,notification-service,order-service verify
```
Suíte completa, incluindo testes de integração com Testcontainers — exige o Docker em execução.
As suítes de integração do `inventory-service` e do `notification-service` sobem um LocalStack
real via Testcontainers e por isso precisam de `LOCALSTACK_AUTH_TOKEN` disponível como variável de
ambiente ou no `.env` da raiz do repositório — o suporte de teste (`LocalStackTestSupport`) resolve
o token subindo os diretórios pais a partir do módulo, sem configuração extra.

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

### Documentação interativa (Swagger UI)

Cada serviço com endpoints de negócio expõe sua própria Swagger UI, servida direto na porta do
serviço — não através do Gateway:

- `http://localhost:8081/swagger-ui.html` (auth-service)
- `http://localhost:8082/swagger-ui.html` (catalog-service)
- `http://localhost:8083/swagger-ui.html` (inventory-service)
- `http://localhost:8084/swagger-ui.html` (notification-service)
- `http://localhost:8085/swagger-ui.html` (order-service)

Como essas páginas são servidas na porta direta de cada serviço, e não pelo Gateway, os caminhos
mostrados ali aparecem **sem** o prefixo `/api` que o Gateway acrescenta (`StripPrefix=1`): o que
no Gateway é `POST /api/products` aparece na Swagger UI do catalog-service como `POST /products`.

Para exercitar um endpoint protegido: obtenha um token com o `POST /api/auth/login` do fluxo de
demonstração abaixo, clique em **Authorize** na Swagger UI, cole apenas o valor do token (sem
escrever a palavra `Bearer` — a UI acrescenta o prefixo sozinha) e então use o **Try it out** de
qualquer operação.

A UI e o spec JSON (`/v3/api-docs`) são deliberadamente acessíveis sem token — é uma ferramenta
local de desenvolvimento, e as portas 8081/8082/8083/8084 estão ligadas apenas a `127.0.0.1` no
`docker-compose.yml`. Essa liberação deveria ser fechada num eventual profile de produção.

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

### Histórico de notificações (Fase 3 — papel exigido entre parênteses)

- `GET /api/notifications/{productId}` (SELLER_ADMIN) — lista os eventos registrados para o
  produto, em ordem cronológica de `occurredAt`. Cada elemento traz `productId`, `eventId`,
  `eventType`, uma `message` legível, o `payload` original do evento (objeto JSON aninhado, não
  texto escapado), `occurredAt` e `recordedAt`. Produto sem nenhum evento devolve lista vazia
  (nunca `404`). `{productId}` que não é um UUID válido devolve `400 invalid_identifier`; um
  `BUYER` recebe `403 forbidden`.

### Como o evento flui (SQS → DynamoDB)

Um ajuste de estoque (`PUT /api/inventory/{productId}`) é gravado no PostgreSQL do
`inventory-service` dentro de uma transação. Só depois que essa transação já foi commitada, o
`inventory-service` publica um evento `STOCK_ADJUSTED` na fila SQS `notification-events-queue`. O
`notification-service` consome essa fila e grava um item na tabela DynamoDB
`notification-history`, com partition key `productId` e sort key `STOCK_ADJUSTED#<eventId>` — por
isso reentregar a mesma mensagem (mesmo `eventId`) sobrescreve o item existente em vez de duplicar,
e cada ajuste novo (um `eventId` novo) vira uma linha nova. A consulta em
`GET /api/notifications/{productId}` lê a tabela por Query (partition key) e ordena o resultado por
`occurredAt` na leitura, porque a fila padrão do SQS não garante ordem de entrega. **Em nenhum
momento o inventory-service e o notification-service se chamam por HTTP** — a única ligação entre
os dois é a fila.

Fila e tabela são criadas automaticamente pelo init hook do LocalStack montado no
`docker-compose.yml` (`localstack-init/ready.d/01-create-notification-resources.sh`) na primeira
subida da stack — nenhum passo manual é necessário. Para inspecionar os recursos diretamente:

```bash
# Atributos da fila (mensagens visíveis e em processamento)
docker compose exec localstack awslocal --region us-east-1 sqs get-queue-attributes \
  --queue-url "$(docker compose exec -T localstack awslocal --region us-east-1 sqs get-queue-url \
    --queue-name notification-events-queue --query QueueUrl --output text)" \
  --attribute-names All

# Varredura completa da tabela de histórico
docker compose exec localstack awslocal --region us-east-1 dynamodb scan \
  --table-name notification-history
```

Para provar o fluxo de ponta a ponta na stack real, sem abrir o código — fluxo pelo Gateway,
reentrega sem duplicação e ajustes distintos acumulando —, rode:

```bash
bash scripts/smoke-notification-flow.sh
```

### Pedidos (Fase 4 — papel exigido entre parênteses)

- `POST /api/orders` (BUYER) — cria um pedido a partir de itens do catálogo (`productId`,
  `quantity`); a empresa dona do pedido vem sempre do claim `company_id` do próprio token, nunca do
  corpo da requisição.
- `GET /api/orders` (qualquer autenticado) — `BUYER` vê apenas os pedidos da própria empresa;
  `SELLER_ADMIN` vê todos os pedidos. Filtro opcional `?status=`, paginação (`page`, `size`, até
  100), sempre do pedido mais recente para o mais antigo.
- `GET /api/orders/{orderId}` (qualquer autenticado) — pedido de outra empresa responde `404`,
  idêntico ao de um id inexistente (nunca `403`) — um `BUYER` não descobre que um pedido alheio
  existe.
- `POST /api/orders/{orderId}/approve` e `POST /api/orders/{orderId}/reject` (SELLER_ADMIN) —
  decisão manual sobre um pedido `PENDING_APPROVAL`; a rejeição exige motivo (`reason`), a
  aprovação aceita motivo opcional; decidir um pedido fora de `PENDING_APPROVAL` devolve `409`.

### Como a aprovação por crédito funciona

Ao criar um pedido, o `order-service` soma o total do pedido novo à exposição de crédito atual da
empresa — a soma ao vivo dos totais de todos os pedidos dessa empresa em `APPROVED`, `CONFIRMED`,
`SHIPPED` e `DELIVERED` (nunca um saldo pré-calculado). Se `exposição + total <= limite`, o pedido
nasce `APPROVED` automaticamente (`decidedBy: "SYSTEM"`); caso contrário, nasce `PENDING_APPROVAL`
e espera a decisão manual do vendedor. Um pedido aprovado manualmente pelo vendedor passa a
consumir crédito da mesma forma que um aprovado automaticamente, mesmo que a soma resultante
ultrapasse o limite — a aprovação manual não reavalia o limite, é uma decisão de negócio deliberada
que pode exceder a regra automática.

A ordem das chamadas na criação é sempre a mesma: primeiro o catálogo, um item por vez (`GET
/products/{id}` no `catalog-service`, para validar e precificar cada item); depois o limite de
crédito (`GET /companies/{id}/credit-limit` no `auth-service`); só então a transação que decide e
grava o pedido, sob uma trava por empresa (`company_credit_lock`, `PESSIMISTIC_WRITE`) que serializa
a decisão sem fazer nenhuma chamada de rede dentro da transação — essa trava é o que garante que
pedidos simultâneos da mesma empresa nunca aprovem, juntos, mais do que o limite permite (Success
Criteria 5 do ROADMAP).

Para provar o fluxo de ponta a ponta na stack real, pelo Gateway e com auth-service/catalog-service
reais — pedido aprovado e pendente pelo crédito, decisão do vendedor, recusa de produto
descontinuado e isolamento por empresa —, rode:

```bash
bash scripts/smoke-order-flow.sh
```

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

# 7. Consultar o histórico de notificações do produto (evento STOCK_ADJUSTED do passo 3, Fase 3)
curl -s http://localhost:8080/api/notifications/$PRODUCT_ID -H "Authorization: Bearer $TOKEN"
```

## Limitações conhecidas (Fase 3)

1. **Dual-write na publicação do evento** — o `inventory-service` publica `STOCK_ADJUSTED`
   diretamente no SQS depois do commit no PostgreSQL, sem Transactional Outbox (D-29). Se o SQS
   falhar exatamente nesse instante, o ajuste de estoque continua gravado, mas o evento se perde —
   o `inventory-service` registra a perda em log `ERROR` com `eventId`/`productId`/nome da fila,
   sem repetir a publicação. A Fase 5 (saga de reserva de estoque) introduz o padrão Transactional
   Outbox onde a atomicidade entre gravação e publicação passa a ser exigida.
2. **Sem fila de mensagens mortas (DLQ)** — uma mensagem que nunca poderia virar um registro válido
   (JSON malformado, campo obrigatório ausente) é descartada com log `WARN` em vez de reprocessada
   para sempre; uma falha transitória do DynamoDB, por outro lado, faz a mensagem voltar à fila sem
   teto de reentregas. Uma fila de mensagens mortas dedicada é requisito de uma v2 (`DLQ-01`).
3. **Fila sem política de acesso** — no LocalStack local, qualquer processo que alcance a porta
   4566 pode publicar na fila `notification-events-queue`. Em uma conta AWS real, `SendMessage`
   seria restrito à identidade do `inventory-service` por uma política de fila/IAM.
4. **Fila padrão, sem ordem garantida de entrega** — o SQS padrão (não FIFO) não garante que as
   mensagens cheguem na ordem em que foram publicadas; o histórico é reordenado por `occurredAt` no
   momento da leitura, não na gravação.

## Limitações conhecidas (Fase 4)

1. **`APPROVED` não é confirmado nem reserva estoque** — nesta fase, `APPROVED` é o estado final da
   linha do tempo do pedido: nenhuma reserva de estoque é feita no `inventory-service`, e o pedido
   não avança para `CONFIRMED`, `SHIPPED` ou `CANCELLED`. A saga que orquestra a reserva de estoque
   e as transições seguintes chega na Fase 5.
2. **Uma chamada ao catálogo por item, em sequência** — cada item do pedido gera uma chamada
   síncrona separada a `GET /products/{id}`; um endpoint de validação em lote é uma melhoria adiada.
3. **Sem circuit breaker** — as chamadas a `auth-service`/`catalog-service` têm apenas timeouts
   explícitos de conexão e leitura (D-41); um padrão de circuit breaker (Resilience4j) fica para o
   endurecimento da Fase 7.
4. **Espera pela trava de crédito sem timeout** — a trava por empresa (`company_credit_lock`)
   espera indefinidamente por design (D-41: nada que segura a trava faz I/O de rede), mas não tem
   um `lock_timeout` configurado — revisitar se uma fase futura acrescentar trabalho lento dentro da
   mesma transação.
5. **`order-service` repassa o JWT do comprador, sem identidade de serviço própria** — as chamadas a
   `auth-service`/`catalog-service` usam o mesmo token do comprador que criou o pedido; uma
   identidade de serviço dedicada é introduzida em fase futura.
6. **Produto descontinuado e produto inexistente aparecem iguais na recusa** — os dois casos
   devolvem o mesmo `422 invalid_order_items` com o id do produto, sem distinguir a causa no corpo
   da resposta.
7. **O vendedor não filtra a listagem por empresa** — `GET /orders` para `SELLER_ADMIN` sempre
   devolve pedidos de todas as empresas; um filtro por empresa na visão do vendedor não existe
   nesta fase.

## Arquitetura

As decisões arquiteturais desta fase (topologia do Gateway, modelo de Company/User, estratégia de
validação de JWT, isolamento de tenant) estão registradas em
[`.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-SKELETON.md`](.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-SKELETON.md).
