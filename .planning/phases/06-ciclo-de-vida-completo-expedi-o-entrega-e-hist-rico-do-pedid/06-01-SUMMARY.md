---
phase: 06-ciclo-de-vida-completo-expedi-o-entrega-e-hist-rico-do-pedid
plan: "01"
subsystem: order-service
tags: [spring-boot, jpa, flyway, postgres, saga, shipping-simulation, state-machine]

requires:
  - phase: 05-saga-de-reserva-de-estoque
    provides: OrderSagaService com trava de linha do pedido, ramo RESERVING -> CONFIRMED, OrderResponse da saga
provides:
  - Pacote shipping (CarrierGateway, CarrierAssignment, SimulatedCarrierGateway, TrackingCodes S10)
  - Migracao V3 com colunas de transportadora/rastreio/expedicao/entrega, backfill legado e CHECKs
  - Order.confirm(now, CarrierAssignment) e seis campos novos em OrderResponse
  - OrderStatus.canTransitionTo / transitions() (tabela unica de 9 arestas) e Order.moveTo
affects: [06-02, 06-05, 06-06, 06-07]

actuals:
  tokens: 16370
  tasks: 2
  commits: 2

plan_head_before: 552d0b1c76f724e6f62dfd06435d183535b488f6

tech-stack:
  added: []
  patterns:
    - "Costura de integracao injetada por construtor (CarrierGateway) com implementacao simulada deterministica"
    - "Tabela de transicoes unica no enum consultada por um unico moveTo no agregado"
    - "Invariante de dominio repetida como CHECK de banco (chk_orders_shipping_assigned)"

key-files:
  created:
    - order-service/src/main/resources/db/migration/V3__order_fulfillment.sql
    - order-service/src/main/java/com/orderflow/order/shipping/CarrierGateway.java
    - order-service/src/main/java/com/orderflow/order/shipping/CarrierAssignment.java
    - order-service/src/main/java/com/orderflow/order/shipping/SimulatedCarrierGateway.java
    - order-service/src/main/java/com/orderflow/order/shipping/TrackingCodes.java
    - order-service/src/test/java/com/orderflow/order/shipping/TrackingCodesTest.java
    - order-service/src/test/java/com/orderflow/order/shipping/SimulatedCarrierGatewayTest.java
    - order-service/src/test/java/com/orderflow/order/order/OrderStatusTransitionsTest.java
  modified:
    - order-service/src/main/java/com/orderflow/order/order/Order.java
    - order-service/src/main/java/com/orderflow/order/order/OrderStatus.java
    - order-service/src/main/java/com/orderflow/order/order/dto/OrderResponse.java
    - order-service/src/main/java/com/orderflow/order/saga/OrderSagaService.java
    - order-service/src/test/java/com/orderflow/order/ReservationResultListenerIT.java
    - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
    - order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java
    - order-service/src/test/java/com/orderflow/order/OrderSagaMigrationIT.java
    - order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java

key-decisions:
  - "ORDER_RESPONSE_CONTRACT=id,companyId,status,total,createdBy,createdAt,decidedBy,decidedAt,reason,cancellationCode,cancellationReason,confirmedAt,cancelledAt,carrier,trackingCode,shippedAt,shippedBy,deliveredAt,deliveredBy,items[...]"
  - "CARRIER_LIST=Expresso Cerrado,TransSul Cargas,Rapido Paulista,Norte Entregas,Litoral Log (fficticias, sem acento)"
  - "CARRIER_ALGORITHM=sha256-orderId (bytes 0-1 escolhem a transportadora, bytes 2-3 as letras, bytes 4-11 o serial, digito S10, sufixo BR)"
  - "TRACKING_CODE_UNIQUENESS=probabilistic (sem UNIQUE no banco; violacao dentro da transacao do CONFIRMED viraria laco de reentrega)"
  - "LEGACY_TRACKING_BACKFILL=Transportadora Legada + LG + 9 digitos de md5(id::text) + BR (casa o padrao, nao reproduz o digito S10)"
  - "Guarda de gatilho mantida so em approveAutomatically/approveManually, porque APPROVED tem duas origens na tabela"

requirements-completed: [ORD-07, ORD-10]

