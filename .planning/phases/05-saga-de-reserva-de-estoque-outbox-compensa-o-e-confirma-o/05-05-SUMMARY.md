---
phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
plan: "05"
subsystem: testing
tags: [e2e, testcontainers, localstack, spring-boot, saga, maven-module, sqs]

requires:
  - phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o
    plan: "04"
    provides: "SagaTimeoutJob, tombstone/ReleaseStock, STOCK_ADJUSTED pelo outbox — os dois serviços com o fluxo da saga completo e testado isoladamente"
provides:
  - "Módulo Maven e2e-tests (sem src/main) no reactor e nos seis Dockerfiles — Success Criteria 5"
  - "E2eInfrastructure: dois contextos Spring Boot reais (order-service, inventory-service) no mesmo JVM via SpringApplicationBuilder, Postgres/LocalStack reais via Testcontainers"
  - "Mitigação provada do risco de colisão de classpath (05-RESEARCH.md Pitfall 2): cada contexto usa spring.config.location apontando pro application.yml real do próprio serviço + override (porta 0, flyway filesystem:) + argumentos de linha de comando"
  - "E2eContextsSmokeIT: spike de configuração verde, migrações corretas por schema, health sem token nos dois contextos"
  - "OrderReservationSagaE2EIT: falha (INSUFFICIENT_STOCK, PRODUCT_NOT_STOCKED) escrita antes do sucesso (CONFIRMED com reserved refletido, idempotência por republicação, os dois pontos de entrada da saga)"
affects: [05-06]

actuals:
  tokens: 16485
  tasks: 2
  commits: 2
  plan_head_before: f9719b42a6ed93461809fa2bd9d71deba81f39a3

tech-stack:
  added: []
  patterns:
    - "E2E_CONFIG_STRATEGY=config.location real + overrides + argumentos — cada contexto recebe --spring.config.location=file:../<serviço>/src/main/resources/application.yml (arquivo real, nunca cópia), --spring.config.additional-location=classpath:/e2e/<serviço>-overrides.yml (porta 0, flyway filesystem:) e os valores dinâmicos (URLs de containers/stub) como argumento de linha de comando (precedência máxima) — nunca depende da resolução ambígua de classpath:application.yml entre os dois jars"
    - "E2E_FLYWAY_LOCATIONS=filesystem — cada override aponta spring.flyway.locations para a pasta db/migration do PRÓPRIO serviço no disco, nunca a localização padrão classpath:db/migration (que veria as migrações dos dois serviços e falharia na versão 1 duplicada)"
    - "Porta efetiva de cada contexto capturada por um ApplicationListener<WebServerInitializedEvent> registrado via SpringApplicationBuilder.initializers — não existe local.server.port fora do TestContext do Spring (@SpringBootTest), que não se aplica aqui (duas aplicações reais via SpringApplicationBuilder puro, nunca @SpringBootTest)"
    - "Republicação de um comando ReserveStock direto na fila real via SqsAsyncClient (JSON montado à mão com ObjectMapper), sem passar pelo relay do outbox — prova a idempotência do consumidor real (D-65) sem nenhum dublê de teste"

key-files:
  created:
    - e2e-tests/pom.xml
    - e2e-tests/src/test/resources/e2e/order-service-overrides.yml
    - e2e-tests/src/test/resources/e2e/inventory-service-overrides.yml
    - e2e-tests/src/test/java/com/orderflow/e2e/support/E2eInfrastructure.java
    - e2e-tests/src/test/java/com/orderflow/e2e/support/LocalStackTestSupport.java
    - e2e-tests/src/test/java/com/orderflow/e2e/support/LocalStackProvisioningWaiter.java
    - e2e-tests/src/test/java/com/orderflow/e2e/support/DownstreamStubServer.java
    - e2e-tests/src/test/java/com/orderflow/e2e/support/E2eJwt.java
    - e2e-tests/src/test/java/com/orderflow/e2e/support/E2eHttp.java
    - e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java
    - e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java
  modified:
    - pom.xml
    - auth-service/Dockerfile
    - gateway/Dockerfile
    - catalog-service/Dockerfile
    - inventory-service/Dockerfile
    - notification-service/Dockerfile
    - order-service/Dockerfile

