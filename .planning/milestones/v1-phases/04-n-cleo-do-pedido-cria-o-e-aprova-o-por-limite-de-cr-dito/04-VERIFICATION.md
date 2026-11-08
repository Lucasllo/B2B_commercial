---
phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
verified: 2026-09-25T22:30:00Z
status: passed
score: 5/5 must-haves verified
covered_files:

  - ".planning/REQUIREMENTS.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-01-PLAN.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-01-SUMMARY.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-02-PLAN.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-02-SUMMARY.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-03-PLAN.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-03-SUMMARY.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-04-PLAN.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-04-SUMMARY.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-05-PLAN.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-05-SUMMARY.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-CONTEXT.md"
  - ".planning/phases/04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito/04-REVIEW.md"
  - "docker-compose.yml"
  - "gateway/src/main/resources/application.yml"
  - "order-service/src/main/java/com/orderflow/order/client/AuthServiceClient.java"
  - "order-service/src/main/java/com/orderflow/order/client/CatalogServiceClient.java"
  - "order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLockRepository.java"
  - "order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLocker.java"
  - "order-service/src/main/java/com/orderflow/order/credit/CreditPolicy.java"
  - "order-service/src/main/java/com/orderflow/order/order/Order.java"
  - "order-service/src/main/java/com/orderflow/order/order/OrderController.java"
  - "order-service/src/main/java/com/orderflow/order/order/OrderCreationService.java"
  - "order-service/src/main/java/com/orderflow/order/order/OrderDecisionController.java"
  - "order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java"
  - "order-service/src/main/java/com/orderflow/order/order/OrderService.java"
  - "order-service/src/main/java/com/orderflow/order/order/OrderStatus.java"
  - "scripts/smoke-order-flow.sh"

covered_digest: "v1:sha256:72888a082ae3f15426366c0c686bdbe6808655435ca7177c54c87ea2fb462ade"
behavior_unverified: 0
overrides_applied: 0
human_verification:

  - test: "Com a stack no ar (`docker compose up -d --wait`), abrir `http://localhost:8085/swagger-ui.html`, clicar em Authorize com um token de BUYER (gerado pelo `scripts/smoke-order-flow.sh`), executar `POST /orders` e `GET /orders` via Try it out; depois ler as seções \"Como a aprovação por crédito funciona\" e \"Limitações conhecidas (Fase 4)\" do README.md; ao terminar, `docker compose down`."
    expected: "Os cinco endpoints de pedido aparecem com o cadeado (bearerAuth); o Try it out com token funciona e sem token devolve 401; o README explica com clareza a regra de exposição acumulada e diz explicitamente que APPROVED ainda não reserva estoque nem confirma o pedido."
    why_human: "Legibilidade da Swagger UI e clareza do texto para um avaliador externo não são verificáveis por grep — item deferido pelo planner de `checkpoint:human-verify` para o fim da fase (04-05-PLAN.md Task 2 `<human-check>`, harvested em 04-05-SUMMARY.md linha 226)."
---

# Fase 4: Núcleo do Pedido — Criação e Aprovação por Limite de Crédito — Relatório de Verificação

**Objetivo da Fase:** O comprador cria pedidos reais a partir do catálogo e a regra de negócio central do B2B funciona: acima do limite de crédito da empresa o pedido para em PENDING_APPROVAL e espera o vendedor, abaixo do limite segue adiante — tudo ainda sem saga, para que a lógica de aprovação seja verificável sozinha.
**Verificado em:** 2026-09-25
**Status:** human_needed
**Re-verificação:** Não — verificação inicial

**Nota sobre modo MVP:** O ROADMAP marca esta fase com `Mode: mvp`, mas o texto do objetivo não segue o formato estrito "Como um ..., eu quero ..., para que ...` (`gsd_run query user-story.validate` confirma `valid: false`). Essa mesma característica se repete em todas as 7 fases do ROADMAP e nas três verificações anteriores (Fases 1-3), que também trataram o objetivo como uma meta observável comum, não como uma User Story SPIDR. Por consistência com o padrão já estabelecido no projeto, esta verificação segue a metodologia goal-backward padrão (Success Criteria do ROADMAP como must-haves), não o formato de User Flow Coverage do modo MVP.

## Objetivo Alcançado

### Verdades Observáveis (Success Criteria do ROADMAP, 5/5)

