---
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
plan: "06"
subsystem: e2e-and-smoke
tags: [smoke, e2e, testcontainers, docker-compose, saga, shipping, timeline]
status: complete

requires:
  - phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
    provides: "06-01/06-02: confirmacao com transportadora, /ship e /deliver, ShipStock; 06-04/06-05: linha do tempo no notification-service alimentada pelo outbox do order-service"
provides:
  - "scripts/smoke-order-lifecycle.sh: demonstracao de 16 passos do ciclo de vida completo na stack real, pelo Gateway (SMOKE OK)"
  - "OrderShipmentE2EIT: expedicao -> baixa de estoque entre order-service e inventory-service reais, uma unica vez"
  - "Gate da fase: ./mvnw -B verify do reactor inteiro (incluindo e2e-tests) verde"
affects: [06-07]

actuals:
  tokens: 12000
  tasks: 2
  commits: 3

plan_head_before: 4721a1feb8273c125355d1e2191ef42c3055cc6e

tech-stack:
  added: []
  patterns:
    - "Sequencia de eventos da linha do tempo extraida com grep -o + uniq (cada elemento traz eventType duas vezes: topo e payload)"
    - "Idempotencia da baixa provada republicando o ShipStock direto na inventory-commands-queue com eventId novo"

key-files:
  created:
    - scripts/smoke-order-lifecycle.sh
    - e2e-tests/src/test/java/com/orderflow/e2e/OrderShipmentE2EIT.java
  modified:
    - e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java

key-decisions:
  - "SMOKE_ASSERTS_FORMAT_NOT_VALUE: o smoke afirma carrier nao vazio e trackingCode no padrao ^[A-Z]{2}[0-9]{9}BR$, nunca um valor fixo (depende do orderId)"
  - "D-86 mantido: notification-service fora do E2E; a junção dos tres servicos e provada so pelo smoke na stack real"

requirements-completed: [ORD-07, ORD-10]

duration: ~75 min (builds, compose up de 2m52s e dois verify do reactor de ~8 min incluidos)
completed: 2026-10-01

coverage:
  - id: D1
    description: "Smoke na stack real: criacao -> CONFIRMED com carrier e trackingCode no padrao -> ship -> deliver -> baixa de estoque 7/0 -> linha do tempo ORDER_CREATED..ORDER_DELIVERED lida pelo comprador"
    requirement: "ORD-07, ORD-10"
    verification:
      - kind: command
        ref: "bash scripts/smoke-order-lifecycle.sh"
        status: pass
    human_judgment: false
  - id: D2
    description: "Caminho triste e recusas na stack real: INSUFFICIENT_STOCK -> CANCELLED (timeline CREATED/APPROVED/CANCELLED), rejeicao (CREATED/PENDING_APPROVAL/REJECTED), 409 invalid_order_transition, 403 do BUYER em /ship, 404 order_not_found para outra empresa"
    requirement: "ORD-10"
    verification:
      - kind: command
        ref: "bash scripts/smoke-order-lifecycle.sh"
        status: pass
    human_judgment: false
  - id: D3
    description: "Os tres smokes anteriores continuam passando na mesma stack, sem edicao"
    verification:
      - kind: command
        ref: "bash scripts/smoke-order-saga.sh; bash scripts/smoke-order-flow.sh; bash scripts/smoke-notification-flow.sh"
        status: pass
    human_judgment: false
  - id: D4
    description: "E2E: ship baixa quantityOnHand/quantityReserved uma vez (marca shipped), republicar ShipStock nao baixa de novo, deliver nao mexe no estoque, ship em CANCELLED responde 409"
    requirement: "ORD-10"
    verification:
      - kind: integration
        ref: "e2e-tests OrderShipmentE2EIT (5 testes)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Gate da fase: ./mvnw -B verify do reactor inteiro verde com o compose derrubado"
    verification:
      - kind: command
        ref: "./mvnw -B verify (BUILD SUCCESS; e2e-tests 17 testes)"
        status: pass
    human_judgment: false