key-decisions:
  - "E2E_CONFIG_STRATEGY e E2E_FLYWAY_LOCATIONS (decisões do planejador registradas no PLAN.md) confirmadas funcionando: os dois contextos sobem isolados, sem nenhum vazamento de configuração/migração entre serviços."
  - "Jar que vence a resolução ambígua de classpath:application.yml (getResource singular, primeira ocorrência): order-service.jar — documentado por E2eContextsSmokeIT, sem nenhuma parte de E2eInfrastructure depender dessa resolução (mitigação real, não só teórica)."
  - "[Rule 1 - Bug] Consulta a flyway_schema_history do smoke ajustada para 'version IS NOT NULL' — o Flyway grava uma linha adicional (type SCHEMA, version nulo) para o evento de criação do schema, que o <behavior> da task não previa e quebrava a asserção de contagem exata de migrações."

requirements-completed: [TEST-03, ORD-05]

coverage:
  - id: D1
    description: "Os dois contextos Spring reais sobem isolados no mesmo JVM: nomes, schemas do Flyway e títulos do OpenAPI próprios, migrações corretas por schema (order v1-v2, inventory v1-v2-v3), risco de colisão de classpath documentado, health sem token nos dois"
    requirement: "TEST-03"
    verification:
      - kind: integration
        ref: "e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java (7 casos)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Módulo e2e-tests no reactor e nos seis Dockerfiles; imagens do order-service e do inventory-service constroem com o módulo novo"
    requirement: "TEST-03"
    verification:
      - kind: other
        ref: "docker build -f order-service/Dockerfile . && docker build -f inventory-service/Dockerfile ."
        status: pass
      - kind: other
        ref: "grep -l 'COPY e2e-tests/pom.xml' nos 6 Dockerfiles (count 6)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Caminho de falha (escrito primeiro, D-68): pedido com estoque insuficiente termina CANCELLED com INSUFFICIENT_STOCK e motivo citando disponível/solicitado; produto sem linha de estoque termina CANCELLED com PRODUCT_NOT_STOCKED"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java#orderExceedingAvailableStockEndsCancelledWithInsufficientStockAndNoReservation"
        status: pass
      - kind: e2e
        ref: "e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java#orderForProductWithoutAnyStockLineEndsCancelledWithProductNotStocked"
        status: pass
    human_judgment: false
  - id: D4
    description: "Pedido com estoque suficiente passa por RESERVING e termina CONFIRMED, com quantityReserved/quantityAvailable refletidos no inventory-service"
    requirement: "ORD-05"
    verification:
      - kind: e2e
        ref: "e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java#orderWithSufficientStockEndsConfirmedWithStockReservedInInventory"
        status: pass
    human_judgment: false
  - id: D5
    description: "Reenviar o mesmo ReserveStock de um pedido já CONFIRMED direto na fila real não reserva de novo — replay idempotente, mesmo confirmedAt, só um segundo StockReserved sai no outbox"
    requirement: "ORD-05"
    verification:
      - kind: e2e
        ref: "e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java#republishingSameReserveStockAfterConfirmedDoesNotReserveAgain"
        status: pass
    human_judgment: false
  - id: D6
    description: "Os dois pontos de entrada da saga (D-48) de ponta a ponta: pedido acima do limite fica PENDING_APPROVAL, é aprovado manualmente pelo vendedor, e também termina CONFIRMED com o estoque reservado"
    requirement: "ORD-05"
    verification:
      - kind: e2e
        ref: "e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java#pendingApprovalOrderApprovedManuallyAlsoEndsConfirmedWithStockReserved"
        status: pass
    human_judgment: false

duration: 95min
completed: 2026-09-29
status: complete
---

# Phase 5 Plan 5: Módulo e2e-tests — Saga de Ponta a Ponta em Dois Contextos Spring Summary

**Novo módulo Maven `e2e-tests` sobe order-service e inventory-service como dois contextos Spring Boot reais no mesmo JVM, com PostgreSQL e LocalStack reais via Testcontainers, e prova a saga inteira — falha (`INSUFFICIENT_STOCK`/`PRODUCT_NOT_STOCKED`) escrita antes do sucesso (`CONFIRMED` com estoque reservado, idempotência por republicação, os dois pontos de entrada da aprovação) — com um único comando `./mvnw -B -pl e2e-tests -am verify`.**

## Performance

- **Duration:** ~95 min
- **Tasks:** 2
- **Files modified:** 18 (11 criados, 7 modificados)

## Accomplishments

