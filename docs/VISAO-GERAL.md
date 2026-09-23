<!-- generated-by: gsd-doc-writer -->
# OrderFlow — Visão Geral

> Documentação voltada a terceiros (integradores, avaliadores técnicos, futuros mantenedores) que
> precisam entender rapidamente o que o OrderFlow faz e como consumir sua API, sem precisar ler o
> histórico de decisões do projeto.

## O que é o OrderFlow

OrderFlow é uma plataforma de gerenciamento de pedidos B2B/atacado: uma empresa vendedora
("seller") disponibiliza um catálogo de produtos para múltiplas empresas compradoras ("buyers"),
que criam pedidos sujeitos a aprovação por limite de crédito, reserva de estoque, atribuição de
transportadora e acompanhamento até a entrega.

É um projeto de portfólio técnico construído para demonstrar, de ponta a ponta, competências de
arquitetura de microsserviços orientada a eventos — não é um produto comercial em operação.

**Valor central do projeto:** o fluxo de pedido (criação → aprovação por limite de crédito →
reserva de estoque → confirmação) funcionando entre microsserviços via orquestração por eventos
(padrão saga). Esse fluxo completo ainda depende de um serviço que não existe no repositório —
**order-service** (orquestração da saga e aprovação por limite de crédito). A Fase 3 já entrega a
primeira integração assíncrona real do projeto: o **notification-service** consome eventos de
ajuste de estoque via SQS e mantém um histórico consultável em DynamoDB, provando o encanamento de
mensageria que a saga da Fase 5 vai reutilizar. Ver "Estado atual" abaixo para o que já funciona
hoje.

## Estado atual (o que já existe e funciona)

O repositório já expõe, de ponta a ponta e através do Gateway, autenticação, gestão de empresas
compradoras, catálogo de produtos, controle de estoque com reserva protegida contra concorrência, e
um histórico de notificações assíncrono: um ajuste de estoque publica um evento `STOCK_ADJUSTED`
numa fila SQS real (LocalStack) que o `notification-service` consome e grava no DynamoDB, sem
nenhuma chamada HTTP entre os dois serviços. O `order-service` — o núcleo do fluxo de saga descrito
acima — ainda não foi implementado.

| Serviço | Papel | Porta | Exposto externamente |
|---|---|---|---|
| `gateway` | API Gateway — único ponto de entrada para clientes externos | 8080 | Sim |
| `auth-service` | Autenticação (JWT auto-emitido), cadastro de empresas compradoras e limite de crédito | 8081 | Apenas para depuração local; o caminho normal é sempre via `gateway` |
| `catalog-service` | Catálogo de produtos do vendedor (criação, atualização, ativação/descontinuação) | 8082 | Apenas para depuração local; o caminho normal é sempre via `gateway` |
| `inventory-service` | Estoque por produto: quantidade em mãos, reserva e liberação, com proteção contra overselling concorrente; publica `STOCK_ADJUSTED` na fila SQS depois de cada ajuste | 8083 | Apenas para depuração local; o caminho normal é sempre via `gateway` |
| `notification-service` | Consome eventos de ajuste de estoque da fila SQS e mantém o histórico consultável por produto no DynamoDB | 8084 | Apenas para depuração local; o caminho normal é sempre via `gateway` |
| `postgres` | Persistência transacional — um schema por serviço (`auth`, `catalog`, `inventory`) na mesma instância | 5432 | Não |
| `localstack` | Emulação local de SQS (`notification-events-queue`) e DynamoDB (`notification-history`), provisionados automaticamente na subida pelo init hook | 4566 | Não |

Através do Gateway, um usuário autenticado já consegue, hoje: fazer login e obter um JWT; cadastrar
e consultar empresas compradoras e limite de crédito; criar, atualizar, listar e
ativar/descontinuar produtos no catálogo (papel `SELLER_ADMIN`); definir, consultar, reservar e
liberar estoque por produto — inclusive sob concorrência, sem reservar mais do que o disponível; e
consultar o histórico de notificações de um produto, alimentado de forma assíncrona pelos ajustes
de estoque. Ver [API.md](API.md) para a lista completa de endpoints, papéis exigidos e formatos.

## Arquitetura em alto nível

