# OrderFlow

OrderFlow é uma plataforma de gerenciamento de pedidos B2B/atacado: uma empresa vendedora
("seller") disponibiliza um catálogo de produtos para múltiplas empresas compradoras, que criam
pedidos sujeitos a aprovação por limite de crédito, reserva de estoque, atribuição de
transportadora e acompanhamento até a entrega. É um projeto de portfólio técnico voltado a
demonstrar competências exigidas para uma vaga de Desenvolvedor Java Pleno.

O que já funciona hoje (Fases 1 a 5): autenticação com JWT auto-emitido, gestão de empresas
compradoras e limite de crédito, catálogo de produtos, controle de estoque com reserva protegida
contra concorrência, um histórico de notificações assíncrono — um ajuste de estoque publica um
evento numa fila SQS real que o `notification-service` consome e grava no DynamoDB, sem nenhuma
chamada REST entre os dois serviços — a criação de pedidos com aprovação automática ou manual por
limite de crédito, e a saga de reserva de estoque: o pedido aprovado reserva estoque de forma
assíncrona no `inventory-service` (padrão Transactional Outbox nos dois serviços) e termina sempre em
`CONFIRMED` ou `CANCELLED`, nunca preso num estado intermediário — o Core Value do projeto: tudo
rodando atrás de um API Gateway com a stack inteira subindo localmente via `docker compose`.

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
As suítes de integração do `inventory-service`, do `notification-service` e, a partir da Fase 5, do
`order-service` (saga: outbox, consumo da `order-events-queue`, timeout) sobem um LocalStack real via
Testcontainers e por isso precisam de `LOCALSTACK_AUTH_TOKEN` disponível como variável de ambiente ou
no `.env` da raiz do repositório — o suporte de teste (`LocalStackTestSupport`) resolve o token
subindo os diretórios pais a partir do módulo, sem configuração extra. **Se a stack do
`docker compose` estiver de pé, derrube-a antes (`docker compose down`)** — a sessão do LocalStack
Hobby é única por token, e o Testcontainers falha com a stack do compose ocupando a mesma sessão.

```
./mvnw -B -pl inventory-service verify -Dit.test=StockReservationConcurrencyIT
```
Roda isoladamente o teste que prova o Success Criteria 3 da Fase 2: requisições HTTP reais e
concorrentes, disparadas de threads virtuais e liberadas juntas por uma barreira, nunca reservam
mais do que o disponível.

```
./mvnw -B -pl e2e-tests -am verify
```
Prova a saga inteira (Fase 5) de ponta a ponta num único comando: sobe o `order-service` e o
`inventory-service` como dois contextos Spring Boot reais no mesmo JVM (via `SpringApplicationBuilder`,
cada um isolado por `spring.config.location` real + overrides de teste), com PostgreSQL e LocalStack
reais via Testcontainers e `auth-service`/`catalog-service` stubados — cobre falha por estoque
insuficiente e por produto sem linha de estoque, sucesso com o estoque refletido no inventory, a
idempotência por republicação do mesmo comando e os dois pontos de entrada da saga (aprovação
automática e manual). Exige `LOCALSTACK_AUTH_TOKEN` (mesma ressalva acima: derrube o
`docker compose` antes).

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
local de desenvolvimento, e as portas 8081/8082/8083/8084/8085 estão ligadas apenas a `127.0.0.1` no
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
  (`quantityOnHand`); upsert — a primeira chamada cria a linha. Publica `STOCK_ADJUSTED` pelo
  Transactional Outbox do `inventory-service` (Fase 5, D-60).
- `GET /api/inventory/{productId}` (qualquer autenticado) — devolve `quantityOnHand`,
  `quantityReserved` e `quantityAvailable` (número exato, nunca um booleano).
- `POST /api/inventory/{productId}/reservations` (SELLER_ADMIN) — reserva `{reservationId,
  quantity}`; idempotente pelo `reservationId` fornecido pelo chamador; `409` se a quantidade
  pedida excede o disponível.
