---
phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
plan: "04"
subsystem: orders
tags: [spring-boot, spring-security, jpa, entitymanager-refresh, pessimistic-locking, postgresql]

# Dependency graph
requires:
  - phase: 04-01
    provides: order-service (porta 8085, schema order), Order/OrderStatus/OrderRepository, CompanyCreditLocker (trava por empresa), TestJwt/DownstreamStubServer/ConcurrentRequests/OrderTestInfrastructure
  - phase: 04-02
    provides: GlobalExceptionHandler com o envelope uniforme de erro, AuthenticationEntryPoint em JSON
  - phase: 04-03
    provides: OrderController.list (GET /orders?status=PENDING_APPROVAL) como fila de aprovação do vendedor
provides:
  - "ORD-03: POST /orders/{id}/approve e POST /orders/{id}/reject restritos a SELLER_ADMIN — decisão manual registrando decidedBy (claim sub), decidedAt e reason"
  - "D-38: aprovação manual acima do limite passa a consumir crédito sem consultar nem alterar o auth-service (gate de fonte: OrderDecisionService sem dependência de cliente HTTP)"
  - "D-37: rejeição não consome crédito — REJECTED fora de OrderStatus.CREDIT_CONSUMING"
  - "D-46 sobre D-40: decisão manual passa pela mesma trava de crédito da criação (CompanyCreditLocker), com releitura via EntityManager.refresh depois da trava — decisões concorrentes e criações concorrentes da mesma empresa nunca se atropelam"
  - "409 order_not_pending para decisão fora de PENDING_APPROVAL, sem alterar decidedBy/decidedAt/reason já registrados"
affects: [04-05-compose-gateway-smoke]

# Actuals (#2632)
actuals:
  tokens: 12619
  tasks: 3
  commits: 3
plan_head_before: c5f134353b1da3c255d924a0bce54e31246797e9

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Releitura pós-trava via EntityManager.refresh — o pedido é buscado antes da trava só para descobrir a empresa dona; a checagem de PENDING_APPROVAL usa sempre o estado relido depois de adquirir a trava, nunca o lido antes (T-04-22/T-04-23)"
    - "Método privado 'decide' parametrizado por Consumer<Order> — approve e reject compartilham busca→trava→releitura→instante sem chamar método @Transactional de dentro do mesmo bean; a anotação fica só nos dois métodos públicos"
    - "Segundo @RestController sobre o mesmo /orders — OrderDecisionController não disputa OrderController (dono do 04-03) no mesmo arquivo, os dois coexistem na mesma onda sem conflito"

key-files:
  created:
    - order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java
    - order-service/src/main/java/com/orderflow/order/order/OrderDecisionController.java
    - order-service/src/main/java/com/orderflow/order/order/dto/ApproveOrderRequest.java
    - order-service/src/main/java/com/orderflow/order/order/dto/RejectOrderRequest.java
    - order-service/src/main/java/com/orderflow/order/order/exception/OrderNotPendingException.java
    - order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java
    - order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java
  modified:
    - order-service/src/main/java/com/orderflow/order/order/Order.java
    - order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java
    - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java

key-decisions:
  - "OrderDecisionService.decide privado, parametrizado por Consumer<Order>, reaproveitado por approve e reject — evita duplicar a sequência busca→trava→releitura→instante, sem chamar um método @Transactional a partir de outro método do mesmo bean (a auto-invocação não passaria pelo proxy do Spring)"
  - "Reason em branco na aprovação é gravado como nulo (Order.approveManually blankToNull); na rejeição o motivo é obrigatório e nunca chega em branco ao agregado, mas Order.reject valida de novo (IllegalArgumentException) para o agregado se defender mesmo que a validação do DTO falhe"
  - "409 não informa o status atual do pedido no corpo — o vendedor consulta com GET /orders/{id} (assunção do plano, sem decisão de contexto explícita)"
  - "OrderDecisionController é um segundo @RestController sobre /orders, não um método a mais em OrderController — evita disputa de arquivo com o 04-03 na mesma wave"

requirements-completed: [ORD-03, ORD-02]

