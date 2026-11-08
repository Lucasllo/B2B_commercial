---
phase: "04"
slug: "n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: "2026-09-25"
---

# Phase 04 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Cliente → Gateway `:8080` → order-service | `POST /orders`, `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/approve|reject`; o Gateway não valida JWT (D-05), o order-service valida localmente | JWT (`role`, `company_id`, `sub`), corpo do pedido, id de caminho, parâmetros de query, motivo da decisão |
| order-service → catalog-service / auth-service | Chamadas síncronas diretas pela rede do compose com o JWT do BUYER repassado | JWT do comprador, snapshot de produto/preço, limite de crédito |
| Transação de crédito → PostgreSQL | Soma da exposição lida sob a trava da empresa, na mesma transação do INSERT/decisão | Pedidos e exposição acumulada |
| Script de smoke → stack | Credencial de demonstração e tokens de compradores criados pelo script | Tokens (mantidos só em variáveis) |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-04-01 | Spoofing | JWT forjado/expirado/emissor errado | high | mitigate | `application.yml` `jwk-set-uri` + `issuer-uri`; 401 asserted em `OrderControllerIT`, `OrderApprovalIT`, `OpenApiDocsIT` e tokens adversariais em `OrderCreationEdgeCasesIT` | closed |
| T-04-02 | Elevation of Privilege | Criação por não-BUYER | high | mitigate | `@PreAuthorize("hasRole('BUYER')")` em `OrderController.create`; teste de 403 | closed |
| T-04-03 | Information Disclosure | Detalhe de pedido de outra empresa | high | mitigate | `OrderService.getById(…, callerCompanyId)` colapsa para 404 idêntico | closed |
| T-04-04 | Tampering | `companyId`/preço/total no corpo | high | mitigate | DTOs sem esses campos + `@JsonIgnoreProperties`; empresa só do claim; preço do snapshot do catálogo | closed |
| T-04-05 | Tampering | Aprovações simultâneas além do limite | high | mitigate | `CompanyCreditLockRepository` com `PESSIMISTIC_WRITE`; `CreditLimitBoundaryConcurrencyIT` (verde) | closed |
| T-04-06 | Repudiation | Pedido sem autoria/decisão registrada | medium | mitigate | `created_by`/`decided_by`/`decided_at`/`reason` em `V1__init_order_schema.sql`; `Order` grava `SYSTEM` na aprovação automática | closed |
| T-04-07 | Denial of Service | Vizinho travado segurando thread/trava | high | mitigate | Timeouts de conexão/leitura em `ClientConfig`; HTTP antes da transação (gates de fonte do plano) | closed |
| T-04-08 | Information Disclosure | Corpo de erro vazando host/exceção | medium | mitigate | `GlobalExceptionHandler` com mensagens fixas; `OrderCreationEdgeCasesIT` asserta ausência de `http://`, `127.0.0.1`, `Exception`, `at com.orderflow` | closed |
| T-04-09 | Tampering | SSRF por host derivado da requisição | low | mitigate | `base-url` fixa em `application.yml`; id do produto como variável de template em `CatalogServiceClient` | closed |
| T-04-10 | Information Disclosure | Token do BUYER em log | medium | mitigate | Logs dos clientes registram só vizinho + status/classe da causa (`AuthServiceClient`, `CatalogServiceClient`) | closed |
| T-04-11 | Denial of Service | Milhares de itens amplificando chamadas | high | mitigate | `@Size(max = MAX_ITEMS_PER_ORDER)` = 50 em `CreateOrderRequest`; teste de 51 itens | closed |
| T-04-12 | Elevation of Privilege | Aprovação fail-open sem limite legível | high | mitigate | `AuthServiceClient` lança `AuthServiceUnavailableException` em todo não-200 → 503 `auth_service_unavailable`; testes sem linha gravada | closed |
| T-04-13 | Denial of Service | Vizinho lento | medium | mitigate | Modo `SLOW` do `DownstreamStubServer` provado em `OrderCreationEdgeCasesIT` | closed |
| T-04-14 | Tampering | Pedido parcial gravado | medium | mitigate | Validação de todos os itens antes da gravação; 422 lista todos os ids; asserção de zero linhas | closed |
| T-04-15 | Tampering | Total estourando `NUMERIC(19,2)` | low | mitigate | Guarda de 17 dígitos em `OrderCreationService` → `OrderTotalOutOfRangeException` (422) | closed |
| T-04-16 | Information Disclosure | Listagem de pedidos de outra empresa | high | mitigate | Consulta do BUYER filtrada por `company_id` do JWT; `OrderListIT` verifica `content` e `totalElements` | closed |
| T-04-17 | Tampering | `sort` arbitrário do cliente | low | mitigate | Ordenação montada no servidor (`createdAt` desc); teste com `sort=total,asc` | closed |
| T-04-18 | Denial of Service | `size` gigante | low | mitigate | `max-page-size: 100` em `application.yml`; teste `size=500` | closed |
| T-04-19 | Elevation of Privilege | BUYER sem `company_id` | medium | mitigate | `requireCompanyId` em `OrderController` → 403 antes da consulta | closed |
| T-04-20 | Elevation of Privilege | BUYER aprovando o próprio pedido | high | mitigate | `@PreAuthorize("hasRole('SELLER_ADMIN')")` em `OrderDecisionController`; teste de 403 | closed |
| T-04-21 | Repudiation | Decisão sem autor/instante/motivo | medium | mitigate | `decided_by` do claim `sub`, `decided_at` no servidor, motivo `@NotBlank` na rejeição; `OrderApprovalIT` | closed |
| T-04-22 | Tampering | Decisões simultâneas sobre o mesmo pedido | high | mitigate | Trava da empresa + releitura + guarda `OrderNotPendingException` (409); `OrderDecisionConcurrencyIT` | closed |
| T-04-23 | Tampering | Criação sem enxergar aprovação manual recém-comitada | high | mitigate | Aprovação manual pela mesma trava da criação (D-46); terceiro cenário de `OrderDecisionConcurrencyIT` | closed |
| T-04-24 | Tampering | Aprovação alterando limite no auth-service | medium | mitigate | `OrderDecisionService` sem dependência de cliente HTTP (grep: nenhuma); teste de zero requisições ao stub | closed |
| T-04-25 | Denial of Service | Motivo gigante | low | mitigate | `@Size(max = MAX_REASON_LENGTH)` = 500 em `ApproveOrderRequest`/`RejectOrderRequest`; teste de 501 caracteres | closed |
| T-04-26 | Spoofing | `/api/orders/**` sem validação de token | high | mitigate | Resource server local no order-service; smoke passo 4 (401 pelo Gateway); `OpenApiDocsIT` | closed |
| T-04-27 | Information Disclosure | Porta direta exposta fora da máquina | low | mitigate | `127.0.0.1:8085` em `docker-compose.yml` | closed |
| T-04-28 | Information Disclosure | Token impresso pelo smoke | medium | mitigate | Tokens só em variáveis de `scripts/smoke-order-flow.sh`, nenhum `echo` de token | closed |
| T-04-29 | Elevation of Privilege | Chamadas aos vizinhos via Gateway/host da requisição | low | mitigate | URLs fixas por variável do compose apontando para nomes de serviço | closed |
| T-04-30 | Repudiation | Documentação prometendo garantia inexistente | low | mitigate | README "Limitações conhecidas (Fase 4)"; confirmado em 04-UAT.md teste 1 | closed |
| T-04-SC | Tampering | Cadeia de suprimentos | low | accept | Nenhuma coordenada Maven nova; stub usa só `com.sun.net.httpserver` da JDK | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-04-01 | T-04-SC | Fase não introduz nenhuma dependência Maven nova (04-RESEARCH.md §Package Legitimacy Audit); o stub de teste usa apenas a JDK | plano 04-01 (threat model) | 2026-09-25 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-09-25 | 31 | 31 | 0 | gsd-secure-phase (orchestrator, L1 grep-depth; short-circuit: register authored at plan time, ASVS 1) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-09-25
