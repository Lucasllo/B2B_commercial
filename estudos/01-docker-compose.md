# Docker Compose

O `docker-compose.yml` define os serviços que sobem juntos, localmente, para rodar o
projeto inteiro. Ele define 8 serviços (`postgres`, `localstack`, `auth-service`,
`catalog-service`, `inventory-service`, `notification-service`, `order-service` e
`gateway`), com dependências de saúde encadeadas
(`depends_on: condition: service_healthy`) para garantir a ordem correta de subida.

## 1. `postgres` — banco relacional único

- Imagem `postgres:16.15`
- Credenciais vêm de variáveis de ambiente obrigatórias (`${VAR:?}` falha se não
  definidas — provavelmente num `.env`)
- Exposto apenas em `127.0.0.1:5432` (não acessível externamente)
- Volume nomeado `postgres-data` para persistência entre restarts
- Healthcheck via `pg_isready`

## 2. `localstack` — emulação AWS local (SQS + DynamoDB)

- Imagem `localstack/localstack:2026.08.3`
- Exige `LOCALSTACK_AUTH_TOKEN` (alinhado com a mudança de política da LocalStack desde
  2026.03)
- `SERVICES=sqs,dynamodb` limita os serviços emulados aos necessários
- `PERSISTENCE=0` — estado não persiste entre restarts (aceitável para portfólio/dev)
- Exposto apenas em `127.0.0.1:4566`
- **Init hook:** a pasta `localstack-init/ready.d` é montada em
  `/etc/localstack/init/ready.d`. Quando o LocalStack fica pronto, ele roda os scripts dessa
  pasta: `01-create-notification-resources.sh` cria a fila `notification-events-queue` e a
  tabela DynamoDB `notification-history`; `02-create-order-saga-resources.sh` cria as filas
  da saga (`inventory-commands-queue`, `order-events-queue`) e suas DLQs. Nenhum serviço
  Java cria esses recursos — esses scripts são o único lugar que faz isso.
- **A chave da tabela mudou na Fase 6 (D-80).** A partição da `notification-history` agora
  se chama `entityId` — o id do produto **ou** do pedido (ver
  [17-dynamodb.md](17-dynamodb.md)). Um LocalStack que ficou ligado desde a Fase 5 ainda
  guardaria a tabela antiga, com partição `productId`. Por isso o script `01` confere a
  chave: se não for `entityId`, apaga e recria a tabela. Não há dado a perder, porque
  `PERSISTENCE=0`. Um `docker compose down` também resolve.
- **Healthcheck:** não usa só o endpoint `/_localstack/health`, porque ele responde
  "saudável" *antes* de os scripts terminarem (a fila e a tabela podem nem existir ainda).
  Em vez disso, o healthcheck confere os recursos de verdade (no arquivo é uma linha só):

  ```text
  awslocal --region us-east-1 sqs get-queue-url --queue-name notification-events-queue > /dev/null
  && awslocal --region us-east-1 dynamodb describe-table --table-name notification-history --query Table.KeySchema --output text | grep -q entityId
  && awslocal --region us-east-1 sqs get-queue-url --queue-name inventory-commands-queue > /dev/null
  && awslocal --region us-east-1 sqs get-queue-url --queue-name order-events-queue > /dev/null
  ```

  O `grep -q entityId` é o detalhe da Fase 6: a tabela antiga, com partição `productId`,
  **não** conta como saudável. Quem depende do `localstack` só sobe quando as filas e a
  tabela com a chave nova estão lá.

## 3. `auth-service` — primeiro microsserviço da arquitetura

- Build local via `Dockerfile` próprio (`auth-service/Dockerfile`, contexto na raiz do
  repo — permite reaproveitar um parent pom multi-módulo)
- Espera `postgres` e `localstack` saudáveis antes de subir
- Conecta no Postgres usando `currentSchema=auth` — ou seja, é o padrão "schema por
  serviço" dentro de **uma única instância** Postgres
- Exposto em `127.0.0.1:8081`, com healthcheck no Actuator

## 4. `catalog-service` — catálogo de produtos

- Mesmo padrão de build do `auth-service` (`Dockerfile` próprio, contexto na raiz)
- Espera `postgres` e `auth-service` saudáveis — não depende do `localstack`, porque
  não usa SQS nem DynamoDB nesta fase
- Conecta no Postgres usando `currentSchema=catalog` — mesmo padrão "schema por
  serviço" do `auth-service`, dentro da mesma instância
- Exposto em `127.0.0.1:8082`, com healthcheck no Actuator

## 5. `inventory-service` — estoque e reserva atômica

- Parecido com o `catalog-service` em estrutura (build próprio), mas depende de
  `postgres`, `auth-service` **e** `localstack` — porque fala com o SQS: publica pelo
  outbox (`STOCK_ADJUSTED` e as respostas da saga) e escuta a `inventory-commands-queue`
