---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0004 — Sem service discovery nem config server

## Contexto e problema

Muitos tutoriais de microsserviços Spring incluem, por padrão, um registro de serviços (Eureka ou
Consul) e um servidor de configuração (Spring Cloud Config). O OrderFlow tem um conjunto fixo e
conhecido de serviços (auth, catalog, inventory, order, notification e o Gateway), com nomes e
portas definidos no `docker-compose.yml`. A pergunta é se vale adicionar essas duas peças de
infraestrutura ou se elas resolveriam um problema que o projeto não tem.

## Fatores de decisão

- Os endereços dos serviços são estáticos: o DNS interno do Docker Compose resolve os nomes dos
  serviços.
- Seis serviços configurados por variáveis de ambiente não têm o problema que um config server
  resolve (configuração central e atualizável em muitos serviços e ambientes).
- Cada componente extra é mais um serviço para subir, documentar, testar e explicar.
- O foco do portfólio está na saga, no outbox e no crédito, não em infraestrutura de plataforma.

## Alternativas consideradas

- **Rotas estáticas no Gateway, DNS do Compose e configuração por variáveis de ambiente** —
  **escolhida**.
- **Service discovery com Eureka ou Consul** — **rejeitada**: resolve a localização dinâmica de
  instâncias em uma frota elástica, problema que o DNS do Compose já resolve nesta escala; o Eureka
  está em modo de manutenção, e adicionar o registro aumentaria a superfície operacional sem
  demonstrar competência nova.
- **Spring Cloud Config Server** — **rejeitada**: a configuração centralizada e recarregável não
  tem utilidade com poucos serviços cuja configuração vem de variáveis de ambiente e de profiles do
  `application.yml`.

## Decisão

O Gateway tem rotas estáticas, uma por serviço, com `StripPrefix=1`, e os serviços se endereçam
pelo nome do container no Compose. O Gateway só roteia: não valida o JWT, e cada serviço valida
localmente (D-05; ver ADR 0005). As URLs dos destinos são placeholders de propriedade
(`orderflow.gateway.upstream.*`) com valor padrão apontando para o nome do serviço, e a configuração
dos demais serviços vem de variáveis de ambiente com valor padrão no `application.yml` (por exemplo,
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` no order-service).

### Consequências

- Bom: menos três peças para operar e explicar; a subida inteira continua sendo `docker compose up`.
- Bom: o fluxo de uma requisição é legível diretamente no `application.yml` do Gateway.
- Ruim: adicionar uma réplica de um serviço, ou mudar uma porta, exige editar as rotas do Gateway; não
  há balanceamento dinâmico entre instâncias.
- Ruim: mudar uma configuração exige redeploy do serviço, sem atualização em tempo de execução.
- Em escala maior, o caminho de evolução seria um registro de serviços (por exemplo, Consul) com
  balanceamento no Gateway; ele fica registrado como evolução possível, não como pendência.

## Prós e contras das alternativas

### Rotas estáticas e variáveis de ambiente

- Bom: simples, transparente, sem componentes novos.
- Ruim: não escala para instâncias dinâmicas sem mudança de configuração.

### Eureka ou Consul

- Bom: localização dinâmica de instâncias e base para balanceamento no cliente.
- Ruim: infraestrutura extra sem problema real a resolver aqui; o Eureka está em manutenção.

### Spring Cloud Config Server

- Bom: configuração centralizada, versionada e atualizável.
- Ruim: outro serviço para rodar e explicar; os seis serviços não precisam dessa centralização.

## Mais informações

Fase de origem: Fase 1 (esqueleto vertical e infraestrutura).

- Decisão: D-05 em
  [01-CONTEXT.md](../../.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-CONTEXT.md).
- Base da alternativa rejeitada: [ARCHITECTURE.md](../../.planning/research/ARCHITECTURE.md),
  Anti-Pattern 2 (Eureka e Config Server por padrão), e a seção "What NOT to Use" do
  [CLAUDE.md](../../.claude/CLAUDE.md).
- Código e configuração: [application.yml do Gateway](../../gateway/src/main/resources/application.yml)
  e [docker-compose.yml](../../docker-compose.yml).
- Relacionado: ADR 0009 (a ser escrito na Task 3).