coverage:
  - id: D1
    description: "SELLER_ADMIN aprova pedido PENDING_APPROVAL (POST /orders/{id}/approve): 200, status APPROVED, decidedBy igual ao sub do JWT, decidedAt preenchido e não anterior a createdAt, reason igual ao motivo enviado (ou nulo sem corpo); GET /orders/{id} reflete a mesma decisão"
    requirement: "ORD-03"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java#sellerAdminApprovesPendingOrderAndDecisionIsRecordedAndConsumesCredit"
        status: pass
    human_judgment: false
  - id: D2
    description: "Pedido aprovado manualmente acima do limite passa a consumir crédito (D-38): novo pedido da mesma empresa que somado ao aprovado ultrapassa o limite nasce PENDING_APPROVAL; a aprovação não chama auth-service nem catalog-service (stub sem nenhuma requisição registrada)"
    requirement: "ORD-02"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java#sellerAdminApprovesPendingOrderAndDecisionIsRecordedAndConsumesCredit"
        status: pass
      - kind: other
        ref: "! grep -q '^import com\\.orderflow\\.order\\.client\\.' order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java"
        status: pass
    human_judgment: false
  - id: D3
    description: "Decidir (approve ou reject) um pedido que não está em PENDING_APPROVAL devolve 409 order_not_pending e não altera status/decidedBy/decidedAt/reason já registrados"
    requirement: "ORD-03"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java#sellerAdminApprovesPendingOrderAndDecisionIsRecordedAndConsumesCredit"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java#sellerRejectsWithMandatoryReasonAndDecisionsOutsideTheWindowOrByTheWrongRoleAreRefused"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java#approveManuallyAndRejectRecordDecisionFieldsOnlyFromPendingApprovalAndThrowOtherwise"
        status: pass
    human_judgment: false
  - id: D4
    description: "Só SELLER_ADMIN decide: BUYER (inclusive dono do pedido) recebe 403 nos dois endpoints; sem token, 401; id inexistente, 404 order_not_found; id que não é UUID, 400 invalid_parameter"
    requirement: "ORD-03"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java#sellerRejectsWithMandatoryReasonAndDecisionsOutsideTheWindowOrByTheWrongRoleAreRefused"
        status: pass
    human_judgment: false
  - id: D5
    description: "SELLER_ADMIN rejeita com motivo obrigatório: sem corpo → 400 malformed_request; corpo vazio, motivo em branco ou acima de 500 caracteres → 400 validation_failed com fields.reason; motivo de 501 caracteres na aprovação também → 400 validation_failed; em todos os casos o pedido continua PENDING_APPROVAL"
    requirement: "ORD-03"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java#sellerRejectsWithMandatoryReasonAndDecisionsOutsideTheWindowOrByTheWrongRoleAreRefused"
        status: pass
      - kind: unit
        ref: "order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java#rejectWithNullOrBlankReasonThrowsIllegalArgumentExceptionEvenIfDtoValidationFails"
        status: pass
    human_judgment: false
  - id: D6
    description: "Pedido rejeitado não consome crédito: depois da rejeição, um novo pedido que cabe no limite (sem a exposição do rejeitado) nasce APPROVED"
    requirement: "ORD-02"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java#sellerRejectsWithMandatoryReasonAndDecisionsOutsideTheWindowOrByTheWrongRoleAreRefused"
        status: pass
    human_judgment: false
  - id: D7
    description: "Aprovação e rejeição disparadas juntas no mesmo pedido pendente (5 pares distintos) terminam com exatamente uma 200 e uma 409, nenhuma 5xx, e o status final no banco é o da resposta 200; 10 aprovações simultâneas do mesmo pedido terminam com exatamente uma 200 e o decided_by gravado é o sub do vencedor"
    requirement: "ORD-03"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java#approvalAndRejectionFiredTogetherOnTheSamePendingOrderNeverBothSucceed"
        status: pass
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java#tenSimultaneousApprovalsOfTheSamePendingOrderYieldExactlyOneWinner"
        status: pass
    human_judgment: false
  - id: D8
    description: "Aprovação manual disparada junto com criações da mesma empresa é serializada pela trava de crédito (D-46 sobre D-40): nenhum pedido aprovado automaticamente tem decided_at posterior ao da aprovação manual, e a soma dos aprovados automaticamente nunca passa do limite"
    requirement: "ORD-02"
    verification:
      - kind: integration
        ref: "order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java#manualApprovalOfPendingOrderIsSeenByConcurrentCreationsOfTheSameCompany"
        status: pass
    human_judgment: false

duration: 20min
completed: 2026-09-25
status: complete
---

# Phase 4 Plan 4: Aprovação e Rejeição Manual de Pedidos Summary

