---
phase: 02-cat-logo-e-estoque
plan: "03"
subsystem: inventory, gateway, infra
tags: [docker-compose, spring-cloud-gateway, testcontainers, virtual-threads, cyclicbarrier, spring-retry, optimistic-locking, concurrency-testing]

# Dependency graph
requires:
  - phase: 02-cat-logo-e-estoque
    provides: "02-01: catalog-service completo; 02-02: inventory-service completo com InventoryService.reserve/release/setStock e RetryConfig"
provides:
  - "catalog-service e inventory-service alcancaveis pelo API Gateway (porta 8080), cada um com schema proprio, e nenhum dos dois exposto sem JWT"
  - "docker compose up -d --wait sobe os seis servicos da Fase 2 (postgres, localstack, auth-service, catalog-service, inventory-service, gateway) com um comando so"
  - "Success Criteria 3 do ROADMAP provado: StockReservationConcurrencyIT (HTTP real + threads virtuais + CyclicBarrier) confirma que a reserva de estoque nunca excede o disponivel sob concorrencia real"
  - "InventoryRetryContentionIT: prova independente e barata de que a reexecucao por conflito de lock otimista (@Retryable + @Transactional, RetryConfig) de fato reexecuta, isolando o mecanismo que RESEARCH.md registrava como suposicao (A1)"
affects: [05-saga-reserva-estoque]

# Actuals (#2632)
actuals:
  tokens: 8770
  tasks: 2
  commits: 2

plan_head_before: 32dedd1

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Teste de concorrencia por socket real: @SpringBootTest(webEnvironment = RANDOM_PORT) + java.net.http.HttpClient compartilhado + Executors.newVirtualThreadPerTaskExecutor() + CyclicBarrier dimensionada para o numero exato de contendores — a classe deliberadamente nao estende a base MockMvc, porque MockMvc nao satisfaz a exigencia de HTTP real (D-22)"
    - "RetryListener registrado como bean de teste (@TestConfiguration) para contar reexecucoes reais sem tocar codigo de producao — Spring Retry aplica automaticamente todo bean RetryListener do contexto a qualquer metodo @Retryable via RetryConfiguration"
    - "Conflito de versao forcado deterministicamente: ler uma copia via repositorio (que volta detached apos a transacao implicita fechar), alterar a linha por outro caminho legitimo, e tentar persistir a copia obsoleta diretamente pelo repositorio — prova a deteccao do JPA sem depender de timing de threads"

key-files:
  created:
    - inventory-service/src/test/java/com/orderflow/inventory/InventoryRetryContentionIT.java
    - inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java
  modified:
    - docker-compose.yml
    - gateway/src/main/resources/application.yml
    - README.md
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
    - .planning/WINDOWS.md

key-decisions:
  - "Rotas do Gateway: catalog-service-route (/api/products/** -> http://catalog-service:8082) e inventory-service-route (/api/inventory/** -> http://inventory-service:8083), StripPrefix=1, sem autenticacao no Gateway (D-05)"
  - "Retry de InventoryService elevado de maxAttempts=4 para maxAttempts=10, com teto de backoff maxDelay=200ms — o valor herdado de 02-02 esgotava as reexecucoes sob dez gravadores simultaneos na mesma linha, mesmo sem escassez real de estoque"

patterns-established:
  - "Pattern: prova de mecanismo isolada e barata (InventoryRetryContentionIT) escrita ANTES/ao lado da prova agregada por HTTP (StockReservationConcurrencyIT) — quando a prova agregada falhar, a prova isolada aponta se o problema esta no mecanismo de retry ou na logica de negocio"

requirements-completed: [CAT-02, INV-02]