- Módulo `e2e-tests` (sem `src/main`) acrescentado ao reactor e aos seis Dockerfiles — as imagens Docker do order-service e do inventory-service constroem normalmente com o módulo novo.
- `E2eInfrastructure` sobe os dois serviços reais via `SpringApplicationBuilder` num único Postgres (schemas `order`/`inventory`, mesmo desenho do `docker-compose.yml`) e um único LocalStack — cada contexto isolado por `spring.config.location` (arquivo real do próprio serviço) + override de teste (porta 0, `spring.flyway.locations=filesystem:...`) + argumentos de linha de comando (URLs dinâmicas, precedência máxima).
- `E2eContextsSmokeIT` resolve o spike do risco de colisão de classpath (05-RESEARCH.md Pitfall 2): confirma que os dois contextos nunca leem a configuração um do outro (nome, schema, título do OpenAPI, migrações corretas), documenta que há de fato 2 URLs de `application.yml` no classpath e que `order-service.jar` vence a resolução singular — e confirma que nenhuma parte de `E2eInfrastructure` depende dessa resolução ambígua.
- `OrderReservationSagaE2EIT` prova a saga inteira entre os dois serviços reais via SQS/LocalStack, sem nenhum dublê de teste: falha por estoque insuficiente e por produto sem linha de estoque (escritas primeiro, D-68); sucesso com estoque refletido; republicação do mesmo `ReserveStock` não reserva de novo (idempotência, Success Criteria 4); os dois pontos de entrada da saga (aprovação automática e manual) chegando a `CONFIRMED`.

## Task Commits

1. **Task 1: Tracer — os dois serviços sobem no mesmo JVM com configurações isoladas e um pedido sem estoque suficiente termina CANCELLED** — `a2ce213` (feat): módulo `e2e-tests` completo (pom, overrides, suporte, `E2eContextsSmokeIT`, os dois testes de falha de `OrderReservationSagaE2EIT`).
2. **Task 2: O pedido com estoque percorre RESERVING até CONFIRMED, a republicação não reserva de novo e a aprovação do vendedor também chega a CONFIRMED** — `d0ab08e` (feat): os três testes de sucesso acrescentados abaixo dos de falha em `OrderReservationSagaE2EIT.java`.

**Plan metadata:** commit deste SUMMARY (a seguir).

_Nota: esta task não seguiu o RED→GREEN clássico por commit — é módulo de teste novo (infraestrutura + os próprios testes), sem código de produção a testar. O "RED" real foi o ciclo iterativo de rodar `./mvnw -B -pl e2e-tests -am verify` até verde, documentado em "Deviations"/"Issues Encountered" abaixo._

## Files Created/Modified

- `e2e-tests/pom.xml` — módulo só de teste, `maven-failsafe-plugin`, dependências de teste em `order-service`/`inventory-service` + Testcontainers/Awaitility
- `e2e-tests/src/test/resources/e2e/{order-service,inventory-service}-overrides.yml` — porta 0, `spring.flyway.locations=filesystem:...`, `spring.jmx.enabled=false`
- `e2e-tests/src/test/java/com/orderflow/e2e/support/E2eInfrastructure.java` — Postgres/LocalStack/stub singleton + os dois contextos Spring reais
- `e2e-tests/src/test/java/com/orderflow/e2e/support/{LocalStackTestSupport,LocalStackProvisioningWaiter}.java` — cópia do suporte do inventory-service (dois init hooks, três filas)
- `e2e-tests/src/test/java/com/orderflow/e2e/support/{DownstreamStubServer,E2eJwt,E2eHttp}.java` — stub enxuto de auth/catalog + JWKS, par RSA único para os dois resource servers, cliente HTTP de teste
- `e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java` — spike de configuração
- `e2e-tests/src/test/java/com/orderflow/e2e/OrderReservationSagaE2EIT.java` — E2E completo da saga
- `pom.xml` + os seis `*/Dockerfile` — módulo novo no reactor e nas imagens

## Decisions Made

