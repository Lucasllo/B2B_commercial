---
phase: 02-cat-logo-e-estoque
plan: "02"
subsystem: inventory
tags: [spring-boot, spring-security, oauth2-resource-server, jpa, optimistic-locking, spring-retry, flyway, postgresql, testcontainers, mockmvc]

# Dependency graph
requires:
  - phase: 02-cat-logo-e-estoque
    provides: "02-01: template de resource server (SecurityConfig, GlobalExceptionHandler, TestJwt, AbstractIntegrationTest, pom.xml, Dockerfile) pronto para replicar; convencao de porta/jwk-set-uri"
provides:
  - Modulo inventory-service completo e construivel no reactor Maven (pom.xml, Dockerfile eclipse-temurin:21.0.12_8, migracao Flyway propria)
  - "INV-01: PUT/GET /inventory/{productId} — definicao de estoque via upsert e leitura de disponibilidade exata para qualquer autenticado"
  - "INV-02: POST /inventory/{productId}/reservations e DELETE .../reservations/{reservationId} — reserva/liberacao atomicas por lock otimista com reexecucao automatica (Spring Retry) e idempotentes por reservationId fornecido pelo chamador"
  - "RESERVATION_ID_SCOPE=scope-per-product — contrato de idempotencia congelado para a saga da Fase 5 consumir (ORD-06, TEST-03)"
affects: [02-03-gateway-docker-compose, 05-saga-reserva-estoque]

# Actuals (#2632)
actuals:
  tokens: 21836
  tasks: 3
  commits: 2

plan_head_before: 9272ca5

# Tech tracking
tech-stack:
  added:
    - "org.springframework.retry:spring-retry — reexecucao declarativa de conflito de lock otimista (D-20)"
    - "org.springframework.boot:spring-boot-starter-aop — infraestrutura de proxy exigida por @Retryable"
  patterns:
    - "@EnableRetry(order = Ordered.LOWEST_PRECEDENCE) — reexecucao envolve a transacao por fora, garantindo transacao nova por tentativa (evita o Pitfall de aspecto de retry dentro da mesma transacao ja condenada)"
    - "Idempotencia por constraint UNIQUE(product_id, reservation_id) no banco + checagem antecipada como caminho rapido — nao check-then-act isolado"
    - "@Version (lock otimista JPA) + saveAndFlush dentro da tentativa retryable, para o conflito de versao ser capturado pela reexecucao e nao so no commit"
    - "@Recover com primeiro parametro DataAccessException e assinatura espelhada do metodo retryable, ligando reexecucoes esgotadas a uma excecao de dominio (503)"

key-files:
  created:
    - inventory-service/pom.xml
    - inventory-service/Dockerfile
    - inventory-service/src/main/resources/application.yml
    - inventory-service/src/main/resources/db/migration/V1__init_inventory_schema.sql
    - inventory-service/src/main/java/com/orderflow/inventory/InventoryServiceApplication.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/SecurityConfig.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/RetryConfig.java
    - inventory-service/src/main/java/com/orderflow/inventory/config/GlobalExceptionHandler.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/Inventory.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryRepository.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservation.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/StockReservationRepository.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryNotFoundException.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/InsufficientStockException.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/ReservationConflictException.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/StockBelowReservedException.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/SetStockRequest.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/ReserveStockRequest.java
    - inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockResponse.java
    - inventory-service/src/test/resources/application-test.yml
    - inventory-service/src/test/java/com/orderflow/inventory/AbstractIntegrationTest.java
    - inventory-service/src/test/java/com/orderflow/inventory/support/TestJwt.java
    - inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java
  modified:
    - pom.xml
    - auth-service/Dockerfile
    - gateway/Dockerfile
    - catalog-service/Dockerfile

key-decisions:
  - "RESERVATION_ID_SCOPE=scope-per-product"
  - "Porta HTTP do inventory-service: 8083; jwk-set-uri default herdado: http://localhost:8081/.well-known/jwks.json"
  - "Reexecucao: maxAttempts=4, backoff exponencial delay=25ms multiplier=2 (Claude's Discretion na 02-CONTEXT.md)"
  - "Formato de reserva: POST /inventory/{productId}/reservations com corpo {reservationId, quantity}; liberacao: DELETE /inventory/{productId}/reservations/{reservationId}"
  - "Reservar e liberar restritos a SELLER_ADMIN nesta fase — pendencia explicita para a Fase 5 (order-service precisa de identidade de servico propria)"