coverage:
  - id: D1
    description: "O sistema inteiro sobe com um comando (docker compose up -d --wait) com os seis servicos da Fase 2 saudaveis; catalogo e estoque exigem JWT valido atraves do Gateway (401 sem token em /api/products e /api/inventory/{id}); a rota do auth-service da Fase 1 continua respondendo"
    requirement: "CAT-02"
    verification:
      - kind: manual
        ref: "Verificado pelo orquestrador fora do sandbox (Docker Desktop + .env nao disponiveis no worktree isolado) — docker compose config --quiet, docker compose up -d --wait (6/6 saudaveis), docker compose ps, 401 em POST/GET /api/products e GET /api/inventory/{uuid} sem token, 400/200 em POST /api/auth/login (sem regressao), fluxo completo de demonstracao seller (login admin -> criar produto -> definir estoque -> consultar disponibilidade -> reservar 10 -> disponibilidade cai para 90) e fluxo buyer (criar company+buyer, login buyer -> listagem 200, disponibilidade 200, criar produto 403, definir estoque 403)"
        status: pass
    human_judgment: true
    rationale: "Task 1 (tracer) exige docker compose up com Docker Desktop real e um .env preenchido, nenhum dos dois disponivel dentro do worktree isolado deste executor. O orquestrador executou a verificacao completa diretamente no host (fora de qualquer sandbox) e reportou os resultados no prompt de dispatch desta task; nao houve reexecucao dentro deste worktree. Ver Deviations abaixo."
  - id: D2
    description: "Requisicoes HTTP simultaneas por socket real, disparadas de threads virtuais liberadas ao mesmo tempo por uma barreira, contra as ultimas unidades de um produto, nunca reservam mais do que o disponivel — em tres cenarios de contencao (1, 3 e 5 unidades, ate 20 contendores)"
    requirement: "INV-02"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java#umaUnidadeComVinteContendoresReservaExatamenteUma"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java#tresUnidadesComVinteContendoresReservamExatamenteTres"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java#cincoUnidadesComContendoresDeDoisNuncaUltrapassaCinco"
        status: pass
    human_judgment: false
  - id: D3
    description: "A reexecucao configurada por conflito de lock otimista (@Retryable + @Transactional, RetryConfig com Ordered.LOWEST_PRECEDENCE) de fato reexecuta contra transacao nova e releitura da linha, provado isoladamente e a baixo custo, sem depender apenas da prova agregada por HTTP"
    requirement: "INV-02"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryRetryContentionIT.java#duasReservasConcorrentesSobreEstoqueDezAmbasSucedemComReexecucao"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryRetryContentionIT.java#dezReservasConcorrentesSobreEstoqueDezTerminamComDezSucessos"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryRetryContentionIT.java#conflitoDeVersaoForcadoDeliberadamenteNaoImpedeSucessoDaProximaChamada"
        status: pass
    human_judgment: false
  - id: D4
    description: "A prova de concorrencia e capaz de ficar vermelha quando o mecanismo que a sustenta e removido (experimento de sanidade obrigatorio do plano)"
    requirement: "INV-02"
    verification:
      - kind: manual
        ref: "Experimento de sanidade: @Version removida temporariamente de Inventory.java, cenario de uma unidade rodado isoladamente (StockReservationConcurrencyIT#umaUnidadeComVinteContendoresReservaExatamenteUma) — 10 sucessos observados de 20 contendores contra 1 unidade de estoque (esperado 1); @Version restaurada e suite reexecutada verde antes do commit"
        status: pass
    human_judgment: true
    rationale: "Verificacao manual de um dia, nao repetivel automaticamente por desenho — o proposito e confirmar que o teste tem poder de deteccao, nao adicionar um teste permanente que desliga producao."

duration: 38min
completed: 2026-09-20
status: complete
---

# Phase 2 Plan 3: Gateway, Docker Compose e Prova de Concorrencia Summary

**Catalogo e estoque alcancaveis pelo Gateway com um `docker compose up` so, e a reserva de estoque provada — por HTTP real, threads virtuais e barreira de liberacao simultanea — incapaz de vender acima do disponivel mesmo sob vinte contendores na mesma linha**

## Performance

- **Duration:** ~38 min (Task 2 apenas; Task 1 foi recuperada de sessao anterior e verificada pelo orquestrador fora deste worktree, sem tempo de execucao computado aqui)
- **Tasks:** 2 (Task 1: tracer — compose/gateway/README; Task 2: auto tdd — prova de concorrencia)
- **Files touched:** 6 (3 na Task 1 — docker-compose.yml, gateway/application.yml, README.md; 2 criados + 1 modificado na Task 2 — os dois `*IT.java` novos e `InventoryService.java`; mais `.planning/WINDOWS.md` fechando dois itens abertos)

## Accomplishments