Ver `key-decisions` do frontmatter — `E2E_CONFIG_STRATEGY`/`E2E_FLYWAY_LOCATIONS` confirmados, jar vencedor da resolução de classpath documentado, e a correção da consulta de migrações do Flyway (linha de criação de schema).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Consulta de `flyway_schema_history` do smoke test não excluía a linha de criação de schema**
- **Found during:** Task 1, primeira execução de `./mvnw -B -pl e2e-tests -am verify`
- **Issue:** `SELECT version FROM "order".flyway_schema_history WHERE success ORDER BY installed_rank` devolvia `[null, "1", "2"]` — o Flyway grava uma linha adicional (`type=SCHEMA`, `version` nulo) para o evento de criação do schema, que o `<behavior>` da task ("devolve `1` e `2`") não previa.
- **Fix:** Consulta ajustada para `WHERE success AND version IS NOT NULL` nos dois testes (order e inventory).
- **Files modified:** `e2e-tests/src/test/java/com/orderflow/e2e/E2eContextsSmokeIT.java`
- **Verification:** `./mvnw -B -pl e2e-tests -am verify` reexecutado, `E2eContextsSmokeIT` 7/7 verde.
- **Committed in:** `a2ce213` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (bug de asserção de teste, exposto pelo comportamento real do Flyway)
**Impact on plan:** Correção necessária para a suíte ser deterministicamente verde; nenhum scope creep.

## Issues Encountered

- Nenhuma versão do Spring Boot expõe `local.server.port` fora do `TestContext` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`), que não se aplica aqui (duas aplicações reais via `SpringApplicationBuilder` puro). Resolvido registrando um `ApplicationListener<WebServerInitializedEvent>` via `SpringApplicationBuilder.initializers` para capturar a porta efetiva de cada contexto — técnica estável entre versões do Boot, sem depender de `@SpringBootTest`.
- Mesma limitação do `gsd_run check tdd-red-evidence` já documentada em `05-01`/`05-02`/`05-03`/`05-04-SUMMARY.md` (espera saída TAP, não interpreta Failsafe) — não aplicável de qualquer forma aqui, já que este plano não segue o ciclo RED→GREEN de código de produção (é módulo de teste novo).

## User Setup Required

None - nenhuma configuração de serviço externo nova (`LOCALSTACK_AUTH_TOKEN` já exigido desde a Fase 1; as filas da saga já existem desde `05-01`).

## Next Phase Readiness

- Success Criteria 5 do ROADMAP (E2E num único comando) entregue; Success Criteria 2, 3 e 4 também provados de ponta a ponta pelo mesmo módulo.
- Risco de colisão de configuração entre dois jars Spring Boot no mesmo classpath (05-RESEARCH.md Pitfall 2) resolvido e documentado — reutilizável por qualquer E2E futuro que precise subir mais de um serviço real no mesmo JVM.
- `05-06` (demonstração na stack real via `scripts/smoke-order-saga.sh`, D-69) não depende de nada deste plano além do fluxo da saga já estar completo (entregue em `05-01`–`05-04`).
- Nenhum bloqueio conhecido para `05-06`.

---
*Phase: 05-saga-de-reserva-de-estoque-outbox-compensa-o-e-confirma-o*
*Completed: 2026-09-29*

## Self-Check: PASSED

- Todos os arquivos-chave criados confirmados em disco (`[ -f ]`): `e2e-tests/pom.xml`, os dois overrides, `E2eInfrastructure`, `LocalStackTestSupport`, `LocalStackProvisioningWaiter`, `DownstreamStubServer`, `E2eJwt`, `E2eHttp`, `E2eContextsSmokeIT`, `OrderReservationSagaE2EIT`, os seis Dockerfiles.
- Os 2 commits do plano confirmados em `git log --oneline --all`: `a2ce213`, `d0ab08e`.
- `./mvnw -B -pl e2e-tests -am verify` reexecutado (compose derrubado): 12 testes verdes no módulo `e2e-tests` (7 `E2eContextsSmokeIT` + 5 `OrderReservationSagaE2EIT`), mais as suítes completas de `order-service` (70 testes) e `inventory-service` (51 testes) via `-am`, todas verdes.
- `docker build -f order-service/Dockerfile .` e `docker build -f inventory-service/Dockerfile .` reexecutados: `BUILD SUCCESS` nos dois.
- Gate de grep reverificado: `grep -l 'COPY e2e-tests/pom.xml' auth-service/Dockerfile gateway/Dockerfile catalog-service/Dockerfile inventory-service/Dockerfile notification-service/Dockerfile order-service/Dockerfile | wc -l` devolve `6`; `grep -rqE 'org\.mockito|MockBean|MockitoBean' e2e-tests/src` devolve não-zero (nenhum dublê de teste).
- Acceptance criteria da Task 2 reverificado: primeiro commit que adicionou `OrderReservationSagaE2EIT.java` não contém o literal `"CONFIRMED"` (`grep -c` devolve `0`).