**O vendedor aprova ou rejeita, pelo pedido, um `PENDING_APPROVAL` através de `POST /orders/{id}/approve`/`reject` sob a mesma trava de crédito da criação — decisão manual registra quem/quando/por quê, consome crédito acima do limite sem reavaliá-lo (D-38), e decisões/criações concorrentes da mesma empresa nunca se atropelam (prova por socket real).**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-09-25T23:03:44Z
- **Completed:** 2026-09-25T23:20:38Z
- **Tasks:** 3 (Task 1: tracer — aprovação sob a trava; Task 2: rejeição com motivo obrigatório e bordas; Task 3: prova de concorrência)
- **Files modified:** 10 (7 novos, 3 modificados)

## Accomplishments
- ORD-03: `POST /orders/{id}/approve` e `POST /orders/{id}/reject`, restritos a `SELLER_ADMIN` (`@PreAuthorize("hasRole('SELLER_ADMIN')")`), registram `decidedBy` (sempre do claim `sub` do JWT, nunca do corpo), `decidedAt` e `reason` — a aprovação aceita motivo opcional (em branco vira nulo), a rejeição exige motivo (`@NotBlank`, até 500 caracteres)
- D-38 provado: um pedido `PENDING_APPROVAL` aprovado manualmente acima do limite passa a consumir crédito — um novo pedido da mesma empresa que somado ultrapassa o limite nasce `PENDING_APPROVAL`; a aprovação não chama `auth-service` nem `catalog-service` (gate de fonte: `OrderDecisionService` sem `import com.orderflow.order.client.*`, e o stub não registra nenhuma requisição durante a chamada)
- D-37 provado: pedido rejeitado não consome — depois da rejeição, um pedido que cabe no limite (sem a exposição do rejeitado) nasce `APPROVED`
- Decisão fora de `PENDING_APPROVAL` (pedido já aprovado automaticamente, já decidido manualmente) devolve 409 `order_not_pending` sem tocar em nenhum campo da trilha de auditoria; `OrderNotPendingException` mapeada em `GlobalExceptionHandler`
- Entrada malformada (sem corpo, corpo vazio, motivo em branco, motivo acima de 500 caracteres) devolve 400 sem alterar o pedido pendente; `BUYER` (inclusive dono do pedido) recebe 403 nos dois endpoints; sem token, 401; id inexistente, 404; id que não é UUID, 400 `invalid_parameter`
- D-46 sobre D-40 provado por socket real (`OrderDecisionConcurrencyIT`, sem `MockMvc`): aprovação e rejeição disparadas juntas no mesmo pedido pendente (5 pares) terminam sempre com exatamente uma 200 e uma 409; 10 aprovações simultâneas do mesmo pedido terminam com exatamente uma 200; e uma aprovação manual disparada junto com criações da mesma empresa nunca deixa uma criação aprovar automaticamente por cima — nenhum aprovado automaticamente tem `decided_at` posterior ao da aprovação manual, e a soma dos aprovados automaticamente nunca ultrapassa o limite
- `OrderController.java` (dono do plano `04-03`) permanece intocado — `OrderDecisionController` é um segundo `@RestController` sobre o mesmo `/orders`

## Task Commits

Each task was committed atomically:

1. **Task 1: Tracer — o vendedor aprova um pedido pendente, a decisão fica registrada e passa a consumir crédito** - `ae6d3c5` (feat)
2. **Task 2: O vendedor rejeita com motivo obrigatório, e toda decisão fora de hora ou de quem não pode decidir é recusada** - `0fd343d` (feat)
3. **Task 3: Decisões concorrentes não se sobrepõem, e a aprovação manual é vista pelas criações que vêm depois** - `fcba22b` (test)

**Plan metadata:** commit de documentação a ser criado logo após este SUMMARY.

_Nota: as três tasks carregavam `tdd="true"`; `workflow.tdd_mode` está desativado neste projeto (mesma configuração dos planos anteriores da fase), então o gate rígido de commits separados `test(...)`/`feat(...)` não se aplicava. RED/GREEN: em cada task, o teste foi escrito e executado antes da implementação de produção (Task 1 e 2: falha de compilação/asserção antes das classes existirem; Task 3: os três cenários de concorrência passaram já na primeira execução, sem correção de produção necessária — a ordem trava→releitura→instante implementada na Task 1 já estava correta), GREEN documentado dentro do commit único de cada task, conforme o protocolo padrão já usado em `04-01`/`04-02`/`04-03`._