```
Cliente externo
      │
      ▼
  gateway (:8080)  ──── único ponto de entrada; roteia por prefixo de path, não valida token
      │
      ├── /api/auth/**, /api/companies/**  ──▶ auth-service (:8081)  ──▶ PostgreSQL (schema auth)
      │
      ├── /api/products/**                  ──▶ catalog-service (:8082) ──▶ PostgreSQL (schema catalog)
      │
      ├── /api/inventory/**                 ──▶ inventory-service (:8083) ──▶ PostgreSQL (schema inventory)
      │                                            │
      │                                            │ publica STOCK_ADJUSTED após o commit
      │                                            ▼
      │                                      SQS notification-events-queue (LocalStack)
      │                                            │
      │                                            │ consumido de forma assíncrona
      │                                            ▼
      └── /api/notifications/**             ──▶ notification-service (:8084) ──▶ DynamoDB notification-history (LocalStack)

Cada serviço acima valida o JWT localmente (JWKS publicado por auth-service em
/.well-known/jwks.json) — nenhuma chamada síncrona de volta ao auth-service a cada requisição. A
ligação entre inventory-service e notification-service é só a fila SQS — nenhum dos dois chama o
outro por HTTP.
```

- O Gateway (Spring Cloud Gateway Server WebMVC) apenas roteia por prefixo de caminho — a validação
  de autenticação e autorização acontece em cada serviço downstream, de forma independente; o
  Gateway em si não tem nenhuma configuração de `spring.security`.
- Todo serviço downstream (`auth-service`, `catalog-service`, `inventory-service`) valida o mesmo
  JWT localmente como OAuth2 Resource Server, buscando as chaves públicas via JWKS
  (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`, apontando para
  `http://auth-service:8081/.well-known/jwks.json`), sem depender de uma chamada síncrona ao
  `auth-service` a cada requisição.
- Isolamento entre empresas compradoras é reforçado no nível de aplicação (um "guard" avaliado
  antes de qualquer acesso a dados), não por um filtro implícito de banco de dados.
- Cada serviço com persistência transacional é dono de seu próprio schema PostgreSQL (`auth`,
  `catalog`, `inventory`) dentro da mesma instância de banco — isolamento lógico de dados por
  serviço sem o custo de operar um container Postgres por serviço.

## Stack técnica

Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.3 ("Northfields") com Spring Cloud Gateway Server
WebMVC, Spring Security (OAuth2 Resource Server, JWT auto-emitido via Nimbus), PostgreSQL 16.15 com
Flyway, springdoc-openapi 2.9.1 (Swagger UI por serviço), Docker Compose V2 para orquestração
local. SQS (via Spring Cloud AWS) e DynamoDB (via AWS SDK v2 Enhanced Client) rodam contra
LocalStack (imagem `localstack/localstack:2026.08.3`) — a fila `notification-events-queue` e a
tabela `notification-history` são provisionadas automaticamente por um init hook na subida da
stack, e o `inventory-service`/`notification-service` já produzem e consomem eventos reais nessa
fila (Fase 3).

## Documentação interativa (Swagger UI)

Além da referência estática em [API.md](API.md), `auth-service`, `catalog-service`,
`inventory-service` e `notification-service` servem Swagger UI na própria porta direta de cada um —
**não** pelo Gateway:

| Serviço | Swagger UI |
|---|---|
| `auth-service` | http://localhost:8081/swagger-ui.html |
| `catalog-service` | http://localhost:8082/swagger-ui.html |
| `inventory-service` | http://localhost:8083/swagger-ui.html |
| `notification-service` | http://localhost:8084/swagger-ui.html |

A página carrega sem token (é uma ferramenta local de desenvolvimento, deliberadamente aceita —
ver `SecurityConfig.java` de cada serviço). O botão **Authorize** aceita um JWT colado (obtido em
`POST /api/auth/login`) e permite exercitar qualquer endpoint protegido via **Try it out**
diretamente no navegador, sem montar `curl` à mão. Como a UI roda na porta do serviço, não do
Gateway, os caminhos exibidos não têm o prefixo `/api` (`StripPrefix=1` do Gateway não se aplica
aqui).

## Para onde ir a partir daqui

- **Consumir a API hoje:** [API.md](API.md) — todos os endpoints disponíveis, com autenticação,
  formatos de requisição/resposta e códigos de erro.
- **Testar no navegador:** as três Swagger UIs acima — visualização e teste rápido sem cliente HTTP.
- **Subir o projeto localmente:** [README.md](../README.md) na raiz do repositório — pré-requisitos,
  variáveis de ambiente e o passo a passo de `docker compose up`.

## Como esta documentação é mantida atualizada

Os arquivos desta pasta (`docs/`) são gerados e revisados automaticamente a partir do código-fonte
real do projeto (controllers, DTOs, configuração de segurança e rotas), não escritos à mão a partir
de memória. Sempre que o projeto ganhar novos serviços ou endpoints, regenere esta pasta descrevendo
a mudança para o Claude Code (por exemplo: "atualize a documentação de terceiros com os novos
endpoints do inventory-service").