---

# Phase 6 Plan 06: Smoke do ciclo de vida e E2E da expedicao Summary

**Smoke de 16 passos na stack real do docker compose (criacao, transportadora, expedicao, entrega, baixa de estoque, linha do tempo, caminhos tristes e recusas) mais um E2E de expedicao entre order-service e inventory-service reais, com o reactor inteiro verde.**

## Performance

- **Duration:** ~75 min (inclui `docker compose up --build` de 2m52s, E2E isolado de 1m16s e dois `./mvnw -B verify` do reactor de ~8 min cada)
- **Tasks:** 2 (tracer + auto), mais 1 correcao de teste obsoleto
- **Files:** 2 criados, 1 modificado

## Accomplishments

- `scripts/smoke-order-lifecycle.sh` (modo `100755`) roda sem passo manual e termina com `SMOKE OK`: pedido de 3 x P1 nasce RESERVING, chega a CONFIRMED com transportadora e rastreio no padrao dos Correios, BUYER em `/ship` leva 403, `deliver` em CONFIRMED leva 409 `invalid_order_transition`, vendedor expede (SHIPPED, `shippedBy` = userId do vendedor), o estoque cai para onHand 7 / reserved 0, `ship` repetido leva 409, `deliver` entrega, e o comprador le a linha do tempo `ORDER_CREATED ORDER_APPROVED ORDER_CONFIRMED ORDER_SHIPPED ORDER_DELIVERED` (mensagem de confirmacao cita transportadora e rastreio); BUYER de outra empresa leva 404 `order_not_found`; 20 x P1 termina CANCELLED `INSUFFICIENT_STOCK` (timeline CREATED/APPROVED/CANCELLED, `ship` 409); 100 x P1 fica PENDING_APPROVAL e, rejeitado, mostra CREATED/PENDING_APPROVAL/REJECTED.
- `OrderShipmentE2EIT` com cinco cenarios sobre order + inventory reais (Postgres e LocalStack de Testcontainers): formato de carrier/trackingCode, baixa 10/3 -> 7/0 com `stock_reservations.shipped` verdadeiro, republicacao do `ShipStock` sem nova baixa, `deliver` sem mexer no estoque e `ship` em pedido CANCELLED com 409. Leitura de banco somente leitura (TEST-03, nenhum INSERT/UPDATE/DELETE).
- Gate da fase: `./mvnw -B verify` do reactor inteiro verde (todos os modulos com 0 falhas; order-service 165 unitarios + 131 ITs; e2e-tests 17 testes).

## Saidas finais dos smokes (stack real, mesma subida)

| Smoke | Ultima linha |
|---|---|
| smoke-order-lifecycle.sh | `SMOKE OK 49dab186-591a-4ff7-9cf2-068f646317a0 2c8a4cd2-3baf-49ac-bff7-bb860d464dde 2a75ce77-a19f-4d2f-8952-4f0fb050e549` |
| smoke-order-saga.sh | `SMOKE OK 784a2951-f9bd-4593-aa4f-bdae95101d2f 45dcaa17-25c4-4cba-9fdd-f8a55e41e552 703ec8c6-7458-4e85-8480-a100da8d6dc3 f0844513-afd5-42f6-97f4-205a64b36f54` |
| smoke-order-flow.sh | `SMOKE OK 606b6bb3-5f89-4ad2-ac9a-d5e840f85ef4 5343f1f5-cb29-46c7-b1b2-cdd08fe6cc3c 317d4730-c364-4cf8-96d2-16f010750cfb` |
| smoke-notification-flow.sh | `SMOKE OK 5cc636d3-878e-4658-81ce-dc3e8cc14cfb 2s fluxo, reentrega sem duplicacao e ajustes distintos confirmados` |

Nenhum dos tres smokes anteriores foi editado (D-85). `docker compose down` executado ao final da Task 1; `docker ps` sem containers antes dos Testcontainers.

## Tempos