- Recebe `SPRING_CLOUD_AWS_ENDPOINT=http://localstack:4566` (nome do serviço no compose,
  não `localhost`) para achar o LocalStack
- Conecta no Postgres usando `currentSchema=inventory`
- Exposto em `127.0.0.1:8083`, com healthcheck no Actuator
- É o serviço que implementa a reserva de estoque protegida contra concorrência
  (lock otimista + reexecução automática) — ver `StockReservationConcurrencyIT` e a
  explicação para iniciantes em [34-lock-otimista-e-relay.md](34-lock-otimista-e-relay.md)

## 6. `notification-service` — histórico de notificações

- Build próprio (`notification-service/Dockerfile`, mesmo padrão dos outros)
- Espera `localstack` e `auth-service` saudáveis
- **Não usa Postgres** — guarda o histórico no DynamoDB (tabela `notification-history`),
  por isso não tem `SPRING_DATASOURCE_*`
- Consome a fila `notification-events-queue` (via `SPRING_CLOUD_AWS_ENDPOINT=http://localstack:4566`)
- Exposto em `127.0.0.1:8084`, com healthcheck no Actuator

## 7. `order-service` — pedidos

- Build próprio (`order-service/Dockerfile`, mesmo padrão)
- Espera `postgres`, `auth-service`, `catalog-service` **e** `localstack` saudáveis — desde
  a Fase 5 ele fala com o SQS (manda comandos da saga e eventos da linha do tempo pelo
  outbox, e escuta a `order-events-queue`), por isso também recebe
  `SPRING_CLOUD_AWS_ENDPOINT=http://localstack:4566`
- Conecta no Postgres usando `currentSchema=order`
- Chama o `auth-service` e o `catalog-service` **direto pela rede do compose**, nunca pelo
  gateway. As URLs vêm de variáveis de ambiente que apontam para o nome do serviço (não
  `localhost`): `ORDERFLOW_AUTH_SERVICE_BASE_URL=http://auth-service:8081` e
  `ORDERFLOW_CATALOG_SERVICE_BASE_URL=http://catalog-service:8082` — ver
  [20-configuration-properties.md](20-configuration-properties.md) para como essas
  variáveis viram propriedades Java
- Exposto em `127.0.0.1:8085`, com healthcheck no Actuator

## Como os resource servers acham as chaves do JWT

Os 4 resource servers (`catalog-service`, `inventory-service`, `notification-service` e
`order-service`) recebem a mesma variável
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://auth-service:8081/.well-known/jwks.json`
— é daí que eles baixam a chave pública para validar a assinatura do token (ver
[06-jwks.md](06-jwks.md)). O `issuer-uri` **não** é definido no compose: vale o valor
padrão do `application.yml` de cada serviço (`orderflow-auth-service`).

## 8. `gateway` — API Gateway

- Depende de `auth-service`, `catalog-service`, `inventory-service`,
  `notification-service` e `order-service` estarem saudáveis — a lista cresceu à medida
  que cada novo serviço ganhou uma rota
- Único serviço exposto sem bind a `127.0.0.1` (porta `8080` aberta), sendo o ponto de
  entrada externo do sistema

## Observações sobre o estágio atual do projeto

- Os 6 módulos da aplicação (`auth-service`, `catalog-service`, `inventory-service`,
  `notification-service`, `order-service` e `gateway`) já estão no compose, todos atrás
  do gateway — o projeto foi crescendo "fase a fase" até aqui.
- `auth-service`, `inventory-service`, `notification-service` e `order-service` esperam o
  `localstack` ficar saudável antes de subir. Existem três fluxos assíncronos:
  - o `inventory-service` grava `STOCK_ADJUSTED` no outbox, o relay (o "carteiro" do
    outbox, ver [34-lock-otimista-e-relay.md](34-lock-otimista-e-relay.md)) manda para a
    `notification-events-queue` e o `notification-service` grava no DynamoDB;
  - a **saga** (Fases 5 e 6): o `order-service` manda `ReserveStock`, `ReleaseStock` e
    `ShipStock` pela `inventory-commands-queue` e recebe a resposta da reserva pela
    `order-events-queue` (ver [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md));
  - a **linha do tempo do pedido** (Fase 6): o `order-service` manda os oito eventos
    `ORDER_*` para a `notification-events-queue` (ver
    [31-linha-do-tempo-do-pedido.md](31-linha-do-tempo-do-pedido.md)).
- Filas e tabela só são criadas pelos scripts de `localstack-init/ready.d/` (seção 2).
  Nenhum serviço Java cria fila nem tabela.
- A escolha de schema único por Postgres (`orderflow` com schemas separados) é uma
  decisão específica já tomada, diferente da alternativa "um Postgres por serviço" —
  agora com quatro schemas em uso (`auth`, `catalog`, `inventory`, `order`). O
  `notification-service` não usa Postgres.