patterns-established:
  - "Pattern: @EnableRetry + @Transactional na mesma assinatura com ordem de advisor explicita — primeiro uso de Spring Retry no repositorio, reutilizavel por qualquer servico futuro com conflito de lock otimista"
  - "Pattern: livro de idempotencia em tabela separada com UNIQUE(product_id, reservation_id) — mesmo mecanismo que a Fase 5 vai reusar para a idempotencia do consumidor SQS (ORD-06, TEST-03)"

requirements-completed: [INV-01, INV-02]

coverage:
  - id: D1
    description: "SELLER_ADMIN define e redefine o nivel de estoque de um produto (upsert, D-18); qualquer autenticado le a disponibilidade exata (on_hand - reserved), nunca um booleano (D-25)"
    requirement: "INV-01"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java#setStockWithSellerAdminTokenCreatesRowAndReturns200WithExpectedBody"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java#secondSetStockCallForSameProductUpdatesSameRowInsteadOfCreatingNewOne"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java#getStockWithBuyerTokenReturns200WithNumericAvailableQuantity"
        status: pass
    human_judgment: false
  - id: D2
    description: "Reserva atomica e idempotente: reservar mais que o disponivel devolve 409 com available/requested; reenviar a mesma reserva nao duplica o decremento; disputa esgotada devolve 503 distinto do 409"
    requirement: "INV-02"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java#reservingMoreThanAvailableReturns409WithAvailableAndRequestedAndDoesNotChangeReserved"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java#reservationSucceedsAndRepeatingSameReservationIdDoesNotDuplicateAndThirdReservationExceedingStockReturns409"
        status: pass
    human_judgment: false
  - id: D3
    description: "Liberacao idempotente (D-14): repetir a liberacao ou liberar um reservationId inexistente sobre produto com linha e no-op; liberar contra productId sem linha e 404; apos liberar, a quantidade volta a caber em nova reserva"
    requirement: "INV-02"
    verification:
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java#releaseIsIdempotentAndFreesReservedQuantityForReuse"
        status: pass
      - kind: integration
        ref: "inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java#releasingAgainstProductWithoutInventoryLineReturns404"
        status: pass
    human_judgment: false
  - id: D4
    description: "Prova de atomicidade sob concorrencia real (Success Criteria 3 do ROADMAP, StockReservationConcurrencyIT com HTTP real + virtual threads) — nao entregue neste plano, e o objeto do plano 02-03"
    verification: []
    human_judgment: true
    rationale: "O plano 02-02 explicitamente delega a prova de concorrencia real ao StockReservationConcurrencyIT do plano 02-03 (secao <verification> do 02-02-PLAN.md); registrado em .planning/WINDOWS.md como unrun-verify ate la."

duration: 27min
completed: 2026-09-20
status: complete
---

# Phase 2 Plan 2: Estoque e Reserva Atomica Summary

**inventory-service novo no reactor — estoque upsert (INV-01) e reserva/liberacao atomicas por lock otimista com reexecucao automatica via Spring Retry, idempotentes por reservationId fornecido pelo chamador (INV-02)**

## Performance

- **Duration:** 27 min (execucao das Tasks 2 e 3; Task 1 foi um checkpoint de decisao `blocking-human` resolvido pelo usuario antes da execucao — `RESERVATION_ID_SCOPE=scope-per-product` — sem tempo de execucao de codigo)
- **Started:** 2026-09-20T02:24:00Z
- **Completed:** 2026-09-20T02:51:00Z
- **Tasks:** 3 (Task 1: checkpoint de decisao; Task 2: tracer; Task 3: expansao — reserva/liberacao)
- **Files modified:** 29 (25 no commit da Task 2, 12 no commit da Task 3, com 8 arquivos sobrepostos)

## RESERVATION_ID_SCOPE

`RESERVATION_ID_SCOPE=scope-per-product` — unicidade por par `(productId, reservationId)`. O identificador e uma `String` fornecida pelo chamador, de ate 255 caracteres (`@NotBlank @Size(max = 255)`), enviada no corpo de `POST /inventory/{productId}/reservations` e usada na URL de `DELETE /inventory/{productId}/reservations/{reservationId}`. A Fase 5 deve gerar identificadores opacos (UUID) para o `reservationId` — esse e o mecanismo de idempotencia que ORD-06/TEST-03 vao consumir para sobreviver a reentrega de evento SQS.