- E2E isolado (`-pl e2e-tests -am verify -Dit.test=OrderShipmentE2EIT`): 1m16s, 5 testes em ~60 s.
- `./mvnw -B verify` do reactor: ~7m53s (e2e-tests 1m11s, 17 testes, 0 falhas).

## Real-stack exercise dos artefatos de 06-04

O LocalStack init hook (ramo de recriacao da tabela antiga) e o novo healthcheck do `localstack` no compose foram exercitados de verdade nesta subida: `docker compose up -d --build --wait` terminou com todos os servicos `healthy` (incluindo `localstack`, cujo healthcheck exige a tabela `notification-history` com particao `entityId`), e `describe-table` devolveu `entityId HASH / sortKey RANGE`. Observacao: a maquina tinha volumes de Docker antigos, mas o LocalStack roda com `PERSISTENCE=0`, entao o ramo "recriar tabela antiga" nao teve uma tabela antiga para recriar (a tabela nasceu direto com o esquema novo); o healthcheck novo, porem, foi aprovado de verdade. As migracoes V3 do order-service e V4 do inventory-service aplicaram sobre o volume Postgres existente sem falha.

## Task Commits

1. **Task 1 (tracer): smoke-order-lifecycle.sh** - `b0edef9` (feat)
2. **Task 2: OrderShipmentE2EIT** - `9743c79` (test)
3. **Correcao Rule 1: E2eContextsSmokeIT** - `023cb18` (fix)

**Plan metadata:** commit docs(06-06) a seguir.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] E2eContextsSmokeIT com contagem de migracoes obsoleta**
- **Found during:** Task 2 (`./mvnw -B verify` do reactor, gate da fase)
- **Issue:** `E2eContextsSmokeIT` afirmava `containsExactly("1","2")` para o schema `order` e `("1","2","3")` para `inventory`; as migracoes `order` V3 (06-01) e `inventory` V4 (06-02) tornaram as duas asserções falsas, quebrando o `verify` do reactor (2 falhas, e2e-tests FAILURE). O escopo de "gate da fase" do plano exige o reactor verde.
- **Fix:** asserções atualizadas para `("1","2","3")` (order) e `("1","2","3","4")` (inventory); metodos renomeados para `...ThreeMigrations...` e `...FourMigrations...`.
- **Files modified:** `e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java`
- **Verification:** segundo `./mvnw -B verify` completo: BUILD SUCCESS, e2e-tests 17 testes, 0 falhas.
- **Commit:** `023cb18`

**2. [Nota de execucao] Comando do E2E isolado**
- O comando de verificacao do plano foi rodado com `-Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` adicionais para evitar reexecutar os testes unitarios dos modulos `-am`; relatorio do Failsafe do `e2e-tests` mostra `Tests run: 5` (nao 0).

**Total deviations:** 1 auto-fixed (1 bug em teste obsoleto de plano anterior). **Impact:** nenhum no comportamento de producao; so alinha um teste a migracoes ja entregues.

## Issues Encountered

None alem do descrito acima. Em ambos os runs do reactor, o inventory-service e o order-service levam ~2 min cada por causa dos ITs com Testcontainers.

## Known Stubs

None.

## Threat Flags

None. O smoke so imprime ids, status e tipos de evento (T-06-23); 403 do BUYER em `/ship` e 404 do BUYER de outra empresa na linha do tempo sao afirmados pelo Gateway (T-06-24).

## Self-Check: PASSED

- `scripts/smoke-order-lifecycle.sh` e `OrderShipmentE2EIT.java` existem; `git ls-files -s` do smoke comeca com `100755`.
- Commits `b0edef9`, `9743c79` e `023cb18` existem em `master`.
- O script contem `/api/notifications/orders/`, `/ship`, `/deliver`, `invalid_order_transition`, `order_not_found` e `uniq`; `OrderShipmentE2EIT` contem `/ship` (via `postAction`), `/deliver`, `inventory-commands-queue` e nenhuma instrucao SQL de escrita.