- **Task 1 (recuperada de sessao anterior, verificada pelo orquestrador fora do sandbox):** `docker-compose.yml` ganhou os blocos `catalog-service` (porta 8082, schema `catalog`) e `inventory-service` (porta 8083, schema `inventory`), cada um dependendo de `postgres` e `auth-service` saudaveis, sem dependencia de `localstack` (D-15 — nenhum dos dois usa SQS/DynamoDB nesta fase). `gateway/src/main/resources/application.yml` ganhou `catalog-service-route` (`/api/products/** -> http://catalog-service:8082`) e `inventory-service-route` (`/api/inventory/** -> http://inventory-service:8083`), ambas com `StripPrefix=1`, preservando a rota `auth-service-route` intacta. `README.md` ampliado com os endpoints das duas fases e um fluxo de demonstracao completo em comandos copiaveis.
- **Task 2:** `InventoryRetryContentionIT` prova, de forma barata e focada, que a reexecucao por conflito de lock otimista (D-20) realmente acontece: duas e dez threads concorrentes reservando 1 unidade cada sobre um estoque de dez terminam todas com sucesso (nenhuma escassez real, contencao pura de versao), e um `RetryListener` registrado como bean de teste conta os conflitos reais capturados pelo Spring Retry — nao apenas infere sucesso do resultado agregado. Um terceiro cenario forca um conflito de versao de forma deterministica (sem depender de timing de threads): le uma copia via repositorio, altera a linha por outro caminho legitimo, e confirma que a tentativa de persistir a copia obsoleta diretamente pelo repositorio lanca `ObjectOptimisticLockingFailureException`.
- `StockReservationConcurrencyIT` prova o Success Criteria 3 por HTTP real: sobe o servidor embutido em porta aleatoria, dispara requisicoes via `java.net.http.HttpClient` compartilhado sobre um executor de uma thread virtual por tarefa, com uma `CyclicBarrier` dimensionada para o numero exato de contendores forcando sobreposicao real. Tres cenarios (1, 3 e 5 unidades, ate 20 contendores) confirmam que a quantidade reservada final nunca ultrapassa o estoque disponivel e que nenhuma resposta e erro de servidor.
- **Experimento de sanidade obrigatorio executado:** `@Version` removida temporariamente de `Inventory.java`, o cenario de uma unidade rodado isoladamente produziu **10 sucessos de 20 contendores contra 1 unidade de estoque** (esperado exatamente 1) — confirma que o teste tem poder de deteccao real, nao e um verde vazio. `@Version` restaurada antes do commit; `git diff` confirmou round-trip limpo sem alteracao liquida no arquivo.
- `InventoryService`: `maxAttempts` elevado de 4 para 10 com teto de backoff (`maxDelay=200ms`) apos o cenario de dez threads concorrentes (`InventoryRetryContentionIT`) esgotar as reexecucoes com o valor herdado de `02-02` — nao havia escassez real de estoque nesse cenario (10 unidades para 10 contendores de 1 unidade cada), so contencao pura de versao acima do que 4 tentativas absorviam. Nenhuma asserção foi relaxada para acomodar o ajuste.
- Suite completa verde: `catalog-service` + `inventory-service` (27/27), `auth-service` (46/46, sem regressao da Fase 1), executados duas vezes seguidas sem instabilidade.
- Dois itens do `.planning/WINDOWS.md` fechados como `fixed`: o `unrun-verify` do `StockReservationConcurrencyIT` (agora existe e passa) e o `unrun-verify` do human-check de navegacao BUYER/SELLER_ADMIN pelo Gateway (verificado pelo orquestrador, ver Deviations).

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — catalogo e estoque alcancaveis pelo Gateway, com todo o sistema subindo em um comando** - `42088d0` (wip) — codigo recuperado de sessao anterior interrompida; verificacao completa (automated + human-check) executada pelo orquestrador diretamente no host, fora deste worktree (ver Deviations)
2. **Task 2: A prova de que a reserva nunca vende acima do estoque, sob concorrencia HTTP real** - `cf874fa` (test) — RED confirmado antes do ajuste de producao (10-thread scenario esgotava retries) → ajuste de `maxAttempts`/backoff → GREEN (6/6 nos dois `*IT` novos, estavel em duas execucoes consecutivas)

**Plan metadata:** commit de documentacao final a ser criado logo apos este SUMMARY.

## Files Created/Modified