## Accomplishments
- `inventory-service` entra no reactor Maven como quarto modulo, com Dockerfile proprio (`eclipse-temurin:21.0.12_8`, jar `-exec`) e migracao Flyway dona do schema `inventory`; `auth-service`/`gateway`/`catalog-service` Dockerfiles ganham a linha `COPY inventory-service/pom.xml` sem a qual a imagem quebraria ao ler o reactor
- INV-01: SELLER_ADMIN define e redefine o estoque de um produto via `PUT /inventory/{productId}` (upsert, D-18); qualquer autenticado le `quantityOnHand`/`quantityReserved`/`quantityAvailable` via `GET /inventory/{productId}` — o comprador recebe o numero exato, nunca um indicador booleano (D-25)
- INV-02: `POST /inventory/{productId}/reservations` reserva de forma atomica (lock otimista `@Version` + `saveAndFlush` dentro da tentativa) e idempotente (constraint `UNIQUE(product_id, reservation_id)` + checagem antecipada); `DELETE .../reservations/{reservationId}` libera de forma idempotente (D-14)
- Conflito de lock otimista e reexecutado automaticamente ate 4 vezes com backoff exponencial (25ms, multiplicador 2); reexecucoes esgotadas devolvem 503 com codigo `reservation_conflict`, distinto do 409 `insufficient_stock` (D-10 x D-21)
- `chk_inventory_not_oversold` no banco e a rede de seguranca final: mesmo que toda a logica de aplicacao falhe, a linha que vender acima do estoque e recusada pelo Postgres
- Nenhuma chamada HTTP sincrona sai do inventory-service para o catalog-service — `productId` e referencia opaca (D-15), confirmado por teste dedicado e por ausencia de `RestClient`/`RestTemplate`/`WebClient`/`HttpClient` no codigo de producao
- Reactor Maven e as imagens Docker de `auth-service`/`gateway`/`catalog-service` continuam construindo com sucesso depois da entrada do quarto modulo; `catalog-service` (27/27) e `auth-service` (46/46) sem regressao

## Task Commits

Each task was committed atomically:

1. **Task 1: Congelar o contrato de idempotencia da reserva de estoque** - checkpoint de decisao `blocking-human`, sem codigo produzido; decisao registrada acima em RESERVATION_ID_SCOPE e em Decisions Made
2. **Task 2: Tracer — inventory-service no reactor, upsert de estoque e disponibilidade exata** - `6f44b43` (feat) — RED (12 testes escritos antes do codigo de producao) → GREEN (12/12)
3. **Task 3: Reserva e liberacao de estoque atomicas/idempotentes** - `3128b47` (feat) — RED (1 falha genuina de asserção — contagem global do livro de reservas contaminada por testes anteriores na mesma suite; corrigida escopando a contagem por produto, nao um bug de producao) → GREEN (21/21 no total)

**Plan metadata:** commit de documentacao final a ser criado logo apos este SUMMARY.