coverage:
  - id: D1
    description: "StockReserved leva RESERVING a CONFIRMED com transportadora e codigo S10 gravados na mesma transacao e visiveis em GET /orders/{id}"
    requirement: "ORD-07"
    verification:
      - kind: integration
        ref: "ReservationResultListenerIT#stockReservedAssignsSimulatedCarrierAndTrackingCodeInTheSameTransactionAsConfirmed"
        status: pass
    human_judgment: false
  - id: D2
    description: "Atribuicao deterministica por orderId; duplicata nao troca; pedido cancelado nunca recebe transportadora"
    requirement: "ORD-07"
    verification:
      - kind: unit
        ref: "SimulatedCarrierGatewayTest"
        status: pass
      - kind: integration
        ref: "ReservationResultListenerIT#duplicateStockReservedIsANoOpTheSecondTime"
        status: pass
      - kind: integration
        ref: "ReservationResultListenerIT#orderCancelledByFailureNeverGetsACarrierNotEvenAfterALateStockReserved"
        status: pass
    human_judgment: false
  - id: D3
    description: "Codigo S10 com digito verificador correto (exemplo oficial 47312482 -> 9) e padrao ^[A-Z]{2}[0-9]{9}BR$"
    requirement: "ORD-07"
    verification:
      - kind: unit
        ref: "TrackingCodesTest"
        status: pass
    human_judgment: false
  - id: D4
    description: "V3 aplicada sobre base legada faz backfill de CONFIRMED e o banco recusa CONFIRMED sem transportadora"
    requirement: "ORD-07"
    verification:
      - kind: integration
        ref: "OrderSagaMigrationIT#applyingV3OverAV2BaseBackfillsConfirmedOrdersAndTheCheckRefusesConfirmedWithoutCarrier"
        status: pass
    human_judgment: false
  - id: D5
    description: "Tabela unica de 9 transicoes testada nos 81 pares e consultada por todo metodo de transicao do Order e pelas guardas da saga"
    requirement: "ORD-10"
    verification:
      - kind: unit
        ref: "OrderStatusTransitionsTest (84 execucoes) e OrderDomainTest#everyTransitionMethodCalledFromAStatusTheTableForbidsThrowsTheExpectedExceptionAndChangesNothing"
        status: pass
      - kind: other
        ref: "grep -c 'this.status = ' Order.java (sem comentarios) == 1"
        status: pass
    human_judgment: false

duration: 17min
completed: 2026-10-01
status: complete
---

# Phase 6 Plan 01: Transportadora simulada, rastreio S10 e tabela unica de transicoes Summary

**Pedido CONFIRMED passa a nascer com transportadora ficticia e codigo de rastreio Correios/UPU S10 deterministicos (SHA-256 do orderId) gravados na mesma transacao, e todas as transicoes de status vivem numa unica tabela de 9 arestas travada por teste de 81 pares.**

## Performance

- **Duration:** 17 min
- **Started:** 2026-09-30T23:50:00Z (aproximado)
- **Completed:** 2026-10-01T00:06:00Z
- **Tasks:** 2
- **Files modified:** 17 (8 criados, 9 alterados; +871 / -58 linhas)

## Accomplishments

- **ORD-07:** `OrderSagaService.applyStockReserved` chama `carrierGateway.assign(order.getId())` e `order.confirm(now, assignment)` no unico ramo que confirma, sob `findByIdForUpdate`. O mesmo `orderId` produz sempre a mesma atribuicao, entao um `StockReserved` duplicado e um no-op e um sucesso tardio para pedido CANCELLED nunca recebe transportadora.
- **Pacote `shipping`:** `CarrierGateway` (costura), `CarrierAssignment` (record que valida carrier e codigo), `SimulatedCarrierGateway` (5 nomes ficticios, sem I/O, sem excecao) e `TrackingCodes` (`PATTERN`, `checkDigit`, `fromDigest`, `isValid`). O javadoc deixa explicito que e simulacao.
- **V3:** seis colunas novas, backfill de CONFIRMED/SHIPPED/DELIVERED legados antes das CHECKs, `chk_orders_tracking_code_format` e `chk_orders_shipping_assigned` (D-70 como invariante de banco). Sem `UNIQUE` em `tracking_code`.
- **Contrato REST:** `OrderResponse` ganha `carrier, trackingCode, shippedAt, shippedBy, deliveredAt, deliveredBy` entre `cancelledAt` e `items`; pedidos nao confirmados devolvem os seis nulos.
- **ORD-10:** `OrderStatus.canTransitionTo` e `transitions()` (visao imutavel); `Order.moveTo` e o unico ponto que atribui `this.status`; as guardas de `OrderSagaService` consultam a tabela. `OrderNotPendingException` (409 `order_not_pending`) e `IllegalStateException` preservadas; `OrderApprovalIT` passou sem alteracao.