| # | Verdade | Status | Evidência |
|---|---------|--------|-----------|
| 1 | BUYER cria pedido do catálogo; order-service consulta catalog-service síncrono para validar itens e calcular total; pedido "nasce em CREATED" | ✓ VERIFIED (decisão documentada) | `Order.create` (Order.java:80-94) sempre inicia com `status = OrderStatus.CREATED`; `OrderCreationService.create` chama `catalogServiceClient.findOrderableProduct` por item antes de qualquer persistência. **Divergência textual coberta por decisão explícita:** D-45 (04-CONTEXT.md:36) e o Javadoc de `Order` (linhas 26-31) documentam que CREATED é o estado de origem do agregado e transiciona para APPROVED/PENDING_APPROVAL **na mesma transação** — nunca observado pela API desta fase. `OrderResponse`/`OrderSummaryResponse` portanto nunca expõem `status: "CREATED"` a um cliente HTTP; isso é projeto deliberado (a Fase 5 assume APPROVED como ponto de partida da saga), não uma lacuna. Confirmado por 62 testes verdes em `order-service/target/{surefire,failsafe}-reports` (`OrderControllerIT`, `OrderCreationServiceTest`, `OrderDomainTest`). |
| 2 | Pedido acima do limite → PENDING_APPROVAL; abaixo → segue automaticamente — dois pedidos de demonstração com totais diferentes | ✓ VERIFIED | `OrderService.createWithCreditCheck` (linhas 38-66): `CreditPolicy.fitsWithinLimit` decide `approveAutomatically`/`holdForApproval` na mesma transação. `scripts/smoke-order-flow.sh` passos 5-6: pedido de 400.00 (limite 1000.00) nasce APPROVED `decidedBy=SYSTEM`; pedido de 700.00 nasce PENDING_APPROVAL. Testes: `CreditLockAndExposureIT` (5 casos, verde), `OrderControllerIT` (3 casos, verde). |
| 3 | SELLER_ADMIN aprova/rejeita pedido pendente pelo Gateway; pedido registra quem decidiu, quando e o motivo | ✓ VERIFIED | `OrderDecisionController` (`POST /orders/{id}/approve`, `/reject`, `@PreAuthorize("hasRole('SELLER_ADMIN')")`); `Order.approveManually`/`reject` gravam `decidedBy`/`decidedAt`/`reason` e recusam origem != PENDING_APPROVAL com `OrderNotPendingException` (409). `scripts/smoke-order-flow.sh` passos 10-13 provam pelo Gateway: BUYER tentando aprovar → 403; vendedor aprova com motivo → 200 `decidedBy=userId do vendedor`; rejeição sem corpo → 400; com motivo → REJECTED; decidir de novo → 409. Testes: `OrderApprovalIT` (2, verde), `OrderDecisionConcurrencyIT` (3, verde). |
| 4 | Comprador lista/abre detalhe só da própria empresa; vendedor lista/abre detalhe de todos | ✓ VERIFIED | `OrderController.getById`/`list` derivam escopo de `Authentication` (nunca de parâmetro de query): BUYER usa `company_id` do JWT, SELLER_ADMIN vê tudo. `OrderService.getById` devolve 404 idêntico para pedido inexistente e pedido de outra empresa (D-47, nunca 403). `scripts/smoke-order-flow.sh` passos 14-15: BUYER de B recebe 404 no pedido da empresa A e `totalElements=0`; BUYER de A vê `totalElements=3`; vendedor abre qualquer pedido. Testes: `OrderListIT` (2, verde), `CreditLockAndExposureIT`. |
| 5 | Dois pedidos concorrentes na fronteira do limite não passam ambos — checagem transacional com bloqueio comprovada por teste | ✓ VERIFIED | `CompanyCreditLocker.acquire` (`Propagation.MANDATORY`) + `CompanyCreditLockRepository.lockForUpdate` (`PESSIMISTIC_WRITE`) serializam a checagem por `company_id`; nenhuma chamada HTTP acontece dentro da transação que segura a trava (D-41). `CreditLimitBoundaryConcurrencyIT` dispara requisições reais por socket (`CyclicBarrier`) e passou: 3 testes, 0 falhas, 25.34s. `OrderDecisionConcurrencyIT` prova o mesmo para aprovação/rejeição simultâneas (3 testes, verde). |