- `DELETE /api/inventory/{productId}/reservations/{reservationId}` (SELLER_ADMIN) — libera uma
  reserva; repetir ou liberar um identificador inexistente é no-op silencioso (idempotente).

**Ferramenta administrativa, não usada pela saga (Fase 5).** As duas rotas de reserva/liberação
acima continuam existindo só como ferramenta manual do `SELLER_ADMIN` — o `order-service` e o
`inventory-service` só conversam entre si pela fila SQS na saga de reserva de estoque (nunca por
REST). Ambas compartilham o mesmo livro de reservas (`stock_reservations`) usado pela saga —
reaproveitar o id de um pedido como `reservationId` numa chamada REST manual interfere no
funcionamento da saga daquele pedido.

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
  corpo da requisição. Um pedido decidido dentro do limite de crédito já nasce `RESERVING` — a
  criação dispara a saga de reserva de estoque da Fase 5, ver abaixo.
- `GET /api/orders` (qualquer autenticado) — `BUYER` vê apenas os pedidos da própria empresa;
  `SELLER_ADMIN` vê todos os pedidos. Filtro opcional `?status=` (aceita `RESERVING`, `CONFIRMED`,
  `CANCELLED`, entre outros), paginação (`page`, `size`, até 100), sempre do pedido mais recente
  para o mais antigo.
- `GET /api/orders/{orderId}` (qualquer autenticado) — pedido de outra empresa responde `404`,
  idêntico ao de um id inexistente (nunca `403`) — um `BUYER` não descobre que um pedido alheio
  existe. É por este endpoint que o cliente acompanha a saga até `CONFIRMED`/`CANCELLED` (a criação
  e a aprovação não bloqueiam esperando o resultado da reserva).
- `POST /api/orders/{orderId}/approve` e `POST /api/orders/{orderId}/reject` (SELLER_ADMIN) —
  decisão manual sobre um pedido `PENDING_APPROVAL`; a rejeição exige motivo (`reason`), a
  aprovação aceita motivo opcional; decidir um pedido fora de `PENDING_APPROVAL` devolve `409`. Uma
  aprovação também dispara a saga e devolve o pedido já `RESERVING` (mesma mecânica da criação
  automática).

### Como a aprovação por crédito funciona

Ao criar um pedido, o `order-service` soma o total do pedido novo à exposição de crédito atual da
empresa — a soma ao vivo dos totais de todos os pedidos dessa empresa em `APPROVED`, `RESERVING`,
`CONFIRMED`, `SHIPPED` e `DELIVERED` (nunca um saldo pré-calculado). Se `exposição + total <=
limite`, o pedido nasce `RESERVING` automaticamente (`decidedBy: "SYSTEM"`, aprovação automática
seguida imediatamente pela reserva de estoque, ver "Saga de reserva de estoque" abaixo); caso
contrário, nasce `PENDING_APPROVAL` e espera a decisão manual do vendedor. Um pedido aprovado
manualmente pelo vendedor segue para a mesma reserva de estoque e passa a consumir crédito da mesma
forma que um aprovado automaticamente, mesmo que a soma resultante ultrapasse o limite — a
aprovação manual não reavalia o limite, é uma decisão de negócio deliberada que pode exceder a
regra automática.

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

### Saga de reserva de estoque (Fase 5)

A partir do momento em que um pedido é aprovado — automática ou manualmente (D-48) —, o
`order-service` orquestra a reserva de estoque com o `inventory-service` de forma assíncrona, via
SQS, até o pedido terminar sempre em `CONFIRMED` ou `CANCELLED`. Nunca fica preso em `RESERVING`:

```
Aprovação (automática ou manual)
        │
        ▼
Pedido → RESERVING + comando ReserveStock gravado na tabela outbox_event
        (MESMA transação da decisão — nunca duas escritas separadas)
        │
        ▼
Relay do outbox (@Scheduled, ~1s) publica o comando na fila real
        │
        ▼
   inventory-commands-queue (SQS)
        │
        ▼
inventory-service reserva TODOS os itens do pedido numa única transação
local, tudo ou nada — sem reserva parcial a compensar
        │
        ▼
Resultado (StockReserved ou StockReservationFailed) gravado no outbox do
inventory-service, na MESMA transação da reserva
        │
        ▼
Relay do outbox do inventory-service publica o resultado
        │
        ▼
   order-events-queue (SQS)
        │
        ▼
order-service consome o resultado e decide:
  - StockReserved          → pedido CONFIRMED (estoque continua reservado
                              até a expedição, Fase 6)
  - StockReservationFailed → pedido CANCELLED com cancellationCode e
                              cancellationReason legível
```

**Por que Transactional Outbox em vez de publicação direta.** Gravar o pedido no banco e publicar o
evento no SQS como duas operações separadas (dual-write) permite que uma falhe sem a outra —
exatamente a limitação da Fase 3 (D-29/D-30, ver "Limitações conhecidas (Fase 3)" abaixo). A Fase 5
grava o comando/resultado na mesma transação de negócio (tabela `outbox_event`, uma por serviço,
D-62) e um relay `@Scheduled` publica depois — a escrita no banco e o evento nunca divergem.

**Entrega pelo menos uma vez, nunca exatamente uma vez ou em ordem garantida.** A fila SQS usada
(padrão, não FIFO) pode reentregar uma mensagem e não garante a ordem de chegada. Por isso os dois
lados da saga são idempotentes, não dependem de a mensagem chegar uma única vez ou na ordem certa:

- **order-service** — a transição é guardada pelo estado (D-64): um resultado só é aplicado se o
  pedido ainda estiver `RESERVING`; um resultado duplicado é no-op (com log). Não existe tabela de
  mensagens processadas.
- **inventory-service** — a idempotência é pelo livro `stock_reservations` (D-65): um `ReserveStock`
  repetido com o mesmo `reservationId` não decrementa de novo e reemite o mesmo resultado.
- **Lápide (tombstone) contra a corrida da fila sem FIFO (D-66)** — como a fila não garante ordem,
  um `ReleaseStock` de compensação pode chegar ao `inventory-service` ANTES do `ReserveStock`
  correspondente. Nesse caso a liberação grava uma "lápide" (linha já marcada como liberada,
  inclusive para produto sem linha de estoque) — o `ReserveStock` que chegar depois encontra a
  lápide e responde falha `RESERVATION_CANCELLED`, sem reservar nada. A corrida sempre termina em
  "nada reservado", em qualquer ordem de chegada.

**Timeout com compensação (D-63) — a garantia de "nunca preso".** Se o resultado da reserva nunca
chegar (mensagem perdida, inventory-service fora do ar), um job `@Scheduled` no order-service cancela
o pedido preso em `RESERVING` além de `orderflow.saga.reservation-timeout` (2 minutos em produção)
com `cancellationCode: RESERVATION_TIMEOUT`, e grava, na mesma transação, um `ReleaseStock` de
compensação — para o caso de a reserva ter acontecido tarde, depois do cancelamento. Um
`StockReserved` que chega depois de o pedido já estar `CANCELLED` por timeout também gera um
`ReleaseStock` compensatório (sucesso tardio nunca deixa estoque órfão).

**`CONFIRMED` mantém o estoque reservado** (`quantityReserved`) até a expedição — a baixa física de
`quantityOnHand` só acontece no envio (`SHIPPED`), que chega na Fase 6.

### Como observar a saga

Sem abrir o código, com a stack no ar (`docker compose up -d --wait`):

