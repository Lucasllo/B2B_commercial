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
(padrão saga). Esse fluxo completo depende de serviços que ainda não existem no repositório
(inventory-service, order-service, notification-service) e está previsto para fases futuras — ver
"Estado atual" abaixo.

## Estado atual (o que já existe e funciona)

O repositório está na fase de esqueleto vertical: autenticação, gestão de empresas compradoras e o
API Gateway já funcionam de ponta a ponta. Os serviços de pedido, estoque e notificação (o núcleo
do fluxo de saga descrito acima) ainda não foram implementados.

| Serviço | Papel | Porta | Exposto externamente |
|---|---|---|---|
| `gateway` | API Gateway — único ponto de entrada para clientes externos | 8080 | Sim |
| `auth-service` | Autenticação (JWT auto-emitido), cadastro de empresas compradoras e limite de crédito | 8081 | Apenas para depuração local; o caminho normal é sempre via `gateway` |
| `postgres` | Persistência transacional (schema `auth` hoje; um schema por serviço à medida que novos serviços forem adicionados) | 5432 | Não |
| `localstack` | Emulação local de SQS e DynamoDB (ainda não consumidos por nenhum serviço nesta fase) | 4566 | Não |

Todos os endpoints de negócio disponíveis hoje pertencem ao `auth-service` e são acessados através
do Gateway — ver [API.md](API.md) para a lista completa.

## Arquitetura em alto nível

```
Cliente externo
      │
      ▼
  gateway (:8080)  ──── único ponto de entrada; roteia por prefixo de path, não valida token
      │
      ▼
 auth-service (:8081) ── valida o próprio JWT localmente (OAuth2 Resource Server);
      │                  não há chamada de volta ao emissor a cada requisição
      ▼
  PostgreSQL (schema auth)
```

- O Gateway (Spring Cloud Gateway Server WebMVC) apenas roteia por prefixo de caminho — a validação
  de autenticação e autorização acontece em cada serviço downstream, de forma independente.
- O `auth-service` emite e valida seus próprios JWTs (assinados com RSA, chave exposta como JWKS em
  `/.well-known/jwks.json`). Serviços futuros validarão o mesmo token localmente, sem depender de
  uma chamada síncrona ao `auth-service` a cada requisição.
- Isolamento entre empresas compradoras é reforçado no nível de aplicação (um "guard" avaliado antes
  de qualquer acesso a dados), não por um filtro implícito de banco de dados.

## Stack técnica

Java 21, Spring Boot 3.5, Spring Cloud Gateway Server WebMVC, Spring Security (OAuth2 Resource
Server, JWT auto-emitido via Nimbus), PostgreSQL 16 com Flyway, Docker Compose para orquestração
local. SQS e DynamoDB via LocalStack estão provisionados na stack, mas ainda sem nenhum serviço
consumidor nesta fase.

## Para onde ir a partir daqui

- **Consumir a API hoje:** [API.md](API.md) — todos os endpoints disponíveis, com autenticação,
  formatos de requisição/resposta e códigos de erro.
- **Subir o projeto localmente:** [README.md](../README.md) na raiz do repositório — pré-requisitos,
  variáveis de ambiente e o passo a passo de `docker compose up`.

## Como esta documentação é mantida atualizada

Os arquivos desta pasta (`docs/`) são gerados e revisados automaticamente a partir do código-fonte
real do projeto (controllers, DTOs, configuração de segurança e rotas), não escritos à mão a partir
de memória. Sempre que o projeto ganhar novos serviços ou endpoints, regenere esta pasta descrevendo
a mudança para o Claude Code (por exemplo: "atualize a documentação de terceiros com os novos
endpoints do inventory-service").