**Placar:** 5/5 verdades verificadas (0 presentes-mas-não-comportamentalmente-provadas)

### Artefatos Requeridos

| Artefato | Esperado | Status | Detalhes |
|----------|----------|--------|----------|
| `order-service/.../order/Order.java` | Máquina de estados CREATED→APPROVED/PENDING_APPROVAL/REJECTED com guardas | ✓ VERIFIED | Presente, substantivo, usado por `OrderService`/`OrderDecisionService`. |
| `order-service/.../order/OrderStatus.java` | 8 estados da ORD-10 + conjunto `CREDIT_CONSUMING` | ✓ VERIFIED | `CREDIT_CONSUMING = {APPROVED, CONFIRMED, SHIPPED, DELIVERED}`, usado por `OrderService.createWithCreditCheck` na soma de exposição. |
| `order-service/.../credit/CreditPolicy.java` | Regra de fronteira por `compareTo` | ✓ VERIFIED | `fitsWithinLimit` usa `compareTo`, nunca `equals` (evita bug de escala do `BigDecimal`); coberto por `OrderDomainTest`. |
| `order-service/.../credit/CompanyCreditLocker.java` + `CompanyCreditLockRepository.java` | Trava pessimista por empresa, só dentro de transação existente | ✓ VERIFIED | `Propagation.MANDATORY` + `PESSIMISTIC_WRITE`; `ensureExists` idempotente via `ON CONFLICT DO NOTHING`. |
| `order-service/.../order/OrderCreationService.java` | Orquestração não transacional (catálogo + crédito antes da transação) | ✓ VERIFIED | Nenhum `@Transactional`; delega a `orderService.createWithCreditCheck` (bean diferente, evita self-invocation). |
| `order-service/.../order/OrderService.java` | Decisão transacional sob trava | ✓ VERIFIED | `@Transactional` em `createWithCreditCheck`; não injeta nenhum `RestClient`. |
| `order-service/.../order/OrderDecisionController.java` + `OrderDecisionService.java` | `POST /orders/{id}/approve`/`reject` restritos a SELLER_ADMIN | ✓ VERIFIED | `@PreAuthorize("hasRole('SELLER_ADMIN')")`; releitura pós-trava via `EntityManager.refresh`. |
| `order-service/.../order/OrderController.java` | `POST/GET /orders`, `GET /orders/{id}` com escopo por papel | ✓ VERIFIED | Escopo derivado de `Authentication`, nunca de query param. |
| `docker-compose.yml` (bloco `order-service`) | order-service saudável, dependente de postgres/auth-service/catalog-service, porta só em 127.0.0.1:8085 | ✓ VERIFIED | Bloco presente linhas 140-167; `depends_on` com `condition: service_healthy` nos três; `ports: "127.0.0.1:8085:8085"`. |
| `gateway/src/main/resources/application.yml` (rota `order-service-route`) | `/api/orders/**` → `order-service:8085`, `StripPrefix=1` | ✓ VERIFIED | Confirmado linhas 38-43. |
| `scripts/smoke-order-flow.sh` | Prova ponta a ponta pelo Gateway dos Success Criteria 1-4 | ✓ VERIFIED | 15 passos cobrindo login, criação de empresas/produtos, aprovação automática/pendente, produto DISCONTINUED, decisão manual, isolamento por empresa; termina com `SMOKE OK`. Executado duas vezes pelo orquestrador contra a stack real com sucesso (evidência reportada, não re-executado por esta verificação — ver nota abaixo). |
| `docs/API.md` / `README.md` | Documentação dos 5 endpoints, códigos de erro, limitações da fase | ✓ VERIFIED | `docs/API.md` linhas 79-83 (tabela de rotas), 676-870 (contratos detalhados); `README.md` seção "Como a aprovação por crédito funciona" e "Limitações conhecidas (Fase 4)". |

### Verificação de Key Links