## Files Created/Modified
- `order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java` - `approve`/`reject` transacionais: busca → trava (`CompanyCreditLocker`) → `EntityManager.refresh` → instante → transição, sem cliente HTTP
- `order-service/src/main/java/com/orderflow/order/order/OrderDecisionController.java` - `POST /orders/{id}/approve` e `/reject`, restritos a `SELLER_ADMIN`, decisor do claim `sub`
- `order-service/src/main/java/com/orderflow/order/order/dto/ApproveOrderRequest.java` - motivo opcional, `MAX_REASON_LENGTH=500`
- `order-service/src/main/java/com/orderflow/order/order/dto/RejectOrderRequest.java` - motivo obrigatório (`@NotBlank`)
- `order-service/src/main/java/com/orderflow/order/order/exception/OrderNotPendingException.java` - 409 `order_not_pending`
- `order-service/src/main/java/com/orderflow/order/order/Order.java` - `approveManually`/`reject`: transições a partir de `PENDING_APPROVAL`, guardadas por `OrderNotPendingException`
- `order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java` - handler de `OrderNotPendingException` → 409
- `order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java` - 2 testes de integração (aprovação/D-38; rejeição/bordas/papéis)
- `order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java` - 3 testes de concorrência por socket real
- `order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java` - 2 novos testes unitários das transições do agregado

## Decisions Made
- **`OrderDecisionService.decide` privado, parametrizado por `Consumer<Order>`** — `approve` e `reject` reaproveitam a mesma sequência busca→trava→releitura→instante sem auto-invocação de método `@Transactional`.
- **Reason em branco vira nulo na aprovação** (`Order.approveManually`); a rejeição exige motivo e `Order.reject` valida de novo mesmo que o DTO já valide — o agregado se defende sozinho.
- **409 não informa o status atual no corpo** — o vendedor consulta com `GET /orders/{id}` (suposição do plano, registrada no frontmatter `Flagged Assumptions`).
- **`OrderDecisionController` como segundo `@RestController`** sobre `/orders`, não um método a mais em `OrderController` — evita disputa de arquivo com o `04-03` na mesma wave.

## Deviations from Plan

None - plan executed exactly as written. Nenhum ajuste de Rules 1-4 foi necessário: todos os testes passaram na primeira execução completa depois da implementação (inclusive os três cenários de concorrência da Task 3), e nenhuma acceptance criteria exigiu correção.

## Issues Encountered

Nenhum bloqueante. A suíte inteira do módulo (`./mvnw -pl order-service verify`) foi reexecutada ao final da Task 3 e permaneceu verde: `BUILD SUCCESS`, 0 falhas/erros em 8 classes de teste (56 testes no total, incluindo as 5 IT/unit suítes dos planos `04-01`/`04-02`/`04-03`, que não regrediram).

## User Setup Required

None - nenhuma configuração de serviço externo é necessária.

## Next Phase Readiness
- ORD-03 e o Success Criteria 3 do ROADMAP estão entregues: o vendedor aprova ou rejeita um pedido pendente e a decisão fica registrada com quem, quando e por quê.
- O plano `04-05` (compose, Gateway, smoke, Swagger, documentação) pode reutilizar `ORDER_RESPONSE_CONTRACT` (já expõe `decidedBy`/`decidedAt`/`reason`) e os dois novos endpoints de decisão para o roteiro de smoke.
- Nenhum bloqueio conhecido. A reserva de estoque, o Transactional Outbox e a saga permanecem fora de escopo desta fase (Fase 5), como planejado.

---
*Phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito*
*Completed: 2026-09-25*

## Self-Check: PASSED

All key files verified present on disk (`OrderDecisionService.java`, `OrderDecisionController.java`, `ApproveOrderRequest.java`, `RejectOrderRequest.java`, `OrderNotPendingException.java`, `OrderApprovalIT.java`, `OrderDecisionConcurrencyIT.java`). All three task commits (`ae6d3c5`, `0fd343d`, `fcba22b`) verified present in `git log --oneline --all`. Full module suite re-verified green (`./mvnw -pl order-service verify` — `BUILD SUCCESS`, 0 failures/errors across all 8 test classes: `OrderApprovalIT` 2/2, `OrderDecisionConcurrencyIT` 3/3, `OrderDomainTest` 8/8, plus the 5 pre-existing suites unchanged). Both plan-level `<verification>` commands re-run and passing; source gate re-checked (`OrderDecisionService` has no `import com.orderflow.order.client.*`); `OrderController.java` confirmed untouched by this plan (`git diff` against the plan's starting commit shows no changes to that file).