```bash
# Atributos das duas filas da saga e das suas DLQs (mensagens visíveis/em processamento)
for QUEUE in inventory-commands-queue inventory-commands-dlq order-events-queue order-events-dlq; do
  echo "== ${QUEUE} =="
  docker compose exec -T localstack awslocal --region us-east-1 sqs get-queue-attributes \
    --queue-url "$(docker compose exec -T localstack awslocal --region us-east-1 sqs get-queue-url \
      --queue-name "${QUEUE}" --query QueueUrl --output text)" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
done

# Tabela outbox_event de cada serviço (published_at, attempts, last_error)
docker compose exec -T postgres psql -U orderflow -d orderflow \
  -c 'SELECT id, event_type, published_at, attempts, last_error FROM "order".outbox_event ORDER BY created_at DESC LIMIT 10;'
docker compose exec -T postgres psql -U orderflow -d orderflow \
  -c 'SELECT id, event_type, published_at, attempts, last_error FROM inventory.outbox_event ORDER BY created_at DESC LIMIT 10;'

# Acompanhar um pedido específico até CONFIRMED/CANCELLED
curl -s http://localhost:8080/api/orders/$ORDER_ID -H "Authorization: Bearer $TOKEN"
```

Ou, para ver a saga inteira de uma vez, na stack real, pelo Gateway:

```bash
bash scripts/smoke-order-saga.sh
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

1. **Dual-write na publicação do evento — resolvido na Fase 5.** O `inventory-service` publicava
   `STOCK_ADJUSTED` diretamente no SQS depois do commit no PostgreSQL, sem Transactional Outbox
   (D-29). A Fase 5 move `STOCK_ADJUSTED` para o mesmo Transactional Outbox usado pela saga de
   reserva de estoque (D-60) — a gravação no banco e a publicação do evento acontecem na mesma
   transação, sem a janela de perda descrita aqui anteriormente.
2. **Sem fila de mensagens mortas (DLQ) — parcialmente resolvido na Fase 5.** As duas filas novas da
   saga (`inventory-commands-queue`, `order-events-queue`) já nascem com sua própria DLQ
   (`maxReceiveCount` 3, D-61). A `notification-events-queue` desta fase continua sem DLQ: uma
   mensagem que nunca poderia virar um registro válido (JSON malformado, campo obrigatório ausente)
   é descartada com log `WARN` em vez de reprocessada para sempre; uma falha transitória do
   DynamoDB, por outro lado, faz a mensagem voltar à fila sem teto de reentregas. Uma DLQ para essa
   fila e uma ferramenta de reprocessamento das mensagens mortas continuam sendo requisito de uma v2
   (`DLQ-01`).
3. **Fila sem política de acesso** — no LocalStack local, qualquer processo que alcance a porta
   4566 pode publicar em qualquer fila do projeto (`notification-events-queue` e, desde a Fase 5,
   `inventory-commands-queue`/`order-events-queue`). Em uma conta AWS real, `SendMessage` seria
   restrito à identidade de cada serviço por uma política de fila/IAM.
4. **Fila padrão, sem ordem garantida de entrega** — o SQS padrão (não FIFO) não garante que as
   mensagens cheguem na ordem em que foram publicadas; o histórico é reordenado por `occurredAt` no
   momento da leitura, não na gravação. A saga de reserva de estoque (Fase 5) enfrenta a mesma
   característica entre `ReserveStock`/`ReleaseStock` e resolve com lápide (tombstone, D-66), não com
   FIFO — ver "Saga de reserva de estoque (Fase 5)" acima.

## Limitações conhecidas (Fase 4)

1. **`APPROVED` não é confirmado nem reserva estoque — resolvido na Fase 5.** Nesta fase,
   `APPROVED` era o estado final da linha do tempo do pedido: nenhuma reserva de estoque era feita
   no `inventory-service`, e o pedido não avançava para `CONFIRMED`/`CANCELLED`. A Fase 5 entrega a
   saga completa — `APPROVED` passou a ser um passo lógico da decisão (D-50), nunca um estado de
   repouso de um pedido novo, e todo pedido decidido termina em `CONFIRMED` ou `CANCELLED`.
2. **Uma chamada ao catálogo por item, em sequência** — cada item do pedido gera uma chamada
   síncrona separada a `GET /products/{id}`; um endpoint de validação em lote é uma melhoria adiada.
3. **Sem circuit breaker** — as chamadas a `auth-service`/`catalog-service` têm apenas timeouts
   explícitos de conexão e leitura (D-41); um padrão de circuit breaker (Resilience4j) fica para o
   endurecimento da Fase 7.
4. **Espera pela trava de crédito sem timeout** — a trava por empresa (`company_credit_lock`)
   espera indefinidamente por design (D-41: nada que segura a trava faz I/O de rede), mas não tem
   um `lock_timeout` configurado — revisitar se uma fase futura acrescentar trabalho lento dentro da
   mesma transação.
5. **`order-service` repassa o JWT do comprador, sem identidade de serviço própria, só para
   auth/catalog** — as chamadas síncronas a `auth-service`/`catalog-service` usam o mesmo token do
   comprador que criou o pedido; uma identidade de serviço dedicada é introduzida em fase futura. A
   saga de reserva de estoque da Fase 5 não é afetada por essa limitação: `order-service` e
   `inventory-service` conversam só por SQS, sem nenhum JWT envolvido.
6. **Produto descontinuado e produto inexistente aparecem iguais na recusa** — os dois casos
   devolvem o mesmo `422 invalid_order_items` com o id do produto, sem distinguir a causa no corpo
   da resposta.
7. **O vendedor não filtra a listagem por empresa** — `GET /orders` para `SELLER_ADMIN` sempre
   devolve pedidos de todas as empresas; um filtro por empresa na visão do vendedor não existe
   nesta fase.

## Limitações conhecidas (Fase 5)

1. **Latência do relay por polling** — cada salto da saga (order → inventory, inventory → order)
   passa por um relay `@Scheduled` que verifica a tabela `outbox_event` a cada ~1 segundo; a saga
   inteira (aprovação → `CONFIRMED`/`CANCELLED`) leva, tipicamente, poucos segundos — nunca é
   instantânea como uma chamada síncrona.
2. **Linhas publicadas do outbox não são apagadas** (`OUTBOX_RETENTION=none-this-phase`) — nenhum
   job de retenção/limpeza roda nesta fase; as tabelas `"order".outbox_event` e
   `inventory.outbox_event` crescem indefinidamente. Uma rotina de expurgo é melhoria adiada.
3. **Prazo do timeout da saga é global e fixo** — `orderflow.saga.reservation-timeout` (2 minutos em
   produção) vale para todo pedido, sem variar por cliente, produto ou tamanho do pedido.
4. **Mensagens na DLQ só são inspecionáveis à mão** — não existe endpoint ou ferramenta para
   consultar/reprocessar `inventory-commands-dlq`/`order-events-dlq`; a inspeção é feita via
   `awslocal` diretamente (ver "Como observar a saga" acima).
5. **O motivo de cancelamento revela ao comprador a quantidade disponível** (D-56, decisão
   deliberada) — `cancellationReason` de um `INSUFFICIENT_STOCK` inclui `disponível`/`solicitado`
   por produto; pensado para tornar o motivo legível a um avaliador no Swagger, não para esconder o
   nível de estoque de um comprador.
6. **Filas sem política de acesso no LocalStack** — mesma limitação já registrada em "Limitações
   conhecidas (Fase 3)" item 3, agora valendo também para `inventory-commands-queue` e
   `order-events-queue`.
7. **Lápides (tombstones) ficam no livro de reservas sem limpeza** — uma linha de
   `stock_reservations` marcada como liberada por uma lápide (D-66) nunca é removida; acumula junto
   com as reservas reais.
8. **Cancelamento de pedido pelo comprador não existe** — o comprador só influencia o destino do
   pedido indiretamente (por exemplo, criando um pedido sem estoque suficiente); não há um endpoint
   para o próprio comprador cancelar um pedido em `RESERVING`/`PENDING_APPROVAL`.

## Arquitetura

As decisões arquiteturais desta fase (topologia do Gateway, modelo de Company/User, estratégia de
validação de JWT, isolamento de tenant) estão registradas em
[`.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-SKELETON.md`](.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-SKELETON.md).