## Files Created/Modified
- `pom.xml` - adiciona `inventory-service` a `<modules>`
- `auth-service/Dockerfile`, `gateway/Dockerfile`, `catalog-service/Dockerfile` - ganham `COPY inventory-service/pom.xml inventory-service/pom.xml`
- `inventory-service/pom.xml` - dependencias identicas a `catalog-service` mais `spring-retry` e `spring-boot-starter-aop`, ambas sem tag `<version>`
- `inventory-service/Dockerfile` - build multi-stage, mesma tag fixa `eclipse-temurin:21.0.12_8`
- `inventory-service/src/main/resources/application.yml` - porta 8083, schema `inventory`, `jwk-set-uri`; `spring.threads.virtual.enabled` deliberadamente ausente (02-RESEARCH.md Pitfall 5)
- `inventory-service/.../db/migration/V1__init_inventory_schema.sql` - tabelas `inventory` (com `version` e `chk_inventory_not_oversold`) e `stock_reservations` (livro de idempotencia, `UNIQUE(product_id, reservation_id)`)
- `inventory-service/.../stock/Inventory.java` - entidade JPA com `@Version`, mutadores nomeados `setOnHand`/`reserve`/`release`, leitura `availableQuantity()`
- `inventory-service/.../stock/InventoryRepository.java` - `findByProductId`
- `inventory-service/.../stock/StockReservation.java` - entidade do livro de idempotencia, mutador `markReleased`
- `inventory-service/.../stock/StockReservationRepository.java` - `findByProductIdAndReservationId`
- `inventory-service/.../stock/InventoryService.java` - `setStock`, `getStock`, `reserve`, `release` — os tres primeiros com reexecucao declarativa, `reserve`/`release` chamados apenas pelo controller (sem auto-invocacao, Pitfall 2)
- `inventory-service/.../stock/InventoryController.java` - `PUT/GET /{productId}`, `POST /{productId}/reservations`, `DELETE /{productId}/reservations/{reservationId}`
- `inventory-service/.../stock/{InventoryNotFoundException,InsufficientStockException,ReservationConflictException,StockBelowReservedException}.java` - excecoes de dominio → 404/409/503/409
- `inventory-service/.../stock/dto/{SetStockRequest,ReserveStockRequest,StockResponse}.java`
- `inventory-service/.../config/SecurityConfig.java` - resource server puro, replicado do catalog-service
- `inventory-service/.../config/RetryConfig.java` - `@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)` — primeiro uso de Spring Retry no repositorio
- `inventory-service/.../config/GlobalExceptionHandler.java` - 400/401/403/404/409 (`insufficient_stock`, `stock_below_reserved`, `data_conflict`)/503 (`reservation_conflict`), corpo uniforme sem stack trace/SQL
- `inventory-service/src/test/.../{support/TestJwt,AbstractIntegrationTest,InventoryControllerIT}.java` - suite de 21 testes de integracao contra PostgreSQL real

## Decisions Made
- **RESERVATION_ID_SCOPE=scope-per-product** (Task 1, checkpoint `blocking-human` resolvido pelo usuario) — ver secao dedicada acima.
- **Porta e JWKS registrados para o plano 02-03:** `inventory-service` escuta em `8083`; mesmo `jwk-set-uri` default do catalog-service.
- **Reexecucao: `maxAttempts=4`, backoff `delay=25ms, multiplier=2`** — Claude's Discretion na `02-CONTEXT.md`; registrado aqui para o plano `02-03` ajustar se o teste de concorrencia (`StockReservationConcurrencyIT`, 20 requisicoes simultaneas sobre a ultima unidade) mostrar que o valor precisa subir.
- **Contrato de reserva/liberacao registrado para a Fase 5:** corpo de `POST /inventory/{productId}/reservations` e `{"reservationId": string, "quantity": int}`; `DELETE /inventory/{productId}/reservations/{reservationId}` usa o mesmo identificador na URL.
- **Pendencia explicita da Fase 5:** `reserve`/`release` estao restritos a `SELLER_ADMIN` nesta fase (leitura conservadora); a Fase 5 precisa de uma identidade de servico propria para o `order-service` chamar essas rotas via saga.
- **`GlobalExceptionHandler` criado na Task 2, nao na Task 3** — ver Deviations abaixo.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] `GlobalExceptionHandler.java` criado na Task 2 em vez da Task 3**
- **Found during:** Task 2 (tracer)
- **Issue:** O plano listava `config/GlobalExceptionHandler.java` apenas nos `<files>` da Task 3, mas os casos de `<behavior>` da propria Task 2 exigem 404 com corpo `{error, message}` para `InventoryNotFoundException` e 400 com `fields.quantityOnHand` para erro de validacao — nenhum dos dois acontece sem um `@RestControllerAdvice` traduzindo as excecoes; sem ele, `InventoryNotFoundException` nao tratada vira 500 e o corpo de validacao padrao do Spring nao tem a chave `fields`.
- **Fix:** Criado um `GlobalExceptionHandler` minimo na Task 2 (validacao, corpo malformado, `InventoryNotFoundException`→404, acesso negado, autenticacao) — subconjunto suficiente para o contrato da Task 2. A Task 3 estendeu o mesmo arquivo com `InsufficientStockException`→409, `StockBelowReservedException`→409, `ReservationConflictException`→503 e a rede de seguranca para `DataIntegrityViolationException`.
- **Files modified:** `inventory-service/src/main/java/com/orderflow/inventory/config/GlobalExceptionHandler.java`
- **Verification:** Testes de 404/400 da Task 2 (`getStockForUnknownProductIdReturns404WithErrorAndMessageKeys`, `setStockWithNegativeQuantityReturns400AndEmptyBodyReturns400AndZeroReturns200`) passam contra PostgreSQL real.
- **Commit:** `6f44b43` (criacao minima, Task 2); estendido em `3128b47` (Task 3)