| De | Para | Via | Status | Detalhes |
|----|------|-----|--------|----------|
| `OrderCreationService` | `OrderService` | chamada entre beans distintos (`orderService.createWithCreditCheck`) | ✓ WIRED | Confirma que HTTP de saída nunca ocorre dentro de transação (self-invocation não seria interceptada pelo proxy Spring). |
| `OrderService` | `CompanyCreditLocker` | trava adquirida antes da soma de exposição | ✓ WIRED | `creditLocker.acquire(companyId)` é a primeira linha do método transacional. |
| `OrderRepository` | `OrderStatus.CREDIT_CONSUMING` | conjunto único de status que consome crédito | ✓ WIRED | `sumTotalByCompanyIdAndStatusIn(companyId, OrderStatus.CREDIT_CONSUMING)`. |
| `OrderDecisionService` | `CompanyCreditLocker` | mesma trava da criação, com releitura (`entityManager.refresh`) | ✓ WIRED | Evita corrida entre decisão manual e criação/decisão concorrente (D-40/D-46). |
| `gateway/application.yml` | `docker-compose.yml` | host:porta da rota bate com o serviço | ✓ WIRED | `order-service:8085` (gateway) == bloco `order-service` porta 8085 (compose). |
| `docker-compose.yml` | `order-service/application.yml` | variáveis de ambiente sobrescrevem defaults localhost | ✓ WIRED | `ORDERFLOW_AUTH_SERVICE_BASE_URL`/`ORDERFLOW_CATALOG_SERVICE_BASE_URL` apontam para os nomes de serviço do compose, não localhost. |

### Execução de Testes (evidência independente)

62 testes de `order-service` (14 unitários em `target/surefire-reports`, 48 de integração em `target/failsafe-reports`), todos com `Failures: 0, Errors: 0, Skipped: 0`:

| Suíte | Testes | Resultado |
|-------|--------|-----------|
| `OrderDomainTest` | 8 | verde |
| `OrderCreationServiceTest` | 4 | verde |
| `DownstreamClientsTest` | 2 | verde |
| `OrderControllerIT` | 3 | verde |
| `CreditLockAndExposureIT` | 5 | verde |
| `CreditLimitBoundaryConcurrencyIT` | 3 | verde (25.34s, prova SC5 por socket real) |
| `OrderCreationEdgeCasesIT` | 26 | verde |
| `OrderListIT` | 2 | verde |
| `OrderApprovalIT` | 2 | verde |
| `OrderDecisionConcurrencyIT` | 3 | verde (prova concorrência de decisão) |
| `OpenApiDocsIT` | 4 | verde |

Contagem bate com o relato do orquestrador (`./mvnw verify` reactor completo, order-service 62 testes, 0 suítes falhando).

### Cobertura de Requisitos

| Requisito | Plano de Origem | Descrição | Status | Evidência |
|-----------|-----------------|-----------|--------|-----------|
| ORD-01 | 04-01, 04-02, 04-05 | Comprador cria pedido selecionando produtos/quantidades do catálogo | ✓ SATISFIED | `OrderController.create` + `OrderCreationService`; smoke passo 5. |
| ORD-02 | 04-01, 04-02, 04-04, 04-05 | Pedido acima do limite → PENDING_APPROVAL; abaixo → segue | ✓ SATISFIED | `CreditPolicy.fitsWithinLimit`, `OrderService.createWithCreditCheck`; smoke passos 5-6. |
| ORD-03 | 04-04, 04-05 | Vendedor aprova ou rejeita pedidos pendentes | ✓ SATISFIED | `OrderDecisionController`/`OrderDecisionService`; smoke passos 11-13. |
| ORD-08 | 04-01, 04-03, 04-05 | Comprador lista e visualiza detalhe dos próprios pedidos | ✓ SATISFIED | `OrderController.list`/`getById` com escopo por `company_id`; smoke passos 14-15. |
| ORD-09 | 04-01, 04-03, 04-05 | Vendedor lista e visualiza detalhe de todos os pedidos | ✓ SATISFIED | `isSellerAdmin` remove o filtro de empresa; smoke passo 15. |

Nenhum requisito órfão: `.planning/REQUIREMENTS.md` mapeia ORD-01, ORD-02, ORD-03, ORD-08, ORD-09 para "Phase 4 / Complete", igual à lista declarada nos frontmatters dos 5 planos desta fase. ORD-04 a ORD-07 e ORD-10 permanecem corretamente `Pending` para Fases 5/6.

### Anti-Padrões Encontrados

Nenhum marcador de dívida (`TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER`) encontrado em `order-service/src/main/java`. Nenhum stub, handler vazio ou dado estático hardcoded identificado nos arquivos de produção revisados.

