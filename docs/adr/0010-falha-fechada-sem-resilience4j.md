---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0010 — Falha fechada (503) nas chamadas síncronas, sem Resilience4j

## Contexto e problema

Para criar um pedido, o order-service faz duas chamadas HTTP síncronas: ao catalog-service (um
`GET /products/{id}` por item, para validar e capturar preço) e ao auth-service (o limite de crédito
da empresa). Se um desses vizinhos estiver fora do ar ou lento, o order-service precisa decidir o que
fazer. Sem um limite de crédito confiável não existe decisão de aprovação, e aprovar por omissão
seria uma elevação de privilégio.

Também é preciso decidir se essas chamadas ganham circuit breaker e retry (Resilience4j) ou se o
comportamento sob falha fica simples e explícito.

## Fatores de decisão

- Nunca aprovar um pedido sem um limite de crédito confiável (fail-closed).
- Uma chamada travada não pode segurar uma thread para sempre; o `RestClient` do Spring não tem
  timeout padrão.
- Sem cópia local do limite e sem claim novo no JWT: a fonte única é o auth-service (D-39).
- Escopo: só duas chamadas síncronas, ambas leituras `GET`, e nenhuma delas acontece com a trava de
  crédito aberta (D-41).

## Alternativas consideradas

- **Falha fechada: timeouts explícitos e resposta 503 clara, sem criar o pedido** — **escolhida**.
- **Circuit breaker e retry com Resilience4j** — **rejeitada por enquanto**, adiada para o backlog:
  o 503 fechado já cobre o caso, e a Fase 6 também não tem chamada externa dentro da transação que
  justifique circuit breaker (o mock da transportadora nunca falha, D-72).
- **Falha aberta (aprovar quando o auth-service não responde)** — **rejeitada**: aprovar por omissão
  sem limite confiável é elevação de privilégio e quebra a regra de crédito, que é o motor do fluxo.
- **Cópia local do limite no order-service, ou claim de limite no JWT** — **rejeitada**: criaria
  duas fontes da verdade para o limite e dados potencialmente desatualizados (D-39).

## Decisão

As chamadas ao catalog-service e ao auth-service usam `RestClient` com timeout de conexão e de leitura
explícitos, definidos em `ClientConfig` e configuráveis em `application.yml`
(`orderflow.clients.*`, hoje 2 s de conexão e 3 s de leitura). Se qualquer um dos dois não responder de
forma confiável (conexão recusada, timeout, status inesperado fora de 2xx ou resposta sem o campo esperado), a criação falha com HTTP 503, sem criar nenhum
pedido: `catalog_service_unavailable` ou `auth_service_unavailable`, no formato de erro
`{"error","message","fields"}` (D-39, D-42). Um 404 real do catálogo (inclusive o de produto
descontinuado para BUYER) não é indisponibilidade e continua sendo erro de negócio. Todas as chamadas
HTTP acontecem antes de abrir a transação com a trava de crédito (D-41; ver ADR 0007).

Não há Resilience4j no projeto. Essa ausência é uma consequência declarada desta decisão.

### Consequências

- Bom: comportamento previsível e fácil de explicar: o vizinho falhou, o pedido não nasce e o cliente
  recebe 503; nenhum pedido ou estado é gravado pela metade.
- Bom: nenhuma dependência nova e nenhuma configuração de circuit breaker para manter.
- **Ruim e declarado: não existe circuit breaker nem retry.** Cada falha do auth-service ou do
  catalog-service é sentida requisição a requisição; sob um vizinho degradado, cada chamada espera o
  timeout completo antes de falhar, e uma falha transitória não é tentada de novo automaticamente.
- Ruim: a competência de resiliência com Resilience4j (circuit breaker, retry, bulkhead) não é
  demonstrada. Está no backlog, listada em Deferred Ideas do `07-CONTEXT.md`, junto com a falha
  simulada da transportadora.
- Quando entrar, o ponto de adoção é o `ClientConfig` e os dois clientes, sem mudar o contrato de erro
  503 nem a regra de falha fechada.

## Prós e contras das alternativas

### Falha fechada com timeouts explícitos

- Bom: simples, seguro e explícito.
- Ruim: sem proteção contra um vizinho degradado além do timeout.

### Resilience4j

- Bom: circuit breaker, retry e bulkhead prontos para chamadas entre serviços.
- Ruim: mais configuração e mais conceitos para um caso de duas leituras simples; sem ganho de
  segurança, porque a falha continuaria fechada.

### Falha aberta

- Bom: o usuário quase nunca vê erro.
- Ruim: aprova pedidos sem limite confiável, o que quebra a regra central do fluxo.

### Cópia local ou claim do limite

- Bom: sem chamada ao auth-service no caminho do pedido.
- Ruim: duas fontes da verdade e risco de limite desatualizado.

## Mais informações

Fase de origem: Fase 4 (criação do pedido e aprovação por limite de crédito).

- Decisões: D-39, D-41 e D-42 em
  [04-CONTEXT.md](../../.planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-CONTEXT.md);
  D-72 em [06-CONTEXT.md](../../.planning/phases/06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid/06-CONTEXT.md).
- Resilience4j como item adiado: seção Deferred Ideas de
  [07-CONTEXT.md](../../.planning/phases/07-endurecimento-observabilidade-e-entrega/07-CONTEXT.md).
- Decisão-chave sobre falha fechada: [PROJECT.md](../../.planning/PROJECT.md), Key Decisions.
- Código:
  [CatalogServiceClient.java](../../order-service/src/main/java/com/orderflow/order/client/CatalogServiceClient.java),
  [AuthServiceClient.java](../../order-service/src/main/java/com/orderflow/order/client/AuthServiceClient.java)
  e [ClientConfig.java](../../order-service/src/main/java/com/orderflow/order/config/ClientConfig.java).
- Relacionado: [ADR 0007](0007-credito-serializado-por-empresa.md).
