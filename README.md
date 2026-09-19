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

Esse comando sobe os quatro serviços (`postgres`, `localstack`, `auth-service`, `gateway`) e só
retorna quando todos estiverem `healthy`. Confira com:

```
docker compose ps
```

Para derrubar a stack:

```
docker compose down -v
```

## Build e testes

```
./mvnw -B -pl auth-service test
```
Testes unitários, sem necessidade de Docker.

```
./mvnw -B -pl auth-service verify
```
Suíte completa, incluindo testes de integração com Testcontainers — exige o Docker em execução.

## Credenciais de demonstração

O usuário `admin@orderflow.local` com a senha `ChangeMe!123` é semeado automaticamente pela
migração Flyway `V2__seed_seller_admin.sql` (papel `SELLER_ADMIN`) na primeira subida do banco.
Essa credencial existe **exclusivamente para demonstração deste portfólio**: ela não é uma
credencial administrativa real, não deve ser reaproveitada em nenhum outro contexto ou ambiente,
e o fato de estar versionada no repositório é intencional — é um dado de demonstração, não um
segredo vazado.

## Endpoints disponíveis nesta fase

Todos os endpoints abaixo são acessados através do API Gateway, em `http://localhost:8080`:

- `POST /api/auth/login` — autentica com `{ "email": ..., "password": ... }` e devolve um JWT.
- `GET /api/auth/me` — devolve os claims do JWT enviado (requer header `Authorization: Bearer <token>`).
- `GET /actuator/health` — health check do próprio Gateway.

`GET /.well-known/jwks.json` é interno ao `auth-service` (não roteado pelo Gateway) e existe para
que os serviços das Fases 2+ validem o token localmente, sem chamar o auth-service a cada
requisição (AUTH-03, D-03).

## Arquitetura

As decisões arquiteturais desta fase (topologia do Gateway, modelo de Company/User, estratégia de
validação de JWT, isolamento de tenant) estão registradas em
[`.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-SKELETON.md`](.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-SKELETON.md).