`04-REVIEW.md` (revisão de código já concluída, 67 arquivos, 0 critical, 2 warning, 4 info) foi lido e suas conclusões conferem com a leitura independente desta verificação:

- **WR-01** (info arquitetural, não bloqueante): `GET /orders` serializa `Page<T>` cru — risco de contrato de API instável entre versões do Spring Data, não um bug funcional hoje.
- **WR-02** (info, não bloqueante): `AuthenticationEntryPoint` não define charset explícito no 401 — risco latente só se uma mensagem com acentuação for adicionada a esse handler específico.
- **IN-01 a IN-04**: código morto, soma duplicada por conveniência, Dockerfiles repetidos, ausência de guarda para limite de crédito negativo vindo do auth-service — todos de manutenibilidade, nenhum bloqueia o objetivo da fase.

Nenhum desses achados é um `must-have` desta fase nem contradiz qualquer Success Criteria do ROADMAP; portanto não geram gap.

### Verificação Humana Necessária

### 1. Swagger UI e legibilidade da documentação para avaliador externo

**Teste:** Com a stack no ar (`docker compose up -d --wait`), abrir `http://localhost:8085/swagger-ui.html`, clicar em Authorize com um token de BUYER (gerado por `scripts/smoke-order-flow.sh`), executar `POST /orders` e `GET /orders` via Try it out; depois ler as seções "Como a aprovação por crédito funciona" e "Limitações conhecidas (Fase 4)" do `README.md`; ao terminar, `docker compose down`.

**Esperado:** Os cinco endpoints de pedido aparecem com o cadeado (esquema `bearerAuth`); o Try it out com token funciona e sem token devolve 401; o README explica com clareza a regra de exposição acumulada e diz explicitamente que APPROVED ainda não reserva estoque nem confirma o pedido.

**Por que humano:** A legibilidade da Swagger UI e a clareza do texto para um avaliador externo não são verificáveis por grep. Este item foi deliberadamente adiado pelo planejador de `checkpoint:human-verify` para o fim da fase (`workflow.human_verify_mode: end-of-phase`), registrado em `04-05-PLAN.md` Task 2 `<human-check>` e coletado (harvested) em `04-05-SUMMARY.md` linha 226. `OpenApiDocsIT` (4 testes, verde) já prova estruturalmente que o spec OpenAPI existe e declara `bearerAuth` — o que falta é só o julgamento de legibilidade humana, não a existência do artefato.

**Nota sobre a stack:** Segundo o orquestrador, `scripts/smoke-order-flow.sh` já rodou duas vezes contra a stack real com `SMOKE OK`, cobrindo pelo Gateway os Success Criteria 1 a 4 (incluindo a recusa do produto DISCONTINUED pelo catálogo real e o isolamento entre empresas). A stack está atualmente parada; esta verificação não a subiu novamente por não ser essencial (a evidência de execução real já existe e os 62 testes JVM cobrem a mesma lógica de negócio em isolamento). Se o avaliador quiser reconfirmar a execução ao vivo, rodar `docker compose up -d --build --wait && bash scripts/smoke-order-flow.sh`.

### Resumo de Gaps

Nenhum gap encontrado. Todos os 5 Success Criteria do ROADMAP para a Fase 4 estão implementados, conectados e comprovados por teste automatizado (unitário, integração com concorrência real por socket, e smoke ponta a ponta pelo Gateway contra a stack real). A única pendência é um item de verificação humana (legibilidade de Swagger UI/README para avaliador externo) já identificado e adiado deliberadamente pelo próprio plano — não uma lacuna de implementação.

A divergência textual entre o Success Criterion 1 ("o pedido nasce em CREATED") e o comportamento observável pela API (o cliente nunca vê `CREATED`, só `APPROVED`/`PENDING_APPROVAL`) está coberta pela decisão D-45, documentada em `04-CONTEXT.md` e no Javadoc de `Order.java`: CREATED é o estado de origem do agregado, mas a transição para APPROVED/PENDING_APPROVAL acontece na mesma transação da criação — nunca existe uma janela em que um pedido fica CREATED de forma observável. Isso é consistente com o próprio texto do Success Criterion 2, que já descreve a decisão automática acontecendo "na mesma transação" que a criação.

---

_Verificado em: 2026-09-25_
_Verificador: Claude (gsd-verifier)_
