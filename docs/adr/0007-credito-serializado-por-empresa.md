---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0007 — Decisão de crédito serializada por empresa (`company_credit_lock`)

## Contexto e problema

O OrderFlow aprova um pedido automaticamente quando a exposição acumulada da empresa compradora, mais
o total do novo pedido, não passa do limite de crédito; acima do limite o pedido espera aprovação do
vendedor. A exposição é a soma dos pedidos que consomem crédito (D-36, D-37), ou seja, um valor
calculado sobre várias linhas, não um contador numa linha só.

Se dois pedidos da mesma empresa chegarem juntos na fronteira do limite, ambos podem ler a mesma
exposição, ambos concluir que cabem e ser aprovados, estourando o limite (a leitura desatualizada ou
não atômica descrita como risco na pesquisa de pitfalls). O critério de sucesso da fase exige que, na
fronteira, no máximo um pedido seja aprovado automaticamente.

## Fatores de decisão

- Corretude sob concorrência na fronteira do limite, comprovada por teste.
- Fonte única de verdade para a exposição: uma consulta de soma sobre os próprios pedidos, sem saldo
  desnormalizado (D-37).
- Empresas diferentes não devem se bloquear.
- Nunca segurar a trava durante I/O de rede.

## Alternativas consideradas

- **Linha de trava por empresa (`company_credit_lock`) com `SELECT ... FOR UPDATE`** —
  **escolhida**.
- **Lock otimista (`@Version`) com retry** — **rejeitada**: serve ao inventory-service porque a
  contenção está na mesma linha sendo alterada; aqui o valor protegido é um agregado calculado sobre
  várias linhas de pedido, e não um contador mutável único, e evitaria o saldo desnormalizado que a
  D-37 proíbe.
- **Advisory lock do Postgres (`pg_advisory_xact_lock`)** — **rejeitada**: é uma alternativa válida
  e de menor cerimônia, mas a linha de trava é mais coerente com o estilo do projeto (linhas reais, não
  identificadores opacos de lock) e se documenta sozinha no esquema.
- **`SELECT ... FOR UPDATE` direto numa linha da tabela de pedidos** — **rejeitada**: uma empresa
  sem nenhum pedido ainda não teria linha para travar; a tabela de trava existe para esse primeiro
  pedido.

## Decisão

A tabela `company_credit_lock(company_id PK)` guarda uma linha por empresa. A transação de criação
faz a criação idempotente da linha e `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE`), soma a exposição,
decide o status e insere o pedido, tudo na mesma transação (D-40). Empresas diferentes travam linhas
diferentes e não se bloqueiam. A aprovação manual do vendedor passa pela mesma trava, porque muda a
exposição (D-46).

Consomem crédito os pedidos em `APPROVED`, `CONFIRMED`, `SHIPPED` e `DELIVERED` (e `RESERVING`,
acrescentado na Fase 5); `PENDING_APPROVAL`, `REJECTED` e `CANCELLED` não consomem (D-37). As
chamadas HTTP ao auth-service e ao catalog-service acontecem antes de abrir a transação com trava,
para a trava nunca ser mantida durante I/O de rede (D-41).

### Consequências

- Bom: dois pedidos simultâneos da mesma empresa na fronteira nunca são ambos aprovados; um teste de
  concorrência por socket real prova isso.
- Bom: a exposição vem sempre de uma consulta de soma sobre os pedidos, sem saldo para reconciliar.
- Ruim: pedidos da mesma empresa serializam durante a decisão; é aceitável, pois a seção crítica é
  curta e não faz I/O.
- Ruim: o mecanismo de trava vira o padrão para qualquer mudança de status que afete crédito, e a
  saga na Fase 5 precisou segui-lo (D-40 está marcada como custosa de reverter).
- Ruim: a aprovação manual não reavalia o limite, de modo que um pedido acima do limite aprovado pelo
  vendedor passa a consumir crédito e pode estourar o limite (D-38, risco assumido pelo vendedor).

## Prós e contras das alternativas

### Linha de trava por empresa

- Bom: serialização exata por empresa, simples de explicar e de testar.
- Ruim: uma tabela extra e serialização por empresa.

### Lock otimista com retry

- Bom: sem bloqueio de linha.
- Ruim: não protege um agregado calculado sobre várias linhas sem um saldo desnormalizado.

### Advisory lock do Postgres

- Bom: sem tabela; serialização por chave.
- Ruim: identificadores opacos de lock, menos coerentes com o estilo do projeto.

### Trava numa linha de `orders`

- Bom: reaproveita uma tabela existente.
- Ruim: não existe linha para a primeira compra de uma empresa.

## Mais informações

Fase de origem: Fase 4 (criação do pedido e aprovação por limite de crédito).

- Decisões: D-36, D-37, D-38, D-40, D-41 e D-46 em
  [04-CONTEXT.md](../../.planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-CONTEXT.md).
- Alternativas avaliadas: [04-RESEARCH.md](../../.planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-RESEARCH.md)
  e [04-DISCUSSION-LOG.md](../../.planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-DISCUSSION-LOG.md).
- Decisão-chave: [PROJECT.md](../../.planning/PROJECT.md), Key Decisions.
- Código:
  [CompanyCreditLocker.java](../../order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLocker.java)
  e [CreditPolicy.java](../../order-service/src/main/java/com/orderflow/order/credit/CreditPolicy.java).
- Relacionado: ADR 0010 (a ser escrito na Task 3), sobre as chamadas feitas antes da
  trava.