- `docker-compose.yml` - blocos `catalog-service` (8082) e `inventory-service` (8083), `gateway.depends_on` estendido
- `gateway/src/main/resources/application.yml` - rotas `catalog-service-route` e `inventory-service-route`
- `README.md` - endpoints de catalogo/estoque e fluxo de demonstracao completo
- `inventory-service/src/test/java/com/orderflow/inventory/InventoryRetryContentionIT.java` - prova isolada da reexecucao por conflito de versao (2 e 10 threads + `RetryListener`; conflito deterministico via entidade obsoleta)
- `inventory-service/src/test/java/com/orderflow/inventory/StockReservationConcurrencyIT.java` - Success Criteria 3: HTTP real + threads virtuais + `CyclicBarrier`, tres cenarios de contencao
- `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java` - `maxAttempts` 4→10, `maxDelay=200ms` adicionado, constantes nomeadas extraidas para as tres anotações `@Retryable`
- `.planning/WINDOWS.md` - dois itens `unrun-verify` da Fase 2 marcados `fixed`

## Decisions Made

- **Rotas do Gateway e portas publicadas (Task 1):** `catalog-service-route` (`/api/products/**` → `http://catalog-service:8082`) e `inventory-service-route` (`/api/inventory/**` → `http://inventory-service:8083`), ambas `StripPrefix=1`.
- **Retry de `InventoryService`: `maxAttempts=10`, `delay=20ms`, `multiplier=2`, `maxDelay=200ms`** (era `maxAttempts=4`, `delay=25ms`, `multiplier=2`, sem teto). Motivo registrado em Deviations abaixo.
- **Mecanica de teste para a prova deterministica de conflito de versao (`InventoryRetryContentionIT`, terceiro cenario):** em vez de forcar overlap via threads (nao deterministico), le-se uma copia da entidade via repositorio (que volta detached apos a transacao implicita do metodo de repositorio fechar), altera-se a linha por outro caminho legitimo (`inventoryService.reserve` normal), e tenta-se persistir a copia obsoleta diretamente pelo repositorio — a excecao de conflito de versao e disparada de forma 100% reproduzivel, sem depender de timing de threads.
- **`RetryListener` como bean de teste, nao alteracao de producao:** o Spring Retry aplica automaticamente todo bean `RetryListener` presente no contexto a qualquer metodo `@Retryable` (via `RetryConfiguration`, ativada por `@EnableRetry`) — usado para contar conflitos de versao reais capturados durante os cenarios de 2 e 10 threads, sem tocar `InventoryService.java` para isso.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking issue] `maxAttempts` elevado de 4 para 10 com teto de backoff**
- **Found during:** Task 2, primeira execucao de `InventoryRetryContentionIT`
- **Issue:** O cenario de dez threads concorrentes reservando 1 unidade cada sobre um estoque de dez (sem escassez real — matematicamente todas deveriam caber) falhou com `ReservationConflictException` (disputa esgotada) em uma das dez chamadas. O `maxAttempts=4` herdado de `02-02` nao bastava para dez gravadores simultaneos disputando a MESMA linha.
- **Fix:** `maxAttempts` elevado para 10 nos tres metodos retryable (`setStock`, `reserve`, `release`), com `@Backoff(delay=20, multiplier=2, maxDelay=200)` — o teto evita que o crescimento exponencial deixasse o pior caso lento demais (sem teto, a decima tentativa esperaria mais de 12 segundos). Nao relaxa nenhuma asserção de teste: o conjunto de respostas aceito pelos testes de concorrencia ja incluia a disputa esgotada (D-21) como recusa legitima; o ajuste so reduz a frequencia dela no caso em que a matematica do cenario diz que todo mundo deveria caber.
- **Files modified:** `inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java`
- **Verification:** `InventoryRetryContentionIT` (3/3) e `StockReservationConcurrencyIT` (3/3) verdes apos o ajuste, reexecutados duas vezes seguidas sem instabilidade; `catalog-service`+`inventory-service` (27/27) e `auth-service` (46/46) sem regressao.
- **Commit:** `cf874fa`

### Documented Deviations (not auto-fixed, no code impact)