## Registros exigidos pelo plano

- `ORDER_RESPONSE_CONTRACT=id,companyId,status,total,createdBy,createdAt,decidedBy,decidedAt,reason,cancellationCode,cancellationReason,confirmedAt,cancelledAt,carrier,trackingCode,shippedAt,shippedBy,deliveredAt,deliveredBy,items[...]`
- `CARRIER_LIST=Expresso Cerrado,TransSul Cargas,Rapido Paulista,Norte Entregas,Litoral Log`
- `CARRIER_ALGORITHM=sha256-orderId`
- `TRACKING_CODE_UNIQUENESS=probabilistic` (limitacao conhecida a documentar em 06-07)
- `LEGACY_TRACKING_BACKFILL=Transportadora Legada + 'LG' || 9 digitos de md5(id::text) || 'BR'` (so bases de desenvolvimento anteriores a Fase 6)

## Task Commits

1. **Task 1 (tracer): transportadora e rastreio S10 no CONFIRMED** - `66af366` (feat)
2. **Task 2: tabela unica de transicoes e V3 provada sobre base legada** - `5066b6b` (feat)

## Verification

- `./mvnw -B -pl order-service test -Dtest=TrackingCodesTest,SimulatedCarrierGatewayTest,OrderDomainTest,OrderCreationServiceTest` - verde.
- `./mvnw -B -pl order-service verify -Dit.test=ReservationResultListenerIT` - 13 testes, verde (Docker disponivel, compose derrubado).
- `./mvnw -B -pl order-service verify` (completo, contra Postgres e LocalStack reais via Testcontainers) - 73 testes de integracao verdes, BUILD SUCCESS. `OrderStatusTransitionsTest` executa 84 casos (81 pares + 3).
- Gate de atribuicao unica: `grep -vE '^\s*(\*|//)' Order.java | grep -c 'this.status = '` retorna 1.
- Tracer gate: o `<verify>` do tracer (unitarios + `ReservationResultListenerIT`) foi reexecutado de ponta a ponta antes da Task 2 e passou.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] CreditLockAndExposureIT inseria pedidos CONFIRMED sem transportadora**
- **Found during:** Task 2 (`./mvnw -B -pl order-service verify` completo)
- **Issue:** O helper `insertOrder` do teste gravava linhas CONFIRMED/SHIPPED/DELIVERED por JDBC sem `carrier`/`tracking_code`; a nova `chk_orders_shipping_assigned` (D-70) recusou as linhas e dois testes quebraram.
- **Fix:** O helper passou a gravar `carrier` e `tracking_code` validos em toda linha. O arquivo nao estava na lista `files_modified` do plano, mas a quebra e consequencia direta da CHECK introduzida aqui.
- **Files modified:** `order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java`
- **Verification:** `CreditLockAndExposureIT` 5/5 e suite completa verde.
- **Commit:** `5066b6b`

**2. [Process] Ciclo RED/GREEN sem commit separado de RED**
- Os testes foram escritos antes da implementacao, mas cada tarefa produziu um unico commit `feat` (conforme "commit each task"). Um commit de RED isolado deixaria `master` sem compilar (classes inexistentes), e o `<verify>` do plano trata a tarefa como unidade. Nao houve registro formal de evidencia RED via `check tdd-red-evidence`.

**Total deviations:** 2 (1 auto-fixed bug de teste, 1 de processo). **Impact:** nenhum no escopo funcional.

## Known Stubs

None. `shipped_*`/`delivered_*` ficam nulos por desenho ate 06-02 (endpoints de `/ship` e `/deliver`).

## Threat Flags

None alem do modelo de ameacas do plano (T-06-01 a T-06-04 mitigados/aceitos conforme previsto).

## Issues Encountered

- A leitura do `.env` e bloqueada para o agente; a resolucao do `LOCALSTACK_AUTH_TOKEN` e feita pelo proprio `LocalStackTestSupport` dos testes, que leu o token sem intervencao e os ITs rodaram normalmente.

## Next Phase Readiness

Pronto para 06-02: `Order.moveTo` (privado) e `OrderStatus.canTransitionTo` estao disponiveis para `ship`/`deliver`, e as colunas `shipped_*`/`delivered_*` e o contrato do `OrderResponse` ja estao na V3.

## Self-Check: PASSED

- Arquivos criados verificados em disco (V3, quatro classes de `shipping`, tres testes novos).
- Commits `66af366` e `5066b6b` presentes em `git log`.
