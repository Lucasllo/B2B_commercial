# Docker Compose

O `docker-compose.yml` define os serviços que sobem juntos, localmente, para rodar o
projeto inteiro. Ele define 4 serviços, com dependências de saúde encadeadas
(`depends_on: condition: service_healthy`) para garantir a ordem correta de subida.

## 1. `postgres` — banco relacional único

- Imagem `postgres:16.15`
- Credenciais vêm de variáveis de ambiente obrigatórias (`${VAR:?}` falha se não
  definidas — provavelmente num `.env`)
- Exposto apenas em `127.0.0.1:5432` (não acessível externamente)
- Volume nomeado `postgres-data` para persistência entre restarts
- Healthcheck via `pg_isready`

## 2. `localstack` — emulação AWS local (SQS + DynamoDB)

- Exige `LOCALSTACK_AUTH_TOKEN` (alinhado com a mudança de política da LocalStack desde
  2026.03)
- `SERVICES=sqs,dynamodb` limita os serviços emulados aos necessários
- `PERSISTENCE=0` — estado não persiste entre restarts (aceitável para portfólio/dev)
- Healthcheck no endpoint `/_localstack/health`

## 3. `auth-service` — primeiro microsserviço da arquitetura

- Build local via `Dockerfile` próprio (`auth-service/Dockerfile`, contexto na raiz do
  repo — permite reaproveitar um parent pom multi-módulo)
- Espera `postgres` e `localstack` saudáveis antes de subir
- Conecta no Postgres usando `currentSchema=auth` — ou seja, é o padrão "schema por
  serviço" dentro de **uma única instância** Postgres
- Exposto em `8081`, com healthcheck no Actuator

## 4. `gateway` — API Gateway

- Depende apenas do `auth-service` estar saudável
- Único serviço exposto sem bind a `127.0.0.1` (porta `8080` aberta), sendo o ponto de
  entrada externo do sistema

## Observações sobre o estágio atual do projeto

- Só `auth-service` e `gateway` existem até agora — `catalog`, `inventory`, `order` e
  `notification` ainda não foram adicionados ao compose, coerente com o processo
  incremental "fase a fase" do projeto.
- Não há serviço de mensageria SQS consumido ainda por nenhum serviço além do
  LocalStack estar disponível — a saga (order → inventory → notification) ainda não
  está representada aqui.
- A escolha de schema único por Postgres (`orderflow` com schemas separados) é uma
  decisão específica já tomada, diferente da alternativa "um Postgres por serviço".