**2. [Rule 1 - Bug] Asserção de contagem de reservas escopada incorretamente no teste, corrigida antes do commit**
- **Found during:** Task 3, primeira execução do RED/GREEN da suíte completa
- **Issue:** `reservationSucceedsAndRepeatingSameReservationIdDoesNotDuplicateAndThirdReservationExceedingStockReturns409` chamava `stockReservationRepository.count()` esperando `1`, mas o repositório é compartilhado por toda a suíte de testes (mesmo container Postgres, sem rollback entre métodos `@Test`) — outros testes já haviam gravado reservas antes deste, então a contagem global era `5`, não `1`. Isto é um bug no teste (asserção mal escopada), não no código de produção: a idempotência real (uma única linha por `(productId, reservationId)`) estava correta.
- **Fix:** Trocada `stockReservationRepository.count()` por uma contagem filtrada pelo `productId` do próprio teste (`findAll().stream().filter(...).count()`).
- **Files modified:** `inventory-service/src/test/java/com/orderflow/inventory/InventoryControllerIT.java`
- **Verification:** Suíte completa GREEN (21/21) após a correção; nenhuma mudança em código de produção foi necessária.
- **Commit:** `3128b47` (parte do commit único da Task 3 — a correção aconteceu durante o próprio ciclo RED→GREEN antes do commit, não como um commit separado)

---

**Total deviations:** 2 auto-fixed (1 Rule 2 - funcionalidade crítica ausente, 1 Rule 1 - bug de teste)
**Impact on plan:** Ambos os ajustes eram necessários para o contrato da Task 2/3 funcionar corretamente contra PostgreSQL real; nenhum scope creep — nenhuma funcionalidade além do que o plano e as decisões (D-08 a D-25) já exigiam foi adicionada.

## Issues Encountered
Nenhum problema bloqueante. Um aviso de depreciação do compilador em `Inventory.java` (uso de uma API do Hibernate marcada deprecated) apareceu no build — o mesmo padrão (`@Generated(GenerationTime.INSERT)`) já existe em `Product.java` do `catalog-service` (plano 02-01) e é a técnica documentada em `02-PATTERNS.md`/`02-RESEARCH.md` para colunas com default do banco; não é uma regressão introduzida por este plano.

## User Setup Required
None - nenhuma configuração de serviço externo necessária.

## Threat Flags
Nenhuma superfície nova além das já registradas no `<threat_model>` do plano (T-02-10 a T-02-18, T-02-SC) — todas com disposição `mitigate`/`accept` implementadas e cobertas por teste (lock otimista + reexecução, idempotência por constraint, guardas de papel, validação de JWT, validação de quantidade em duas camadas, limite de tamanho do identificador, corpo de erro sem vazamento, auditoria de dependências novas).

## Known Stubs
Nenhum. `PUT`/`GET /inventory/{productId}` e `POST`/`DELETE /inventory/{productId}/reservations{,/{reservationId}}` têm implementação completa e testada contra PostgreSQL real, sem dados mock ou placeholder.

## Next Phase Readiness
- O plano `02-03` (rotas do Gateway + docker-compose) consome a porta `8083` e o `jwk-set-uri` default registrados acima, e deve escrever `StockReservationConcurrencyIT` (HTTP real + virtual threads + `CyclicBarrier`, Success Criteria 3 do ROADMAP) — registrado como item aberto em `.planning/WINDOWS.md` (kind `unrun-verify`). O plano `02-03` também deve executar o `<human-check>` da Task 3 deste plano (reservar duas vezes com o mesmo identificador, reservar acima do disponível) com a stack completa no ar.
- A Fase 5 (saga de reserva) consome diretamente o contrato `RESERVATION_ID_SCOPE=scope-per-product`, o formato do corpo de reserva/liberação e a recomendação de gerar identificadores opacos (UUID) por item de pedido — nenhum bloqueio conhecido.
- A Fase 5 precisa introduzir uma identidade de serviço própria para o `order-service` chamar `POST`/`DELETE .../reservations{,/{id}}` — hoje restritas a `SELLER_ADMIN` — registrado como pendência explícita acima.

---
*Phase: 02-cat-logo-e-estoque*
*Completed: 2026-09-20*

## Self-Check: PASSED