**2. Verificacao da Task 1 executada pelo orquestrador fora deste worktree, nao por este executor**
- **Contexto:** A Task 1 (tracer) exige `docker compose up -d --wait` com Docker Desktop real e um arquivo `.env` preenchido na raiz do repositorio — nenhum dos dois esta disponivel dentro deste worktree isolado (ambiente de execucao de agente, sem `.env` nem daemon Docker acessivel pelo mesmo caminho de host que o `docker-compose.yml` assume).
- **Resolucao:** O orquestrador executou a verificacao completa diretamente no host, fora de qualquer sandbox, e reportou os resultados no prompt de dispatch desta task: `docker compose config --quiet` (OK), `docker compose up -d --wait` (6/6 servicos saudaveis), `docker compose ps` (saudavel), 401 em `/api/products` e `/api/inventory/{uuid}` sem token, sem regressao em `/api/auth/login`, fluxo de demonstracao seller completo (login admin → criar produto → definir estoque → consultar disponibilidade → reservar 10 → disponibilidade cai para 90) e fluxo buyer completo (criar company+buyer, login buyer → listagem 200, disponibilidade 200, criar produto 403, definir estoque 403).
- **Por que isso nao e um problema:** E uma consequencia estrutural do isolamento por worktree (correto e intencional para paralelismo entre agentes), nao uma lacuna de cobertura — a verificacao aconteceu, apenas por um agente diferente e em um ambiente diferente do que normalmente executaria a Task 1. Registrado aqui em vez de re-executado neste worktree porque re-executar exigiria acesso a Docker Desktop e a um `.env` que este ambiente nao tem por desenho.
- **Impacto:** Nenhum no codigo produzido; apenas um ajuste no fluxo padrao de "um agente faz tudo" documentado para rastreabilidade.

---

**Total deviations:** 1 auto-fixed (Rule 3 — ajuste de tentativas de reexecucao), 1 documentada sem impacto de codigo (verificacao da Task 1 feita pelo orquestrador fora do worktree)
**Impact on plan:** Nenhum scope creep — o ajuste de retry e exatamente o que o plano previa como possivel ("Ajuste de producao, se necessario... registre o valor novo e o motivo no SUMMARY"); a verificacao da Task 1 aconteceu integralmente, apenas fora deste processo de execucao.

## Issues Encountered

Nenhum problema bloqueante alem do ajuste de retry documentado acima. O aviso `spring.jpa.open-in-view is enabled by default` continua aparecendo nos logs de teste — o mesmo aviso ja presente desde `02-01`/`02-02`, nao introduzido por este plano.

## User Setup Required

None - nenhuma configuracao de servico externo necessaria alem do que a Fase 1/2 ja documentam (`.env` com credenciais Postgres e token LocalStack).

## Threat Flags

Nenhuma superficie nova alem das ja registradas no `<threat_model>` do plano (T-02-20 a T-02-26, T-02-SC) — todas com disposicao `mitigate`/`accept` implementadas: T-02-20 (rotas do Gateway sem auth propria, 401 confirmado por chamada sem token), T-02-21 (venda acima do estoque sob concorrencia real, `StockReservationConcurrencyIT`), T-02-22 (reexecucao anulada em silencio pela ordem de aspectos, `InventoryRetryContentionIT`), T-02-23 (portas restritas a `127.0.0.1`), T-02-24 (nenhuma tag flutuante), T-02-25 (sem dependencia desnecessaria do LocalStack), T-02-26/T-02-SC (aceitos, sem mudanca).

## Known Stubs

Nenhum. Os dois testes de concorrencia exercitam a pilha completa (Gateway nao esta no caminho do teste, mas o servidor embutido real, filtros de seguranca, transacao e banco Postgres real via Testcontainers estao todos presentes) — nenhum dado mock ou placeholder.

## Next Phase Readiness

- Fase 2 esta funcionalmente completa: os tres planos (`02-01` catalogo, `02-02` estoque, `02-03` gateway/compose/concorrencia) entregam os quatro requisitos da fase (CAT-01, CAT-02, INV-01, INV-02) e os quatro Success Criteria do ROADMAP.
- Nenhum bloqueio conhecido para a Fase 5 (saga de reserva) alem do ja registrado em `02-02-SUMMARY.md` (identidade de servico propria para o `order-service` chamar as rotas de reserva/liberacao, hoje restritas a `SELLER_ADMIN`).
- `maxAttempts=10`/`maxDelay=200ms` e o novo valor de referencia para qualquer trabalho futuro em `InventoryService` — registrado aqui para nao ser silenciosamente revertido para o valor de `02-02` em um refactor futuro.
- Ambos os itens de `.planning/WINDOWS.md` abertos pela Fase 2 estao fechados (`fixed`) — nenhum item pendente na ledger para esta fase no momento deste commit.

---
*Phase: 02-cat-logo-e-estoque*
*Completed: 2026-09-20*

## Self-Check: PASSED
