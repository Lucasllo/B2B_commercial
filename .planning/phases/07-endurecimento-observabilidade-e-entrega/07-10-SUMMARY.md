---
phase: 07-endurecimento-observabilidade-e-entrega
plan: "10"
subsystem: testing
tags: [coverage-matrix, unit-tests, mockito, check-script, test-01, test-02, d-105]
status: complete

requires:
  - phase: 07-endurecimento-observabilidade-e-entrega
    provides: testes de gateway (07-01), auth/catalog (07-05) e WR-01/02/03 (07-07, 07-09) que entram na matriz como fechados
provides:
  - 07-COVERAGE.md — matriz regra -> teste com 72 regras em 7 módulos, sem nenhuma LACUNA
  - scripts/check-coverage-matrix.sh — verificação mecânica (modo estrito e --report) consumida por 07-11
  - Testes unitários das regras de estoque, da orquestração do pedido, do login e da tabela de rotas do gateway
affects: [07-11]

plan_head_before: ec1985ae572af736d3493b7a12a86df5a98499b1
actuals:
  tokens: 35000
  tasks: 2
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Matriz de cobertura mecanicamente verificada: nome de teste citado entre crases precisa existir como <Nome>.java no módulo (e #método no arquivo)"
    - "Unitário de orquestração com Mockito + InOrder + doAnswer para provar ordem (trava -> refresh -> transição)"

key-files:
  created:
    - scripts/check-coverage-matrix.sh
    - .planning/phases/07-endurecimento-observabilidade-e-entrega/07-COVERAGE.md
    - inventory-service/src/test/java/com/orderflow/inventory/stock/InventoryTest.java
    - inventory-service/src/test/java/com/orderflow/inventory/stock/StockReservationTest.java
    - inventory-service/src/test/java/com/orderflow/inventory/stock/InventoryServiceTest.java
    - order-service/src/test/java/com/orderflow/order/order/OrderServiceTest.java
    - order-service/src/test/java/com/orderflow/order/order/OrderDecisionServiceTest.java
    - order-service/src/test/java/com/orderflow/order/order/OrderShipmentServiceTest.java
    - order-service/src/test/java/com/orderflow/order/saga/OrderSagaServiceTest.java
    - auth-service/src/test/java/com/orderflow/auth/auth/AuthControllerTest.java
    - gateway/src/test/java/com/orderflow/gateway/GatewayRoutesTest.java
  modified:
    - gateway/src/test/java/com/orderflow/gateway/CorrelationIdFilterTest.java
    - e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java

key-decisions:
  - "COVERAGE_MATRIX_TIMING=after-test-plans — a matriz é a auditoria do estado final depois de 07-01..07-09; o que ela acusou foi fechado aqui"
  - "COVERAGE_MATRIX_FORMAT=uma seção '## <módulo>' por módulo (7) com tabela '| # | Regra central | Origem | Teste unitário | Teste de integração (dependência real) | Status |'; e2e-tests com '| # | Fluxo entre serviços | Origem | Teste E2E | Status |'"
  - "Linhas LACUNA não têm os nomes conferidos (o teste ainda não existe por definição); o modo --report as lista sem falhar, o estrito as recusa"
  - "O script confere o arquivo do teste DENTRO do módulo da seção (CorrelationIdFilterTest, OutboxRelayTest etc. existem em vários módulos) e o método com 'void <método>('"
  - "Regras de configuração de segurança (401/403 por serviço) ficam só em IT e não viram linha própria; entram quando há unitário da decisão (CompanyGuard, filtro, visibilidade) — declarado no cabeçalho da matriz"

requirements-completed: [TEST-01, TEST-02]
---

# Phase 7 Plan 10: Matriz regra -> teste e fechamento das lacunas Summary

**Matriz regra -> teste de 72 regras em 7 módulos, verificada por `scripts/check-coverage-matrix.sh`, com as 21 lacunas que ela acusou fechadas por 10 classes de teste unitário novas; reactor inteiro (`./mvnw -B verify`) verde contra Postgres e LocalStack reais.**

## Performance

- **Duration:** ~1h30 (estimado; o horário de início não foi capturado)
- **Completed:** 2026-10-03
- **Tasks:** 2 (Task 1 tracer, Task 2 auto/tdd)
- **Files modified:** 13 (11 criados, 2 alterados) mais `07-COVERAGE.md`

## Accomplishments

- `07-COVERAGE.md`: 72 regras — auth 9, catalog 6, inventory 15, notification 7, order 22, gateway 4, e2e 9 — cada uma com origem (REQ/D-xx), unitário e IT (ou E2E). Nenhuma termina como `LACUNA`; o cabeçalho explica que "sem lacunas" é regra -> teste, não porcentagem (D-105).
- `scripts/check-coverage-matrix.sh` (modo `100755`): acusa seção ausente, teste inexistente, método inexistente, linha de serviço sem unitário ou sem IT, E2E sem teste, status inválido e `LACUNA` aberta; `--report` lista lacunas sem falhar. Prova negativa feita: citar `FantasmaTest` produziu `COVERAGE CHECK FALHOU: teste inexistente FantasmaTest (auth-service #1)` e exit 1.
- Tracer verificado de ponta a ponta antes da expansão: `--report` terminou `COVERAGE CHECK OK 72 regras` listando 21 `LACUNA:`; o modo estrito acusou 21 `lacuna aberta`.
- Lacunas fechadas com unitários sem Docker (Mockito/AssertJ, `InOrder`):
  - inventory: `InventoryTest` (10), `StockReservationTest` (4), `InventoryServiceTest` (21)
  - order: `OrderServiceTest` (10), `OrderDecisionServiceTest` (5), `OrderShipmentServiceTest` (6), `OrderSagaServiceTest` (12)
  - auth: `AuthControllerTest` (4)
  - gateway: `GatewayRoutesTest` (6) e 2 casos novos em `CorrelationIdFilterTest` (linha de acesso)
- `./mvnw -B verify` verde nos 7 módulos (gateway, auth, catalog, inventory, notification, order, e2e-tests) e no parent, nenhum relatório com `Tests run: 0`.

## Task Commits

1. **Task 1: matriz + script (tracer)** — `2b14481` (feat)
2. **Task 2: lacunas fechadas e matriz sem LACUNA** — `3ac1eaf` (test)
3. **Task 2: correção do E2eContextsSmokeIT achada pelo verify do reactor** — `ca0d966` (fix)

## Testes criados para fechar lacunas

Os cinco previstos no plano — `InventoryTest`, `StockReservationTest`, `OrderServiceTest`, `OrderDecisionServiceTest`, `OrderShipmentServiceTest` — mais os que a matriz acusou (autorizados pela Task 2):

- `InventoryServiceTest` — reserva atômica, idempotência por reservationId, lápide, multi-item tudo-ou-nada, baixa pelo livro, ajuste de estoque
- `OrderSagaServiceTest` — resultado da saga idempotente por estado, sucesso tardio -> `ReleaseStock`, itens divergentes, timeout com Correlation-ID de criação
- `AuthControllerTest` — token só após autenticar; 401 uniforme
- `GatewayRoutesTest` — tabela de rotas estáticas lida do `application.yml` real
- 2 casos novos em `gateway/.../CorrelationIdFilterTest` — linha de acesso sem query string/Authorization e sem `/actuator`
- `OrderServiceTest` também cobre `getById` e `list` (visibilidade por empresa e ordenação fixa), além do previsto

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `E2eContextsSmokeIT` esperava 3 migrações no order e 4 no inventory**
- **Found during:** Task 2, primeiro `./mvnw -B verify` do reactor
- **Issue:** 07-02 adicionou `V4__correlation_id.sql` no order e 07-04 adicionou `V5__outbox_correlation_id.sql` no inventory; os dois testes de contagem do e2e (`...ThreeMigrations...`, `...FourMigrations...`) ficaram inválidos. O e2e-tests só roda no verify do reactor inteiro, por isso não apareceu nos planos anteriores.
- **Fix:** testes renomeados para `orderSchemaHasExactlyItsOwnFourMigrationsApplied` (V1-V4) e `inventorySchemaHasExactlyItsOwnFiveMigrationsApplied` (V1-V5); linha 9 do e2e na matriz atualizada. Só teste — nenhum código de produção alterado.
- **Files modified:** `e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java`, `07-COVERAGE.md`
- **Commit:** `ca0d966`

**2. [Rule 2 - Missing coverage] Lacunas além das cinco previstas**
- **Found during:** Task 1 (montagem da matriz lendo os testes)
- **Issue:** `InventoryService`, `OrderSagaService`, o login (`AuthController`), a tabela de rotas e a linha de acesso do gateway só tinham IT.
- **Fix:** testes listados acima, conforme a instrução da Task 2 para "qualquer outra linha LACUNA".
- **Commit:** `3ac1eaf`

Nenhum bug de produção foi encontrado pelos testes novos (nenhum código de produção foi alterado neste plano).

## Known Stubs

None.

## Threat Flags

None — o plano só adiciona testes, um script de verificação e documentação.

## Notas de honestidade da matriz

- Regras de configuração de segurança por serviço (401 por token inválido, 403 por papel) estão provadas só por IT e não são linhas próprias; o cabeçalho da matriz declara isso.
- Em algumas linhas o unitário prova a lógica de decisão e o IT prova o restante (ex.: order #4, serialização por empresa: o unitário prova a trava antes da exposição; a não-interferência entre empresas é do IT). Idem para a linha de acesso nos serviços além do gateway, que fica no IT.
- Suposição sinalizada mantida: "sem lacunas" = matriz regra -> teste, sem JaCoCo.

## Self-Check: PASSED

- `scripts/check-coverage-matrix.sh` (estrito) -> `COVERAGE CHECK OK 72 regras`
- Arquivos criados presentes; commits `2b14481`, `3ac1eaf`, `ca0d966` existem
- `./mvnw -B verify` -> `BUILD SUCCESS` em todos os módulos
