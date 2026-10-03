---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0009 — Spring Cloud Gateway Server WebMVC em vez do gateway reativo

## Contexto e problema

O projeto precisa de uma porta de entrada única para os cinco serviços: um API Gateway que roteie
`/api/<serviço>/**` para o destino certo, devolva o Correlation-ID e sirva a Swagger UI agregada. O
Spring Cloud oferece duas famílias de gateway: o clássico, reativo (WebFlux, Project Reactor), e o
Server WebMVC, baseado em servlet, que a linha 2025.0 do Spring Cloud passou a recomendar no lugar do
antigo "Gateway MVC".

Todos os serviços de domínio do OrderFlow são Spring MVC com JPA bloqueante. A escolha do modelo de
programação do Gateway precisa ser coerente com isso.

## Fatores de decisão

- O Gateway só roteia (D-05): não tem lógica de domínio nem validação de JWT.
- Um único modelo de programação em todo o projeto, o servlet, para não obrigar quem lê o código a
  aprender Project Reactor só por causa do Gateway.
- O foco do portfólio está na saga, no outbox e no crédito, não em programação reativa.
- A versão do Spring Cloud usada (2025.0.x "Northfields") tem o Server WebMVC como caminho
  recomendado.

## Alternativas consideradas

- **Spring Cloud Gateway Server WebMVC, com rotas estáticas** — **escolhida**.
- **Spring Cloud Gateway clássico (WebFlux, reativo)** — **rejeitada**: introduziria um segundo
  modelo de programação, com `Mono` e `Flux`, e mais complexidade de depuração, sem benefício para o
  domínio; só valeria se o objetivo fosse demonstrar programação reativa, que não é o caso aqui.

## Decisão

O Gateway é um módulo Spring Boot com `spring-cloud-starter-gateway-server-webmvc`, com rotas estáticas
declaradas em `application.yml` (uma por serviço, com `StripPrefix=1`; ver ADR 0004). Ele só roteia
(D-05). O modelo servlet permite que o Gateway rode com o mesmo estilo dos demais serviços, e é nele
que ficam o filtro de Correlation-ID (ADR 0008) e a Swagger UI única com o seletor dos cinco serviços
(D-87): o springdoc agregado do Gateway usa o mesmo modelo, porque o starter
`springdoc-openapi-starter-webmvc-ui` é o do servlet.

### Consequências

- Bom: um único modelo de programação em todo o repositório; o filtro do Gateway é um filtro de
  servlet como o dos demais serviços.
- Bom: a Swagger UI agregada roda no mesmo modelo, sem pilha reativa adicional.
- Ruim: o Gateway não tem a vazão sob muita concorrência de conexões de um gateway reativo; para o
  tráfego de demonstração isso não é uma restrição.
- Ruim: sem programação reativa, o projeto não demonstra essa competência. Se a vaga a exigir, o
  Gateway é o único ponto em que a troca seria feita, sem tocar nos serviços de domínio.
- As rotas são estáticas e sem discovery, como no ADR 0004.

## Prós e contras das alternativas

### Gateway Server WebMVC

- Bom: simples, coerente com o resto do projeto, usa virtual threads do Java 21 se habilitadas.
- Ruim: menos adequado a um volume muito alto de conexões simultâneas.

### Gateway clássico reativo (WebFlux)

- Bom: modelo não bloqueante, tradicional para gateways.
- Ruim: um segundo modelo de programação a ensinar e depurar sem valor para o domínio do projeto.

## Mais informações

Fase de origem: Fase 1 (esqueleto vertical e infraestrutura); a Swagger UI agregada veio na Fase 7.

- Decisão: D-05 em
  [01-CONTEXT.md](../../.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-CONTEXT.md);
  D-87 em [07-CONTEXT.md](../../.planning/phases/07-endurecimento-observabilidade-e-entrega/07-CONTEXT.md).
- Base da escolha: a seção Recommended Stack e Alternatives Considered do
  [CLAUDE.md](../../.claude/CLAUDE.md) e o [01-RESEARCH.md](../../.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-RESEARCH.md).
- Código e configuração: [pom.xml do Gateway](../../gateway/pom.xml) e
  [application.yml do Gateway](../../gateway/src/main/resources/application.yml).
- Relacionado: [ADR 0004](0004-sem-service-discovery-nem-config-server.md) e
  [ADR 0008](0008-correlation-id-proprio-em-vez-de-tracing-distribuido.md).
